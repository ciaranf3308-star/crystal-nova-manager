@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.storage.LocationState
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Test

/**
 * Requirement 3 — focus can traverse every row.
 *
 * On the Settings list and the Launchers list: programmatically focus
 * the first row, send D-pad DOWN repeatedly, and assert each expected
 * row gains focus in order. [assertDpadTraversalInViewport] fails if
 * focus is lost, lands on the wrong row, or a row never becomes
 * focused — so focus can never silently land on a non-focusable or
 * vanish.
 */
class NovaDpadTraversalTest : NovaUiTest() {

    @Test
    fun dpadTraversesSettingsRowsInOrder() {
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

        val rows = listOf(
            "settings-update-app",
            "settings-row-rom",
            "settings-row-media",
            "settings-row-esde",
            "settings-row-probe",
            "settings-row-themes",
            "settings-row-channel",
            "settings-row-diagnostics",
        )
        assertDpadTraversalInViewport(
            rows.map { tag -> { composeTestRule.onNodeWithTag(tag) } },
        )
    }

    @Test
    fun dpadTraversesLauncherRowsInOrder() {
        val systems = fakePegasusSystems(6)
        setNovaContent {
            PegasusLaunchersScreen(
                systems = systems,
                onSelectSystem = { _, _ -> },
                onBack = {},
            )
        }

        assertDpadTraversalInViewport(
            systems.map { sys ->
                { composeTestRule.onNodeWithTag("launcher-row-${sys.slug}") }
            },
        )
    }
}
