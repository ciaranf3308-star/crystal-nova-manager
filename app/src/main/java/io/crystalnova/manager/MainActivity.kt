package io.crystalnova.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.EsdeThemeEntry
import io.crystalnova.manager.data.EsdeThemeLibrary
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.data.InstalledPack
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.data.PackEntry
import io.crystalnova.manager.data.PackLibrary
import io.crystalnova.manager.data.TestPackInjector
import io.crystalnova.manager.data.compareVersions
import io.crystalnova.manager.storage.EsdeThemeInstaller
import io.crystalnova.manager.storage.EsdeThemeInstallResult
import io.crystalnova.manager.ui.EsdeInstallUiState
import io.crystalnova.manager.ui.EsdeThemeScreen
import io.crystalnova.manager.ui.ManualImport
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
import io.crystalnova.manager.ui.Dest
import io.crystalnova.manager.ui.decodePreviewImage
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
 * u48: one IO-thread probe of the ES-DE SAF grant — whether the
 * persisted themes URI still resolves, whether `themes/crystal/`
 * exists, the version marker, and the folder label.
 */
private data class EsdeFolderProbe(
    val granted: Boolean,
    val onDisk: Boolean,
    val marker: Pair<String, Int?>?,
    val label: String?,
)

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

        /**
         * u48: the iiSU pack UI is HIDDEN, not deleted. Flip back to
         * true to restore the Crystal iiSU pack manager on the THEME
         * screen; all iiSU code paths stay intact.
         */
        const val SHOW_IISU_PACKS = false

        /** ES-DE's Android package (+ the Galaxy-store variant). */
        const val ESDE_PACKAGE = "org.es_de.frontend"
        const val ESDE_PACKAGE_GALAXY = "org.es_de.frontend.galaxy"

        /** Prefs key for the ES-DE themes folder SAF grant. */
        const val KEY_ESDE_THEMES_TREE_URI = "esde.themes.treeUri"
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
    /** u45: the Crystal iiSU pack library (remote catalog + downloads). */
    private lateinit var packLibrary: PackLibrary
    /** u48: the ES-DE "crystal" theme library (remote catalog + downloads). */
    private lateinit var esdeThemeLibrary: EsdeThemeLibrary
    /** u48: the SAF installer for the ES-DE theme ZIP. */
    private lateinit var esdeThemeInstaller: EsdeThemeInstaller
    /** u48: UI state for one ES-DE theme install run. */
    private var esdeInstallUi: EsdeInstallUiState by mutableStateOf(EsdeInstallUiState.Idle)
    /** u48: last ES-DE themes-folder grant failure, surfaced on the screen. */
    private var esdeFolderNotice: String? by mutableStateOf(null)
    /** u48: last ES-DE launch failure, surfaced on the screen. */
    private var esdeLaunchNotice: String? by mutableStateOf(null)
    /**
     * u48: bumped when the ES-DE grant changes (or an install lands)
     * so the screen recomputes disk presence (state lives in SAF, not
     * in a flow).
     */
    private var esdeGrantRev: Int by mutableStateOf(0)
    /** Lazily-loaded pack preview bitmaps, keyed by pack id. */
    private var packPreviews by mutableStateOf(mapOf<String, androidx.compose.ui.graphics.ImageBitmap>())
    /** Pack ids whose preview fetch has already been kicked off. */
    private val previewLoadsStarted = mutableSetOf<String>()
    /** Manual-import guidance shown when iiSU does not take the share. */
    private var manualImport: ManualImport? by mutableStateOf(null)
    /** Notice inside the manual-import panel (e.g. no file manager). */
    private var manualImportNotice: String? by mutableStateOf(null)
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
    /** u46: test-pack inject busy flag (Settings test utility). */
    private var injectBusy by mutableStateOf(false)
    /** u46: last INJECT TEST PACK result, shown on the Settings screen. */
    private var injectStatus: String? by mutableStateOf(null)
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
     * u49: ES-DE themes-folder picker. Grants the ES-DE themes folder
     * ONCE with a persistable SAF tree permission — the only storage
     * access the theme updater holds. NO MANAGE_EXTERNAL_STORAGE, ever.
     *
     * The initial URI hints at the Nova's real layout
     * (`FOUND.000/themes` on internal storage); the user can still
     * browse anywhere, and `ES-DE/themes` works as a fallback.
     *
     * The user's pick is NEVER rejected: the persistable permission
     * is taken on the exact URI the picker returned, FIRST, before
     * any DocumentFile probing. If the picked folder is not itself
     * named `themes` and contains a `themes` child, we descend into
     * it (e.g. the user picked FOUND.000 itself). A wrong pick is
     * recoverable from the screen's CHANGE/RE-PICK button.
     */
    private val esdeFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            var notice: String? = null
            if (uri != null) {
                if (adoptEsdeThemesDir(uri)) {
                    esdeGrantRev++
                } else {
                    notice = "COULD NOT KEEP ACCESS — PLEASE TRY AGAIN"
                }
            }
            esdeFolderNotice = notice
        }

    /** Opens the ES-DE themes-folder picker with the FOUND.000 hint. */
    private fun launchEsdeFolderPicker() {
        val initial = runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:FOUND.000/themes",
            )
        }.getOrNull()
        esdeFolderPicker.launch(initial)
    }

    /**
     * Adopts the ES-DE themes folder grant. The persistable permission
     * is taken on the EXACT picker-returned URI FIRST, before any
     * DocumentFile probing — the user's pick is never vetoed.
     * Best-effort descend: when the picked folder isn't itself named
     * `themes` and contains a `themes` child directory, the child's
     * permission is taken too and its URI persisted; when that take
     * fails, the parent grant is kept and the installer descends into
     * the child itself.
     */
    private fun adoptEsdeThemesDir(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            return false
        }
        var targetUri = uri
        runCatching {
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root != null && root.isDirectory &&
                !root.name.equals("themes", ignoreCase = true)
            ) {
                val child = root.findFile("themes")
                if (child != null && child.isDirectory) {
                    val childTreeUri = DocumentsContract.buildTreeDocumentUri(
                        uri.authority ?: return@runCatching,
                        DocumentsContract.getDocumentId(child.uri),
                    )
                    runCatching {
                        contentResolver.takePersistableUriPermission(childTreeUri, flags)
                    }.onSuccess { targetUri = childTreeUri }
                }
            }
        }
        getSharedPreferences("crystal-nova-manager", MODE_PRIVATE)
            .edit()
            .putString(KEY_ESDE_THEMES_TREE_URI, targetUri.toString())
            .apply()
        return true
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

        // u45/u47: the Crystal iiSU pack library. The catalog is
        // published by the pack pipeline to the crystal-nova-packs
        // repo's `stable` release — not by the manager CI. Until it
        // is reachable the pack screen reports that honestly.
        packLibrary = PackLibrary(
            workDir = File(cacheDir, "packs").apply { mkdirs() },
            scope = scope,
            prefs = prefs,
        )

        // u48: the ES-DE "crystal" theme updater. The theme catalog is
        // published by the theme pipeline to the crystal-esde-theme
        // repo's `stable` release — not by the manager CI. Until it is
        // reachable the theme screen reports that honestly.
        esdeThemeLibrary = EsdeThemeLibrary(
            workDir = File(cacheDir, "esde-theme").apply { mkdirs() },
            scope = scope,
            prefs = prefs,
        )
        esdeThemeInstaller = EsdeThemeInstaller(
            context = this,
            cacheDir = cacheDir,
        )

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
                    val installedPack: InstalledPack? = packLibrary.installedPack()
                    val homeStatus: HomeStatus = remember(
                        scraperState.systems,
                        scraperState.romLocation,
                        iisuInstalled,
                        iisuVersion,
                        installedPack,
                    ) {
                        buildHomeStatus(
                            systems = scraperState.systems.map { it.label to it.gameCount },
                            romReady = scraperState.romLocation is LocationState.Ready,
                            iisuInstalled = iisuInstalled,
                            iisuVersion = iisuVersion,
                            // u45: the installed pack is real now —
                            // persisted when the user hands a pack ZIP
                            // to iiSU; null keeps the honest "none".
                            packName = installedPack?.name,
                            packVersion = installedPack?.version,
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
                is Dest.Theme -> {
                    if (SHOW_IISU_PACKS) {
                        // iiSU pack UI (HIDDEN since u48 — kept intact).
                        val packCatalog by packLibrary.catalog.collectAsState()
                        val packDownload by packLibrary.download.collectAsState()
                        ThemeScreen(
                            catalogState = packCatalog,
                            downloadState = packDownload,
                            installed = packLibrary.installedPack(),
                            previewOf = { packPreviews[it] },
                            onPreviewNeeded = { requestPackPreview(it) },
                            manualImport = manualImport,
                            manualImportNotice = manualImportNotice,
                            onRefresh = { packLibrary.refresh() },
                            onDownload = { packLibrary.downloadPack(it) },
                            onInstallToIisu = { pack, zip -> sharePackToIisu(pack, zip) },
                            onOpenFileManager = { openPackInFileManager() },
                            onDismissManualImport = {
                                manualImport = null
                                manualImportNotice = null
                            },
                            onBack = pop,
                        )
                    } else {
                        // u48: the ES-DE "crystal" theme updater.
                        val esdeCatalog by esdeThemeLibrary.catalog.collectAsState()
                        val esdeDownload by esdeThemeLibrary.download.collectAsState()
                        val treeUri = prefs.getString(KEY_ESDE_THEMES_TREE_URI)
                        // SAF state lives outside Compose: probe it on IO
                        // when the grant changes or an install lands
                        // (esdeGrantRev) — a resolver query on Main can
                        // block. The installer still re-validates the
                        // persisted URI on every install.
                        var esdeProbe by remember { mutableStateOf<EsdeFolderProbe?>(null) }
                        LaunchedEffect(treeUri, esdeGrantRev) {
                            esdeProbe = withContext(Dispatchers.IO) {
                                val granted =
                                    esdeThemeInstaller.resolveThemesDir(treeUri) != null
                                EsdeFolderProbe(
                                    granted = granted,
                                    onDisk = granted &&
                                        esdeThemeInstaller.themePresentOnDisk(treeUri),
                                    marker = if (granted) {
                                        esdeThemeInstaller.readInstalledMarker(treeUri)
                                    } else {
                                        null
                                    },
                                    label = if (granted) {
                                        esdeThemesFolderLabel(treeUri)
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        val catalog = (esdeCatalog as? io.crystalnova.manager.data.EsdeThemeCatalogState.Ready)?.catalog
                        val minManagerNotice = catalog?.minManagerVersion
                            ?.takeIf { it != BuildConfig.VERSION_NAME }
                            ?.let { want ->
                                "THIS THEME WANTS MANAGER $want — " +
                                    "THIS MANAGER IS ${BuildConfig.VERSION_NAME}. " +
                                    "UPDATE THE MANAGER FIRST."
                            }
                        EsdeThemeScreen(
                            catalogState = esdeCatalog,
                            downloadState = esdeDownload,
                            installed = esdeThemeLibrary.installedTheme(),
                            installedOnDisk = esdeProbe?.onDisk == true,
                            diskVersion = esdeProbe?.marker?.first,
                            folderGranted = esdeProbe?.granted == true,
                            folderLabel = esdeProbe?.label,
                            folderNotice = esdeFolderNotice,
                            installState = esdeInstallUi,
                            esdeInstalled = isEsdeInstalled(),
                            esdeNotice = esdeLaunchNotice,
                            minManagerNotice = minManagerNotice,
                            onRefresh = { esdeThemeLibrary.refresh() },
                            onGrantFolder = { launchEsdeFolderPicker() },
                            onDownload = { esdeThemeLibrary.downloadTheme(it) },
                            onInstall = { entry, zip -> performEsdeInstall(entry, zip) },
                            onLaunchEsde = { launchEsde() },
                            onDismissInstall = {
                                esdeInstallUi = EsdeInstallUiState.Idle
                                esdeLaunchNotice = null
                            },
                            onBack = pop,
                        )
                    }
                }
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
                    injectBusy = injectBusy,
                    injectStatus = injectStatus,
                    onInjectTestPack = { onInjectTestPack() },
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
     * Hands a downloaded pack ZIP to iiSU. Prefers a direct share to
     * iiSU itself; when iiSU does not resolve the share (unconfirmed
     * on current iiSU builds) the screen shows manual-import guidance
     * instead. Never touches iiSU's private storage.
     */
    private fun sharePackToIisu(pack: PackEntry, zip: File) {
        if (!zip.isFile || zip.length() == 0L) {
            packLibrary.clearDownload()
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                zip,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("pack", uri)
            }
            val resolvesIisu = packageManager
                .queryIntentActivities(send, 0)
                .any { it.activityInfo.packageName == IISU_PACKAGE }
            if (resolvesIisu) {
                send.setPackage(IISU_PACKAGE)
                grantUriPermission(
                    IISU_PACKAGE,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                startActivity(send)
                // Handoff, not proof of import: the pack card keeps a
                // re-share action so a failed import is recoverable.
                packLibrary.noteInstalled(pack)
            } else {
                manualImportNotice = null
                manualImport = ManualImport(pack = pack, zipName = zip.name)
            }
        } catch (_: Exception) {
            manualImportNotice = null
            manualImport = ManualImport(pack = pack, zipName = zip.name)
        }
    }

    /** Opens the pack ZIP in a file manager from the manual-import panel. */
    private fun openPackInFileManager() {
        val zipName = manualImport?.zipName ?: return
        try {
            val zip = File(File(cacheDir, "packs"), zipName)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                zip,
            )
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/zip")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(view, "Open pack ZIP"))
        } catch (_: Exception) {
            manualImportNotice = "NO FILE MANAGER FOUND — IMPORT " +
                "THE ZIP FROM iiSU'S OWN THEMES SCREEN"
        }
    }

    /** Lazily fetches and decodes one pack preview image. */
    private fun requestPackPreview(pack: PackEntry) {
        if (!previewLoadsStarted.add(pack.id)) return
        scope.launch {
            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                packLibrary.previewBytes(pack)?.let(::decodePreviewImage)
            }
            if (bitmap != null) {
                packPreviews = packPreviews + (pack.id to bitmap)
            }
        }
    }

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
        // u45: the installed pack is real now (persisted at share time).
        installedPacks = packLibrary.installedPack()
            ?.let { listOf("${it.name} v${it.version ?: "?"}") }
            ?: emptyList(),
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

    /**
     * u46 test utility: copies the APK-bundled crystal-test-pack.zip into
     * the shared Downloads collection via MediaStore, SHA-256-verifying
     * the copy. Runs off the main thread; the result (honest success or
     * failure) lands in [injectStatus] for the Settings screen.
     */
    private fun onInjectTestPack() {
        if (injectBusy) return
        injectBusy = true
        injectStatus = null
        scope.launch {
            val result = TestPackInjector.inject(this@MainActivity)
            injectBusy = false
            injectStatus = when (result) {
                is TestPackInjector.Result.Ok -> result.message
                is TestPackInjector.Result.Err -> result.message
            }
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

    /**
     * u48: runs the ES-DE theme install. Validates the persisted themes
     * grant first (re-prompt on failure), skips the ThemeSet write when
     * ES-DE is running, and only records the install in prefs after the
     * SAF swap succeeded.
     */
    private fun performEsdeInstall(entry: EsdeThemeEntry, zip: File) {
        if (esdeInstallUi is EsdeInstallUiState.Installing) return
        esdeInstallUi = EsdeInstallUiState.Installing("STARTING…")
        scope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("crystal-nova-manager", MODE_PRIVATE)
            val treeUri = prefs.getString(KEY_ESDE_THEMES_TREE_URI, null)
            val valid = esdeThemeInstaller.resolveThemesDir(treeUri) != null
            if (!valid) {
                withContext(Dispatchers.Main) {
                    esdeFolderNotice = "THEMES FOLDER ACCESS LOST — GRANT IT AGAIN"
                    esdeGrantRev++
                    esdeInstallUi = EsdeInstallUiState.Idle
                }
                return@launch
            }
            val running = isEsdeRunning()
            val result = esdeThemeInstaller.install(
                zip = zip,
                entry = entry,
                treeUriString = treeUri!!,
                esdeRunning = running,
                onStep = { step ->
                    scope.launch(Dispatchers.Main) {
                        esdeInstallUi = EsdeInstallUiState.Installing(step)
                    }
                },
            )
            withContext(Dispatchers.Main) {
                when (result) {
                    is EsdeThemeInstallResult.Success -> {
                        esdeThemeLibrary.noteInstalled(entry)
                        esdeThemeLibrary.clearDownload()
                        esdeGrantRev++
                        esdeInstallUi = EsdeInstallUiState.Done(
                            version = entry.version,
                            notes = result.notes,
                        )
                    }
                    is EsdeThemeInstallResult.Failure -> {
                        // Keep the verified ZIP: the user can retry the
                        // install without re-downloading.
                        esdeInstallUi = EsdeInstallUiState.Failed(
                            message = result.message,
                            notes = result.notes,
                        )
                    }
                }
            }
        }
    }

    /** Friendly one-line label for the persisted ES-DE themes folder. */
    private fun esdeThemesFolderLabel(treeUri: String?): String? {
        if (treeUri.isNullOrBlank()) return null
        return runCatching {
            DocumentFile.fromTreeUri(this, Uri.parse(treeUri))?.name
        }.getOrNull()
    }

    /** True when ES-DE appears to be running right now. */
    private fun isEsdeRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.any { proc ->
                proc.processName == ESDE_PACKAGE ||
                    proc.processName.startsWith("$ESDE_PACKAGE:") ||
                    proc.processName == ESDE_PACKAGE_GALAXY ||
                    proc.processName.startsWith("$ESDE_PACKAGE_GALAXY:")
            } == true
        } catch (_: Exception) {
            false
        }
    }

    /** True when either ES-DE package resolves a launch intent. */
    private fun isEsdeInstalled(): Boolean =
        esdeLaunchIntent() != null

    private fun esdeLaunchIntent(): Intent? =
        packageManager.getLaunchIntentForPackage(ESDE_PACKAGE)
            ?: packageManager.getLaunchIntentForPackage(ESDE_PACKAGE_GALAXY)

    /** Launches ES-DE (or reports honestly when it is not installed). */
    private fun launchEsde() {
        try {
            val intent = esdeLaunchIntent()
            if (intent != null) {
                esdeLaunchNotice = null
                startActivity(intent)
            } else {
                esdeLaunchNotice = "ES-DE IS NOT INSTALLED — INSTALL ES-DE TO USE THIS THEME"
            }
        } catch (_: Exception) {
            esdeLaunchNotice = "COULD NOT OPEN ES-DE"
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
