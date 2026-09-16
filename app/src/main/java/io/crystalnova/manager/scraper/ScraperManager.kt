package io.crystalnova.manager.scraper

import android.content.Context
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.data.UrlConnectionHttpClient
import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.provider.LibretroProvider
import io.crystalnova.manager.scraper.provider.PegasusFileMetadataProvider
import io.crystalnova.manager.scraper.scan.DiscoveredSystem
import io.crystalnova.manager.scraper.scan.LibraryScanner
import io.crystalnova.manager.scraper.scan.RomEntry
import io.crystalnova.manager.scraper.store.MediaCache
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
        val needs = !hasGamesFolder()
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
        _state.value = _state.value.copy(scanning = true, notice = null)
        scope.launch {
            try {
                val scanner = LibraryScanner(context, treeUri)
                val result = scanner.scan()
                lastScan = result.games
                pegasusEntries = result.pegasusEntries
                val entries = readIndexEntries()
                _state.value = _state.value.copy(
                    scanning = false,
                    systems = result.systems,
                    stats = ScraperStats.fromEntries(entries).copy(systems = result.systems),
                    notice = if (result.games.isEmpty()) "NO GAMES FOUND IN THIS FOLDER" else null,
                )
                clearError()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val msg = "SCAN FAILED: ${(e.message ?: "unknown").uppercase().take(80)}"
                recordError(msg)
                _state.value = _state.value.copy(
                    scanning = false,
                    notice = msg,
                )
            }
        }
    }

    fun selectPlatform(slug: String?) {
        _state.value = _state.value.copy(selectedPlatform = slug)
    }

    fun startScrape() = launchScrape(onlyIncomplete = false)

    fun retryIncomplete() = launchScrape(onlyIncomplete = true)

    private fun launchScrape(onlyIncomplete: Boolean) {
        if (_state.value.scraping) return
        val treeUri = prefs.getString(KEY_GAMES_TREE_URI) ?: return
        _state.value = _state.value.copy(scraping = true, notice = null)
        scrapeJob = scope.launch {
            try {
                val scanner = LibraryScanner(context, treeUri)
                val games = lastScan.ifEmpty {
                    scanner.scan().also {
                        lastScan = it.games
                        pegasusEntries = it.pegasusEntries
                    }.games
                }
                val job = ScrapeJob(
                    storage = storage(),
                    cache = MediaCache(
                        // Persistent downloads live in SAF crystal-nova-data/cache/;
                        // the app-private dir is disposable staging only.
                        storage(),
                        File(context.cacheDir, "scraper-stage").apply { mkdirs() },
                        UrlConnectionHttpClient(),
                    ),
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
                if (result.cancelled) {
                    _state.value = _state.value.copy(notice = "SCRAPE CANCELLED")
                } else {
                    clearError()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _state.value = _state.value.copy(notice = "SCRAPE CANCELLED")
                } else {
                    val msg = "SCRAPE FAILED: ${(e.message ?: "unknown").uppercase().take(80)}"
                    recordError(msg)
                    _state.value = _state.value.copy(notice = msg)
                }
            } finally {
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

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }
}
