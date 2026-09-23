package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.crystalnova.manager.data.EsdeThemeCatalogState
import io.crystalnova.manager.data.EsdeThemeDownloadState
import io.crystalnova.manager.data.EsdeThemeEntry
import io.crystalnova.manager.data.EsdeThemeInstalled
import io.crystalnova.manager.updater.AppUpdateState
import java.io.File

/**
 * HOME (u50: THE STRIP): the whole app is one screen — the Crystal
 * ES-DE theme updater plus the MANAGER self-update route. No other
 * destinations, no dead buttons. (u52 adds one deliberate exception:
 * the GAME IMPORTER entry, which opens the importer's own
 * destination stack.)
 *
 * The theme updater content lives in [EsdeThemeScreen]; this wrapper
 * pins it to the HOME route (B exits the app) and appends the
 * MANAGER section: current manager version, CHECK FOR UPDATE →
 * DOWNLOAD/INSTALL. Every future manager build ships through that
 * route — it must never break.
 *
 * Pure function of its inputs: every side effect (network, SAF,
 * intents, persistence) arrives as a callback owned by the activity.
 */
@Composable
fun HomeScreen(
    catalogState: EsdeThemeCatalogState,
    downloadState: EsdeThemeDownloadState,
    installed: EsdeThemeInstalled?,
    installedOnDisk: Boolean,
    diskVersion: String?,
    folderGranted: Boolean,
    folderLabel: String?,
    folderNotice: String?,
    installState: EsdeInstallUiState,
    esdeInstalled: Boolean,
    esdeNotice: String?,
    minManagerNotice: String?,
    /** Human-readable manager build tag, e.g. "1.2.4-u50-stripped (65)". */
    managerVersionLabel: String,
    appUpdate: AppUpdateState?,
    onUpdateApp: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onGrantFolder: () -> Unit = {},
    onDownload: (EsdeThemeEntry) -> Unit = {},
    onInstall: (EsdeThemeEntry, File) -> Unit = { _, _ -> },
    onLaunchEsde: () -> Unit = {},
    onDismissInstall: () -> Unit = {},
    /** Opens the game importer destination stack. Null hides the entry. */
    onOpenImporter: (() -> Unit)? = null,
    importerStatusLine: String = "",
    importerAttention: Boolean = false,
    /** B on HOME exits the app. */
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    EsdeThemeScreen(
        catalogState = catalogState,
        downloadState = downloadState,
        installed = installed,
        installedOnDisk = installedOnDisk,
        diskVersion = diskVersion,
        folderGranted = folderGranted,
        folderLabel = folderLabel,
        folderNotice = folderNotice,
        installState = installState,
        esdeInstalled = esdeInstalled,
        esdeNotice = esdeNotice,
        minManagerNotice = minManagerNotice,
        onRefresh = onRefresh,
        onGrantFolder = onGrantFolder,
        onDownload = onDownload,
        onInstall = onInstall,
        onLaunchEsde = onLaunchEsde,
        onDismissInstall = onDismissInstall,
        onBack = onExit,
        routeKey = "home",
        screenTitle = "CRYSTAL NOVA",
        contentHeader = "ES-DE THEME UPDATER",
        backLabel = "EXIT",
        isHome = true,
        showManagerSection = true,
        managerVersionLabel = managerVersionLabel,
        appUpdate = appUpdate,
        onUpdateApp = onUpdateApp,
        onOpenImporter = onOpenImporter,
        importerStatusLine = importerStatusLine,
        importerAttention = importerAttention,
        modifier = modifier,
    )
}
