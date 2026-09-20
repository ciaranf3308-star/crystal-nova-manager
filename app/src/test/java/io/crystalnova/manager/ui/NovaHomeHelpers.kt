@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertTrue

// ---------------------------------------------------------------------------
// Focus + viewport drivers for the u50 single-screen home, recovered
// from the retired NovaScreenFakes.kt (its pack/scraper fakes died with
// the strip; these drivers are pure UI-test helpers with no app deps).
// ---------------------------------------------------------------------------

/**
 * Requests D-pad focus on this node via its `RequestFocus` semantics
 * action (installed by `Modifier.focusRequester`, which every
 * [CrystalButton] carries). Deterministic regardless of the scaffold's
 * per-route focus memory left behind by earlier tests.
 */
fun SemanticsNodeInteraction.requestDpadFocus(): SemanticsNodeInteraction {
    requestFocus()
    return this
}

/**
 * Viewport assertion for a node addressed by an arbitrary interaction
 * (tag- or text-based): it must exist, have non-empty bounds, and
 * overlap the 1280x960 viewport. Same strength as
 * [NovaUiTest.assertNodeInViewport] but usable where no testTag exists.
 */
fun NovaUiTest.assertInteractionInViewport(
    interaction: SemanticsNodeInteraction,
    label: String,
) {
    val bounds = interaction.fetchSemanticsNode().boundsInRoot
    assertTrue(
        "$label has empty bounds ($bounds) — nothing is displayed",
        bounds.width > 0 && bounds.height > 0,
    )
    val viewport = viewportBounds()
    assertTrue(
        "$label (bounds $bounds) does not overlap the 1280x960 viewport ($viewport)",
        viewport.overlaps(bounds),
    )
}

/**
 * Non-asserting viewport check by tag. A tag the lazy list has not
 * composed yet counts as outside — what the scroll tests need.
 */
fun NovaUiTest.isTagInViewport(tag: String): Boolean {
    val nodes = composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
    if (nodes.isEmpty()) return false
    val viewport = viewportBounds()
    return nodes.any { node ->
        val bounds = node.boundsInRoot
        bounds.width > 0 && bounds.height > 0 && viewport.overlaps(bounds)
    }
}

/**
 * Drives D-pad DOWN through [steps] in order and proves the traversal
 * contract: each step is a thunk resolving the [SemanticsNodeInteraction]
 * for the expected row (tag- or text-addressed).
 *
 * - The first step is focused explicitly (immune to focus-memory
 *   leftovers from other tests), then must be focused and in-viewport.
 * - Each subsequent step: press D-pad DOWN on the currently-focused
 *   row (the key event routes by focus, and the thunk is only resolved
 *   after the press so below-fold lazy items are composed first), wait
 *   for the focus-to-viewport scroll to settle, then the next row must
 *   hold focus and sit inside the viewport.
 *
 * Any deviation — focus lost, focus landing on the wrong row, or a row
 * that can never be brought into view — fails the test.
 */
fun NovaUiTest.assertDpadTraversalInViewport(
    steps: List<() -> SemanticsNodeInteraction>,
) {
    require(steps.isNotEmpty()) { "traversal needs at least one step" }
    var focused = steps[0]()
    focused.requestDpadFocus()
    composeTestRule.waitForIdle()
    focused.assertIsFocused()
    assertInteractionInViewport(focused, "traversal step 0")
    for (i in 1 until steps.size) {
        // The press is issued on the currently-focused row's interaction:
        // key events route to whichever node holds focus, and this keeps
        // the node-fetch off rows that may already have scrolled away.
        focused.pressDpadDown()
        composeTestRule.waitForIdle()
        focused = steps[i]()
        focused.assertIsFocused()
        assertInteractionInViewport(focused, "traversal step $i")
    }
}

/**
 * Current vertical scroll offset (px == dp at density 1) of the single
 * scrollable on screen, read from its `VerticalScrollAxisRange`
 * semantics.
 */
fun NovaUiTest.verticalScrollValue(): Float {
    val node = composeTestRule.onNode(hasScrollAction()).fetchSemanticsNode()
    return composeTestRule.runOnUiThread {
        node.config[SemanticsProperties.VerticalScrollAxisRange].value()
    }
}
