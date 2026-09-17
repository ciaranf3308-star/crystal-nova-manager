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
import io.crystalnova.manager.pegasus.EmulatorDetector
import io.crystalnova.manager.pegasus.LauncherAutoConfig
import io.crystalnova.manager.pegasus.LauncherPresets
import io.crystalnova.manager.pegasus.LauncherProfile
import io.crystalnova.manager.pegasus.LauncherSource
import io.crystalnova.manager.pegasus.LauncherType
import io.crystalnova.manager.pegasus.MetafileGenerator
import io.crystalnova.manager.pegasus.PegasusIntents
import io.crystalnova.manager.pegasus.PegasusLibrary
import io.crystalnova.manager.pegasus.PegasusRestartGate
import io.crystalnova.manager.scraper.match.PlatformTable
import io.crystalnova.manager.storage.LocationKind
import io.crystalnova.manager.storage.SafThemeFs
import io.crystalnova.manager.storage.SafThemeStorage
import io.crystalnova.manager.storage.StorageLocations
import io.crystalnova.manager.ui.Dest
import io.crystalnova.manager.ui.DiagnosticsScreen
import io.crystalnova.manager.ui.HomeScreen
import io.crystalnova.manager.ui.LibraryScreen
import io.crystalnova.manager.ui.Navigator
import io.crystalnova.manager.ui.PegasusLauncherScreen
import io.crystalnova.manager.ui.PegasusLaunchersScreen
import io.crystalnova.manager.ui.PegasusSetupScreen
import io.crystalnova.manager.ui.PegasusSystemRow
import io.crystalnova.manager.ui.PlaceholderScreen
import io.crystalnova.manager.ui.ProgressScreen
import io.crystalnova.manager.ui.RetroArchOption
import io.crystalnova.manager.ui.SettingsChannelScreen
import io.crystalnova.manager.ui.SettingsLocationScreen
import io.crystalnova.manager.ui.SettingsScreen
import io.crystalnova.manager.ui.SettingsThemesScreen
import io.crystalnova.manager.ui.StandaloneOption
import io.crystalnova.manager.ui.SystemScreen
import io.crystalnova.manager.ui.ThemeScreen
import io.crystalnova.manager.diag.CrashReporter
import io.crystalnova.manager.diag.DiagnosticsInfo
import io.crystalnova.manager.diag.EmulatorPackageStatus
import io.crystalnova.manager.diag.PegasusMetafileDiag
import io.crystalnova.manager.diag.SystemMetafileDiagRow
import io.crystalnova.manager.scraper.ScraperManager
import io.crystalnova.manager.updater.ApkInstaller
import io.crystalnova.manager.updater.AppUpdateState
import io.crystalnova.manager.updater.ManagerEvent
import io.crystalnova.manager.updater.ManagerState
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
        prefs.edit().remove(key).apply()
    }
}

/**
 * Navigation hub. Owns the [Navigator] back stack, the managers, and
 * the SAF folder pickers; each [Dest] renders a focused screen from
 * ui/. Controller/system back pops exactly one level from any child
 * destination and only exits from HOME.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Pegasus Frontend's Android package, verified against the official
         * source: src/app/platform/android/AndroidManifest.xml in
         * mmatyas/pegasus-frontend declares package="org.pegasus_frontend.android".
         * We never force-stop it and never assume the launch intent exists —
         * [isPegasusInstalled] checks at runtime and the UI falls back to
         * "RESTART PEGASUS TO APPLY" instructions.
         */
        const val PEGASUS_PACKAGE = "org.pegasus_frontend.android"
    }

    private lateinit var manager: UpdateManager
    private lateinit var storage: SafThemeStorage
    private lateinit var locations: StorageLocations
    private lateinit var scraper: ScraperManager
    private lateinit var pegasus: PegasusLibrary
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
    /** Pegasus setup/inject notices, surfaced on the Pegasus screens. */
    private var pegasusNotice: String? by mutableStateOf(null)
    /** True while an explicit INJECT / REFRESH is running. */
    private var pegasusBusy: Boolean by mutableStateOf(false)
    /** True while the setup assistant is applying safe launcher defaults. */
    private var pegasusAutoBusy: Boolean by mutableStateOf(false)
    /** PackageManager-backed detection of known emulator apps. */
    private val emulatorDetector: EmulatorDetector by lazy { EmulatorDetector(this) }
    /**
     * Bumped on every launcher-profile change so the Pegasus screens
     * recompose (profiles live in SharedPreferences, not in a flow).
     */
    private var pegasusProfilesRev: Int by mutableStateOf(0)
    /**
     * Bumped when the Pegasus config root is (re)picked: the setup
     * screen reads the persisted URI directly (not a state flow), so
     * without this the screen would not refresh after selection.
     */
    private var pegasusConfigRev: Int by mutableStateOf(0)

    /**
     * v18 reload path: set after every successful BUILD so the next
     * OPEN PEGASUS tap restarts Pegasus (verified full-rescan reload,
     * see [PegasusIntents]). A plain tap with no pending build keeps
     * the old resume behavior.
     */
    private val pegasusRestartGate = PegasusRestartGate()

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
     * ROM library picker. The SAF grant is taken inside
     * [StorageLocations.adoptTreeUri]; on success the scraper refreshes
     * so the Library screen flips from the picker prompt to the grid.
     *
     * v19: this is also the RE-PICK ROM ROOT repair action on the
     * Pegasus setup screen — re-picking refreshes the persistable
     * read+write grant, so the setup screen re-evaluates the metafile
     * target's writability ([pegasusConfigRev] forces the recompose).
     */
    private val romPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val ok = locations.adoptTreeUri(
                    contentResolver, uri, LocationKind.ROM, storage.treeUri,
                )
                if (ok) {
                    locationError = null
                    pegasusConfigRev++
                    scraper.refresh()
                } else {
                    locationError = "COULD NOT KEEP ROM LIBRARY ACCESS — PLEASE TRY AGAIN"
                }
            }
        }

    /**
     * MEDIA library picker. Adopting rewrites the theme bridge so
     * Pegasus finds the media without a reinstall ([StorageLocations]
     * does that inside adopt); we write the bridge once more for
     * self-healing, then refresh the scraper.
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
     * v19: the legacy pegasus-frontend config-root picker is RETIRED.
     * The metafile now lives at the top level of the ROM root (a
     * registered Pegasus game dir), so there is no separate Pegasus
     * config grant anymore — [romPicker] below doubles as the
     * RE-PICK ROM ROOT repair action on the setup screen.
     */

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
        val fs = SafThemeFs(this) { prefs.getString(SafThemeStorage.KEY_TREE_URI) }
        storage = SafThemeStorage(fs, prefs)
        locations = StorageLocations(this, prefs)
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

        pegasus = PegasusLibrary(this, prefs, locations)

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
            val themeState by manager.state.collectAsState()
            val appUpdate by manager.appUpdate.collectAsState()
            val locError = locationError
            val diagInfo = diagnosticsInfo
            // The manager's own update channel (DEV / CANDIDATE vs STABLE),
            // persisted in prefs; changing it re-checks for an app update.
            var updateChannel by remember { mutableStateOf(AppUpdateChannel.load(prefs)) }
            val pop: () -> Unit = { nav.onBack() }

            when (val dest = nav.current) {
                is Dest.Home -> {
                    // Recompute the Pegasus tile subtitle only when the
                    // profiles or the scanned systems change: pegasusRows()
                    // does PackageManager lookups, so it must not run on
                    // every recomposition (e.g. scan progress ticks).
                    @Suppress("UNUSED_VARIABLE")
                    val homeProfilesRev = pegasusProfilesRev
                    val pegasusSubtitle = remember(homeProfilesRev, scraperState.systems) {
                        val rows = pegasusRows()
                        val withGames = rows.filter { it.gameCount > 0 }
                        val issues = withGames.count {
                            it.launcherStatus == "NOT CONFIGURED" || !it.launcherInstalled
                        }
                        when {
                            withGames.isEmpty() -> "NO GAMES SCANNED"
                            issues == 0 -> "READY"
                            else -> "$issues NEED SETUP"
                        }
                    }
                    val themeSubtitle = when (val s = themeState) {
                        is ManagerState.Ready ->
                            s.installed?.let { "v${it.version}" } ?: "NOT INSTALLED"
                        else -> "—"
                    }
                    HomeScreen(
                        scraperState = scraperState,
                        appVersion = appVersionLabel,
                        appUpdate = appUpdate,
                        pegasusReady = isPegasusInstalled(),
                        pegasusSubtitle = pegasusSubtitle,
                        themeSubtitle = themeSubtitle,
                        settingsSubtitle = "${updateChannel.name} CHANNEL",
                        onUpdateApp = { onUpdateApp() },
                    onLibrary = {
                        scraper.refresh()
                        nav.navigate(Dest.Library)
                    },
                    onTheme = { nav.navigate(Dest.Theme) },
                    onPegasusSetup = { nav.navigate(Dest.PegasusSetup) },
                    onSettings = { nav.navigate(Dest.Settings) },
                    onDiagnostics = { openDiagnostics(nav) },
                    onExit = { finish() },
                    )
                }
                is Dest.Library -> LibraryScreen(
                    state = scraperState,
                    onPickRomLibrary = { romPicker.launch(null) },
                    onSelectSystem = { slug, label ->
                        nav.navigate(Dest.System(slug, label))
                    },
                    onRescan = { scraper.scan() },
                    onDismissNotice = { scraper.dismissNotice() },
                    onBack = pop,
                )
                is Dest.System -> SystemScreen(
                    slug = dest.slug,
                    label = dest.label,
                    state = scraperState,
                    onScrape = {
                        // selectPlatform(null) = all systems.
                        scraper.selectPlatform(dest.slug.ifEmpty { null })
                        scraper.startScrape()
                        nav.navigate(Dest.Progress)
                    },
                    onRetryIncomplete = {
                        scraper.selectPlatform(dest.slug.ifEmpty { null })
                        scraper.retryIncomplete()
                        nav.navigate(Dest.Progress)
                    },
                    onBack = pop,
                )
                is Dest.Progress -> ProgressScreen(
                    state = scraperState,
                    onCancel = { scraper.cancelScrape() },
                    onDone = { nav.onBack() },
                    onDismissNotice = { scraper.dismissNotice() },
                    onBack = pop,
                )
                is Dest.Theme -> ThemeScreen(
                    state = themeState,
                    onEvent = { event ->
                        if (event === ManagerEvent.OpenPegasus) openPegasus()
                        else manager.onEvent(event)
                    },
                    pegasusLaunchable = isPegasusInstalled(),
                    onPickFolder = { folderPicker.launch(null) },
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
                    onOpenThemes = { nav.navigate(Dest.SettingsThemes) },
                    onOpenChannel = { nav.navigate(Dest.SettingsChannel) },
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
                is Dest.PegasusSetup -> {
                    // Read the profiles revision so this screen recomposes
                    // after launcher choices change, and the config revision
                    // so it recomposes right after the ROM root is picked
                    // (the persisted URI is read directly, not via a flow).
                    @Suppress("UNUSED_VARIABLE")
                    val profilesRev = pegasusProfilesRev
                    @Suppress("UNUSED_VARIABLE")
                    val configRev = pegasusConfigRev
                    val rows = pegasusRows()
                    // v19: BUILD is gated on the ROM root being WRITABLE —
                    // the metafile is written to the top level of the ROM
                    // root (a registered Pegasus game dir). The legacy
                    // pegasus-frontend config-root flow is retired.
                    val romWritable = locations.hasWriteAccess(LocationKind.ROM)
                    val unconfigured = rows
                        .filter { it.gameCount > 0 && it.launcherStatus == "NOT CONFIGURED" }
                        .map { it.label.uppercase() }
                    PegasusSetupScreen(
                        metafileTarget = pegasus.metafileTargetDisplay(),
                        romWritable = romWritable,
                        systems = rows,
                        injectEnabled = isInjectEnabled(romWritable, pegasusBusy, rows),
                        injectWarning = unconfigured.takeIf { it.isNotEmpty() }
                            ?.let { "NO LAUNCHER: ${it.joinToString(", ")} — WILL BE SKIPPED" },
                        injecting = pegasusBusy,
                        notice = pegasusNotice,
                        pegasusInstalled = isPegasusInstalled(),
                        onRepickRomRoot = { romPicker.launch(null) },
                        onRescan = { scraper.scan() },
                        onConfigureLaunchers = { nav.navigate(Dest.PegasusLaunchers) },
                        onInject = { injectPegasus() },
                        onDismissNotice = { pegasusNotice = null },
                        onOpenPegasus = { openPegasus() },
                        onBack = pop,
                        autoConfiguring = pegasusAutoBusy,
                        onAutoConfigure = { autoConfigureLaunchers() },
                    )
                }
                is Dest.PegasusLaunchers -> {
                    @Suppress("UNUSED_VARIABLE")
                    val profilesRev = pegasusProfilesRev
                    PegasusLaunchersScreen(
                        systems = pegasusRows(),
                        onSelectSystem = { slug, label ->
                            nav.navigate(Dest.PegasusLauncher(slug, label))
                        },
                        onBack = pop,
                    )
                }
                is Dest.PegasusLauncher -> {
                    @Suppress("UNUSED_VARIABLE")
                    val profilesRev = pegasusProfilesRev
                    val installed = installedEmulatorPackages()
                    val slug = dest.slug
                    val explicit = pegasus.profiles.get(slug)
                    val effective = explicit ?: LauncherPresets.defaultProfile(slug)
                    val current = effective?.takeIf { it.isConfigured() }
                    PegasusLauncherScreen(
                        slug = slug,
                        label = dest.label,
                        currentStatus = current?.displayLabel() ?: "NOT CONFIGURED",
                        isDefault = explicit == null,
                        defaultProfile = LauncherPresets.defaultProfile(slug)
                            ?.takeIf { it.isConfigured() },
                        retroArchOptions = LauncherPresets.retroArchPackages.map { (pkg, tag) ->
                            RetroArchOption(
                                packageName = pkg,
                                tag = tag,
                                core = LauncherPresets.defaultCore(slug),
                                installed = pkg in installed,
                            )
                        },
                        standaloneOptions = LauncherPresets.standaloneFor(slug).map { profile ->
                            StandaloneOption(
                                profile = profile,
                                installed = profile.packageName in installed,
                            )
                        },
                        customCommand = explicit
                            ?.takeIf { it.type == LauncherType.CUSTOM }
                            ?.command.orEmpty(),
                        notice = pegasusNotice,
                        onUseDefault = {
                            LauncherPresets.defaultProfile(slug)?.let { pegasus.profiles.set(slug, it) }
                            pegasusNotice = null
                            pegasusProfilesRev++
                        },
                        onSelectRetroArch = { pkg, core ->
                            pegasus.profiles.set(slug, LauncherPresets.retroArch(pkg, core))
                            pegasusNotice = null
                            pegasusProfilesRev++
                        },
                        onSelectStandalone = { profile ->
                            pegasus.profiles.set(slug, profile)
                            pegasusNotice = null
                            pegasusProfilesRev++
                        },
                        onSaveCustom = { command ->
                            pegasus.profiles.set(
                                slug,
                                LauncherProfile(type = LauncherType.CUSTOM, command = command),
                            )
                            pegasusNotice = "CUSTOM COMMAND SAVED"
                            pegasusProfilesRev++
                        },
                        onClear = {
                            pegasus.profiles.clear(slug)
                            pegasusNotice = null
                            pegasusProfilesRev++
                        },
                        onDismissNotice = { pegasusNotice = null },
                        onBack = pop,
                    )
                }
                is Dest.Diagnostics -> {
                    val info = diagInfo
                    if (info != null) {
                        DiagnosticsScreen(
                            info = info,
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
     * Assembles the hidden Diagnostics screen payload. Runs on open and
     * on REFRESH; the screen itself never touches storage or network.
     */
    private fun buildDiagnostics(): DiagnosticsInfo = DiagnosticsInfo(
        managerVersion = "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
        managerState = manager.state.value,
        appUpdate = manager.appUpdate.value,
        themesRoot = storage.treeUri,
        scraper = scraper.diagnosticsSnapshot(),
        lastCrashTrace = CrashReporter.readTrace(this),
        emulatorPackages = emulatorDetector.detectionReport().map { (pkg, installed) ->
            EmulatorPackageStatus(pkg, installed)
        },
        // v20: the Pegasus panel reports the canonical per-system layout —
        // for every system folder written by the last verified BUILD:
        // ROM folder, metadata present yes/no, byte count, generated
        // game count — plus the aggregate and the last game_dirs.txt
        // merge outcome.
        pegasusMetafile = runCatching {
            val report = pegasus.systemMetafileReport()
            val romUri = locations.romTreeUri()
            PegasusMetafileDiag(
                romRootPath = romUri?.let { locations.displayPath(it) } ?: "NOT SELECTED",
                romWritable = locations.hasWriteAccess(LocationKind.ROM),
                fileName = MetafileGenerator.FILE_NAME,
                systems = report.systems.map { row ->
                    SystemMetafileDiagRow(
                        folder = row.folder,
                        present = row.present,
                        bytes = row.bytes,
                        games = row.games,
                    )
                },
                aggregate = report.aggregate,
                gameDirsStatus = report.gameDirs.display(),
                lastInjected = pegasus.lastInjectSummary(),
            )
        }.getOrNull(),
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

    private fun isPegasusInstalled(): Boolean =
        packageManager.getLaunchIntentForPackage(PEGASUS_PACKAGE) != null

    /**
     * v18 reload path. When a fresh BUILD is pending, launches Pegasus
     * with the verified restart intent (explicit component +
     * NEW_TASK|CLEAR_TASK, see [PegasusIntents]) so Pegasus cold-starts
     * and rescans the library — and clears the pending state. A plain
     * tap with no pending build keeps the old resume behavior (never
     * kills the user's session unnecessarily). Guarded: Pegasus may
     * not be installed, so a failed launch is a no-op, exactly like
     * the old path.
     */
    private fun openPegasus() {
        val restart = pegasusRestartGate.consumeRestart()
        val intent = if (restart) {
            PegasusIntents.restartIntent()
        } else {
            packageManager.getLaunchIntentForPackage(PEGASUS_PACKAGE)
        }
        try {
            intent?.let(::startActivity)
        } catch (_: Exception) {
            // Pegasus not installed (or launch refused): the setup
            // screen already shows the PEGASUS NOT INSTALLED line.
        }
    }

    /**
     * Emulator packages from [LauncherPresets.knownEmulatorPackages]
     * that are installed. Delegates to [EmulatorDetector]; the
     * manifest <queries> block keeps these visible on Android 11+ —
     * anything not visible reads as missing, never as installed.
     */
    private fun installedEmulatorPackages(): Set<String> =
        emulatorDetector.installedPackages()

    /**
     * One row per recognized system: game count from the last library
     * scan, launcher status from the stored profile or the curated
     * default ("NOT CONFIGURED" when neither exists), and whether the
     * named emulator app is installed (CUSTOM needs no app). The
     * effective profile and its USER/AUTO source ride along so the
     * launchers screen can render compact rows without re-deriving.
     */
    private fun pegasusRows(): List<PegasusSystemRow> {
        val installed = installedEmulatorPackages()
        val counts = scraper.state.value.systems.associate { it.platformSlug to it.gameCount }
        return PlatformTable.all().map { platform ->
            val explicit = pegasus.profiles.get(platform.slug)
            val effective = explicit ?: LauncherPresets.defaultProfile(platform.slug)
            val configured = effective?.takeIf { it.isConfigured() }
            PegasusSystemRow(
                slug = platform.slug,
                label = platform.displayName,
                gameCount = counts[platform.slug] ?: 0,
                launcherStatus = configured?.displayLabel() ?: "NOT CONFIGURED",
                launcherInstalled = configured?.let {
                    it.type == LauncherType.CUSTOM || it.packageName in installed
                } ?: true,
                isDefault = explicit == null,
                profile = configured,
                source = if (explicit != null) {
                    pegasus.profiles.getSource(platform.slug)
                } else {
                    LauncherSource.USER
                },
            )
        }
    }

    /**
     * The setup assistant: applies safe launcher defaults for systems
     * with games. Explicit USER choices are never overwritten; systems
     * with no safe installed launcher stay NEEDS ATTENTION. Runs off
     * the main thread; the summary lands in [pegasusNotice].
     */
    private fun autoConfigureLaunchers() {
        if (pegasusAutoBusy) return
        pegasusAutoBusy = true
        pegasusNotice = null
        scope.launch(Dispatchers.IO) {
            val installed = installedEmulatorPackages()
            val slugsWithGames = scraper.state.value.systems
                .filter { it.gameCount > 0 }
                .map { it.platformSlug }
            val outcome = LauncherAutoConfig.apply(pegasus.profiles, slugsWithGames, installed)
            withContext(Dispatchers.Main) {
                pegasusAutoBusy = false
                pegasusProfilesRev++
                pegasusNotice = outcome.summary()
            }
        }
    }

    /**
     * Explicit INJECT / BUILD PEGASUS LIBRARY. Runs off the main
     * thread; the result (counts, skipped systems, or a failure
     * message) lands in [pegasusNotice]. Never runs automatically.
     *
     * v18: a verified build arms the restart gate — the next OPEN
     * PEGASUS tap restarts Pegasus so it cold-starts and rescans the
     * new library (see [PegasusIntents]).
     */
    private fun injectPegasus() {
        if (pegasusBusy) return
        pegasusBusy = true
        pegasusNotice = null
        scope.launch(Dispatchers.IO) {
            val outcome = pegasus.inject()
            withContext(Dispatchers.Main) {
                pegasusBusy = false
                pegasusNotice = when (outcome) {
                    is PegasusLibrary.InjectOutcome.Ok -> {
                        pegasusRestartGate.pendingBuild = true
                        buildString {
                            append("LIBRARY BUILT · ${outcome.metafiles} SYSTEM METAFILES · ${outcome.games} GAMES")
                            if (outcome.skippedNoLauncher.isNotEmpty()) {
                                append(" — SKIPPED — NO LAUNCHER: ")
                                append(outcome.skippedNoLauncher.joinToString(", ").uppercase())
                            }
                            if (outcome.unknownFolders.isNotEmpty()) {
                                append(" — IGNORED FOLDERS (NOT RECOGNIZED): ")
                                append(outcome.unknownFolders.joinToString(", ").uppercase())
                            }
                            append(" · OPEN PEGASUS TO RESTART & RELOAD")
                        }.toString()
                    }
                    is PegasusLibrary.InjectOutcome.Failed -> outcome.message
                }
            }
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
 * INJECT availability for Pegasus Setup. Enabled when the ROM root is
 * writable (v20: one metadata file is written per populated system
 * folder), nothing is already running, and at least one populated system
 * has a configured launcher. Populated-but-unconfigured systems are
 * skipped and reported by the injection itself (see
 * InjectOutcome.Ok.skippedNoLauncher) — they never block it.
 */
internal fun isInjectEnabled(
    romWritable: Boolean,
    busy: Boolean,
    rows: List<PegasusSystemRow>,
): Boolean = romWritable && !busy &&
    rows.any { it.gameCount > 0 && it.launcherStatus != "NOT CONFIGURED" }
