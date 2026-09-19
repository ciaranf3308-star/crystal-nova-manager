@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement 6 — a touch-scroll container remains scrollable.
 *
 * Extends the harness smoke test to a REAL screen: the GAME ARTWORK
 * screen with 25 games. A full-height touch swipe must change the
 * list's scroll position (read black-box from its
 * `VerticalScrollAxisRange` semantics — screens own their
 * `LazyListState` inside the scaffold) and reveal the last row, which
 * starts below the fold.
 *
 * u44: the archived LAUNCHERS screen is retired; the game-artwork list
 * is the current UI's tall focusable list.
 */
class NovaTouchScrollTest : NovaUiTest() {

    @Test
    fun touchSwipeScrollsGameListAndRevealsBelowFoldRow() {
        setNovaContent {
            EsdeManualMatchScreen(
                importPlan = fakeManualMatchPlan(25),
                manualMatchResult = null,
                selectedGame = null,
                artworkSlots = null,
                artworkResult = null,
                onSelectGame = {},
                onApplyMatch = { _, _ -> },
                onPickImage = { _, _, _ -> },
                onClearSlot = { _, _, _ -> },
                onBack = {},
            )
        }

        val target = "esde-manual-game-game-24"
        // The last row starts below the fold: the lazy list has not
        // composed it yet.
        assertNodeOutsideViewport(target)

        val list = composeTestRule.onNode(hasScrollAction())
        val before = verticalScrollValue()

        // Swipe until the target is visible (bounded: a screen that never
        // reveals it fails below instead of hanging the test).
        var swipes = 0
        while (swipes < 12 && !isTagInViewport(target)) {
            list.swipeUpFullHeight()
            composeTestRule.waitForIdle()
            swipes++
        }

        val after = verticalScrollValue()
        assertTrue(
            "touch swipe did not scroll the GAME ARTWORK list " +
                "(scroll position before=$before after=$after, swipes=$swipes)",
            after > before,
        )
        assertNodeInViewport(target)
    }
}
