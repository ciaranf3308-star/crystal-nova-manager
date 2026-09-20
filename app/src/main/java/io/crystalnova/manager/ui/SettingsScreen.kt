package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.storage.LocationState
import io.crystalnova.manager.updater.AppUpdateState

/**
 * SETTINGS: a menu, not a document. The top level is a compact list of
 * rows with one-line summaries; each row opens a focused child screen
 * ([SettingsLocationScreen], [SettingsThemesScreen],
 * [SettingsChannelScreen]) that carries the actions.
 *
 * The manager app's own update status sits at the TOP — never buried —
 * and tapping it runs the same check/download/install handoff the
 * activity owns. The theme updater lives on the THEME screen only.
 */
@Composable
fun SettingsScreen(
    romLocation: LocationState,
    mediaLocation: LocationState,
    themesRootLabel: String,
    updateChannel: AppUpdateChannel,
    appVersion: String,
    appUpdate: AppUpdateState,
    locationError: String?,
    onDismissLocationError: () -> Unit,
    onUpdateApp: () -> Unit,
    onOpenRom: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenEsdeImport: () -> Unit,
    onOpenThemes: () -> Unit,
    onOpenChannel: () -> Unit,
    onOpenAppearance: () -> Unit,
    onDiagnostics: () -> Unit,
    injectBusy: Boolean,
    injectStatus: String?,
    onInjectTestPack: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "settings",
        title = "SETTINGS",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "settings-update-app",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
            // Nine rows must fit the 960px viewport without scrolling
            // (NovaSettingsFitsViewportTest): compact rows and tighter
            // than the default arrangement.
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            control(
                key = "settings-update-app",
                testTag = "settings-update-app",
                compact = true,
                label = updateManagerLabel(appVersion, appUpdate),
                onClick = onUpdateApp,
                enabled = updateActionEnabled(appUpdate),
            )
            control(
                key = "settings-row-rom",
                testTag = "settings-row-rom",
                compact = true,
                label = "ROM LIBRARY\n${friendlyLocation(romLocation)}",
                onClick = onOpenRom,
            )
            control(
                key = "settings-row-media",
                testTag = "settings-row-media",
                compact = true,
                label = "MEDIA LIBRARY\n${friendlyLocation(mediaLocation)}${mediaBadge(mediaLocation)}",
                onClick = onOpenMedia,
            )
            control(
                key = "settings-row-esde-import",
                testTag = "settings-row-esde-import",
                compact = true,
                label = "ES-DE MEDIA IMPORT\nCOPY REAL ART INTO THE CRYSTAL LIBRARY",
                onClick = onOpenEsdeImport,
            )
            control(
                key = "settings-row-themes",
                testTag = "settings-row-themes",
                compact = true,
                label = "THEME STORAGE\n$themesRootLabel",
                onClick = onOpenThemes,
            )
            control(
                key = "settings-row-channel",
                testTag = "settings-row-channel",
                compact = true,
                label = "UPDATE CHANNEL\n${channelLabel(updateChannel)}",
                onClick = onOpenChannel,
            )
            control(
                key = "settings-row-appearance",
                testTag = "settings-row-appearance",
                compact = true,
                label = "APPEARANCE\nRECOLOR THE CRYSTAL IDENTITY",
                onClick = onOpenAppearance,
            )
            locationError?.let {
                section { notice(it, onDismissLocationError) }
            }
            control(
                key = "settings-row-diagnostics",
                testTag = "settings-row-diagnostics",
                compact = true,
                label = "DIAGNOSTICS",
                onClick = onDiagnostics,
            )
            // u46 test utility: bulletproof delivery of the iiSU test
            // pack (chat attachments kept arriving corrupted). Copies the
            // APK-bundled crystal-test-pack.zip into Downloads via
            // MediaStore and SHA-256-verifies the copy. Visually distinct
            // (danger styling + TEST UTILITY label) — not a production
            // feature.
            injectStatus?.let { status ->
                section { StatusLine(status) }
            }
            control(
                key = "settings-row-inject-test-pack",
                testTag = "settings-row-inject-test-pack",
                compact = true,
                label = if (injectBusy) "INJECT TEST PACK (TEST UTILITY)\nCOPYING…"
                else "INJECT TEST PACK (TEST UTILITY)\nCOPY crystal-test-pack.zip TO DOWNLOADS",
                onClick = onInjectTestPack,
                enabled = !injectBusy,
                danger = true,
            )
        }
    }
}

/**
 * One storage location, focused: the current friendly path (never a
 * raw content:// URI), CHANGE / SELECT LOCATION, and CLEAR (danger,
 * only when a location is actually configured). B backs out.
 */
@Composable
fun SettingsLocationScreen(
    routeKey: String,
    title: String,
    location: LocationState,
    showBadge: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val changeTag = "$routeKey-change"
    val clearTag = "$routeKey-clear"
    ScreenScaffold(
        routeKey = routeKey,
        title = title,
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = changeTag,
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                val badge = if (showBadge && location is LocationState.Ready) {
                    if (location.isRemovable) " · SD CARD" else " · INTERNAL"
                } else {
                    ""
                }
                StatusLine(
                    "CURRENT: ${friendlyLocation(location)}$badge",
                    if (location is LocationState.AccessLost) Crystal.Bad else Crystal.Ink,
                )
                if (location is LocationState.AccessLost) {
                    DimLine("THE SAVED FOLDER IS NO LONGER READABLE — PICK IT AGAIN.")
                }
            }
            control(
                key = changeTag,
                testTag = changeTag,
                label = if (location is LocationState.NotConfigured) "SELECT LOCATION" else "CHANGE LOCATION",
                onClick = onPick,
            )
            if (location !is LocationState.NotConfigured) {
                control(
                    key = clearTag,
                    testTag = clearTag,
                    label = "CLEAR",
                    onClick = onClear,
                    danger = true,
                )
            }
        }
    }
}

/** THEME STORAGE child of SETTINGS: current root + CHANGE. */
@Composable
fun SettingsThemesScreen(
    themesRootLabel: String,
    onPickThemesRoot: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "settings-themes",
        title = "THEME STORAGE",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "settings-themes-change",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                StatusLine("CURRENT: $themesRootLabel")
            }
            control(
                key = "settings-themes-change",
                testTag = "settings-themes-change",
                label = "CHANGE THEMES FOLDER",
                onClick = onPickThemesRoot,
            )
        }
    }
}

/**
 * UPDATE CHANNEL child of SETTINGS: the two channel choices. Selecting
 * either re-checks for a manager app update immediately (the activity
 * owns that handoff; this screen only surfaces the choice).
 */
@Composable
fun SettingsChannelScreen(
    channel: AppUpdateChannel,
    onSelect: (AppUpdateChannel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "settings-channel",
        title = "UPDATE CHANNEL",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "settings-channel-dev",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                StatusLine("CURRENT: ${channelLabel(channel)}", Crystal.Cream)
            }
            control(
                key = "settings-channel-dev",
                testTag = "settings-channel-dev",
                label = "DEV / CANDIDATE",
                onClick = { onSelect(AppUpdateChannel.DEV) },
            )
            control(
                key = "settings-channel-stable",
                testTag = "settings-channel-stable",
                label = "STABLE",
                onClick = { onSelect(AppUpdateChannel.STABLE) },
            )
            section {
                DimLine(
                    "DEV FOLLOWS THE ROLLING dev-latest BUILD. " +
                        "STABLE FOLLOWS PUBLISHED RELEASES ONLY.",
                )
            }
        }
    }
}

/** The manager app's own update state as a two-line row label. */
private fun updateManagerLabel(appVersion: String, update: AppUpdateState): String =
    when (update) {
        is AppUpdateState.Idle ->
            "UPDATE MANAGER\nv$appVersion · UP TO DATE — CHECK AGAIN"
        is AppUpdateState.Checking ->
            "UPDATE MANAGER\nCHECKING…"
        is AppUpdateState.Available ->
            "UPDATE MANAGER\nv${update.info.version} AVAILABLE — UPDATE NOW"
        is AppUpdateState.Downloading -> {
            val pct = update.progress?.let { " — ${(it * 100).toInt()}%" } ?: ""
            "UPDATE MANAGER\nDOWNLOADING$pct"
        }
        is AppUpdateState.Downloaded ->
            "UPDATE MANAGER\nREADY — INSTALL NOW"
        is AppUpdateState.Installing ->
            "UPDATE MANAGER\nINSTALLING — FOLLOW THE SYSTEM PROMPT"
        is AppUpdateState.Failed ->
            "UPDATE MANAGER\nCHECK FAILED — RETRY"
    }

/** Tapping the row does nothing meaningful while the updater is busy. */
private fun updateActionEnabled(update: AppUpdateState): Boolean =
    update !is AppUpdateState.Checking &&
        update !is AppUpdateState.Downloading &&
        update !is AppUpdateState.Installing

/** " · SD CARD" / " · INTERNAL" suffix for the media row summary. */
private fun mediaBadge(loc: LocationState): String =
    if (loc is LocationState.Ready) {
        if (loc.isRemovable) " · SD CARD" else " · INTERNAL"
    } else {
        ""
    }

private fun channelLabel(channel: AppUpdateChannel): String = when (channel) {
    AppUpdateChannel.DEV -> "DEV / CANDIDATE"
    AppUpdateChannel.STABLE -> "STABLE"
}

/**
 * ES-DE MEDIA IMPORT (production): copies real artwork from the
 * read-only ES-DE export on the SD card into the existing Crystal
 * media tree (`games/<platform>/<gameId>/<slot>.png`) via the same
 * ScraperStorage path the scraper uses. The theme resolver is
 * untouched.
 *
 * Flow is deliberately two-step and controller-friendly:
 *  1. PRE-SCAN — matches the export against the ROM library and shows
 *     the full report. Copies NOTHING.
 *  2. IMPORT ES-DE MEDIA — one action, executes the reviewed plan.
 *
 * The import is idempotent: re-running only processes new/changed/
 * missing assets. Replacement rules: USER art is never overwritten,
 * REAL ES-DE art may replace GENERATED art, other REAL art is kept.
 */
@Composable
fun EsdeImportScreen(
    esdeLocation: LocationState,
    prescanning: Boolean,
    importStatus: String?,
    importPlanReady: Boolean,
    importReport: String?,
    importRunning: Boolean,
    importProgress: String?,
    importResult: String?,
    unmatchedCount: Int,
    healthCheckRunning: Boolean,
    healthCheckStatus: String?,
    onPrescan: () -> Unit,
    onImport: () -> Unit,
    onPickEsdeFolder: () -> Unit,
    onManualMatch: () -> Unit,
    onHealthCheck: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canPrescan = esdeLocation is LocationState.Ready && !prescanning && !importRunning
    val canImport = importPlanReady && !prescanning && !importRunning
    ScreenScaffold(
        routeKey = "settings-esde-import",
        title = "ES-DE MEDIA IMPORT",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "settings-esde-import-prescan",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                StatusLine(
                    "ES-DE FOLDER: ${friendlyLocation(esdeLocation)}",
                    if (esdeLocation is LocationState.AccessLost) Crystal.Bad else Crystal.Ink,
                )
                if (esdeLocation is LocationState.NotConfigured) {
                    DimLine("PICK THE ES-DE EXPORT FOLDER BELOW FIRST.")
                }
                if (esdeLocation is LocationState.AccessLost) {
                    DimLine("THE SAVED FOLDER IS NO LONGER READABLE — PICK IT AGAIN.")
                }
            }
            // u44: the ES-DE folder picker moved here from SETTINGS — the
            // export is the import screen's only input, so it belongs here.
            control(
                key = "settings-esde-import-pick-folder",
                testTag = "settings-esde-import-pick-folder",
                label = if (esdeLocation is LocationState.NotConfigured) "SELECT ES-DE FOLDER"
                else "CHANGE ES-DE FOLDER",
                onClick = onPickEsdeFolder,
            )
            control(
                key = "settings-esde-import-prescan",
                testTag = "settings-esde-import-prescan",
                label = if (prescanning) "SCANNING…" else "PRE-SCAN",
                onClick = onPrescan,
                enabled = canPrescan,
            )
            importReport?.let { report ->
                section {
                    StatusLine(report)
                }
            }
            if (prescanning) {
                section {
                    // Live phase: automatic ROM-library discovery first,
                    // then the export match. No manual step in between.
                    StatusLine(importStatus ?: "SCANNING…")
                }
            }
            if (importRunning) {
                section {
                    StatusLine("IMPORTING… ${importProgress ?: ""}")
                }
            }
            control(
                key = "settings-esde-import-run",
                testTag = "settings-esde-import-run",
                label = "IMPORT ES-DE MEDIA",
                onClick = onImport,
                enabled = canImport,
            )
            if (importPlanReady) {
                control(
                    key = "settings-esde-import-manual",
                    testTag = "settings-esde-import-manual",
                    label = if (unmatchedCount > 0) "GAME ARTWORK ($unmatchedCount UNMATCHED)"
                    else "GAME ARTWORK",
                    onClick = onManualMatch,
                    enabled = !prescanning && !importRunning,
                )
            }
            control(
                key = "settings-esde-import-health",
                testTag = "settings-esde-import-health",
                label = if (healthCheckRunning) "CHECKING…" else "CHECK MEDIA HEALTH",
                onClick = onHealthCheck,
                enabled = !prescanning && !importRunning && !healthCheckRunning,
            )
            if (healthCheckRunning) {
                section {
                    StatusLine(healthCheckStatus ?: "CHECKING…")
                    DimLine("READ-ONLY — DIAGNOSES WHY GAMES SHOW BLANK ART IN PEGASUS.")
                }
            }
            importResult?.let { result ->
                section {
                    StatusLine(result)
                }
            }
            if (importReport == null && importResult == null && !prescanning && !importRunning) {
                section {
                    DimLine(
                        "PRE-SCAN MATCHES THE EXPORT AGAINST YOUR ROM LIBRARY " +
                            "AND SHOWS EXACTLY WHAT WILL BE COPIED. NOTHING " +
                            "IS WRITTEN UNTIL YOU PRESS IMPORT ES-DE MEDIA. " +
                            "THE EXPORT STAYS READ-ONLY.",
                    )
                }
            }
        }
    }
}
