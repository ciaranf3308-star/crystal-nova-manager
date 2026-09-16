package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.storage.LocationState

/**
 * SETTINGS: the two independent storage locations (ROM library and
 * MEDIA library), the themes storage root, and the diagnostics entry.
 * All paths are friendly display paths — never raw content:// URIs.
 * The media row carries an SD/INTERNAL badge from the location's
 * removable flag.
 */
@Composable
fun SettingsScreen(
    romLocation: LocationState,
    mediaLocation: LocationState,
    themesRootLabel: String,
    locationError: String?,
    onDismissLocationError: () -> Unit,
    onPickRom: () -> Unit,
    onPickMedia: () -> Unit,
    onClearRom: () -> Unit,
    onClearMedia: () -> Unit,
    onPickThemesRoot: () -> Unit,
    onDiagnostics: () -> Unit,
    updateChannel: AppUpdateChannel,
    onUpdateChannel: (AppUpdateChannel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "settings",
        title = "SETTINGS",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "settings-change-rom",
    ) {
        // This screen's single scroll container: D-pad focus on any
        // control scrolls it to a comfortable viewport position.
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                LocationPanel(
                    scope = this,
                    title = "ROM LIBRARY",
                    location = romLocation,
                    showBadge = false,
                    changeKey = "settings-change-rom",
                    clearKey = "settings-clear-rom",
                    onChange = onPickRom,
                    onClear = onClearRom,
                )
            }
            section {
                LocationPanel(
                    scope = this,
                    title = "MEDIA LIBRARY",
                    location = mediaLocation,
                    showBadge = true,
                    changeKey = "settings-change-media",
                    clearKey = "settings-clear-media",
                    onChange = onPickMedia,
                    onClear = onClearMedia,
                )
            }
            section {
                val s = this
                SectionLabel("THEME STORAGE")
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusLine(themesRootLabel)
                        s.control(
                            key = "settings-change-themes-root",
                            label = "CHANGE THEMES FOLDER",
                            onClick = onPickThemesRoot,
                        )
                    }
                }
            }
            locationError?.let {
                section { notice(it, onDismissLocationError) }
            }
            section {
                SectionLabel("APP UPDATE CHANNEL")
                UpdateChannelPanel(
                    scope = this,
                    channel = updateChannel,
                    onSelect = onUpdateChannel,
                )
            }
            section { CrystalDivider() }
            control(
                key = "settings-diagnostics",
                label = "DIAGNOSTICS",
                onClick = onDiagnostics,
            )
        }
    }
}

/**
 * The manager app's own update channel: DEV / CANDIDATE follows the
 * rolling dev-latest prerelease manifest, STABLE follows published
 * GitHub releases only. The active channel is shown as a status line;
 * either button re-checks for an app update immediately.
 */
@Composable
private fun UpdateChannelPanel(
    scope: SectionScope,
    channel: AppUpdateChannel,
    onSelect: (AppUpdateChannel) -> Unit,
) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLine("CURRENT: ${channelLabel(channel)}", Crystal.Cream)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    scope.control(
                        key = "settings-channel-dev",
                        label = "DEV / CANDIDATE",
                        onClick = { onSelect(AppUpdateChannel.DEV) },
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    scope.control(
                        key = "settings-channel-stable",
                        label = "STABLE",
                        onClick = { onSelect(AppUpdateChannel.STABLE) },
                    )
                }
            }
            DimLine(
                "DEV / CANDIDATE FOLLOWS THE ROLLING dev-latest BUILD. " +
                    "STABLE FOLLOWS PUBLISHED RELEASES ONLY.",
            )
        }
    }
}

private fun channelLabel(channel: AppUpdateChannel): String = when (channel) {
    AppUpdateChannel.DEV -> "DEV / CANDIDATE"
    AppUpdateChannel.STABLE -> "STABLE"
}

/**
 * One location row: friendly path (+ optional SD/INTERNAL badge),
 * CHANGE to re-pick, CLEAR to unconfigure (danger, only when a
 * location is actually configured).
 */
@Composable
private fun LocationPanel(
    scope: SectionScope,
    title: String,
    location: LocationState,
    showBadge: Boolean,
    changeKey: String,
    clearKey: String,
    onChange: () -> Unit,
    onClear: () -> Unit,
) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(title)
                if (showBadge && location is LocationState.Ready) {
                    StatusLine(
                        if (location.isRemovable) "SD CARD" else "INTERNAL",
                        Crystal.Cream,
                    )
                }
            }
            StatusLine(
                friendlyLocation(location),
                if (location is LocationState.AccessLost) Crystal.Bad else Crystal.Ink,
            )
            if (location is LocationState.AccessLost) {
                DimLine("THE SAVED FOLDER IS NO LONGER READABLE — PICK IT AGAIN.")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    scope.control(
                        key = changeKey,
                        label = "CHANGE",
                        onClick = onChange,
                    )
                }
                if (location !is LocationState.NotConfigured) {
                    Box(modifier = Modifier.weight(1f)) {
                        scope.control(
                            key = clearKey,
                            label = "CLEAR",
                            onClick = onClear,
                            danger = true,
                        )
                    }
                }
            }
        }
    }
}
