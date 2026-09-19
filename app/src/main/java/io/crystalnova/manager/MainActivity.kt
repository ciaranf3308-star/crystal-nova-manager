package io.crystalnova.manager

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.data.compareVersions
import io.crystalnova.manager.pegasus.EmulatorDetector
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.storage.LocationKind
import io.crystalnova.manager.storage.SafThemeFs
import io.crystalnova.manager.storage.LocationState
import io.crystalnova.manager.storage.SafThemeStorage
import io.crystalnova.manager.storage.StorageLocations
import io.crystalnova.manager.ui.AppearanceScreen
import io.crystalnova.manager.ui.AssetsScreen
import io.crystalnova.manager.ui.Crystal
import io.crystalnova.manager.ui.CrystalPack
import io.crystalnova.manager.ui.Dest
import io.crystalnova.manager.ui.DiagnosticsScreen
import io.crystalnova.manager.ui.EsdeImportScreen
import io.crystalnova.manager.ui.EsdeManualMatchScreen
import io.crystalnova.manager.ui.HomeScreen
import io.crystalnova.manager.ui.HomeStatus
import io.crystalnova.manager.ui.InstalledEmulatorsScreen
import io.crystalnova.manager.ui.LibraryScreen
import io.crystalnova.manager.ui.MediaHealthReportScreen
import io.crystalnova.manager.ui.Navigator
import io.crystalnova.manager.ui.PegasusSystemRow
import io.crystalnova.manager.ui.toHex
import io.crystalnova.manager.ui.PlaceholderScreen
import io.crystalnova.manager.ui.SettingsChannelScreen
import io.crystalnova.manager.ui.SettingsLocationScreen
import io.crystalnova.manager.ui.SettingsScreen
import io.crystalnova.manager.ui.SettingsThemesScreen
import io.crystalnova.manager.ui.SystemHubScreen
import io.crystalnova.manager.ui.ThemeScreen
import io.crystalnova.manager.ui.buildHomeStatus
import io.crystalnova.manager.ui.BiosScreen
import io.crystalnova.manager.ui.BiosScreenState
import io.crystalnova.manager.ui.BiosStatusRow
import io.crystalnova.manager.bios.BiosFirmwareTable
import io.crystalnova.manager.bios.BiosInventory
import io.crystalnova.manager.bios.BiosInventory.BiosScanState
import io.crystalnova.manager.bios.BiosRootState
import io.crystalnova.manager.bios.BiosStatus
import io.crystalnova.manager.diag.CrashReporter
import io.crystalnova.manager.diag.DiagnosticsInfo
import io.crystalnova.manager.diag.EmulatorPackageStatus
import io.crystalnova.manager.scraper.ScraperManager
import io.crystalnova.manager.updater.ApkInstaller
import io.crystalnova.manager.updater.AppUpdateState
import io.crystalnova.manager.updater.UpdateManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key)
    }
}

/**
 * Navigation hub. Owns the [Navigator] back stack, the managers, and
 * the SAF folder pickers; each [Dest] renders a focused screen from
 * ui/. Controller/system back pops exactly one level from any child
 * destination and only exits from HOME.
 *
 * u44 pivot: the Manager is the Nova's companion control centre, not
 * a frontend builder. iiSU (com.iisulauncher) is the frontend;
 * Pegasus is legacy/fallback; the Crystal Launcher is frozen. All
 * Pegasus/launcher-bridge wiring is archived (code kept, UX removed).
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * iiSU's Android package. We never assume the launch intent
         * exists — [isIisuInstalled] checks at runtime and the HOME
         * LAUNCH iiSU button is disabled when it's absent.
         */
        const val IISU_PACKAGE = "com.iisulauncher"
    }

    private lateinit var manager: UpdateManager
    private lateinit var storage: SafThemeStorage
    private lateinit var locations: StorageLocations
    private lateinit var scraper: ScraperManager
    private lateinit var prefsStore: KeyValueStore
    /**
     * APPEARANCE sync state: null = not applied this session,
     * true/false = last `crystal-user-colors.json` write result.
     */
    private var appearanceSyncOk by mutableStateOf<Boolean?>(null)
    /** v24: BIOS inventory (firmware discovery + PS2 import flow). */
    private lateinit var biosInventory: BiosInventory
    /**
     * Bumped when BIOS state changes (folder adopted, import attested)
     * so HOME and the BIOS screen recompute (state lives in prefs, not
     * in a flow).
     */
    private var biosRev: Int by mutableStateOf(0)
    /** Last BIOS folder adopt failure, surfaced on the BIOS screen. */
    private var biosNotice: String? by mutableStateOf(null)
    private val scope = MainScope()

    /** Human-readable build tag, e.g. "1.2.3-u2 (14)". The versionName
     *  alone never changes across DEV builds, so the code is the only
     *  thing that tells them apart. */
    private val appVersionLabel: String =
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** The remembered [Navigator], mirrored here for the back callback. */
    private var navigator: Navigator? = null

    private var diagnosticsInfo: DiagnosticsInfo? by mutableStateOf(null)
    /** Last ROM/MEDIA adopt failure, surfaced on the Settings screen. */
    private var locationError: String? by mutableStateOf(null)
    /** PackageManager-backed detection of known emulator apps. */
    private val emulatorDetector: EmulatorDetector by lazy { EmulatorDetector(this) }

    /**
     * Artwork-studio image picker. The pending (platform, gameId, slot) is
     * captured when the user taps PICK IMAGE; the returned Uri goes
     * straight to the scraper, which transcodes and saves it as
     * user-owned artwork.
     */
    private var pendingArtworkPick: Triple<String, String, AssetSlot>? = null
    private val artworkImagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val pending = pendingArtworkPick
            pendingArtworkPick = null
            if (uri != null && pending != null) {
                scraper.applyCustomArtwork(pending.first, pending.second, pending.third, uri)
            }
        }

    /**
     * Themes-root picker. Keeps the U1.1 normalize + guidance behavior:
     * picking the theme folder itself re-prompts for the themes/ parent.
     */
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            var folderNotice: String? = null
            if (uri != null) {
                val effective = normalizeThemesRoot(uri)
                if (effective != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            effective,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        storage.treeUri = effective.toString()
                    } catch (_: SecurityException) {
                        // Picker granted nothing usable; stay on the onboarding screen.
                        folderNotice = "COULD NOT KEEP ACCESS — PLEASE TRY AGAIN"
                    }
                } else {
                    // The theme folder itself was picked and SAF won't
                    // permit its parent — ask again with explicit guidance.
                    folderNotice = "THAT WAS THE THEME FOLDER ITSELF — " +
                        "PLEASE SELECT THE THEMES FOLDER THAT CONTAINS IT"
                }
            }
            manager.refresh(folderNotice)
        }

    /**
     * BIOS folder picker (v24). Adopts the persistable SAF grant for
     * the `bios/` folder; [BiosInventory] re-verifies PS2 state from
     * scratch on a fresh adopt. The picker opens at the preferred
     * sibling of the ROM root when one can be derived, so the folder
     * is one tap away instead of a manual browse.
     */
    private val biosPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val ok = biosInventory.adoptBiosTreeUri(contentResolver, uri)
                if (ok) {
                    biosNotice = null
                    biosRev++
                    // Fresh folder: the adopt invalidated the cache, so
                    // re-verify from scratch on IO.
                    biosInventory.requestScan(scope)
                } else {
                    biosNotice = "COULD NOT KEEP BIOS FOLDER ACCESS — PLEASE TRY AGAIN"
                }
            }
        }

    /**
     * ROM library picker. The SAF grant is taken inside
     * [StorageLocations.adoptTreeUri]; on success the scraper refreshes
     * so the ROMS screen flips from the picker prompt to the inventory.
     */
    private val romPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val ok = locations.adoptTreeUri(
                    contentResolver, uri, LocationKind.ROM, storage.treeUri,
                )
                if (ok) {
                    locationError = null
                    scraper.refresh()
                    // Adopting a ROM folder discovers the library
                    // immediately — no manual rescan step.
                    scraper.scan()
                } else {
                    locationError = "COULD NOT KEEP ROM LIBRARY ACCESS — PLEASE TRY AGAIN"
                }
            }
        }

    /**
     * MEDIA library picker. Adopting rewrites the theme bridge so
     * media stays findable ([StorageLocations] does that inside
     * adopt); we write the bridge once more for self-healing, then
     * refresh the scraper.
     */
    private val mediaPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val ok = locations.adoptTreeUri(
                    contentResolver, uri, LocationKind.MEDIA, storage.treeUri,
                )
                if (ok) {
                    locationError = null
                    locations.writeBridge(storage.treeUri)
                    scraper.refresh()
                } else {
                    locationError = "COULD NOT KEEP MEDIA LIBRARY ACCESS — PLEASE TRY AGAIN"
                }
            }
        }

    /**
     * ES-DE import-root picker. Adopting stores the persistable grant;
     * the export is used READ-ONLY (the importer
     * only list/read beneath it) — nothing is ever written there, so
     * only the read grant is taken.
     */
    private val esdePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val ok = locations.adoptTreeUri(
                    contentResolver, uri, LocationKind.ESDE, readOnly = true,
                )
                if (ok) {
                    locationError = null
                    scraper.refresh()
                } else {
                    locationError = "COULD NOT KEEP ES-DE FOLDER ACCESS — PLEASE TRY AGAIN"
                }
            }
        }

    /**
     * U1.1: the U1 wording led users to pick crystal-nova-pegasus-theme/
     * itself instead of the themes/ parent. Detects that pick and
     * normalizes to the parent directory when SAF access permits.
     * Returns the URI to persist, or null when the parent isn't
     * accessible (the caller re-prompts with guidance instead of
     * persisting a root that would nest installs).
     */
    private fun normalizeThemesRoot(uri: Uri): Uri? {
        val doc = try {
            DocumentFile.fromTreeUri(this, uri) ?: return uri
        } catch (_: Exception) {
            return uri
        }
        if (doc.name != SafThemeStorage.THEME_DIR_NAME) return uri
        // The theme folder itself was picked — try its parent.
        return try {
            val parent = doc.parentFile ?: return null
            if (!parent.canWrite()) return null
            val authority = uri.authority ?: return null
            val parentId = DocumentsContract.getDocumentId(parent.uri)
            val parentTree = DocumentsContract.buildTreeDocumentUri(authority, parentId)
            // Throws when the system never granted this tree; success
            // means the grant exists and we may persist it.
            contentResolver.takePersistableUriPermission(
                parentTree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            // Final probe: the grant must actually allow listing.
            DocumentFile.fromTreeUri(this, parentTree)?.listFiles()
            parentTree
        } catch (_: Exception) {
            null
        }
    }

    /**
     * U1.1: a tree URI persisted by U1 may already point at the theme
     * folder itself. Normalize it before the manager reads anything;
     * returns a guidance message when the user must re-pick.
     */
    private fun maybeRepairPersistedRoot(): String? {
        val persisted = storage.treeUri ?: return null
        if (!storage.isRootThemeFolderItself()) return null
        val normalized = normalizeThemesRoot(Uri.parse(persisted))
        if (normalized != null && normalized.toString() != persisted) {
            storage.treeUri = normalized.toString()
            return null
        }
        storage.treeUri = null
        return "THAT WAS THE THEME FOLDER ITSELF — " +
            "PLEASE SELECT THE THEMES FOLDER THAT CONTAINS IT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Capture uncaught-crash traces for the Diagnostics screen.
        CrashReporter.install(this)

        val prefs = SharedPrefsStore(getSharedPreferences("crystal-nova-manager", MODE_PRIVATE))
        prefsStore = prefs
        // APPEARANCE: restore the user's custom palette (if any) before
        // the first frame — the shipped look is the fallback.
        Crystal.loadPersistedPalette(prefs)
        val fs = SafThemeFs(this) { prefs.getString(SafThemeStorage.KEY_TREE_URI) }
        storage = SafThemeStorage(fs, prefs)
        locations = StorageLocations(this, prefs)
        biosInventory = BiosInventory(
            context = this,
            prefs = prefs,
            romTreeUriProvider = { locations.romTreeUri() },
        )
        // v24: the recursive BIOS scan is blocking SAF I/O (depth 8,
        // up to 2000 files) — it runs on Dispatchers.IO and caches
        // into biosInventory.scanState. Every reader takes the cached
        // result only; nothing here runs on Main.
        biosInventory.requestScan(scope)
        // Self-healing: make sure the theme bridge reflects the
        // persisted media location even if a previous run died mid-adopt.
        locations.writeBridge(storage.treeUri)
        // U1.1: repair a U1-persisted root that points at the theme folder
        // itself before the manager's initial refresh reads it.
        val pendingFolderNotice = maybeRepairPersistedRoot()
        manager = UpdateManager(
            storage = storage,
            github = GitHubRepository(),
            workDir = File(cacheDir, "updater").apply { mkdirs() },
            scope = scope,
            appVersion = appVersionLabel,
            // BuildConfig.VERSION_CODE defaults in; the manager reads the
            // persisted update channel (default DEV) from prefs itself.
            prefs = prefs,
        )
        if (pendingFolderNotice != null) manager.refresh(pendingFolderNotice)

        scraper = ScraperManager(
            context = this,
            prefs = prefs,
            themesTreeUri = { storage.treeUri },
            scope = scope,
            storageLocations = locations,
        )
        // Seed the library status block on HOME on first paint.
        scraper.refresh()

        // Appliance behavior: the ROM library discovers itself. On
        // startup, scan the ROM roots when this session has not scanned
        // yet (new ROMs appear, removed games are reconciled out of the
        // derived index).
        scope.launch(Dispatchers.IO) {
            scraper.ensureLibraryScan()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val nav = navigator
                if (nav == null || nav.onBack()) finish()
            }
        })

        setContent {
            val nav = remember { Navigator() }
            // Idempotent mirror for the back callback above.
            navigator = nav
            val scraperState by scraper.state.collectAsState()
            val appUpdate by manager.appUpdate.collectAsState()
            // v24: the cached BIOS scan state. The recursive SAF scan
            // runs on Dispatchers.IO (BiosInventory.requestScan);
            // Compose reads the cache only — zero recursive SAF
            // traversal on Main, ever.
            val biosScan by biosInventory.scanState.collectAsState()
            val locError = locationError
            val diagInfo = diagnosticsInfo
            // The manager's own update channel (DEV / CANDIDATE vs STABLE),
            // persisted in prefs; changing it re-checks for an app update.
            var updateChannel by remember { mutableStateOf(AppUpdateChannel.load(prefs)) }
            val pop: () -> Unit = { nav.onBack() }
            // iiSU presence comes straight from PackageManager — cheap,
            // read on every composition so installing iiSU while the
            // Manager is open flips HOME without a restart.
            val iisuInstalled = isIisuInstalled()
            val iisuVersion = iisuVersionName()

            when (val dest = nav.current) {
                is Dest.Home -> {
                    val homeStatus: HomeStatus = remember(
                        scraperState.systems,
                        scraperState.romLocation,
                        iisuInstalled,
                        iisuVersion,
                    ) {
                        buildHomeStatus(
                            systems = scraperState.systems.map { it.label to it.gameCount },
                            romReady = scraperState.romLocation is LocationState.Ready,
                            iisuInstalled = iisuInstalled,
                            iisuVersion = iisuVersion,
                            // u44: no pack library yet — the catalog and
                            // pack #1 land in a later update.
                            packName = null,
                            packVersion = null,
                        )
                    }
                    HomeScreen(
                        status = homeStatus,
                        appVersion = appVersionLabel,
                        appUpdate = appUpdate,
                        onUpdateApp = { onUpdateApp() },
                        onLaunchIisu = { openIisu() },
                        onTheme = { nav.navigate(Dest.Theme) },
                        onAssets = { nav.navigate(Dest.Assets) },
                        onRoms = {
                            scraper.refresh()
                            nav.navigate(Dest.Library)
                        },
                        onSystem = { nav.navigate(Dest.SystemHub) },
                        onSettings = { nav.navigate(Dest.Settings) },
                        onDiagnostics = { openDiagnostics(nav) },
                        onExit = { finish() },
                    )
                }
                is Dest.Library -> LibraryScreen(
                    state = scraperState,
                    onPickRomLibrary = { romPicker.launch(null) },
                    onDismissNotice = { scraper.dismissNotice() },
                    onBack = pop,
                )
                is Dest.Theme -> ThemeScreen(
                    // u44: the pack library is an honest empty state —
                    // the remote catalog and pack #1 land in a later
                    // update.
                    packs = crystalPacks(),
                    onBack = pop,
                )
                is Dest.Assets -> AssetsScreen(
                    onBack = pop,
                )
                is Dest.SystemHub -> SystemHubScreen(
                    onDiagnostics = { openDiagnostics(nav) },
                    onBios = { nav.navigate(Dest.Bios) },
                    onInstalledEmulators = { nav.navigate(Dest.InstalledEmulators) },
                    onBack = pop,
                )
                is Dest.InstalledEmulators -> InstalledEmulatorsScreen(
                    packages = emulatorDetector.detectionReport().map { (pkg, installed) ->
                        EmulatorPackageStatus(pkg, installed)
                    },
                    onBack = pop,
                )
                is Dest.Settings -> SettingsScreen(
                    romLocation = scraperState.romLocation,
                    mediaLocation = scraperState.mediaLocation,
                    themesRootLabel = themesRootLabel(),
                    updateChannel = updateChannel,
                    appVersion = appVersionLabel,
                    appUpdate = appUpdate,
                    locationError = locError,
                    onDismissLocationError = { locationError = null },
                    onUpdateApp = { onUpdateApp() },
                    onOpenRom = { nav.navigate(Dest.SettingsRom) },
                    onOpenMedia = { nav.navigate(Dest.SettingsMedia) },
                    onOpenEsdeImport = { nav.navigate(Dest.SettingsEsdeImport) },
                    onOpenThemes = { nav.navigate(Dest.SettingsThemes) },
                    onOpenChannel = { nav.navigate(Dest.SettingsChannel) },
                    onOpenAppearance = { nav.navigate(Dest.SettingsAppearance) },
                    onDiagnostics = { openDiagnostics(nav) },
                    onBack = pop,
                )
                is Dest.SettingsRom -> SettingsLocationScreen(
                    routeKey = "settings-rom",
                    title = "ROM LIBRARY",
                    location = scraperState.romLocation,
                    showBadge = false,
                    onPick = { romPicker.launch(null) },
                    onClear = {
                        locations.clearLocation(LocationKind.ROM, storage.treeUri)
                        locations.writeBridge(storage.treeUri)
                        scraper.refresh()
                    },
                    onBack = pop,
                )
                is Dest.SettingsMedia -> SettingsLocationScreen(
                    routeKey = "settings-media",
                    title = "MEDIA LIBRARY",
                    location = scraperState.mediaLocation,
                    showBadge = true,
                    onPick = { mediaPicker.launch(null) },
                    onClear = {
                        locations.clearLocation(LocationKind.MEDIA, storage.treeUri)
                        locations.writeBridge(storage.treeUri)
                        scraper.refresh()
                    },
                    onBack = pop,
                )
                is Dest.SettingsEsdeImport -> EsdeImportScreen(
                    esdeLocation = scraperState.esdeLocation,
                    prescanning = scraperState.importPrescanning,
                    importStatus = scraperState.importStatus,
                    importPlanReady = scraperState.importPlan != null,
                    importReport = scraperState.importReport,
                    importRunning = scraperState.importRunning,
                    importProgress = scraperState.importProgress,
                    importResult = scraperState.importResult,
                    unmatchedCount = scraperState.importPlan?.unmatchedGames?.size ?: 0,
                    healthCheckRunning = scraperState.healthCheckRunning,
                    healthCheckStatus = scraperState.healthCheckStatus,
                    onPrescan = { scraper.runEsdeImportPrescan() },
                    onImport = { scraper.runEsdeImport() },
                    // u44: the ES-DE folder picker lives here now that the
                    // SETTINGS row is gone — the export is this screen's
                    // only input.
                    onPickEsdeFolder = { esdePicker.launch(null) },
                    onManualMatch = { nav.navigate(Dest.SettingsEsdeManualMatch) },
                    onHealthCheck = {
                        scraper.runMediaHealthCheck()
                        nav.navigate(Dest.SettingsEsdeHealthReport)
                    },
                    onBack = pop,
                )
                is Dest.SettingsEsdeHealthReport -> MediaHealthReportScreen(
                    report = scraperState.healthReport,
                    running = scraperState.healthCheckRunning,
                    status = scraperState.healthCheckStatus,
                    onRunAgain = { scraper.runMediaHealthCheck() },
                    onBack = pop,
                )
                is Dest.SettingsEsdeManualMatch -> EsdeManualMatchScreen(
                    importPlan = scraperState.importPlan,
                    manualMatchResult = scraperState.manualMatchResult,
                    selectedGame = scraperState.artworkGame,
                    artworkSlots = scraperState.artworkSlots,
                    artworkResult = scraperState.artworkResult,
                    onSelectGame = { game -> scraper.selectArtworkGame(game) },
                    onApplyMatch = { game, media -> scraper.applyManualMatch(game, media) },
                    onPickImage = { platform, gameId, slot ->
                        pendingArtworkPick = Triple(platform, gameId, slot)
                        artworkImagePicker.launch("image/*")
                    },
                    onClearSlot = { platform, gameId, slot ->
                        scraper.clearArtworkSlot(platform, gameId, slot)
                    },
                    onBack = pop,
                )
                is Dest.SettingsAppearance -> AppearanceScreen(
                    themeReady = themeSupportsUserColors(),
                    lastSyncOk = appearanceSyncOk,
                    onApplyColors = { colors -> applyAppearanceColors(colors) },
                    onResetColors = { resetAppearanceColors() },
                    onOpenThemes = { nav.navigate(Dest.SettingsThemes) },
                    onBack = pop,
                )
                is Dest.SettingsThemes -> SettingsThemesScreen(
                    themesRootLabel = themesRootLabel(),
                    onPickThemesRoot = { folderPicker.launch(null) },
                    onBack = pop,
                )
                is Dest.SettingsChannel -> SettingsChannelScreen(
                    channel = updateChannel,
                    onSelect = { channel ->
                        AppUpdateChannel.save(prefs, channel)
                        updateChannel = channel
                        manager.checkAppUpdate()
                    },
                    onBack = pop,
                )
                is Dest.Bios -> {
                    // v24: BIOS state recomputes when the folder is
                    // adopted, the import is attested, the library
                    // changes (PS2 game presence gates everything), or
                    // the cached BIOS scan completes. The scan itself
                    // runs on Dispatchers.IO — this block reads the
                    // cache only, never the SAF tree.
                    @Suppress("UNUSED_VARIABLE")
                    val biosScreenRev = biosRev
                    val ps2Games =
                        scraperState.systems.firstOrNull { it.platformSlug == "ps2" }?.gameCount ?: 0
                    val files = (biosScan as? BiosScanState.Ready)?.files
                    val biosScanning = biosScan is BiosScanState.Scanning
                    val ps2Status = biosInventory.ps2Status(ps2Games, files)
                    // Pure classification of the cached scan — strongest
                    // candidate first. The recursive SAF walk stays on
                    // Dispatchers.IO; this block reads the cache only,
                    // never the SAF tree.
                    val detection = biosInventory.detectPs2(files ?: emptyList())
                    val detected = detection.candidates.firstOrNull()?.file
                        ?: detection.misSized.firstOrNull()
                        ?: detection.ancillary.firstOrNull()
                    val rootState = biosInventory.probeRoot()
                    val rootDisplay = (rootState as? BiosRootState.Granted)?.displayPath
                    // v24 is PS2-first: the only firmware row with a
                    // real, scanned status. Other platforms are out of
                    // scope — omitted, never overclaimed, never gating.
                    val statusRows = BiosFirmwareTable.statusScreenPlatforms().map { fw ->
                        BiosStatusRow(fw.label, ps2Status)
                    }
                    BiosScreen(
                        state = BiosScreenState(
                            rootState = rootState,
                            rows = statusRows,
                            ps2Status = ps2Status,
                            ps2BiosDisplayPath =
                                if (rootDisplay != null && detected != null)
                                    "$rootDisplay/${detected.relativePath}"
                                else null,
                            ps2Candidates = detection.candidates,
                            ps2AncillaryOnly = detection.candidates.isEmpty() &&
                                detection.misSized.isEmpty() &&
                                detection.ancillary.isNotEmpty(),
                            ps2GameCount = ps2Games,
                            netherSX2Installed = emulatorDetector.isInstalled(
                                BiosFirmwareTable.PS2.emulatorPackage.orEmpty(),
                            ),
                            notice = biosNotice,
                            scanning = biosScanning,
                        ),
                        onSelectBiosFolder = {
                            // Open the picker at the preferred `bios/`
                            // sibling of the ROM root when derivable, so
                            // the folder is one tap away.
                            val initial = (rootState as? BiosRootState.NotGranted)
                                ?.candidateTreeUri?.let { Uri.parse(it) }
                            biosNotice = null
                            biosPicker.launch(initial)
                        },
                        onOpenNetherSX2 = { openNetherSX2() },
                        onMarkImported = {
                            biosInventory.setPs2ImportConfirmed(true)
                            biosNotice = null
                            biosRev++
                        },
                        onDismissNotice = { biosNotice = null },
                        onBack = pop,
                    )
                }
                is Dest.Diagnostics -> {
                    val info = diagInfo
                    if (info != null) {
                        DiagnosticsScreen(
                            info = info,
                            libraryScanning = scraperState.scanning,
                            onRescanLibrary = { scraper.scan() },
                            onRefresh = {
                                // SAF index read happens here; keep it off
                                // the main thread for large libraries.
                                scope.launch(Dispatchers.IO) {
                                    diagnosticsInfo = buildDiagnostics()
                                }
                            },
                            onClose = { nav.onBack() },
                        )
                    } else {
                        // Safety net: openDiagnostics always sets the info
                        // before navigating, so this should not happen.
                        PlaceholderScreen(
                            label = "LOADING DIAGNOSTICS…",
                            onBack = pop,
                        )
                    }
                }
            }
        }
    }

    /**
     * Opens the hidden Diagnostics destination. The SAF index read runs
     * off the main thread; navigation happens only after the payload is
     * ready.
     */
    private fun openDiagnostics(nav: Navigator) {
        scope.launch(Dispatchers.IO) {
            val info = buildDiagnostics()
            withContext(Dispatchers.Main) {
                diagnosticsInfo = info
                nav.navigate(Dest.Diagnostics)
            }
        }
    }

    /** Friendly one-line label for the persisted themes root. */
    private fun themesRootLabel(): String {
        val raw = storage.treeUri ?: return "NOT SELECTED"
        val name = runCatching {
            DocumentFile.fromTreeUri(this, Uri.parse(raw))?.name
        }.getOrNull()
        return if (name != null) "THEMES: $name" else "THEMES FOLDER SELECTED"
    }

    /**
     * The Crystal iiSU pack library (u44). Empty until the remote
     * catalog and pack #1 land — HOME and THEME read this and say so
     * honestly instead of inventing packs.
     */
    private fun crystalPacks(): List<CrystalPack> = emptyList()

    /**
     * Assembles the hidden Diagnostics screen payload. Runs on open and
     * on REFRESH; the screen itself never touches storage or network.
     */
    private fun buildDiagnostics(): DiagnosticsInfo = DiagnosticsInfo(
        managerVersion = "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
        appUpdate = manager.appUpdate.value,
        themesRoot = storage.treeUri,
        scraper = scraper.diagnosticsSnapshot(),
        lastCrashTrace = CrashReporter.readTrace(this),
        emulatorPackages = emulatorDetector.detectionReport().map { (pkg, installed) ->
            EmulatorPackageStatus(pkg, installed)
        },
        // u44: no pack library yet — placeholder until the catalog lands.
        installedPacks = emptyList(),
    )

    /**
     * Self-update button handler. The manager owns check/download state;
     * the activity only performs the install handoff, which needs a
     * Context for the FileProvider URI.
     */
    private fun onUpdateApp() {
        when (val s = manager.appUpdate.value) {
            is AppUpdateState.Available -> manager.downloadAppUpdate()
            is AppUpdateState.Downloaded -> installApk(s.file)
            is AppUpdateState.Idle -> manager.checkAppUpdate()
            is AppUpdateState.Failed -> manager.checkAppUpdate()
            else -> { /* Checking / Downloading / Installing: busy */ }
        }
    }

    private fun installApk(apk: File) {
        when (val result = ApkInstaller(this).install(apk)) {
            ApkInstaller.Result.Started -> manager.noteAppInstallStarted()
            ApkInstaller.Result.NeedsPermission -> {
                startActivity(ApkInstaller.unknownSourcesIntent(packageName))
                manager.noteAppNeedsInstallPermission(
                    "ALLOW \"INSTALL UNKNOWN APPS\" FOR CRYSTAL NOVA, " +
                        "THEN TAP UPDATE APP AGAIN",
                )
            }
            is ApkInstaller.Result.Failed -> manager.noteAppUpdateFailed(result.message)
        }
    }

    /** True when iiSU's launch intent resolves — the only install check. */
    private fun isIisuInstalled(): Boolean =
        packageManager.getLaunchIntentForPackage(IISU_PACKAGE) != null

    /** iiSU's versionName, or null when it's not installed. */
    private fun iisuVersionName(): String? = runCatching {
        val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                IISU_PACKAGE,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(IISU_PACKAGE, 0)
        }
        info.versionName
    }.getOrNull()

    /**
     * HOME's LAUNCH iiSU action. The button is disabled when iiSU is
     * not installed, so a null intent here is a no-op by construction.
     */
    private fun openIisu() {
        try {
            packageManager.getLaunchIntentForPackage(IISU_PACKAGE)?.let(::startActivity)
        } catch (_: Exception) {
            // iiSU vanished between the check and the tap — HOME
            // recomputes on resume and the button disables itself.
        }
    }

    /**
     * APPEARANCE: applies the user's four identity colors to the app
     * (persisted + live) and writes `crystal-user-colors.json` for the
     * legacy Pegasus theme. A failed theme write still keeps the
     * app-side palette — the theme simply keeps its defaults until the
     * next successful sync.
     */
    private fun applyAppearanceColors(colors: Crystal.IdentityColors) {
        Crystal.applyCustomPalette(prefsStore, colors)
        val json = locations.userColorsJson(
            backgroundHex = colors.background.toHex(),
            accentHex = colors.accent.toHex(),
            creamHex = colors.cream.toHex(),
            joystickHex = colors.joystick.toHex(),
        )
        appearanceSyncOk = locations.writeUserColors(storage.treeUri, json)
    }

    /**
     * APPEARANCE: drops the custom palette (app + theme file).
     */
    private fun resetAppearanceColors() {
        Crystal.resetToDefault(prefsStore)
        locations.deleteUserColors(storage.treeUri)
        appearanceSyncOk = null
    }

    /**
     * True when the installed Pegasus theme understands
     * `crystal-user-colors.json` (unknown version counts as too old).
     */
    private fun themeSupportsUserColors(): Boolean {
        val installed = storage.readInstalledVersion()?.version ?: return false
        return compareVersions(
            installed,
            StorageLocations.USER_COLORS_MIN_THEME_VERSION,
        ) >= 0
    }

    /**
     * v24: opens NetherSX2's own UI for its in-app BIOS import
     * (App Settings → BIOS → Import BIOS). There is no supported
     * intent into the BIOS picker and no way for Crystal to write
     * NetherSX2's app-private `files/bios/` on Android 11+, so the
     * single user tap inside NetherSX2 is the whole integration —
     * this just gets them there. Failures surface on the BIOS screen.
     */
    private fun openNetherSX2() {
        val pkg = BiosFirmwareTable.PS2.emulatorPackage ?: return
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) startActivity(intent)
            else biosNotice = "NETHERSX2 NOT INSTALLED"
        } catch (_: Exception) {
            biosNotice = "COULD NOT OPEN NETHERSX2"
        }
    }

    override fun onResume() {
        super.onResume()
        // The system package installer gives no callback: if we are
        // still in Installing when the activity comes back, the user
        // backed out of the system prompt and the install never
        // happened (a completed install kills this process). Recover
        // to a retryable state instead of stranding the UI.
        manager.noteAppInstallAborted()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * INJECT availability for the archived Pegasus Setup screen. Kept for
 * the [InjectEnabledTest] contract; the screen itself is unreferenced
 * since u44 (Pegasus is legacy/fallback).
 */
internal fun isInjectEnabled(
    romWritable: Boolean,
    busy: Boolean,
    rows: List<PegasusSystemRow>,
): Boolean = romWritable && !busy &&
    rows.any { it.gameCount > 0 && it.launcherStatus != "NOT CONFIGURED" }
