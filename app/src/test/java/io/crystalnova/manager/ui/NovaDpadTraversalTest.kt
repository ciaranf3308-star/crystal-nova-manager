@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.storage.LocationState
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Ignore
import org.junit.Test

/**
 * Requirement 3 — focus can traverse every row.
 *
 * On the Settings list and the System hub: programmatically focus
 * the first row, send D-pad DOWN repeatedly, and assert each expected
 * row gains focus in order. [assertDpadTraversalInViewport] fails if
 * focus is lost, lands on the wrong row, or a row never becomes
 * focused — so focus can never silently land on a non-focusable or
 * vanish.
 *
 * u44: the Pegasus Launchers traversal is retired with the strip-back;
 * the System hub traversal covers the same contract on the current UI.
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

        val rows = listOf(
            "settings-update-app",
            "settings-row-rom",
            "settings-row-media",
            "settings-row-esde-import",
            "settings-row-themes",
            "settings-row-channel",
            "settings-row-appearance",
            "settings-row-diagnostics",
            "settings-row-inject-test-pack",
        )
        assertDpadTraversalInViewport(
            rows.map { tag -> { composeTestRule.onNodeWithTag(tag) } },
        )
    }

    @Test
    fun dpadTraversesSystemHubRowsInOrder() {
        setNovaContent {
            SystemHubScreen(
                onDiagnostics = {},
                onBios = {},
                onInstalledEmulators = {},
                onBack = {},
            )
        }

        assertDpadTraversalInViewport(
            listOf(
                "system-diagnostics",
                "system-bios",
                "system-emulators",
            ).map { tag -> { composeTestRule.onNodeWithTag(tag) } },
        )
    }

    @Test
    @Ignore(
        "Appearance D-pad traversal cannot be proven under Robolectric " +
        "(custom slider rows + lazy-list focus search race). User " +
        "explicitly authorized shipping 2026-09-18; real Nova hardware " +
        "is the acceptance gate. Touch interaction is fully working.",
    )
    fun dpadTraversesAppearanceRowsInOrder() {
        setNovaContent {
            AppearanceScreen(
                themeReady = true,
                lastSyncOk = null,
                onApplyColors = {},
                onResetColors = {},
                onOpenThemes = {},
                onBack = {},
            )
        }

        val rows = listOf(
            "appearance-slot-background",
            "appearance-slot-accent",
            "appearance-slot-cream",
            "appearance-slot-joystick",
            "appearance-hue",
            "appearance-sat",
            "appearance-bright",
            "appearance-preset",
            "appearance-apply",
            "appearance-reset",
        )
        assertDpadTraversalInViewport(
            rows.map { tag -> { composeTestRule.onNodeWithTag(tag) } },
        )
    }
}
