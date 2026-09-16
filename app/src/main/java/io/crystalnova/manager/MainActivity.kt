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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.storage.SafThemeFs
import io.crystalnova.manager.storage.SafThemeStorage
import io.crystalnova.manager.ui.Crystal
import io.crystalnova.manager.ui.CrystalButton
import io.crystalnova.manager.diag.DiagnosticsInfo
import io.crystalnova.manager.ui.DiagnosticsScreen
import io.crystalnova.manager.ui.FocusDispatcher
import io.crystalnova.manager.ui.ScraperScreen
import io.crystalnova.manager.ui.ThemeUpdateScreen
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
    private lateinit var scraper: ScraperManager
    private val scope = MainScope()

    /** Hidden diagnostics overlay state (5 taps on the version label). */
    private val showDiagnostics = mutableStateOf(false)
    private var diagnosticsInfo: DiagnosticsInfo? by mutableStateOf(null)

    private enum class Section { THEME, SCRAPER }

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

    private val gamesFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    scraper.setGamesFolder(uri.toString())
                } catch (_: SecurityException) {
                    // Stay on the scraper onboarding screen; user can retry.
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

        val prefs = SharedPrefsStore(getSharedPreferences("crystal-nova-manager", MODE_PRIVATE))
        val fs = SafThemeFs(this) { prefs.getString(SafThemeStorage.KEY_TREE_URI) }
        storage = SafThemeStorage(fs, prefs)
        // U1.1: repair a U1-persisted root that points at the theme folder
        // itself before the manager's initial refresh reads it.
        val pendingFolderNotice = maybeRepairPersistedRoot()
        manager = UpdateManager(
            storage = storage,
            github = GitHubRepository(),
            workDir = File(cacheDir, "updater").apply { mkdirs() },
            scope = scope,
            appVersion = BuildConfig.VERSION_NAME,
        )
        if (pendingFolderNotice != null) manager.refresh(pendingFolderNotice)

        scraper = ScraperManager(
            context = this,
            prefs = prefs,
            themesTreeUri = { storage.treeUri },
            scope = scope,
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        setContent {
            var section by remember { mutableStateOf(Section.THEME) }
            val tabDispatcher = remember { FocusDispatcher() }
            val diagDispatcher = remember { FocusDispatcher() }
            val diagOpen by showDiagnostics
            val diagInfo = diagnosticsInfo
            if (diagOpen && diagInfo != null) {
                DiagnosticsScreen(
                    info = diagInfo,
                    dispatcher = diagDispatcher,
                    onRefresh = {
                        scope.launch(Dispatchers.IO) {
                            diagnosticsInfo = buildDiagnostics()
                        }
                    },
                    onClose = { showDiagnostics.value = false },
                )
            } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Crystal.Background),
            ) {
                // Section tabs: THEME | SCRAPER. Controller-navigable.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 12.dp),
                ) {
                    for (tab in Section.entries) {
                        val selected = tab == section
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        ) {
                            CrystalButton(
                                key = "tab-$tab",
                                label = (if (selected) "> " else "") + tab.name,
                                onClick = {
                                    section = tab
                                    if (tab == Section.SCRAPER) scraper.refresh()
                                },
                                dispatcher = tabDispatcher,
                            )
                        }
                    }
                }
                when (section) {
                    Section.THEME -> {
                        val state by manager.state.collectAsState()
                        val appUpdate by manager.appUpdate.collectAsState()
                        ThemeUpdateScreen(
                            state = state,
                            onEvent = { event ->
                                if (event === ManagerEvent.OpenPegasus) openPegasus()
                                else manager.onEvent(event)
                            },
                            pegasusLaunchable = isPegasusInstalled(),
                            onPickFolder = { folderPicker.launch(null) },
                            onExit = { finish() },
                            appVersion = BuildConfig.VERSION_NAME,
                            appUpdate = appUpdate,
                            onUpdateApp = { onUpdateApp() },
                            onDiagnostics = {
                                // SAF index read happens here; keep it off
                                // the main thread for large libraries.
                                scope.launch(Dispatchers.IO) {
                                    diagnosticsInfo = buildDiagnostics()
                                    showDiagnostics.value = true
                                }
                            },
                        )
                    }
                    Section.SCRAPER -> {
                        val scraperState by scraper.state.collectAsState()
                        ScraperScreen(
                            state = scraperState,
                            onPickGamesFolder = { gamesFolderPicker.launch(null) },
                            onScan = { scraper.scan() },
                            onSelectPlatform = { scraper.selectPlatform(it) },
                            onStartScrape = { scraper.startScrape() },
                            onRetry = { scraper.retryIncomplete() },
                            onCancel = { scraper.cancelScrape() },
                            onDismissNotice = { scraper.dismissNotice() },
                            onExit = { finish() },
                        )
                    }
                }
            }
            }
        }
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

    private fun handleBack() {
        if (showDiagnostics.value) {
            showDiagnostics.value = false
            return
        }
        when (manager.state.value) {
            is ManagerState.Ready,
            is ManagerState.NeedsFolder,
            -> finish()
            else -> manager.onEvent(ManagerEvent.Dismiss)
        }
    }

    private fun isPegasusInstalled(): Boolean =
        packageManager.getLaunchIntentForPackage(PEGASUS_PACKAGE) != null

    private fun openPegasus() {
        packageManager.getLaunchIntentForPackage(PEGASUS_PACKAGE)?.let(::startActivity)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
