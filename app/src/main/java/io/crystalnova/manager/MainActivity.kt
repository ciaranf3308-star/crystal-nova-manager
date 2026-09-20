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
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.storage.EsdeThemeInstaller
import io.crystalnova.manager.storage.EsdeThemeInstallResult
import io.crystalnova.manager.storage.SafThemeFs
import io.crystalnova.manager.storage.SafThemeStorage
import io.crystalnova.manager.ui.Dest
import io.crystalnova.manager.ui.EsdeInstallUiState
import io.crystalnova.manager.ui.HomeScreen
import io.crystalnova.manager.ui.Navigator
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
 * u50: one IO-thread probe of the ES-DE SAF grant — whether the
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
 * Navigation hub (u50: THE STRIP). The app is ONE screen: the Crystal
 * ES-DE theme updater + the manager self-update route. Everything the
 * old app did (iiSU pack manager, Pegasus, scraper, BIOS, diagnostics,
 * settings, appearance) is deleted — this activity owns only:
 *
 *   - the [UpdateManager] self-update checker/updater wiring
 *   - the [EsdeThemeLibrary] catalog + download flow
 *   - the [EsdeThemeInstaller] SAF install flow
 *   - the ES-DE themes-folder SAF grant (u49 never-reject behavior)
 *   - the ES-DE activation write + OPEN ES-DE launcher
 *   - B on HOME exits (the only destination)
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** ES-DE's Android package (+ the Galaxy-store variant). */
        const val ESDE_PACKAGE = "org.es_de.frontend"
        const val ESDE_PACKAGE_GALAXY = "org.es_de.frontend.galaxy"

        /** Prefs key for the ES-DE themes folder SAF grant. */
        const val KEY_ESDE_THEMES_TREE_URI = "esde.themes.treeUri"
    }

    private lateinit var manager: UpdateManager
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
    private val scope = MainScope()

    /** Human-readable build tag, e.g. "1.2.4-u50-stripped (65)". */
    private val appVersionLabel: String =
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** The remembered [Navigator], mirrored here for the back callback. */
    private var navigator: Navigator? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = SharedPrefsStore(getSharedPreferences("crystal-nova-manager", MODE_PRIVATE))
        // u50: the legacy theme-storage grant is kept only because the
        // UpdateManager still takes it as its constructor dependency;
        // nothing in the UI reads it anymore.
        val fs = SafThemeFs(this) { prefs.getString(SafThemeStorage.KEY_TREE_URI) }
        val storage = SafThemeStorage(fs, prefs)
        manager = UpdateManager(
            storage = storage,
            github = GitHubRepository(),
            workDir = File(cacheDir, "updater").apply { mkdirs() },
            scope = scope,
            appVersion = appVersionLabel,
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
            val appUpdate by manager.appUpdate.collectAsState()
            val pop: () -> Unit = { if (nav.onBack()) finish() }

            when (val dest = nav.current) {
                is Dest.Home -> {
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
                    val minManagerNotice = catalog?.let { c ->
                        val want = c.minManagerVersion ?: c.minManagerVersionCode?.let { "vc$it" }
                        if (want != null && io.crystalnova.manager.data.managerBelowMinimum(
                                c.minManagerVersion,
                                c.minManagerVersionCode,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE,
                            )
                        )
                            "THIS THEME WANTS MANAGER $want — " +
                                "THIS MANAGER IS ${BuildConfig.VERSION_NAME}. " +
                                "UPDATE THE MANAGER FIRST."
                        else null
                    }
                    HomeScreen(
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
                        managerVersionLabel = appVersionLabel,
                        appUpdate = appUpdate,
                        onUpdateApp = { onUpdateApp() },
                        onRefresh = { esdeThemeLibrary.refresh() },
                        onGrantFolder = { launchEsdeFolderPicker() },
                        onDownload = { esdeThemeLibrary.downloadTheme(it) },
                        onInstall = { entry, zip -> performEsdeInstall(entry, zip) },
                        onLaunchEsde = { launchEsde() },
                        onDismissInstall = {
                            esdeInstallUi = EsdeInstallUiState.Idle
                            esdeLaunchNotice = null
                        },
                        onExit = pop,
                    )
                }
            }
        }
    }

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
