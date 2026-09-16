package io.crystalnova.manager.scraper

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.scraper.match.TitleNormalizer
import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.provider.LibretroProvider
import io.crystalnova.manager.scraper.provider.PegasusFileMetadataProvider
import io.crystalnova.manager.scraper.scan.DiscoveredSystem
import io.crystalnova.manager.scraper.scan.LibraryScanner
import io.crystalnova.manager.scraper.scan.RomEntry
import io.crystalnova.manager.scraper.store.MediaCache
import io.crystalnova.manager.scraper.store.ScraperHttpClient
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.scraper.work.IndexEntry
import io.crystalnova.manager.scraper.work.ScrapeJob
import io.crystalnova.manager.scraper.work.ScrapeProgress
import io.crystalnova.manager.scraper.work.ScraperDiagnostics
import io.crystalnova.manager.scraper.work.ScraperStats
import io.crystalnova.manager.storage.SafThemeFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** UI state for the SCRAPER section. */
data class ScraperUiState(
    val needsGamesFolder: Boolean = true,
    val scanning: Boolean = false,
    val systems: List<DiscoveredSystem> = emptyList(),
    val stats: ScraperStats = ScraperStats(),
    val progress: ScrapeProgress? = null,
    val scraping: Boolean = false,
    val selectedPlatform: String? = null,
    val notice: String? = null,
)

/**
 * Owns the SCRAPER section: games-folder access, library scan, scrape
 * jobs, and dashboard stats. Separate from [io.crystalnova.manager.updater.UpdateManager].
 */
class ScraperManager(
    private val context: Context,
    private val prefs: KeyValueStore,
    private val themesTreeUri: () -> String?,
    private val scope: CoroutineScope,
) {
    companion object {
        const val KEY_GAMES_TREE_URI = "games_tree_uri"
        const val KEY_LAST_ERROR = "scraper_last_error"
    }

    private val _state = MutableStateFlow(ScraperUiState())
    val state: StateFlow<ScraperUiState> = _state.asStateFlow()

    private var scrapeJob: Job? = null
    private var lastScan: List<RomEntry> = emptyList()
    private var pegasusEntries: Map<String, List<io.crystalnova.manager.scraper.provider.PegasusMetadataReader.Entry>> = emptyMap()

    private fun storage(): ScraperStorage =
        ScraperStorage(SafThemeFs(context, themesTreeUri))

    fun hasGamesFolder(): Boolean = prefs.getString(KEY_GAMES_TREE_URI) != null

    /**
     * True when a games folder is picked AND its persisted SAF grant still
     * reads. A revoked grant (pref set, root unreadable) is detected here
     * so the UI can flip back to the folder picker instead of stranding
     * the user on SCAN FAILED with no re-grant path.
     */
    fun hasGamesFolderAccess(): Boolean {
        val uri = prefs.getString(KEY_GAMES_TREE_URI) ?: return false
        return try {
            val doc = DocumentFile.fromTreeUri(context, Uri.parse(uri))
            doc != null && doc.canRead()
        } catch (_: Exception) {
            false
        }
    }

    /** Raw SAF tree URI of the picked games/ROMs folder, for Diagnostics. */
    fun gamesFolderUri(): String? = prefs.getString(KEY_GAMES_TREE_URI)

    /** Last persisted scraper failure, surviving restarts. Null when clean. */
    fun lastError(): String? = prefs.getString(KEY_LAST_ERROR)

    private fun recordError(message: String) {
        prefs.putString(KEY_LAST_ERROR, message)
    }

    private fun clearError() {
        prefs.remove(KEY_LAST_ERROR)
    }

    /**
     * Point-in-time diagnostics for the hidden Diagnostics screen.
     * Never throws: every read is guarded so a broken index or revoked
     * permission shows up as a status, not a crash.
     */
    fun diagnosticsSnapshot(): ScraperDiagnostics {
        val (indexFound, indexParseOk, indexGames) = readIndexStatus()
        val s = _state.value
        return ScraperDiagnostics(
            gamesFolderUri = prefs.getString(KEY_GAMES_TREE_URI),
            indexFound = indexFound,
            indexParseOk = indexParseOk,
            indexGames = indexGames,
            scannedGames = lastScan.size,
            systems = s.systems,
            stats = s.stats,
            notice = s.notice,
            lastError = prefs.getString(KEY_LAST_ERROR),
            lastProgress = s.progress,
        )
    }

    private fun readIndexStatus(): Triple<Boolean, Boolean, Int> {
        return try {
            val text = storage().loadIndexJson() ?: return Triple(false, false, 0)
            val root = JSONObject(text) // throws on malformed JSON
            val games = root.optJSONObject("games")
            Triple(true, true, games?.length() ?: 0)
        } catch (_: Exception) {
            Triple(true, false, 0)
        }
    }

    fun setGamesFolder(uri: String) {
        prefs.putString(KEY_GAMES_TREE_URI, uri)
        refresh()
        scan()
    }

    fun refresh() {
        val needs = !hasGamesFolderAccess()
        _state.value = _state.value.copy(needsGamesFolder = needs)
        if (!needs) loadStats()
    }

    private fun loadStats() {
        scope.launch {
            val entries = readIndexEntries()
            val systems = lastScanSystems()
            _state.value = _state.value.copy(
                stats = ScraperStats.fromEntries(entries).copy(systems = systems),
            )
        }
    }

    private fun lastScanSystems(): List<DiscoveredSystem> {
        // Re-derive from the last scan when available; stats carry the rest.
        return _state.value.systems
    }

    private fun readIndexEntries(): List<IndexEntry> {
        return try {
            val text = storage().loadIndexJson() ?: return emptyList()
            val root = JSONObject(text)
            val games = root.optJSONObject("games") ?: return emptyList()
            games.keys().asSequence().map { key ->
                val o = games.getJSONObject(key)
                IndexEntry(
                    key = key,
                    title = o.optString("title", key),
                    platform = o.optString("platform", ""),
                    completeness = runCatching {
                        Completeness.valueOf(o.optString("completeness", "NO_MATCH"))
                    }.getOrDefault(Completeness.NO_MATCH),
                    real = o.optInt("real", 0),
                    generated = o.optInt("generated", 0),
                )
            }.toList()
        } catch (_: Exception) { emptyList() }
    }

    fun scan() {
        val treeUri = prefs.getString(KEY_GAMES_TREE_URI) ?: return
        if (_state.value.scanning || _state.value.scraping) return
        if (!hasGamesFolderAccess()) {
            _state.value = _state.value.copy(
                scanning = false,
                needsGamesFolder = true,
                notice = "FOLDER ACCESS LOST — PLEASE RESELECT",
            )
            return
        }
        _state.value = _state.value.copy(scanning = true, notice = null)
        scope.launch {
            try {
                val scanner = LibraryScanner(context, treeUri)
                val result = scanner.scan()
                // A revoked grant can surface as an empty listing rather
                // than an exception; that must not read as "no games" —
                // it would prune the entire index below. Fail into the
                // reselection state instead (handled by the catch).
                if (!hasGamesFolderAccess()) throw SecurityException("games folder not accessible")
                lastScan = result.games
                pegasusEntries = result.pegasusEntries
                // Prune ghost index entries (renamed ROMs) after every
                // successful scan — including an empty one, which means the
                // folder was readable but holds no games. Asset dirs are
                // kept until the user deletes them; only the derived index
                // is pruned.
                val orphaned = pruneOrphanedIndexEntries(result.games)
                val entries = readIndexEntries()
                val notices = buildList {
                    if (result.games.isEmpty()) add("NO GAMES FOUND IN THIS FOLDER")
                    if (orphaned > 0) add(
                        "$orphaned ORPHANED " +
                            if (orphaned == 1) "ENTRY REMOVED FROM INDEX"
                            else "ENTRIES REMOVED FROM INDEX",
                    )
                }
                _state.value = _state.value.copy(
                    scanning = false,
                    systems = result.systems,
                    stats = ScraperStats.fromEntries(entries).copy(systems = result.systems),
                    notice = notices.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                )
                clearError()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isRevocation(e) || !hasGamesFolderAccess()) {
                    _state.value = _state.value.copy(
                        scanning = false,
                        needsGamesFolder = true,
                        notice = "FOLDER ACCESS LOST — PLEASE RESELECT",
                    )
                } else {
                    val msg = "SCAN FAILED: ${(e.message ?: "unknown").uppercase().take(80)}"
                    recordError(msg)
                    _state.value = _state.value.copy(
                        scanning = false,
                        notice = msg,
                    )
                }
            }
        }
    }

    /**
     * Removes index entries whose ROM no longer resolves against [games].
     * Returns the number removed. Never throws.
     */
    private fun pruneOrphanedIndexEntries(games: List<RomEntry>): Int {
        return try {
            val scannedKeys = games
                .map { it.platformSlug to TitleNormalizer.slugify(it.fileName) }
                .toSet()
            val text = storage().loadIndexJson() ?: return 0
            val (pruned, removed) =
                ScraperStorage.pruneOrphanedEntries(text, scannedKeys) ?: return 0
            if (removed > 0) storage().saveIndexJson(pruned)
            removed
        } catch (_: Exception) { 0 }
    }

    fun selectPlatform(slug: String?) {
        _state.value = _state.value.copy(selectedPlatform = slug)
    }

    fun startScrape() = launchScrape(onlyIncomplete = false)

    fun retryIncomplete() = launchScrape(onlyIncomplete = true)

    private fun launchScrape(onlyIncomplete: Boolean) {
        if (_state.value.scraping) return
        val treeUri = prefs.getString(KEY_GAMES_TREE_URI) ?: return
        if (!hasGamesFolderAccess()) {
            _state.value = _state.value.copy(
                needsGamesFolder = true,
                notice = "FOLDER ACCESS LOST — PLEASE RESELECT",
            )
            return
        }
        _state.value = _state.value.copy(scraping = true, notice = null)
        scrapeJob = scope.launch {
            // Staging dir for this run's downloads; wiped in the finally
            // below — staged files are disposable copies of the persistent
            // SAF cache, never the source of truth.
            val mediaCache = MediaCache(
                // Persistent downloads live in SAF crystal-nova-data/cache/;
                // the app-private dir is disposable staging only.
                // NOTE: the scraper MUST use ScraperHttpClient, not the
                // updater's UrlConnectionHttpClient — the latter
                // enforces a GitHub-only endpoint boundary that
                // rejects the Libretro artwork CDN outright.
                storage(),
                File(context.cacheDir, "scraper-stage").apply { mkdirs() },
                ScraperHttpClient(),
            )
            try {
                val scanner = LibraryScanner(context, treeUri)
                val games = lastScan.ifEmpty {
                    scanner.scan().also {
                        // Same guard as the scan path: a revoked grant can
                        // surface as an empty listing, never as "no games".
                        if (!hasGamesFolderAccess()) throw SecurityException("games folder not accessible")
                        lastScan = it.games
                        pegasusEntries = it.pegasusEntries
                    }.games
                }
                val job = ScrapeJob(
                    storage = storage(),
                    cache = mediaCache,
                    artworkProviders = listOf(LibretroProvider()),
                    metadataProvider = PegasusFileMetadataProvider(pegasusEntries),
                    openRomInput = { entry -> scanner.openInput(entry) },
                )
                val result = job.run(
                    games = games,
                    onlyPlatform = _state.value.selectedPlatform,
                    onlyIncomplete = onlyIncomplete,
                    onProgress = { p ->
                        _state.value = _state.value.copy(progress = p)
                    },
                )
                if (result.folderAccessLost || !hasGamesFolderAccess()) {
                    _state.value = _state.value.copy(
                        needsGamesFolder = true,
                        notice = "FOLDER ACCESS LOST — PLEASE RESELECT",
                    )
                } else if (result.cancelled) {
                    val notices = mutableListOf("SCRAPE CANCELLED")
                    notices += runNotices(result)
                    _state.value = _state.value.copy(notice = notices.joinToString(" · "))
                } else {
                    clearError()
                    val notices = runNotices(result)
                    if (notices.isNotEmpty()) {
                        _state.value = _state.value.copy(notice = notices.joinToString(" · "))
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _state.value = _state.value.copy(notice = "SCRAPE CANCELLED")
                } else if (isRevocation(e) || !hasGamesFolderAccess()) {
                    _state.value = _state.value.copy(
                        needsGamesFolder = true,
                        notice = "FOLDER ACCESS LOST — PLEASE RESELECT",
                    )
                } else {
                    val msg = "SCRAPE FAILED: ${(e.message ?: "unknown").uppercase().take(80)}"
                    recordError(msg)
                    _state.value = _state.value.copy(notice = msg)
                }
            } finally {
                mediaCache.clearStaging()
                val entries = readIndexEntries()
                _state.value = _state.value.copy(
                    scraping = false,
                    stats = ScraperStats.fromEntries(entries)
                        .copy(systems = _state.value.systems),
                )
            }
        }
    }

    fun cancelScrape() {
        scrapeJob?.cancel()
        scrapeJob = null
    }

    /** Notices derived from a finished scrape run (index rebuild, dupes). */
    private fun runNotices(result: ScrapeJob.JobResult): List<String> {
        val notices = mutableListOf<String>()
        if (result.indexRebuilt) notices += "INDEX REBUILT FROM MANIFESTS"
        // Every skipped ROM surfaces individually: a truncated "+N more"
        // would hide exactly which files lost the dedupe race.
        for (name in result.skippedDuplicates) {
            notices += "DUPLICATE ROM SKIPPED: $name"
        }
        return notices
    }

    /**
     * True when [e] (or any cause in its chain) is a SAF
     * SecurityException: the persisted games-folder grant was revoked.
     */
    private fun isRevocation(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SecurityException) return true
            t = t.cause
        }
        return false
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }
}
