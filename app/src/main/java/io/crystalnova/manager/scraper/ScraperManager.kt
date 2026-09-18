package io.crystalnova.manager.scraper

import android.content.Context
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.scraper.esde.EsdeProbeOutcome
import io.crystalnova.manager.scraper.esde.EsdeProbeRunner
import io.crystalnova.manager.scraper.match.TitleNormalizer
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.provider.LibretroProvider
import io.crystalnova.manager.scraper.provider.PegasusFileMetadataProvider
import io.crystalnova.manager.scraper.scan.DiscoveredSystem
import io.crystalnova.manager.scraper.scan.LibraryScanner
import io.crystalnova.manager.scraper.scan.RomEntry
import io.crystalnova.manager.scraper.scan.ScanProgress
import io.crystalnova.manager.scraper.scan.SystemSnapshot
import io.crystalnova.manager.scraper.scan.restoredSystems
import io.crystalnova.manager.scraper.store.MediaCache
import io.crystalnova.manager.scraper.store.ScraperHttpClient
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.scraper.work.IndexEntry
import io.crystalnova.manager.scraper.work.AssetPresence
import io.crystalnova.manager.scraper.work.BridgeStatus
import io.crystalnova.manager.scraper.work.MediaGameReport
import io.crystalnova.manager.scraper.work.ScrapeJob
import io.crystalnova.manager.scraper.work.ScrapeProgress
import io.crystalnova.manager.scraper.work.ScrapeStorageWriteException
import io.crystalnova.manager.scraper.work.ScraperDiagnostics
import io.crystalnova.manager.scraper.work.ScraperStats
import io.crystalnova.manager.scraper.work.SystemStats
import io.crystalnova.manager.storage.LocationKind
import io.crystalnova.manager.storage.LocationState
import io.crystalnova.manager.storage.SafThemeFs
import io.crystalnova.manager.storage.StorageLocations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val needsMediaFolder: Boolean = false,
    val romLocation: LocationState = LocationState.NotConfigured,
    val mediaLocation: LocationState = LocationState.NotConfigured,
    /** Readiness of the read-only ES-DE import root (probe only for now). */
    val esdeLocation: LocationState = LocationState.NotConfigured,
    /** True while the SD media probe is running. */
    val probeRunning: Boolean = false,
    /** Last probe diagnostic (or failure); null when never run. */
    val probeReport: String? = null,
    val scanning: Boolean = false,
    /** Live scan counters while [scanning]; null when idle. Never a percentage. */
    val scanProgress: ScanProgress? = null,
    val systems: List<DiscoveredSystem> = emptyList(),
    /** Per-platform stats, keyed by platform slug. */
    val systemStats: Map<String, SystemStats> = emptyMap(),
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
    storageLocations: StorageLocations? = null,
    /**
     * The SAF walk, index pruning, and stats all do blocking I/O.
     * They must never run on the caller's dispatcher (MainScope on
     * device): a large library blocks the UI thread and the system
     * kills the app for ANR mid-scan.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        /**
         * Kept for compatibility; aliases the StorageLocations ROM pref so
         * existing installs keep their folder with zero migration.
         */
        const val KEY_GAMES_TREE_URI = StorageLocations.KEY_ROM_TREE_URI
        const val KEY_LAST_ERROR = "scraper_last_error"
        /**
         * Last successful scan's system summary (see [SystemSnapshot]):
         * folder/slug/label/game-count per system, never the catalogue.
         */
        const val KEY_SYSTEM_SNAPSHOT = "scraper_system_snapshot"
        /** Media revocation notice — mirrors the ROM wording. */
        const val MEDIA_ACCESS_LOST_NOTICE = "MEDIA FOLDER ACCESS LOST — PLEASE RESELECT"
        const val GAMES_ACCESS_LOST_NOTICE = "FOLDER ACCESS LOST — PLEASE RESELECT"
    }

    private val locations: StorageLocations = storageLocations ?: StorageLocations(context, prefs)

    private val _state = MutableStateFlow(ScraperUiState())
    val state: StateFlow<ScraperUiState> = _state.asStateFlow()

    private var scrapeJob: Job? = null
    private var lastScan: List<RomEntry> = emptyList()
    private var pegasusEntries: Map<String, List<io.crystalnova.manager.scraper.provider.PegasusMetadataReader.Entry>> = emptyMap()

    private fun storage(): ScraperStorage {
        val mediaUri = locations.mediaTreeUri()
        return if (mediaUri != null) {
            // Dedicated media folder: the data tree roots directly at the
            // SAF tree root — games/<platform>/<gameId>/, cache/,
            // index.json, manifests. No extra nesting.
            ScraperStorage(SafThemeFs(context) { mediaUri }, rootSubdir = null)
        } else {
            // Legacy: crystal-nova-data/ beside the theme in the themes
            // tree — existing installs keep working with zero migration.
            ScraperStorage(SafThemeFs(context, themesTreeUri))
        }
    }

    fun hasGamesFolder(): Boolean = locations.romTreeUri() != null

    /**
     * True when a games folder is picked AND its persisted SAF grant still
     * reads. A revoked grant (pref set, root unreadable) is detected here
     * so the UI can flip back to the folder picker instead of stranding
     * the user on SCAN FAILED with no re-grant path.
     */
    fun hasGamesFolderAccess(): Boolean = locations.hasAccess(LocationKind.ROM)

    /** True when a media folder is picked AND its grant still reads. */
    fun hasMediaFolderAccess(): Boolean = locations.hasAccess(LocationKind.MEDIA)

    /** Raw SAF tree URI of the picked games/ROMs folder, for Diagnostics. */
    fun gamesFolderUri(): String? = locations.romTreeUri()

    /** Raw SAF tree URI of the picked media folder, for Diagnostics. */
    fun mediaFolderUri(): String? = locations.mediaTreeUri()

    /** True when an ES-DE import root is picked AND its grant still reads. */
    fun hasEsdeFolderAccess(): Boolean = locations.hasAccess(LocationKind.ESDE)

    /** Raw SAF tree URI of the picked ES-DE import root, for Diagnostics. */
    fun esdeFolderUri(): String? = locations.esdeTreeUri()

    /**
     * Persists the picked ES-DE import-root URI (MainActivity takes the
     * persistable SAF permission first). Read-only usage: the probe and
     * the future importer only list/read beneath it.
     */
    fun setEsdeFolder(uri: String) {
        locations.adoptTreeUriString(uri, LocationKind.ESDE)
        refresh()
    }

    /** Clears the ES-DE import root. Never touches the export itself. */
    fun clearEsdeFolder() {
        locations.clearLocation(LocationKind.ESDE)
        refresh()
    }

    /**
     * Runs the SD media probe: picks one cover from the ES-DE export
     * and writes `crystal-esde-probe.json` for the theme. Runs off the
     * UI thread; the result lands in [ScraperUiState.probeReport].
     * Never throws.
     */
    fun runEsdeProbe() {
        if (_state.value.probeRunning) return
        _state.value = _state.value.copy(probeRunning = true, probeReport = null)
        scope.launch(ioDispatcher) {
            val report = try {
                when (val outcome = EsdeProbeRunner(context, locations, themesTreeUri).run()) {
                    is EsdeProbeOutcome.Success -> outcome.diagnostic
                    is EsdeProbeOutcome.Failure -> "PROBE FAILED\n\n${outcome.reason}"
                }
            } catch (e: Exception) {
                "PROBE FAILED\n\n${e.message ?: e.javaClass.simpleName}"
            }
            _state.value = _state.value.copy(probeRunning = false, probeReport = report)
        }
    }

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
            gamesFolderUri = locations.romTreeUri(),
            mediaFolderUri = locations.mediaTreeUri(),
            indexFound = indexFound,
            indexParseOk = indexParseOk,
            indexGames = indexGames,
            scannedGames = lastScan.size,
            systems = s.systems,
            stats = s.stats,
            notice = s.notice,
            lastError = prefs.getString(KEY_LAST_ERROR),
            lastProgress = s.progress,
            mediaAccess = mediaAccessNow(),
            bridgeStatus = bridgeStatusNow(),
            representative = representativeGame(),
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

    /**
     * WRITABLE truth for the media root right now (v22): with a
     * dedicated media folder the persisted grant must carry WRITE (not
     * only READ), and in both modes a real create/write/read/delete
     * probe against the ACTUAL ScraperStorage data root must succeed.
     * Readable-but-unwritable is NOT access. Never throws.
     */
    private fun mediaAccessNow(): Boolean {
        return try {
            if (locations.mediaTreeUri() != null && !locations.hasWriteAccess(LocationKind.MEDIA)) {
                return false
            }
            storage().probeWritable() is ScraperStorage.ProbeResult.Writable
        } catch (_: Exception) { false }
    }

    /**
     * Pre-scrape writability gate, run on the IO dispatcher before any
     * network or provider work. Refuses the scrape when the media
     * target cannot actually be written.
     */
    private fun writePreflight(): ScraperStorage.ProbeResult =
        scrapeWritePreflight(
            storage = storage(),
            hasDedicatedMediaTree = locations.mediaTreeUri() != null,
            hasWriteGrant = locations.hasWriteAccess(LocationKind.MEDIA),
        )

    /**
     * Whether the Pegasus theme can find the media root. No media folder
     * means the theme's legacy `../crystal-nova-data/` lookup applies
     * (NOT_REQUIRED); a configured folder needs the bridge file present
     * (PRESENT), otherwise the theme cannot resolve artwork (FAILED —
     * surfaced, never silent).
     */
    private fun bridgeStatusNow(): BridgeStatus {
        val mediaUri = try { locations.mediaTreeUri() } catch (_: Exception) { null }
        val bridgeFilePresent = try {
            val uri = themesTreeUri() ?: return BridgeStatus.FAILED
            val fs = SafThemeFs(context) { uri }
            val root = fs.root() ?: return BridgeStatus.FAILED
            fs.find(root, StorageLocations.BRIDGE_FILE_NAME) != null
        } catch (_: Exception) { false }
        return bridgeStatusFor(mediaUri, bridgeFilePresent)
    }

    /**
     * End-to-end media visibility for one representative game: the
     * theme-visible relative paths plus per-slot presence, so
     * Diagnostics proves the theme can resolve the files — not just
     * that bytes were written. Null when the index is empty or the
     * manifest cannot be read. Never throws.
     */
    private fun representativeGame(): MediaGameReport? {
        return try {
            val entries = readIndexEntries()
            val picked = pickRepresentative(entries) ?: return null
            val gameId = picked.key.substringAfter('/')
            val game = storage().loadManifest(picked.platform, gameId) ?: return null
            val store = storage()
            fun presence(slot: AssetSlot): AssetPresence {
                val prov = game.assets[slot]
                return AssetPresence(
                    path = store.assetPath(picked.platform, gameId, slot),
                    present = store.assetPresent(picked.platform, gameId, slot),
                    provenance = prov?.sourceType?.name,
                )
            }
            MediaGameReport(
                platform = picked.platform,
                gameId = gameId,
                title = picked.title,
                completeness = picked.completeness.name,
                front = presence(AssetSlot.BOX_FRONT),
                spine = presence(AssetSlot.BOX_SPINE),
                back = presence(AssetSlot.BOX_BACK),
                media = presence(AssetSlot.PHYSICAL_MEDIA),
            )
        } catch (_: Exception) { null }
    }

    /**
     * Persists the picked games-folder URI (MainActivity takes the
     * persistable SAF permission first), then refreshes and scans.
     * Delegates persistence to StorageLocations' ROM slot.
     */
    fun setGamesFolder(uri: String) {
        locations.adoptTreeUriString(uri, LocationKind.ROM)
        refresh()
        scan()
    }

    /**
     * Persists the picked media-folder URI and rewrites the theme bridge
     * (so the theme finds the media without a reinstall), then refreshes.
     * Symmetric with [setGamesFolder].
     */
    fun setMediaFolder(uri: String) {
        locations.adoptTreeUriString(uri, LocationKind.MEDIA, themesTreeUri())
        refresh()
    }

    /** Clears the media folder and removes the theme bridge (theme falls back to legacy). */
    fun clearMediaFolder() {
        locations.clearLocation(LocationKind.MEDIA, themesTreeUri())
        refresh()
    }

    fun refresh() {
        val summary = locations.summary()
        val needs = !hasGamesFolderAccess()
        _state.value = _state.value.copy(
            needsGamesFolder = needs,
            needsMediaFolder = locations.mediaTreeUri() != null && !hasMediaFolderAccess(),
            romLocation = summary.rom,
            mediaLocation = summary.media,
            esdeLocation = locations.esdeState(),
        )
        if (!needs) {
            restoreSystemSnapshot()
            loadStats()
        }
    }

    /**
     * Refills the library grid from the persisted system summary when
     * the in-memory scan is gone (app restart, APK update). Runs on
     * startup/refresh; a fresh scan overwrites it.
     */
    private fun restoreSystemSnapshot() {
        _state.value = _state.value.copy(
            systems = restoredSystems(
                live = _state.value.systems,
                gamesFolderPicked = true,
                snapshotJson = prefs.getString(KEY_SYSTEM_SNAPSHOT),
            ),
        )
    }

    /**
     * Revocation state shared by the ROM and media roots: flags whichever
     * root lost its grant (a media-root SecurityException from storage
     * ops lands here exactly like a ROM one) and notices accordingly.
     * Never absorbs revocation into empty results.
     */
    private fun revocationState(): ScraperUiState {
        val romLost = !hasGamesFolderAccess()
        val mediaLost = locations.mediaTreeUri() != null && !hasMediaFolderAccess()
        val notice = when {
            romLost && mediaLost ->
                "$MEDIA_ACCESS_LOST_NOTICE · $GAMES_ACCESS_LOST_NOTICE"
            mediaLost -> MEDIA_ACCESS_LOST_NOTICE
            else -> GAMES_ACCESS_LOST_NOTICE
        }
        return _state.value.copy(
            scanning = false,
            scanProgress = null,
            scraping = false,
            needsGamesFolder = romLost,
            needsMediaFolder = mediaLost,
            notice = notice,
        )
    }

    private fun loadStats() {
        scope.launch {
            val entries = readIndexEntries()
            val systems = lastScanSystems()
            _state.value = _state.value.copy(
                stats = ScraperStats.fromEntries(entries).copy(systems = systems),
                systemStats = SystemStats.perSystem(entries, systems),
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
                notice = GAMES_ACCESS_LOST_NOTICE,
            )
            return
        }
        _state.value = _state.value.copy(scanning = true, notice = null, scanProgress = null)
        scope.launch(ioDispatcher) {
            try {
                val scanner = LibraryScanner(context, treeUri)
                val result = scanner.scan { p ->
                    _state.value = _state.value.copy(scanProgress = p)
                }
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
                    scanProgress = null,
                    needsGamesFolder = false,
                    needsMediaFolder = false,
                    systems = result.systems,
                    stats = ScraperStats.fromEntries(entries).copy(systems = result.systems),
                    systemStats = SystemStats.perSystem(entries, result.systems),
                    notice = notices.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                )
                // Persist the lightweight system summary so a restart or
                // APK update doesn't drop the grid back to 0 SYSTEMS.
                prefs.putString(KEY_SYSTEM_SNAPSHOT, SystemSnapshot.encode(result.systems))
                clearError()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isRevocation(e) || !hasGamesFolderAccess()) {
                    // Covers media-root SecurityExceptions too: scan reads
                    // the index through storage(), which is media-rooted
                    // once a media folder is picked.
                    _state.value = revocationState()
                } else {
                    val msg = "SCAN FAILED: ${(e.message ?: "unknown").uppercase().take(80)}"
                    recordError(msg)
                    _state.value = _state.value.copy(
                        scanning = false,
                        scanProgress = null,
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
                notice = GAMES_ACCESS_LOST_NOTICE,
            )
            return
        }
        // Scrape writes to the media root: a lost media grant is a
        // reselection state, never an empty write target.
        if (locations.mediaTreeUri() != null && !hasMediaFolderAccess()) {
            _state.value = _state.value.copy(
                needsMediaFolder = true,
                notice = MEDIA_ACCESS_LOST_NOTICE,
            )
            return
        }
        _state.value = _state.value.copy(scraping = true, notice = null)
        // v22: the whole scrape pipeline does blocking I/O (scanner,
        // downloads, SAF writes, image processing) — it must run on the
        // IO dispatcher, never on the caller's Main scope. This is the
        // crash/ANR from hardware attempt 1.
        scrapeJob = scope.launch(ioDispatcher) {
            // v22: writability preflight BEFORE any network or provider
            // work — a read-only/unwritable media root refuses the scrape
            // with a repair action instead of burning downloads into a
            // void (hardware attempt 2 finished "successfully" with 0
            // persisted).
            when (val preflight = writePreflight()) {
                is ScraperStorage.ProbeResult.Writable -> Unit
                is ScraperStorage.ProbeResult.Failed -> {
                    val repair = if (locations.mediaTreeUri() != null)
                        "MEDIA FOLDER NOT WRITABLE — RESELECT IT UNDER SETTINGS → MEDIA LIBRARY"
                    else
                        "THEMES FOLDER NOT WRITABLE — RESELECT IT UNDER SETTINGS → THEMES"
                    recordError("SCRAPE PREFLIGHT FAILED: ${preflight.reason}")
                    _state.value = _state.value.copy(scraping = false, notice = repair)
                    return@launch
                }
            }
            // Staging dir for this run's downloads; wiped in the finally
            // below — staged files are disposable copies of the persistent
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
            // Set by the success branch; the finally composes the
            // finished summary from reloaded persisted stats.
            var finishedCleanly = false
            var pendingNotices: List<String> = emptyList()
            try {
                val scanner = LibraryScanner(context, treeUri)
                val games = lastScan.ifEmpty {                    scanner.scan().also {
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
                    ioDispatcher = ioDispatcher,
                )
                val result = job.run(
                    games = games,
                    onlyPlatform = _state.value.selectedPlatform,
                    onlyIncomplete = onlyIncomplete,
                    onProgress = { p ->
                        _state.value = _state.value.copy(progress = p)
                    },
                )
                if (result.folderAccessLost || !hasGamesFolderAccess() ||
                    (locations.mediaTreeUri() != null && !hasMediaFolderAccess())
                ) {
                    _state.value = revocationState()
                } else if (result.cancelled) {
                    val notices = mutableListOf("SCRAPE CANCELLED")
                    notices += runNotices(result)
                    _state.value = _state.value.copy(notice = notices.joinToString(" · "))
                } else {
                    clearError()
                    // The finished summary is composed in the finally
                    // below from the freshly reloaded persisted stats, so
                    // it reflects what is actually in the index — not just
                    // this run's counters.
                    finishedCleanly = true
                    pendingNotices = runNotices(result)
                    _state.value = _state.value.copy(needsMediaFolder = false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    _state.value = _state.value.copy(notice = "SCRAPE CANCELLED")
                } else if (e is ScrapeStorageWriteException) {
                    // v22: a manifest or index write failed — the run is
                    // NOT finished and nothing was counted as processed.
                    // The on-disk index is untouched (atomic writes), so
                    // the previous good index stays safe. Surface the
                    // failure with its context (platform / game / stage)
                    // so the Diagnostics report can be correlated.
                    val context = listOfNotNull(
                        e.platform?.let { "PLATFORM ${it.uppercase()}" },
                        e.gameId?.let { "GAME $it" },
                        e.stage?.let { "STAGE $it" },
                    ).joinToString(" · ")
                    val msg = if (context.isEmpty()) e.message!!
                    else "${e.message} ($context)"
                    recordError(msg)
                    _state.value = _state.value.copy(notice = msg)
                } else if (isRevocation(e) || !hasGamesFolderAccess() ||
                    (locations.mediaTreeUri() != null && !hasMediaFolderAccess())
                ) {
                    // Media-root SecurityExceptions from storage ops land
                    // here exactly like ROM ones — never absorbed into
                    // empty results.
                    _state.value = revocationState()
                } else {
                    val msg = "SCRAPE FAILED: ${(e.message ?: "unknown").uppercase().take(80)}"
                    recordError(msg)
                    _state.value = _state.value.copy(notice = msg)
                }
            } finally {
                mediaCache.clearStaging()
                val entries = readIndexEntries()
                val systems = _state.value.systems
                val stats = ScraperStats.fromEntries(entries).copy(systems = systems)
                val sysStats = SystemStats.perSystem(entries, systems)
                val settled = _state.value.copy(
                    scraping = false,
                    stats = stats,
                    systemStats = sysStats,
                )
                _state.value = if (finishedCleanly) {
                    val summary = scrapeFinishSummary(
                        _state.value.selectedPlatform, systems, stats, sysStats,
                    )
                    settled.copy(notice = (pendingNotices + summary).joinToString(" · "))
                } else {
                    settled
                }
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
     * SecurityException: the persisted games-folder grant — or, once a
     * media folder is picked, the media-root grant for storage ops — was
     * revoked. Handled by [revocationState], never absorbed into empty
     * results.
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

/**
 * Pure writability gate for starting a scrape, unit-testable: with a
 * dedicated media tree the persisted grant must include WRITE (a
 * read-only grant is refused even if the probe would pass); in all
 * modes the ACTUAL ScraperStorage data root must pass the real
 * create/write/read/delete probe. Fails closed — any failure refuses
 * the scrape before a single network request.
 */
internal fun scrapeWritePreflight(
    storage: ScraperStorage,
    hasDedicatedMediaTree: Boolean,
    hasWriteGrant: Boolean,
): ScraperStorage.ProbeResult {
    if (hasDedicatedMediaTree && !hasWriteGrant) {
        return ScraperStorage.ProbeResult.Failed("persisted media grant is read-only")
    }
    return runCatching { storage.probeWritable() }
        .getOrElse { ScraperStorage.ProbeResult.Failed(it.message ?: "probe threw") }
}

/**
 * Pure core of [ScraperManager.bridgeStatusNow], unit-testable: no media
 * folder means the theme's legacy lookup applies; a configured folder
 * needs the bridge file present, otherwise the theme cannot resolve
 * artwork.
 */
internal fun bridgeStatusFor(mediaUri: String?, bridgeFilePresent: Boolean): BridgeStatus =
    when {
        mediaUri == null -> BridgeStatus.NOT_REQUIRED
        bridgeFilePresent -> BridgeStatus.PRESENT
        else -> BridgeStatus.FAILED
    }

/**
 * Picks the Diagnostics representative game: the preferred hardware
 * check (Mario Golf on GBA) when indexed, else the first complete
 * game, else the first indexed game. Pure and unit-testable.
 */
internal fun pickRepresentative(entries: List<IndexEntry>): IndexEntry? {
    if (entries.isEmpty()) return null
    entries.firstOrNull {
        it.platform == "gba" && it.title.contains("mario golf", ignoreCase = true)
    }?.let { return it }
    entries.firstOrNull {
        it.completeness == Completeness.COMPLETE_CASE ||
            it.completeness == Completeness.COMPLETE_CASE_AND_MEDIA
    }?.let { return it }
    return entries.first()
}

/**
 * The obvious finished-state line after a scrape run, built from
 * persisted index stats: `GBA · 12 GAMES · 8 COMPLETE · 3 PARTIAL ·
 * 1 UNMATCHED` (aggregate form for ALL SYSTEMS). When games remain
 * incomplete, RETRY INCOMPLETE is named as the next action. Pure and
 * unit-testable.
 */
internal fun scrapeFinishSummary(
    selectedPlatform: String?,
    systems: List<DiscoveredSystem>,
    stats: ScraperStats,
    systemStats: Map<String, SystemStats>,
): String {
    fun nudge(incomplete: Int) =
        if (incomplete > 0) " — RETRY INCOMPLETE TO FILL THE GAPS" else ""
    if (selectedPlatform == null) {
        return "SCRAPE FINISHED · ALL SYSTEMS · ${stats.totalGames} GAMES · " +
            "${stats.complete} COMPLETE · ${stats.partial} PARTIAL · " +
            "${stats.unmatched} UNMATCHED" +
            nudge(stats.partial + stats.unmatched)
    }
    val row = systemStats[selectedPlatform]
    val label = systems.firstOrNull { it.platformSlug == selectedPlatform }?.label
        ?: row?.label
        ?: selectedPlatform
    val games = row?.games ?: 0
    val complete = row?.complete ?: 0
    val partial = row?.partial ?: 0
    val unmatched = row?.unmatched ?: 0
    return "SCRAPE FINISHED · ${label.uppercase()} · $games GAMES · " +
        "$complete COMPLETE · $partial PARTIAL · $unmatched UNMATCHED" +
        nudge(partial + unmatched)
}
