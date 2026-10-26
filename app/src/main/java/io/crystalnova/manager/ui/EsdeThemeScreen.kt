package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
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
import io.crystalnova.manager.importer.DashboardData
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
 * u89: HOME is a command center, not a stacked list. When [isHome]
 * the grid is zoned — header StatusStrip, COMMAND commandTiles,
 * STATUS statTiles, ACTIVITY StatusStrip, EXIT tile — while every
 * action, status line, callback, key, label, enabled state and
 * testTag from the u88 home survives (actions as tiles, status lines
 * as tiles or slim alert/progress panels; see the home branch below).
 * When [isHome] is false the pre-u89 layout is kept verbatim, so any
 * non-home reuse of this screen is unaffected.
 *
 * Pure function of its inputs: every side effect (network, SAF,
 * intents, persistence) arrives as a callback owned by the activity.
 *
 * Public API is unchanged (u89): same parameters, same defaults, same
 * route/key/testTag contract. The only caller in-tree is HomeScreen.
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
    /** Health dashboard data (u88 home only). Null renders loading. */
    healthDashboardData: DashboardData? = null,
    /** The game library export entry (u56 home only). Null hides it. */
    onOpenLibrary: (() -> Unit)? = null,
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
    // Installed-theme line, shared by the home STATUS tiles and the
    // legacy status card (identical wording to u88).
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
    // u88 overall-health derivation, reused verbatim for the home
    // header strip: empty attentionReasons = ALL OK, anything else =
    // ATTENTION. Never recomputed from raw figures here.
    val healthAttention = healthDashboardData?.attentionReasons?.isNotEmpty() ?: false
    val healthStripText = when (val data = healthDashboardData) {
        null -> "CHECKING DEVICE HEALTH…"
        else -> if (data.attentionReasons.isEmpty()) {
            "ALL OK — DEVICE HEALTHY"
        } else {
            "ATTENTION — " + data.attentionReasons.joinToString(" • ")
        }
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
        ControllerGrid(
            state = gridState,
            dispatcher = dispatcher,
            // u89 column choice: 3 wide, 1 portrait via the u87 helper.
            // A single ControllerGrid has one column count for the whole
            // screen, so the COMMAND tiles and the dense STATUS tiles
            // share it: 3 wide keeps the STATUS grid dense (up to 3
            // tiles per row) and the COMMAND tiles large tiles rather
            // than rows; a literal 2x2 COMMAND block would need a
            // second grid (or per-zone columns) and would squeeze the
            // STATUS tiles to 2 across. Portrait collapses to 1 column
            // by the helper — tiles stacked, still tiles, not rows.
            columns = GridCells.Fixed(gridColumnsForWidth(LocalContentWidth.current.value.toInt(), 3)),
            initialFocus = ::isInitialFocus,
        ) {
            if (isHome) {
                // ================= HOME COMMAND CENTER (u89) ==============
                // Header band: slim overall-health strip. The CRYSTAL
                // NOVA title treatment is the scaffold masthead + title
                // above this grid (unchanged).
                panel(key = "home-health-strip") {
                    StatusStrip(
                        text = healthStripText,
                        attention = healthAttention,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // ---- COMMAND zone ----
                panel(key = "home-command-header") {
                    ZoneHeader("COMMAND", Modifier.fillMaxWidth())
                }
                // Theme current release: the UPDATE CRYSTAL tile, with
                // its version/state sublabel. Download progress /
                // verify states stay slim panels (no tile exists for
                // them in u88 either — the tile returns in the
                // ReadyToInstall / Idle states).
                when (catalogState) {
                    is EsdeThemeCatalogState.Checking -> panel {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            StatusLine("CHECKING THE THEME CATALOG…", Crystal.Joystick)
                        }
                    }
                    is EsdeThemeCatalogState.Unavailable -> panel {
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
                            entryCommandTile(
                                keyPrefix = "esde-current",
                                entry = current,
                                downloadState = downloadState,
                                folderGranted = folderGranted,
                                actionLabel = if (updateAvailable) "UPDATE" else "REINSTALL",
                                subLabel = if (updateAvailable) {
                                    "v${current.version} — UPDATE AVAILABLE"
                                } else {
                                    "v${current.version} — UP TO DATE"
                                },
                                onDownload = onDownload,
                                onInstall = onInstall,
                            )
                        }
                    }
                }
                if (catalogState is EsdeThemeCatalogState.Unavailable) {
                    commandTile(
                        key = "esde-retry",
                        label = "CHECK AGAIN",
                        onClick = onRefresh,
                        testTag = "esde-retry",
                    )
                }
                // OPEN ES-DE: always on the home screen when ES-DE is
                // installed and no install outcome is showing (the
                // install-outcome section below keeps its own copy
                // for the Done state — the two never coexist).
                if (esdeInstalled && installState is EsdeInstallUiState.Idle) {
                    commandTile(
                        key = "esde-launch",
                        label = "OPEN ES-DE",
                        onClick = onLaunchEsde,
                        testTag = "esde-launch",
                    )
                }
                // GAME IMPORTER entry (u52). Its live status rides in
                // the ACTIVITY strip below, not on the tile.
                if (onOpenImporter != null) {
                    commandTile(
                        key = "home-open-importer",
                        label = "OPEN GAME IMPORTER",
                        onClick = onOpenImporter,
                        testTag = "home-open-importer",
                    )
                }
                // GAME LIBRARY entry (u56): read-only ROM scan -> text
                // export.
                if (onOpenLibrary != null) {
                    commandTile(
                        key = "home-open-library",
                        label = "EXPORT GAME LIST",
                        onClick = onOpenLibrary,
                        testTag = "home-open-library",
                    )
                }
                // MANAGER self-update action (u50 home only): the
                // tile carries the action; its version/state lines
                // live as STATUS tiles below. Busy states have no tile
                // in u88 either — a slim status panel stands in.
                if (showManagerSection) {
                    when (val u = appUpdate) {
                        is AppUpdateState.Available -> {
                            commandTile(
                                key = "home-update-app",
                                label = "DOWNLOAD UPDATE",
                                onClick = onUpdateApp,
                                testTag = "home-update-app",
                            )
                            u.notice?.let { notice ->
                                panel {
                                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                        StatusLine(notice.uppercase(), Crystal.Bad)
                                    }
                                }
                            }
                        }
                        is AppUpdateState.Downloaded -> commandTile(
                            key = "home-update-app",
                            label = "INSTALL UPDATE",
                            onClick = onUpdateApp,
                            testTag = "home-update-app",
                        )
                        is AppUpdateState.Downloading -> panel {
                            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                val pct = u.progress
                                    ?.let { " — ${(it * 100).toInt()}%" } ?: ""
                                StatusLine(
                                    "DOWNLOADING APP UPDATE$pct",
                                    Crystal.Divider,
                                )
                            }
                        }
                        is AppUpdateState.Checking -> panel {
                            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                StatusLine(
                                    "CHECKING FOR APP UPDATES…",
                                    Crystal.Divider,
                                )
                            }
                        }
                        is AppUpdateState.Installing -> panel {
                            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                StatusLine(
                                    "INSTALLING — FOLLOW THE SYSTEM PROMPT",
                                    Crystal.Divider,
                                )
                            }
                        }
                        is AppUpdateState.Failed,
                        is AppUpdateState.Idle,
                        null -> commandTile(
                            key = "home-check-update",
                            label = "CHECK FOR UPDATE",
                            onClick = onUpdateApp,
                            testTag = "home-check-update",
                        )
                    }
                }
                // Folder grant / re-pick (u49): the adopted folder is
                // shown in the STATUS tiles below and a wrong pick is
                // recoverable from here — no pick is ever vetoed.
                if (!folderGranted) {
                    commandTile(
                        key = "esde-grant-folder",
                        label = "GRANT THEMES FOLDER",
                        onClick = onGrantFolder,
                        testTag = "esde-grant-folder",
                    )
                } else {
                    commandTile(
                        key = "esde-grant-folder-repick",
                        label = "CHANGE THEMES FOLDER",
                        onClick = onGrantFolder,
                        testTag = "esde-grant-folder-repick",
                    )
                }
                // Rollback: info panel (conditional, not primary
                // layout) + the previous-release tile.
                if (catalogState is EsdeThemeCatalogState.Ready && rollback != null) {
                    panel {
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
                    entryCommandTile(
                        keyPrefix = "esde-rollback",
                        entry = rollback,
                        downloadState = downloadState,
                        folderGranted = folderGranted,
                        actionLabel = "REINSTALL PREVIOUS",
                        subLabel = "PREVIOUS RELEASE",
                        onDownload = onDownload,
                        onInstall = onInstall,
                    )
                }
                // Folder-grant explanation (only until granted) and
                // alert notices: slim conditional panels, not the
                // primary layout.
                if (!folderGranted) {
                    panel {
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
                }
                if (folderNotice != null) {
                    panel {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            StatusLine(folderNotice.uppercase(), Crystal.Bad)
                        }
                    }
                }
                if (minManagerNotice != null) {
                    panel {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            StatusLine(minManagerNotice.uppercase(), Crystal.Bad)
                        }
                    }
                }
                // ---- install outcome (transient; tiles for actions) ----
                when (installState) {
                    is EsdeInstallUiState.Installing -> panel {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            StatusLine(
                                installState.step.uppercase(),
                                Crystal.Joystick,
                            )
                        }
                    }
                    is EsdeInstallUiState.Done -> {
                        panel {
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
                            commandTile(
                                key = "esde-launch",
                                label = "OPEN ES-DE",
                                onClick = onLaunchEsde,
                                testTag = "esde-launch",
                            )
                        } else if (esdeNotice != null) {
                            panel {
                                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                    StatusLine(esdeNotice.uppercase(), Crystal.Bad)
                                }
                            }
                        }
                        commandTile(
                            key = "esde-install-dismiss",
                            label = "DONE",
                            onClick = onDismissInstall,
                            testTag = "esde-install-dismiss",
                        )
                    }
                    is EsdeInstallUiState.Failed -> {
                        panel {
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
                        commandTile(
                            key = "esde-install-dismiss",
                            label = "BACK",
                            onClick = onDismissInstall,
                            testTag = "esde-install-dismiss",
                        )
                    }
                    EsdeInstallUiState.Idle -> { /* nothing */ }
                }
                // ---- STATUS zone ----
                panel(key = "home-status-header") {
                    ZoneHeader("STATUS", Modifier.fillMaxWidth())
                }
                healthDashboardTiles(healthDashboardData)
                // Theme status lines from the u88 status card, as tiles.
                statTile(
                    key = "theme-installed",
                    label = "INSTALLED",
                    value = installedText,
                )
                if (catalog != null) {
                    statTile(
                        key = "theme-latest",
                        label = "LATEST",
                        value = "v${catalog.version}",
                        subline = if (!updateAvailable) "UP TO DATE" else "UPDATE AVAILABLE",
                    )
                    catalog.zipBytes?.let {
                        statTile(
                            key = "theme-size",
                            label = "SIZE",
                            value = formatPackBytes(it),
                        )
                    }
                }
                statTile(
                    key = "theme-folder",
                    label = "THEMES FOLDER",
                    value = if (folderGranted) {
                        folderLabel ?: "GRANTED"
                    } else {
                        "NOT GRANTED"
                    },
                )
                // Manager version/state lines from the u88 MANAGER
                // panel, as tiles. Busy states are already covered by
                // the slim COMMAND-zone panels above, so they get no
                // second tile here.
                if (showManagerSection) {
                    statTile(
                        key = "manager-version",
                        label = "MANAGER",
                        value = managerVersionLabel,
                    )
                    when (val u = appUpdate) {
                        is AppUpdateState.Available -> statTile(
                            key = "manager-update",
                            label = "APP UPDATE",
                            value = "MANAGER v${u.info.version} AVAILABLE",
                        )
                        is AppUpdateState.Downloaded -> statTile(
                            key = "manager-update",
                            label = "APP UPDATE",
                            value = "APP UPDATE READY",
                        )
                        is AppUpdateState.Failed -> statTile(
                            key = "manager-update",
                            label = "APP UPDATE",
                            value = "APP UPDATE FAILED — ${u.message}".uppercase(),
                        )
                        is AppUpdateState.Idle -> statTile(
                            key = "manager-update",
                            label = "APP UPDATE",
                            value = if (u.lastCheckFailed) {
                                "APP UPDATE CHECK FAILED"
                            } else {
                                "APP UPDATE CHECKS RUN ON START"
                            },
                        )
                        is AppUpdateState.Checking,
                        is AppUpdateState.Downloading,
                        is AppUpdateState.Installing,
                        null -> { /* busy panel above, or no state line */ }
                    }
                }
                // ---- ACTIVITY strip: importer state in one line ----
                if (importerStatusLine.isNotEmpty()) {
                    panel(key = "home-activity-strip") {
                        StatusStrip(
                            text = "IMPORTER: $importerStatusLine",
                            attention = importerAttention,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // ---- EXIT (B on HOME exits the app) ----
                commandTile(
                    key = backKey,
                    label = backLabel,
                    onClick = onBack,
                    testTag = backKey,
                )
            } else {
                // ================= LEGACY LAYOUT (non-home) ==============
                // Pre-u89 body verbatim (minus the isHome-gated health
                // panels, which never render here). Kept so any
                // non-home reuse of this screen keeps working exactly.
                panel {
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
                panel {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    panel {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            StatusLine(folderNotice.uppercase(), Crystal.Bad)
                        }
                    }
                }
                // ---- folder grant ----
                if (!folderGranted) {
                    panel {
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
                        onClick = onLaunchEsde
                    )
                }
                // ---- catalog states ----
                when (catalogState) {
                    is EsdeThemeCatalogState.Checking -> panel {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            StatusLine("CHECKING THE THEME CATALOG…", Crystal.Joystick)
                        }
                    }
                    is EsdeThemeCatalogState.Unavailable -> panel {
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
                            entryTiles(
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
                            panel {
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
                            entryTiles(
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
                    is EsdeInstallUiState.Installing -> panel {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            StatusLine(
                                installState.step.uppercase(),
                                Crystal.Joystick,
                            )
                        }
                    }
                    is EsdeInstallUiState.Done -> {
                        panel {
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
                            panel {
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
                        panel {
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
                if (showManagerSection) {
                    // MANAGER header merged into the panel: the u50 home is
                    // 11 rows and every row must fit the 960px viewport.
                    panel {
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
                                // Importer live status (u52) in the MANAGER panel; the
                                // entry below stays a single-line control.
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
                            onClick = onUpdateApp
                        )
                        is AppUpdateState.Downloaded -> control(
                            key = "home-update-app",
                            testTag = "home-update-app",
                            label = "INSTALL UPDATE",
                            onClick = onUpdateApp
                        )
                        is AppUpdateState.Failed,
                        is AppUpdateState.Idle,
                        null -> control(
                            key = "home-check-update",
                            testTag = "home-check-update",
                            label = "CHECK FOR UPDATE",
                            onClick = onUpdateApp
                        )
                        else -> { /* Checking / Downloading / Installing: busy */ }
                    }
                }
                // ---- GAME IMPORTER entry (u52): a hero tile on the home
                // grid. The live status rides in the MANAGER panel.
                if (onOpenImporter != null) {
                    control(
                        key = "home-open-importer",
                        testTag = "home-open-importer",
                        label = "OPEN GAME IMPORTER",
                        onClick = onOpenImporter
                    )
                }
                // ---- GAME LIBRARY entry (u56): read-only ROM scan -> text
                // export. Hero tile like the importer entry.
                if (onOpenLibrary != null) {
                    control(
                        key = "home-open-library",
                        testTag = "home-open-library",
                        label = "EXPORT GAME LIST",
                        onClick = onOpenLibrary
                    )
                }
                control(
                    key = backKey,
                    testTag = backKey,
                    label = backLabel,
                    onClick = onBack,
                )
            }
        }
    }
}

/**
 * Download → verify → install tile for one [EsdeThemeEntry]
 * (current release or rollback target) on the u89 home grid. Mirrors
 * [ControllerGridContent.entryTiles] exactly — same keys, labels,
 * enabled states and callbacks — but as a commandTile instead of a
 * control, with the tile's [subLabel] carrying the entry's state.
 * Downloading / verifying states have no tile in u88 either: they
 * render a slim progress panel and the tile returns when the state
 * changes.
 */
private fun ControllerGridContent.entryCommandTile(
    keyPrefix: String,
    entry: EsdeThemeEntry,
    downloadState: EsdeThemeDownloadState,
    folderGranted: Boolean,
    actionLabel: String,
    subLabel: String?,
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
        downloadState is EsdeThemeDownloadState.Downloading && thisEntry -> panel {
            val pct = downloadState.total
                ?.takeIf { it > 0 }
                ?.let { (downloadState.done * 100 / it).toInt().coerceIn(0, 100) }
            StatusLine(
                if (pct != null) "DOWNLOADING v${entry.version}… $pct%" else "DOWNLOADING v${entry.version}…",
                Crystal.Joystick,
            )
        }
        downloadState is EsdeThemeDownloadState.Verifying && thisEntry -> panel {
            StatusLine("VERIFYING CHECKSUM…", Crystal.Joystick)
        }
        downloadState is EsdeThemeDownloadState.ReadyToInstall && thisEntry -> commandTile(
            key = "$keyPrefix-install",
            label = "INSTALL v${entry.version} NOW",
            subLabel = subLabel,
            enabled = folderGranted && !busyElsewhere,
            onClick = { onInstall(entry, downloadState.zip) },
            testTag = "$keyPrefix-install",
        )
        downloadState is EsdeThemeDownloadState.Failed && thisEntry -> {
            panel {
                StatusLine(
                    "DOWNLOAD FAILED: ${downloadState.message}".uppercase(),
                    Crystal.Bad,
                )
            }
            commandTile(
                key = "$keyPrefix-retry",
                label = "RETRY DOWNLOAD",
                subLabel = subLabel,
                enabled = !busyElsewhere,
                onClick = { onDownload(entry) },
                testTag = "$keyPrefix-retry",
            )
        }
        else -> commandTile(
            key = "$keyPrefix-download",
            label = "$actionLabel v${entry.version}",
            subLabel = subLabel,
            enabled = !busyElsewhere,
            onClick = { onDownload(entry) },
            testTag = "$keyPrefix-download",
        )
    }
    if (!folderGranted &&
        downloadState is EsdeThemeDownloadState.ReadyToInstall && thisEntry
    ) {
        panel {
            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                DimLine(
                    "GRANT THE THEMES FOLDER BEFORE INSTALLING — " +
                        "THE DOWNLOAD IS VERIFIED AND WAITING.",
                )
            }
        }
    }
}

/**
 * Download → verify → install controls for one [EsdeThemeEntry]
 * (current release or rollback target). One download at a time: while
 * another entry is mid-download this entry's button disables instead
 * of silently no-opping. Used by the legacy (non-home) layout only;
 * the home grid uses [ControllerGridContent.entryCommandTile].
 */
private fun ControllerGridContent.entryTiles(
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
        downloadState is EsdeThemeDownloadState.Downloading && thisEntry -> panel {
            val pct = downloadState.total
                ?.takeIf { it > 0 }
                ?.let { (downloadState.done * 100 / it).toInt().coerceIn(0, 100) }
            StatusLine(
                if (pct != null) "DOWNLOADING v${entry.version}… $pct%" else "DOWNLOADING v${entry.version}…",
                Crystal.Joystick,
            )
        }
        downloadState is EsdeThemeDownloadState.Verifying && thisEntry -> panel {
            StatusLine("VERIFYING CHECKSUM…", Crystal.Joystick)
        }
        downloadState is EsdeThemeDownloadState.ReadyToInstall && thisEntry -> control(
            key = "$keyPrefix-install",
            testTag = "$keyPrefix-install",
            label = "INSTALL v${entry.version} NOW",
            enabled = folderGranted && !busyElsewhere,
            onClick = { onInstall(entry, downloadState.zip) }
        )
        downloadState is EsdeThemeDownloadState.Failed && thisEntry -> {
            panel {
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
            onClick = { onDownload(entry) }
        )
    }
    if (!folderGranted &&
        downloadState is EsdeThemeDownloadState.ReadyToInstall && thisEntry
    ) {
        panel {
            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                DimLine(
                    "GRANT THE THEMES FOLDER BEFORE INSTALLING — " +
                        "THE DOWNLOAD IS VERIFIED AND WAITING.",
                )
            }
        }
    }
}
