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

/**
 * THEME: the ES-DE "crystal" theme updater (u48). Shows the installed
 * theme version, the remote catalog version, and drives the
 * download → verify → SAF-install flow plus rollback to the previous
 * release.
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
    /** Pops one navigation level (B). */
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val catalog = (catalogState as? EsdeThemeCatalogState.Ready)?.catalog
    val current = catalog?.current()
    val rollback = catalog?.rollbackTarget()
    ScreenScaffold(
        routeKey = "theme",
        title = "ES-DE THEME",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "theme-back",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                BasicText(
                    text = "CRYSTAL THEME FOR ES-DE",
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            val newer = installed == null ||
                                installed.versionCode < catalog.versionCode
                            StatusLine(
                                "LATEST: v${catalog.version}" +
                                    if (!newer) " — UP TO DATE" else " — UPDATE AVAILABLE",
                                if (!newer) Crystal.Good else Crystal.Joystick,
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
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            // ---- catalog states ----
            when (catalogState) {
                is EsdeThemeCatalogState.Checking -> section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        StatusLine("CHECKING THE THEME CATALOG…", Crystal.Joystick)
                    }
                }
                is EsdeThemeCatalogState.Unavailable -> section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            actionLabel = if (
                                installed == null ||
                                installed.versionCode < current.versionCode
                            ) {
                                "UPDATE"
                            } else {
                                "REINSTALL"
                            },
                            onDownload = onDownload,
                            onInstall = onInstall,
                        )
                    }
                    // ---- rollback ----
                    if (rollback != null) {
                        section {
                            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            control(
                key = "theme-back",
                testTag = "theme-back",
                label = "BACK",
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
