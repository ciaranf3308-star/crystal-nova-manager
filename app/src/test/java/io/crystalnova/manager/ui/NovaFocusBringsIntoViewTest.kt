@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Test

/**
 * Requirement 4 — scrolling screens bring focused rows into view.
 *
 * The LAUNCHERS screen with 25 systems is far taller than the 960dp
 * viewport. The test first proves the target row starts below the fold
 * (not even composed), then walks D-pad DOWN from the first row to row
 * 12, asserting every focused row lands inside the viewport. Each hop
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

        // Sanity: row 12 really starts below the fold (the lazy list has
        // not composed it yet, which counts as outside the viewport).
        assertNodeOutsideViewport("launcher-row-sys-12")

        val steps = (0..12).map { i ->
            { composeTestRule.onNodeWithTag("launcher-row-sys-$i") }
        }
        assertDpadTraversalInViewport(steps)
    }
}
