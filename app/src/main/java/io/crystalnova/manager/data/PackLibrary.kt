package io.crystalnova.manager.data

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pack the user has handed to iiSU, persisted across restarts. */
data class InstalledPack(
    val id: String,
    val name: String,
    val version: String?,
)

/** The remote catalog fetch state. Pure data — no Android APIs. */
sealed interface PackCatalogState {
    data object Checking : PackCatalogState
    /** Fetch or parse failed. `message` is a human, honest explanation. */
    data class Unavailable(val message: String) : PackCatalogState
    data class Ready(val packs: List<PackEntry>) : PackCatalogState
}

/** The per-pack download state. One active download at a time. */
sealed interface PackDownloadState {
    data object Idle : PackDownloadState
    data class Downloading(
        val packId: String,
        val done: Long,
        val total: Long?,
    ) : PackDownloadState
    data class Verifying(val packId: String) : PackDownloadState
    data class ReadyToInstall(val packId: String, val zip: File) : PackDownloadState
    data class Failed(val packId: String, val message: String) : PackDownloadState
}

/**
 * The Crystal iiSU pack library: fetches the remote catalog, downloads
 * pack ZIPs with SHA-256 verification, and persists which pack the user
 * handed to iiSU.
 *
 * The Manager NEVER writes into iiSU's storage and never guesses at
 * iiSU's private paths. The ZIP handoff goes through Android's share
 * sheet / iiSU's own import UI (Appearance > iiSU Themes); this class
 * only gets the verified ZIP into app-private cache and remembers the
 * outcome.
 *
 * A failed catalog fetch is an honest [PackCatalogState.Unavailable],
 * never invented packs and never a crash. The catalog is published by
 * the pack pipeline, not by the manager CI — "not published yet" is an
 * expected state, not an error to hide.
 */
class PackLibrary(
    private val http: HttpClient = PackHttpClient(),
    private val workDir: File,
    private val scope: CoroutineScope,
    private val prefs: KeyValueStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val KEY_PACK_ID = "pack.installed.id"
        const val KEY_PACK_NAME = "pack.installed.name"
        const val KEY_PACK_VERSION = "pack.installed.version"

        /** Cache-buster: the rolling dev-latest catalog is CDN-cached. */
        internal fun catalogUrl(): String = "$PACK_CATALOG_URL?t=${System.currentTimeMillis()}"
    }

    private val _catalog = MutableStateFlow<PackCatalogState>(PackCatalogState.Checking)
    val catalog: StateFlow<PackCatalogState> = _catalog

    private val _download = MutableStateFlow<PackDownloadState>(PackDownloadState.Idle)
    val download: StateFlow<PackDownloadState> = _download

    private val previewCache = ConcurrentHashMap<String, ByteArray>()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        launchCheck()
    }

    /** Re-fetch the catalog (user-initiated retry counts too). */
    fun refresh() {
        if (_catalog.value is PackCatalogState.Checking) return
        _catalog.value = PackCatalogState.Checking
        launchCheck()
    }

    private fun launchCheck() {
        checkJob?.cancel()
        checkJob = scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { fetchCatalog() }
            }
            _catalog.value = result.fold(
                onSuccess = { PackCatalogState.Ready(it.packs) },
                onFailure = {
                    if (it is CancellationException) PackCatalogState.Checking
                    else PackCatalogState.Unavailable(friendlyMessage(it))
                },
            )
        }
    }

    @Throws(IOException::class)
    private fun fetchCatalog(): PackCatalog {
        val resp = http.get(catalogUrl())
        if (resp.code == 404) {
            throw IOException("The pack catalog is not published yet — check back after the next update")
        }
        if (resp.code != 200) throw IOException("Catalog returned HTTP ${resp.code}")
        val catalog = parsePackCatalog(resp.bodyText())
            ?: throw IOException("The pack catalog is malformed")
        // Pin every pack URL to the Crystal repos, exactly like the dev
        // updater pins the APK URL: a catalog entry can never pull the
        // device somewhere unexpected.
        catalog.packs.forEach { pack ->
            try {
                GitHubEndpoints.checkAllowed(pack.zipUrl)
                pack.previewUrl?.let(GitHubEndpoints::checkAllowed)
            } catch (e: SecurityException) {
                throw IOException("Catalog pack URL rejected: ${e.message}")
            }
        }
        return catalog
    }

    /**
     * Downloads [pack]'s ZIP into app-private cache and SHA-256-verifies
     * it against the catalog before the state flips to [PackDownloadState.ReadyToInstall].
     * A checksum mismatch deletes the file and fails loudly — an
     * unverified ZIP never reaches the share handoff.
     */
    fun downloadPack(pack: PackEntry) {
        when (val cur = _download.value) {
            is PackDownloadState.Downloading,
            is PackDownloadState.Verifying,
            -> return // one download at a time; the UI disables the button
            is PackDownloadState.ReadyToInstall -> if (cur.packId == pack.id && cur.zip.isFile) return
            else -> { /* fall through */ }
        }
        downloadJob?.cancel()
        val dest = File(workDir, "pack-${pack.id}-${pack.versionCode}.zip")
        downloadJob = scope.launch {
            _download.value = PackDownloadState.Downloading(pack.id, 0, pack.zipBytes)
            val failure: String? = withContext(ioDispatcher) {
                try {
                    workDir.mkdirs()
                    // Drop stale pack zips so a retry can never hand an
                    // older build to the installer.
                    workDir.listFiles()
                        ?.filter { it.name.startsWith("pack-") && it.name.endsWith(".zip") && it != dest }
                        ?.forEach { it.delete() }
                    http.download(pack.zipUrl, dest) { done, total ->
                        _download.value = PackDownloadState.Downloading(
                            pack.id,
                            done,
                            total ?: pack.zipBytes,
                        )
                    }
                    if (!dest.isFile || dest.length() == 0L) {
                        throw IOException("Downloaded file is missing or empty")
                    }
                    _download.value = PackDownloadState.Verifying(pack.id)
                    if (!sha256Hex(dest).equals(pack.zipSha256, ignoreCase = true)) {
                        throw IOException("Pack checksum mismatch — the download may be corrupt")
                    }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dest.delete()
                    friendlyMessage(e)
                }
            }
            _download.value = if (failure == null) {
                PackDownloadState.ReadyToInstall(pack.id, dest)
            } else {
                PackDownloadState.Failed(pack.id, failure)
            }
        }
    }

    /** Forget an in-flight or failed download (deletes the partial file). */
    fun clearDownload() {
        downloadJob?.cancel()
        val cur = _download.value
        if (cur is PackDownloadState.ReadyToInstall) {
            cur.zip.delete()
        }
        _download.value = PackDownloadState.Idle
    }

    /**
     * Records that the user handed [pack]'s ZIP to iiSU. Handoff is not
     * proof the import completed — the pack card keeps a re-share
     * action so a failed import is recoverable without re-downloading.
     */
    fun noteInstalled(pack: PackEntry) {
        prefs?.putString(KEY_PACK_ID, pack.id)
        prefs?.putString(KEY_PACK_NAME, pack.name)
        prefs?.putString(KEY_PACK_VERSION, pack.version)
    }

    /** The pack recorded as handed to iiSU, or null when none. */
    fun installedPack(): InstalledPack? {
        val id = prefs?.getString(KEY_PACK_ID)?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs?.getString(KEY_PACK_NAME)?.takeIf { it.isNotBlank() } ?: id
        val version = prefs?.getString(KEY_PACK_VERSION)?.takeIf { it.isNotBlank() }
        return InstalledPack(id = id, name = name, version = version)
    }

    /**
     * Raw preview image bytes for [pack], or null when the pack has no
     * preview or it could not be fetched. Results are cached in memory.
     * A preview whose checksum does not match the catalog is discarded —
     * a wrong image is worse than a placeholder.
     */
    suspend fun previewBytes(pack: PackEntry): ByteArray? {
        val url = pack.previewUrl ?: return null
        previewCache[url]?.let { return it }
        return withContext(ioDispatcher) {
            runCatching {
                val resp = http.get(url)
                if (resp.code != 200) return@runCatching null
                val bytes = resp.body
                if (bytes.isEmpty()) return@runCatching null
                val expected = pack.previewSha256
                if (expected != null &&
                    !sha256Hex(bytes).equals(expected, ignoreCase = true)
                ) {
                    return@runCatching null
                }
                if (previewCache.size > 24) previewCache.clear()
                previewCache[url] = bytes
                bytes
            }.getOrNull()
        }
    }

    private fun friendlyMessage(e: Throwable): String {
        val raw = e.message?.takeIf { it.isNotBlank() }
        return when {
            raw != null && raw.length <= 140 -> raw.uppercase()
            else -> "PACK LIBRARY UNAVAILABLE"
        }
    }
}
