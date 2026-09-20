package io.crystalnova.manager.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The theme the Manager installed, persisted across restarts. */
data class EsdeThemeInstalled(
    val version: String,
    val versionCode: Int,
)

/** The remote catalog fetch state. Pure data — no Android APIs. */
sealed interface EsdeThemeCatalogState {
    data object Checking : EsdeThemeCatalogState
    /** Fetch or parse failed. `message` is a human, honest explanation. */
    data class Unavailable(val message: String) : EsdeThemeCatalogState
    data class Ready(val catalog: EsdeThemeCatalog) : EsdeThemeCatalogState
}

/** The download state. One active download at a time. */
sealed interface EsdeThemeDownloadState {
    data object Idle : EsdeThemeDownloadState
    data class Downloading(
        val entry: EsdeThemeEntry,
        val done: Long,
        val total: Long?,
    ) : EsdeThemeDownloadState

    data class Verifying(val entry: EsdeThemeEntry) : EsdeThemeDownloadState
    data class ReadyToInstall(val entry: EsdeThemeEntry, val zip: File) :
        EsdeThemeDownloadState

    data class Failed(val entry: EsdeThemeEntry, val message: String) :
        EsdeThemeDownloadState
}

/**
 * The ES-DE "crystal" theme library (u48): fetches the remote theme
 * catalog, downloads theme ZIPs with SHA-256 verification, and
 * persists the installed version.
 *
 * This class only fetches and verifies: the SAF install into ES-DE's
 * themes folder happens in [io.crystalnova.manager.storage.EsdeThemeInstaller],
 * which the activity drives after a [EsdeThemeDownloadState.ReadyToInstall].
 * A failed catalog fetch is an honest [EsdeThemeCatalogState.Unavailable],
 * never an invented theme and never a crash. "Not published yet" is an
 * expected state, not an error to hide.
 */
class EsdeThemeLibrary(
    private val http: HttpClient = PackHttpClient(),
    private val workDir: File,
    private val scope: CoroutineScope,
    private val prefs: KeyValueStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val KEY_THEME_VERSION = "esde.theme.installed.version"
        const val KEY_THEME_VERSION_CODE = "esde.theme.installed.versionCode"

        /** Cache-buster: the rolling stable catalog is CDN-cached. */
        internal fun catalogUrl(): String =
            "$ESDE_THEME_CATALOG_URL?t=${System.currentTimeMillis()}"
    }

    private val _catalog =
        MutableStateFlow<EsdeThemeCatalogState>(EsdeThemeCatalogState.Checking)
    val catalog: StateFlow<EsdeThemeCatalogState> = _catalog

    private val _download =
        MutableStateFlow<EsdeThemeDownloadState>(EsdeThemeDownloadState.Idle)
    val download: StateFlow<EsdeThemeDownloadState> = _download

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        launchCheck()
    }

    /** Re-fetch the catalog (user-initiated retry counts too). */
    fun refresh() {
        if (_catalog.value is EsdeThemeCatalogState.Checking) return
        _catalog.value = EsdeThemeCatalogState.Checking
        launchCheck()
    }

    private fun launchCheck() {
        checkJob?.cancel()
        checkJob = scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { fetchCatalog() }
            }
            _catalog.value = result.fold(
                onSuccess = { EsdeThemeCatalogState.Ready(it) },
                onFailure = {
                    if (it is CancellationException) EsdeThemeCatalogState.Checking
                    else EsdeThemeCatalogState.Unavailable(friendlyMessage(it))
                },
            )
        }
    }

    @Throws(IOException::class)
    private fun fetchCatalog(): EsdeThemeCatalog {
        val resp = http.get(catalogUrl())
        if (resp.code == 404) {
            throw IOException(
                "The theme catalog is not published yet — " +
                    "check back after the next theme release",
            )
        }
        if (resp.code != 200) throw IOException("Catalog returned HTTP ${resp.code}")
        val catalog = parseEsdeThemeCatalog(resp.bodyText())
            ?: throw IOException("The theme catalog is malformed")
        // Pin the theme URL to the Crystal repos, exactly like the dev
        // updater pins the APK URL: a catalog entry can never pull the
        // device somewhere unexpected.
        GitHubEndpoints.checkAllowed(catalog.zipUrl)
        catalog.history.forEach { GitHubEndpoints.checkAllowed(it.zipUrl) }
        return catalog
    }

    /**
     * Downloads [entry]'s ZIP into app-private cache and SHA-256-verifies
     * it against the catalog before the state flips to
     * [EsdeThemeDownloadState.ReadyToInstall]. A checksum mismatch
     * deletes the file and fails loudly — an unverified ZIP never
     * reaches the installer.
     */
    fun downloadTheme(entry: EsdeThemeEntry) {
        when (val cur = _download.value) {
            is EsdeThemeDownloadState.Downloading,
            is EsdeThemeDownloadState.Verifying,
            -> return // one download at a time; the UI disables the button
            is EsdeThemeDownloadState.ReadyToInstall ->
                if (cur.entry.versionCode == entry.versionCode && cur.zip.isFile) return
            else -> { /* fall through */ }
        }
        downloadJob?.cancel()
        val dest = File(workDir, "esde-theme-${entry.versionCode}.zip")
        downloadJob = scope.launch {
            _download.value = EsdeThemeDownloadState.Downloading(
                entry,
                0,
                entry.zipBytes,
            )
            val failure: String? = withContext(ioDispatcher) {
                try {
                    workDir.mkdirs()
                    // Drop stale theme zips so a retry can never hand an
                    // older build to the installer. The device holds
                    // exactly ONE theme version — no accumulation.
                    workDir.listFiles()
                        ?.filter {
                            it.name.startsWith("esde-theme-") &&
                                it.name.endsWith(".zip") && it != dest
                        }
                        ?.forEach { it.delete() }
                    http.download(entry.zipUrl, dest) { done, total ->
                        _download.value = EsdeThemeDownloadState.Downloading(
                            entry,
                            done,
                            total ?: entry.zipBytes,
                        )
                    }
                    if (!dest.isFile || dest.length() == 0L) {
                        throw IOException("Downloaded file is missing or empty")
                    }
                    _download.value = EsdeThemeDownloadState.Verifying(entry)
                    if (!sha256Hex(dest).equals(entry.zipSha256, ignoreCase = true)) {
                        throw IOException(
                            "Theme checksum mismatch — the download may be corrupt",
                        )
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
                EsdeThemeDownloadState.ReadyToInstall(entry, dest)
            } else {
                EsdeThemeDownloadState.Failed(entry, failure)
            }
        }
    }

    /** Forget an in-flight or failed download (deletes the partial file). */
    fun clearDownload() {
        downloadJob?.cancel()
        val cur = _download.value
        if (cur is EsdeThemeDownloadState.ReadyToInstall) {
            cur.zip.delete()
        }
        _download.value = EsdeThemeDownloadState.Idle
    }

    /**
     * Records a successful install. Called only after the SAF swap
     * completed — the prefs record must never claim an install that
     * did not happen.
     */
    fun noteInstalled(entry: EsdeThemeEntry) {
        prefs?.putString(KEY_THEME_VERSION, entry.version)
        prefs?.putString(KEY_THEME_VERSION_CODE, entry.versionCode.toString())
    }

    /** The theme recorded as installed, or null when none. */
    fun installedTheme(): EsdeThemeInstalled? {
        val version = prefs?.getString(KEY_THEME_VERSION)
            ?.takeIf { it.isNotBlank() } ?: return null
        val versionCode = prefs?.getString(KEY_THEME_VERSION_CODE)
            ?.toIntOrNull() ?: return null
        return EsdeThemeInstalled(version = version, versionCode = versionCode)
    }

    private fun friendlyMessage(e: Throwable): String {
        val raw = e.message?.takeIf { it.isNotBlank() }
        return when {
            raw != null && raw.length <= 140 -> raw.uppercase()
            else -> "THEME LIBRARY UNAVAILABLE"
        }
    }
}
