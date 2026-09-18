@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.storage.LocationState
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Test

/**
 * Requirement 2 — the Settings top-level menu fits.
 *
 * Renders the real [SettingsScreen] (top level) in the 1280x960
 * viewport and asserts all six rows plus the footer are inside the
 * viewport with NO scroll interaction anywhere in the test.
 */
class NovaSettingsFitsViewportTest : NovaUiTest() {

    @Test
    fun settingsTopLevelFitsWithoutScrolling() {
        setNovaContent {
            SettingsScreen(
                romLocation = LocationState.Ready(
                    "INTERNAL STORAGE /Roms",
                    isRemovable = false,
                ),
                mediaLocation = LocationState.NotConfigured,
                esdeLocation = LocationState.NotConfigured,
                themesRootLabel = "INTERNAL STORAGE /Themes",
                updateChannel = AppUpdateChannel.STABLE,
                appVersion = "1.0.2",
                appUpdate = AppUpdateState.Idle(),
                locationError = null,
                onDismissLocationError = {},
                onUpdateApp = {},
                onOpenRom = {},
                onOpenMedia = {},
                onOpenEsde = {},
                onOpenProbe = {},
                onOpenThemes = {},
                onOpenChannel = {},
                onDiagnostics = {},
                onBack = {},
            )
        }

        assertNodeInViewport("settings-update-app")
        assertNodeInViewport("settings-row-rom")
        assertNodeInViewport("settings-row-media")
        assertNodeInViewport("settings-row-esde")
        assertNodeInViewport("settings-row-probe")
        assertNodeInViewport("settings-row-themes")
        assertNodeInViewport("settings-row-channel")
        assertNodeInViewport("settings-row-diagnostics")

        // Pinned footer: A SELECT · B BACK.
        assertInteractionInViewport(
            composeTestRule.onNodeWithText("A"),
            "footer keycap A",
        )
        assertInteractionInViewport(
            composeTestRule.onNodeWithText("B"),
            "footer keycap B",
        )
    }
}
