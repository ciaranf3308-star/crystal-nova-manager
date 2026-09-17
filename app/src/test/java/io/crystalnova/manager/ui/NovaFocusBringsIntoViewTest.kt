@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Test

/**
 * KNOWN-FAILING HARDWARE-BEHAVIOR TEST — do not delete, weaken, or
 * "fix" for the JVM environment.
 *
 * Physical Nova D-pad focus scrolling cannot be proven under
 * Robolectric: after DPAD_DOWN the newly focused row reports empty
 * bounds in the JVM test environment even with all focus-scroll code
 * disabled, so this failure is environmental, not behavioral. This test
 * is the executable specification of the hardware behavior: it runs in
 * CI as a separate non-gating job and is verified on the physical
 * Retroid Pocket Nova instead.
 *
 * Requirement 4 — scrolling screens bring focused rows into view.
 *
 * The LAUNCHERS screen with 25 systems is far taller than the 960dp
 * viewport. The test first proves the target row starts below the fold
 * (not even composed), then walks D-pad DOWN from the first row to the
 * last row, asserting every focused row lands inside the viewport. Each hop
 * drives worker A's focus-to-viewport engine for real: focusing a row
 * near the bottom edge scrolls it centered, which composes the rows
 * below it, which the next D-pad press can then reach. If the engine
 * ever strands a focused row offscreen, the traversal fails.
 */
class NovaFocusBringsIntoViewTest : NovaUiTest() {

    @Test
    fun dpadFocusBringsBelowFoldLauncherRowIntoViewport() {
        val systems = fakePegasusSystems(25)
        setNovaContent {
            PegasusLaunchersScreen(
                systems = systems,
                onSelectSystem = { _, _ -> },
                onBack = {},
            )
        }

        // Sanity: the last row really starts below the fold (the lazy list
        // has not composed it yet, which counts as outside the viewport).
        // (Rows are compact — row 12 is already visible in 960dp.)
        assertNodeOutsideViewport("launcher-row-sys-24")

        val steps = (0..24).map { i ->
            { composeTestRule.onNodeWithTag("launcher-row-sys-$i") }
        }
        assertDpadTraversalInViewport(steps)
    }
}
