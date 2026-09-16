package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onOpenThemes: () -> Unit,
    onOpenChannel: () -> Unit,
    onDiagnostics: () -> Unit,
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
        ) {
            control(
                key = "settings-update-app",
                testTag = "settings-update-app",
                label = updateManagerLabel(appVersion, appUpdate),
                onClick = onUpdateApp,
                enabled = updateActionEnabled(appUpdate),
            )
            control(
                key = "settings-row-rom",
                testTag = "settings-row-rom",
                label = "ROM LIBRARY\n${friendlyLocation(romLocation)}",
                onClick = onOpenRom,
            )
            control(
                key = "settings-row-media",
                testTag = "settings-row-media",
                label = "MEDIA LIBRARY\n${friendlyLocation(mediaLocation)}${mediaBadge(mediaLocation)}",
                onClick = onOpenMedia,
            )
            control(
                key = "settings-row-themes",
                testTag = "settings-row-themes",
                label = "THEME STORAGE\n$themesRootLabel",
                onClick = onOpenThemes,
            )
            control(
                key = "settings-row-channel",
                testTag = "settings-row-channel",
                label = "UPDATE CHANNEL\n${channelLabel(updateChannel)}",
                onClick = onOpenChannel,
            )
            locationError?.let {
                section { notice(it, onDismissLocationError) }
            }
            control(
                key = "settings-row-diagnostics",
                testTag = "settings-row-diagnostics",
                label = "DIAGNOSTICS",
                onClick = onDiagnostics,
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
