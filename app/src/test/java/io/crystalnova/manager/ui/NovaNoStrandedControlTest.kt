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
 * The SYSTEM hub and the THEME pack screen are rendered and focus is
 * traversed through EVERY focusable control to the last one, asserting
 * each is in-viewport when focused. If any focusable could never be
 * brought into view, the traversal fails — that is the point of this
 * test.
 *
 * Notes on the fixtures:
 * - The System hub's three rows (DIAGNOSTICS / BIOS / INSTALLED
 *   EMULATORS) plus the scaffold BACK are all focusable; the walk
 *   covers the three rows.
 * - Theme renders two fake packs: pack cards are display-only by
 *   design (install actions land with the catalog), so the only
 *   focusable control is BACK — the walk proves it is reachable and
 *   the pack cards' text is rendered.
 *
 * u44: the archived PEGASUS SETUP walk is retired with the strip-back.
 */
class NovaNoStrandedControlTest : NovaUiTest() {

    @Test
    fun everySystemHubControlReachableByDpad() {
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
                { composeTestRule.onNodeWithTag("system-diagnostics") },
                { composeTestRule.onNodeWithTag("system-bios") },
                { composeTestRule.onNodeWithTag("system-emulators") },
            ),
        )
    }

    @Test
    fun everyThemeControlReachableByDpad() {
        setNovaContent {
            ThemeScreen(
                packs = fakeCrystalPacks(2),
                onBack = {},
            )
        }

        // Pack cards render their names; the only focusable control is
        // BACK, which the traversal proves reachable.
        composeTestRule.onNodeWithText("CRYSTAL PACK 0").assertExists()
        composeTestRule.onNodeWithText("CRYSTAL PACK 1").assertExists()
        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("theme-back") },
            ),
        )
    }
}
