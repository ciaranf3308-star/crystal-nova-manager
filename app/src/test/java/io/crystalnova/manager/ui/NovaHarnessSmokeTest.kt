package io.crystalnova.manager.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the [NovaUiTest] harness works end-to-end on the JVM:
 *
 * 1. A [LazyColumn] taller than the 960dp viewport actually scrolls in
 *    response to a swipe gesture (scroll offset changes).
 * 2. An item that starts outside the 1280x960 viewport ends up inside it
 *    after scrolling (viewport-bounds assertions, not just `assertIsDisplayed`).
 * 3. D-pad DOWN key injection moves focus between `focusable()` nodes.
 *
 * No text is rendered (boxes only) so nothing depends on font measurement
 * under Robolectric.
 */
class NovaHarnessSmokeTest : NovaUiTest() {

    @Test
    fun swipeScrollsLazyColumnAndRevealsOffscreenItem() {
        val listState = LazyListState()
        val itemCount = 12
        val itemHeightDp = 200 // 12 * 200dp = 2400dp of content in a 960dp viewport

        setNovaContent {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("scroll-list")
            ) {
                items(itemCount) { index ->
                    Box(
                        Modifier
                            .requiredSize(NOVA_VIEWPORT_WIDTH_DP.dp, itemHeightDp.dp)
                            .testTag("item-$index")
                    )
                }
            }
        }

        // item-10 (y 2000..2200) starts below the fold; LazyColumn hasn't
        // composed it yet, which assertNodeOutsideViewport treats as outside.
        assertNodeOutsideViewport("item-10")

        val scrolledBefore = composeTestRule.runOnUiThread { listState.scrollPositionForTest() }

        repeat(3) {
            composeTestRule.onNodeWithTag("scroll-list").swipeUpFullHeight()
        }

        val scrolledAfter = composeTestRule.runOnUiThread { listState.scrollPositionForTest() }
        assertTrue(
            "LazyColumn scroll offset did not change after swipeUp " +
                "(before=$scrolledBefore after=$scrolledAfter)",
            scrolledAfter > scrolledBefore
        )

        // Max scroll is 2400 - 960 = 1440, so the viewport now covers
        // [1440, 2400] and item-10 (y 2000..2200) must be inside it.
        assertNodeInViewport("item-10")
    }

    @Test
    fun dpadDownMovesFocusToNextItem() {
        val focusRequesters = List(3) { FocusRequester() }

        setNovaContent {
            Column(Modifier.fillMaxSize()) {
                focusRequesters.forEachIndexed { index, requester ->
                    Box(
                        Modifier
                            .requiredSize(400.dp, 120.dp)
                            .focusRequester(requester)
                            .focusable()
                            .testTag("focus-$index")
                    )
                }
            }
        }

        composeTestRule.runOnUiThread { focusRequesters[0].requestFocus() }

        composeTestRule.onNodeWithTag("focus-0").assertIsFocused()
        composeTestRule.onNodeWithTag("focus-0").pressDpadDown()
        composeTestRule.onNodeWithTag("focus-1").assertIsFocused()
        composeTestRule.onNodeWithTag("focus-1").pressDpadDown()
        composeTestRule.onNodeWithTag("focus-2").assertIsFocused()
    }
}
