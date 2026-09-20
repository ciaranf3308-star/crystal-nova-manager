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
 * viewport and asserts every row plus the footer are inside the
 * viewport with NO scroll interaction anywhere in the test.
 *
 * u44: eight rows — the INSTALL/UPDATE LAUNCHER row and the separate
 * ES-DE export-folder row are gone with the strip-back.
 * u46: nine rows — the INJECT TEST PACK test-utility row is added.
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
                themesRootLabel = "INTERNAL STORAGE /Themes",
                updateChannel = AppUpdateChannel.STABLE,
                appVersion = "1.2.4-u44-stripback",
                appUpdate = AppUpdateState.Idle(),
                locationError = null,
                onDismissLocationError = {},
                onUpdateApp = {},
                onOpenRom = {},
                onOpenMedia = {},
                onOpenEsdeImport = {},
                onOpenThemes = {},
                onOpenChannel = {},
                onOpenAppearance = {},
                onDiagnostics = {},
                injectBusy = false,
                injectStatus = null,
                onInjectTestPack = {},
                onBack = {},
            )
        }

        assertNodeInViewport("settings-update-app")
        assertNodeInViewport("settings-row-rom")
        assertNodeInViewport("settings-row-media")
        assertNodeInViewport("settings-row-esde-import")
        assertNodeInViewport("settings-row-themes")
        assertNodeInViewport("settings-row-channel")
        assertNodeInViewport("settings-row-appearance")
        assertNodeInViewport("settings-row-diagnostics")
        assertNodeInViewport("settings-row-inject-test-pack")

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
