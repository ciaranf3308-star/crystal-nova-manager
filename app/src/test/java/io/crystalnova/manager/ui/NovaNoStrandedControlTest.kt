@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Test

/**
 * Requirement 5 — no essential action can remain permanently below the
 * viewport.
 *
 * The previously-worst screens (PEGASUS SETUP, THEME) are rendered in
 * their busiest states and focus is traversed through EVERY focusable
 * control to the last one, asserting each is in-viewport when focused.
 * If any focusable could never be brought into view, the traversal
 * fails — that is the point of this test.
 *
 * Notes on the fixtures:
 * - Pegasus Setup uses `romWritable = false` (shows RE-PICK ROM ROOT),
 *   an inject warning, a dismissible notice, and Pegasus installed — the
 *   tallest honest configuration. `injectEnabled = true` keeps INJECT
 *   focusable so the walk covers it; a disabled control is correctly
 *   skipped by D-pad and is a separate behavior.
 * - The notice's DISMISS button carries no testTag (it is built by
 *   `SectionScope.notice`), so it is addressed by its exact label text.
 * - Theme uses the busiest `ManagerState.Ready`: update available,
 *   backup present, notice set — five focusable controls.
 */
class NovaNoStrandedControlTest : NovaUiTest() {

    @Test
    fun everyPegasusSetupControlReachableByDpad() {
        setNovaContent {
            PegasusSetupScreen(
                metafileTarget = "INTERNAL STORAGE /ROMs · NOT WRITABLE — RE-PICK ROM ROOT",
                romWritable = false,
                systems = listOf(
                    PegasusSystemRow(
                        slug = "snes",
                        label = "Super Nintendo",
                        gameCount = 12,
                        launcherStatus = "NOT CONFIGURED",
                        launcherInstalled = false,
                        isDefault = false,
                    ),
                    PegasusSystemRow(
                        slug = "gba",
                        label = "Game Boy Advance",
                        gameCount = 8,
                        launcherStatus = "RetroArch 64",
                        launcherInstalled = true,
                        isDefault = true,
                    ),
                ),
                injectEnabled = true,
                injectWarning = "1 SYSTEM NEEDS LAUNCHER SETUP — INJECT WILL SKIP IT.",
                injecting = false,
                notice = "LIBRARY REFRESHED — 20 GAMES FOUND.",
                pegasusInstalled = true,
                onRepickRomRoot = {},
                onRescan = {},
                onConfigureLaunchers = {},
                onInject = {},
                onDismissNotice = {},
                onOpenPegasus = {},
                onBack = {},
            )
        }

        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("pegasus-setup-rom-root") },
                { composeTestRule.onNodeWithTag("pegasus-setup-autoconfigure") },
                { composeTestRule.onNodeWithTag("pegasus-setup-launchers") },
                { composeTestRule.onNodeWithTag("pegasus-setup-refresh") },
                { composeTestRule.onNodeWithTag("pegasus-setup-inject") },
                // DISMISS has no testTag by construction; exact label text.
                { composeTestRule.onNodeWithText("DISMISS") },
                { composeTestRule.onNodeWithTag("pegasus-setup-open") },
            ),
        )
    }

    @Test
    fun everyThemeControlReachableByDpad() {
        setNovaContent {
            ThemeScreen(
                state = fakeThemeReadyState(),
                onEvent = {},
                pegasusLaunchable = true,
                onPickFolder = {},
                onBack = {},
            )
        }

        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("theme-update") },
                { composeTestRule.onNodeWithTag("theme-rollback") },
                { composeTestRule.onNodeWithTag("theme-retry") },
                { composeTestRule.onNodeWithTag("theme-open-pegasus") },
                { composeTestRule.onNodeWithTag("theme-change-folder") },
            ),
        )
    }
}
