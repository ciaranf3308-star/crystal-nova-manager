package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.data.EsdeThemeCatalogState
import io.crystalnova.manager.data.EsdeThemeDownloadState
import io.crystalnova.manager.data.EsdeThemeEntry
import io.crystalnova.manager.data.EsdeThemeInstalled
import io.crystalnova.manager.updater.AppUpdateState
import java.io.File

/**
 * UI state for one SAF install run. Separate from the download state:
 * a download can be verified and waiting while no install is running.
 */
sealed interface EsdeInstallUiState {
    data object Idle : EsdeInstallUiState
    data class Installing(val step: String) : EsdeInstallUiState
    data class Done(val version: String, val notes: List<String>) : EsdeInstallUiState
    data class Failed(val message: String, val notes: List<String>) : EsdeInstallUiState
}

/** Moved here from the deleted (iiSU) ThemeScreen.kt — the only surviving user. */
internal fun formatPackBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "%.1f MB".format(bytes / (1024f * 1024f))
    }

/**
 * THEME: the ES-DE "crystal" theme updater (u48). Shows the installed
 * theme version, the remote catalog version, and drives the
 * download → verify → SAF-install flow plus rollback to the previous
 * release.
 *
 * u50: THE STRIP — this screen IS the home screen now. The optional
 * [showManagerSection] appends the MANAGER self-update route
 * (current version, CHECK FOR UPDATE → UPDATE) to the same scroll;
 * [isHome] makes B exit instead of popping.
 *
 * Pure function of its inputs: every side effect (network, SAF,
 * intents, persistence) arrives as a callback owned by the activity.
 */
@Composable
fun EsdeThemeScreen(
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
    onRefresh: () -> Unit = {},
    onGrantFolder: () -> Unit = {},
    onDownload: (EsdeThemeEntry) -> Unit = {},
    onInstall: (EsdeThemeEntry, File) -> Unit = { _, _ -> },
    onLaunchEsde: () -> Unit = {},
    onDismissInstall: () -> Unit = {},
    /** Pops one navigation level (B); on HOME it exits the app. */
    onBack: () -> Unit = {},
    // ---- u50 home-screen configuration ----
    routeKey: String = "theme",
    screenTitle: String = "ES-DE THEME",
    contentHeader: String = "CRYSTAL THEME FOR ES-DE",
    backLabel: String = "BACK",
    isHome: Boolean = false,
    /** The manager self-update section (u50 home only). */
    showManagerSection: Boolean = false,
    managerVersionLabel: String = "",
    appUpdate: AppUpdateState? = null,
    onUpdateApp: () -> Unit = {},
    /** The game importer entry (u52 home only). Null hides it. */
    onOpenImporter: (() -> Unit)? = null,
    importerStatusLine: String = "",
    importerAttention: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val catalog = (catalogState as? EsdeThemeCatalogState.Ready)?.catalog
    val current = catalog?.current()
    val rollback = catalog?.rollbackTarget()
    val updateAvailable = catalog != null &&
        (installed == null || installed.versionCode < catalog.versionCode)
    val backKey = if (isHome) "home-exit" else "theme-back"
    // Initial D-pad focus: the primary CTA for the current state, or
    // the back/exit control when nothing actionable is up.
    val fallbackFocusKey: Any? = when {
        installState is EsdeInstallUiState.Installing -> backKey
        installState is EsdeInstallUiState.Done ||
            installState is EsdeInstallUiState.Failed -> "esde-install-dismiss"
        updateAvailable ->
            if (downloadState is EsdeThemeDownloadState.ReadyToInstall &&
                downloadState.entry.versionCode == current?.versionCode
            ) {
                "esde-current-install"
            } else {
                "esde-current-download"
            }
        !folderGranted -> "esde-grant-folder"
        else -> backKey
    }
    ScreenScaffold(
        routeKey = routeKey,
        title = screenTitle,
        onBack = onBack,
        modifier = modifier,
        isHome = isHome,
        footerLabel = backLabel,
        fallbackFocusKey = fallbackFocusKey,
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
            // u50 home: 11 rows must fit the 960px viewport with no
            // stranded control — the tighter rhythm is the difference
            // between EXIT composing or not.
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            section {
                BasicText(
                    text = contentHeader,
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = Crystal.BodySize,
                        color = Crystal.Cream,
                    ),
                )
            }
            // ---- status card ----
            section {
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val installedText = when {
                            installed == null && !installedOnDisk ->
                                "NOT INSTALLED"
                            installed != null ->
                                "v${installed.version}" +
                                    if (!installedOnDisk) " (FILES MISSING)"
                                    else ""
                            else -> "ON DISK" +
                                (diskVersion?.let { " v$it" } ?: "")
                        }
                        StatusLine(
                            "INSTALLED: $installedText",
                            if (installedOnDisk) Crystal.Good else Crystal.Joystick,
                        )
                        if (catalog != null) {
                            StatusLine(
                                "LATEST: v${catalog.version}" +
                                    if (!updateAvailable) " — UP TO DATE" else " — UPDATE AVAILABLE",
                                if (!updateAvailable) Crystal.Good else Crystal.Joystick,
                            )
                            catalog.zipBytes?.let {
                                DimLine("SIZE: ${formatPackBytes(it)}")
                            }
                        }
                        StatusLine(
                            if (folderGranted) {
                                "FOLDER: ${folderLabel ?: "GRANTED"}"
                            } else {
                                "THEMES FOLDER: NOT GRANTED"
                            },
                            if (folderGranted) Crystal.Good else Crystal.Bad,
                        )
                        if (minManagerNotice != null) {
                            StatusLine(minManagerNotice.uppercase(), Crystal.Bad)
                        }
                    }
                }
            }
            if (folderNotice != null) {
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        StatusLine(folderNotice.uppercase(), Crystal.Bad)
                    }
                }
            }
            // ---- folder grant ----
            if (!folderGranted) {
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusLine("GRANT THE THEMES FOLDER", Crystal.Joystick)
                            DimLine(
                                "PICK THE ES-DE THEMES FOLDER IN THE SYSTEM " +
                                    "PICKER — ON THIS NOVA IT IS " +
                                    "FOUND.000/THEMES (FALLBACK: ES-DE/THEMES). " +
                                    "THE MANAGER ONLY EVER TOUCHES THAT " +
                                    "FOLDER — NO BROAD STORAGE PERMISSION.",
                            )
                        }
                    }
                }
                control(
                    key = "esde-grant-folder",
                    testTag = "esde-grant-folder",
                    label = "GRANT THEMES FOLDER",
                    onClick = onGrantFolder,
                )
            }
            // ---- re-pick (u49): the adopted folder is shown above and a
            // wrong pick is recoverable from here — no pick is ever vetoed ----
            if (folderGranted) {
                control(
                    key = "esde-grant-folder-repick",
                    testTag = "esde-grant-folder-repick",
                    label = "CHANGE THEMES FOLDER",
                    onClick = onGrantFolder,
                )
            }
            // ---- OPEN ES-DE: always on the home screen when ES-DE is
            // installed (the install-outcome panel keeps its own copy) ----
            if (esdeInstalled && installState is EsdeInstallUiState.Idle) {
                control(
                    key = "esde-launch",
                    testTag = "esde-launch",
                    label = "OPEN ES-DE",
                    onClick = onLaunchEsde,
                )
            }
            // ---- catalog states ----
            when (catalogState) {
                is EsdeThemeCatalogState.Checking -> section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        StatusLine("CHECKING THE THEME CATALOG…", Crystal.Joystick)
                    }
                }
                is EsdeThemeCatalogState.Unavailable -> section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusLine("THEME CATALOG UNAVAILABLE", Crystal.Bad)
                            DimLine(catalogState.message.uppercase())
                            DimLine(
                                "THE CATALOG IS PUBLISHED SEPARATELY FROM " +
                                    "THE MANAGER — NOTHING IS BROKEN ON " +
                                    "YOUR NOVA. CHECK YOUR CONNECTION AND " +
                                    "RETRY.",
                            )
                        }
                    }
                }
                is EsdeThemeCatalogState.Ready -> {
                    if (current != null) {
                        entryControls(
                            keyPrefix = "esde-current",
                            entry = current,
                            downloadState = downloadState,
                            folderGranted = folderGranted,
                            actionLabel = if (updateAvailable) "UPDATE" else "REINSTALL",
                            onDownload = onDownload,
                            onInstall = onInstall,
                        )
                    }
                    // ---- rollback ----
                    if (rollback != null) {
                        section {
                            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    StatusLine(
                                        "ROLLBACK: v${rollback.version} (PREVIOUS)",
                                        Crystal.Joystick,
                                    )
                                    DimLine(
                                        "REINSTALLS THE LAST RELEASE BEFORE " +
                                            "v${current?.version ?: "?"}. THE DEVICE " +
                                            "KEEPS EXACTLY ONE THEME VERSION.",
                                    )
                                }
                            }
                        }
                        entryControls(
                            keyPrefix = "esde-rollback",
                            entry = rollback,
                            downloadState = downloadState,
                            folderGranted = folderGranted,
                            actionLabel = "REINSTALL PREVIOUS",
                            onDownload = onDownload,
                            onInstall = onInstall,
                        )
                    }
                }
            }
            if (catalogState is EsdeThemeCatalogState.Unavailable) {
                control(
                    key = "esde-retry",
                    testTag = "esde-retry",
                    label = "CHECK AGAIN",
                    onClick = onRefresh,
                )
            }
            // ---- install outcome ----
            when (installState) {
                is EsdeInstallUiState.Installing -> section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        StatusLine(
                            installState.step.uppercase(),
                            Crystal.Joystick,
                        )
                    }
                }
                is EsdeInstallUiState.Done -> {
                    section {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusLine(
                                    "THEME v${installState.version} INSTALLED",
                                    Crystal.Good,
                                )
                                installState.notes.forEach { note ->
                                    DimLine(note.uppercase())
                                }
                                DimLine(
                                    "IF ES-DE WAS ALREADY RUNNING, QUIT IT " +
                                        "AND START IT AGAIN INSIDE ES-DE ONCE " +
                                        "FOR THE NEW THEME TO LOAD — THE " +
                                        "MANAGER CANNOT RESTART ANOTHER APP.",
                                )
                            }
                        }
                    }
                    if (esdeInstalled) {
                        control(
                            key = "esde-launch",
                            testTag = "esde-launch",
                            label = "OPEN ES-DE",
                            onClick = onLaunchEsde,
                        )
                    } else if (esdeNotice != null) {
                        section {
                            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                StatusLine(esdeNotice.uppercase(), Crystal.Bad)
                            }
                        }
                    }
                    control(
                        key = "esde-install-dismiss",
                        testTag = "esde-install-dismiss",
                        label = "DONE",
                        onClick = onDismissInstall,
                    )
                }
                is EsdeInstallUiState.Failed -> {
                    section {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusLine(
                                    "INSTALL FAILED: ${installState.message}".uppercase(),
                                    Crystal.Bad,
                                )
                                installState.notes.forEach { note ->
                                    DimLine(note.uppercase())
                                }
                                DimLine(
                                    "THE PREVIOUS THEME (IF ANY) WAS LEFT " +
                                        "UNTOUCHED.",
                                )
                            }
                        }
                    }
                    control(
                        key = "esde-install-dismiss",
                        testTag = "esde-install-dismiss",
                        label = "BACK",
                        onClick = onDismissInstall,
                    )
                }
                EsdeInstallUiState.Idle -> { /* nothing */ }
            }
            // ---- MANAGER self-update (u50 home only): surfaced here,
            // never buried behind a route. Every future manager build
            // ships through this path. ----
            // TEMP-DIAG: importer entry moved up to test visibility.
            if (showManagerSection && onOpenImporter != null) {
                control(
                    key = "home-open-importer",
                    testTag = "home-open-importer",
                    label = "OPEN GAME IMPORTER",
                    onClick = onOpenImporter,
                )
            }
            if (showManagerSection) {
                // MANAGER header merged into the panel: the u50 home is
                // 11 rows and every row must fit the 960px viewport.
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusLine("MANAGER", Crystal.Cream)
                            StatusLine(
                                "VERSION: $managerVersionLabel",
                                Crystal.Cream,
                            )
                            when (val u = appUpdate) {
                                is AppUpdateState.Available -> {
                                    StatusLine(
                                        "MANAGER v${u.info.version} AVAILABLE",
                                        Crystal.Joystick,
                                    )
                                    u.notice?.let { notice ->
                                        StatusLine(notice.uppercase(), Crystal.Bad)
                                    }
                                }
                                is AppUpdateState.Downloaded ->
                                    StatusLine("APP UPDATE READY", Crystal.Joystick)
                                is AppUpdateState.Downloading -> {
                                    val pct = u.progress
                                        ?.let { " — ${(it * 100).toInt()}%" } ?: ""
                                    StatusLine(
                                        "DOWNLOADING APP UPDATE$pct",
                                        Crystal.Divider,
                                    )
                                }
                                is AppUpdateState.Checking ->
                                    StatusLine(
                                        "CHECKING FOR APP UPDATES…",
                                        Crystal.Divider,
                                    )
                                is AppUpdateState.Failed ->
                                    StatusLine(
                                        "APP UPDATE FAILED — ${u.message}".uppercase(),
                                        Crystal.Bad,
                                    )
                                is AppUpdateState.Idle ->
                                    if (u.lastCheckFailed) {
                                        StatusLine(
                                            "APP UPDATE CHECK FAILED",
                                            Crystal.Bad,
                                        )
                                    } else {
                                        DimLine("APP UPDATE CHECKS RUN ON START")
                                    }
                                is AppUpdateState.Installing ->
                                    StatusLine(
                                        "INSTALLING — FOLLOW THE SYSTEM PROMPT",
                                        Crystal.Divider,
                                    )
                                null -> { /* CHECK FOR UPDATE button below */ }
                            }
                            // ---- Importer status (u52): lives in the
                            // MANAGER panel so the entry below stays a
                            // single-line control — a two-line subLabel
                            // button mis-measures (16px) on first layout in
                            // a ControllerList, breaking D-pad traversal.
                            if (importerStatusLine.isNotEmpty()) {
                                StatusLine(
                                    "IMPORTER: $importerStatusLine",
                                    if (importerAttention) Crystal.Joystick
                                    else Crystal.InkDim,
                                )
                            }
                        }
                    }
                }
                when (appUpdate) {
                    is AppUpdateState.Available -> control(
                        key = "home-update-app",
                        testTag = "home-update-app",
                        label = "DOWNLOAD UPDATE",
                        onClick = onUpdateApp,
                    )
                    is AppUpdateState.Downloaded -> control(
                        key = "home-update-app",
                        testTag = "home-update-app",
                        label = "INSTALL UPDATE",
                        onClick = onUpdateApp,
                    )
                    is AppUpdateState.Failed,
                    is AppUpdateState.Idle,
                    null -> control(
                        key = "home-check-update",
                        testTag = "home-check-update",
                        label = "CHECK FOR UPDATE",
                        onClick = onUpdateApp,
                    )
                    else -> { /* Checking / Downloading / Installing: busy */ }
                }
            }
            // ---- GAME IMPORTER entry (u52 home only): a single control
            // row — TEMP-DIAG: moved above for visibility test.
            control(
                key = backKey,
                testTag = backKey,
                label = backLabel,
                onClick = onBack,
            )
        }
    }
}

/**
 * Download → verify → install controls for one [EsdeThemeEntry]
 * (current release or rollback target). One download at a time: while
 * another entry is mid-download this entry's button disables instead
 * of silently no-opping.
 */
private fun ControllerListContent.entryControls(
    keyPrefix: String,
    entry: EsdeThemeEntry,
    downloadState: EsdeThemeDownloadState,
    folderGranted: Boolean,
    actionLabel: String,
    onDownload: (EsdeThemeEntry) -> Unit,
    onInstall: (EsdeThemeEntry, File) -> Unit,
) {
    val thisEntry = when (downloadState) {
        is EsdeThemeDownloadState.Downloading -> downloadState.entry.versionCode == entry.versionCode
        is EsdeThemeDownloadState.Verifying -> downloadState.entry.versionCode == entry.versionCode
        is EsdeThemeDownloadState.ReadyToInstall -> downloadState.entry.versionCode == entry.versionCode
        is EsdeThemeDownloadState.Failed -> downloadState.entry.versionCode == entry.versionCode
        else -> false
    }
    val busyElsewhere = (downloadState is EsdeThemeDownloadState.Downloading ||
        downloadState is EsdeThemeDownloadState.Verifying) && !thisEntry
    when {
        downloadState is EsdeThemeDownloadState.Downloading && thisEntry -> section {
            val pct = downloadState.total
                ?.takeIf { it > 0 }
                ?.let { (downloadState.done * 100 / it).toInt().coerceIn(0, 100) }
            StatusLine(
                if (pct != null) "DOWNLOADING v${entry.version}… $pct%" else "DOWNLOADING v${entry.version}…",
                Crystal.Joystick,
            )
        }
        downloadState is EsdeThemeDownloadState.Verifying && thisEntry -> section {
            StatusLine("VERIFYING CHECKSUM…", Crystal.Joystick)
        }
        downloadState is EsdeThemeDownloadState.ReadyToInstall && thisEntry -> control(
            key = "$keyPrefix-install",
            testTag = "$keyPrefix-install",
            label = "INSTALL v${entry.version} NOW",
            enabled = folderGranted && !busyElsewhere,
            onClick = { onInstall(entry, downloadState.zip) },
        )
        downloadState is EsdeThemeDownloadState.Failed && thisEntry -> {
            section {
                StatusLine(
                    "DOWNLOAD FAILED: ${downloadState.message}".uppercase(),
                    Crystal.Bad,
                )
            }
            control(
                key = "$keyPrefix-retry",
                testTag = "$keyPrefix-retry",
                label = "RETRY DOWNLOAD",
                enabled = !busyElsewhere,
                onClick = { onDownload(entry) },
            )
        }
        else -> control(
            key = "$keyPrefix-download",
            testTag = "$keyPrefix-download",
            label = "$actionLabel v${entry.version}",
            enabled = !busyElsewhere,
            onClick = { onDownload(entry) },
        )
    }
    if (!folderGranted &&
        downloadState is EsdeThemeDownloadState.ReadyToInstall && thisEntry
    ) {
        section {
            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                DimLine(
                    "GRANT THE THEMES FOLDER BEFORE INSTALLING — " +
                        "THE DOWNLOAD IS VERIFIED AND WAITING.",
                )
            }
        }
    }
}
