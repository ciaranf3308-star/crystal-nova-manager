package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dispatcher = remember { FocusDispatcher() }
    ScreenRoot(onBack = onBack, dispatcher = dispatcher, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("SETTINGS")
            LocationPanel(
                title = "ROM LIBRARY",
                location = romLocation,
                showBadge = false,
                dispatcher = dispatcher,
                changeKey = "settings-change-rom",
                clearKey = "settings-clear-rom",
                onChange = onPickRom,
                onClear = onClearRom,
                requestInitialFocus = true,
            )
            LocationPanel(
                title = "MEDIA LIBRARY",
                location = mediaLocation,
                showBadge = true,
                dispatcher = dispatcher,
                changeKey = "settings-change-media",
                clearKey = "settings-clear-media",
                onChange = onPickMedia,
                onClear = onClearMedia,
            )
            SectionLabel("THEME STORAGE")
            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusLine(themesRootLabel)
                    CrystalButton(
                        key = "settings-change-themes-root",
                        label = "CHANGE THEMES FOLDER",
                        onClick = onPickThemesRoot,
                        dispatcher = dispatcher,
                    )
                }
            }
            locationError?.let {
                NoticeBlock(it, onDismissLocationError, dispatcher)
            }
            CrystalDivider()
            CrystalButton(
                key = "settings-diagnostics",
                label = "DIAGNOSTICS",
                onClick = onDiagnostics,
                dispatcher = dispatcher,
            )
            BackFooter()
        }
    }
}

/**
 * One location row: friendly path (+ optional SD/INTERNAL badge),
 * CHANGE to re-pick, CLEAR to unconfigure (danger, only when a
 * location is actually configured).
 */
@Composable
private fun LocationPanel(
    title: String,
    location: LocationState,
    showBadge: Boolean,
    dispatcher: FocusDispatcher,
    changeKey: String,
    clearKey: String,
    onChange: () -> Unit,
    onClear: () -> Unit,
    requestInitialFocus: Boolean = false,
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
                    CrystalButton(
                        key = changeKey,
                        label = "CHANGE",
                        onClick = onChange,
                        dispatcher = dispatcher,
                        requestInitialFocus = requestInitialFocus,
                    )
                }
                if (location !is LocationState.NotConfigured) {
                    Box(modifier = Modifier.weight(1f)) {
                        CrystalButton(
                            key = clearKey,
                            label = "CLEAR",
                            onClick = onClear,
                            dispatcher = dispatcher,
                            danger = true,
                        )
                    }
                }
            }
        }
    }
}
