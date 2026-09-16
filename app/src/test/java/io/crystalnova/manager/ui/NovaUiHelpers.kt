@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp

/** Logical viewport width of the Retroid Pocket Nova screen (4:3). */
const val NOVA_VIEWPORT_WIDTH_DP = 1280

/** Logical viewport height of the Retroid Pocket Nova screen (4:3). */
const val NOVA_VIEWPORT_HEIGHT_DP = 960

/** Test tag of the fixed-size viewport box installed by [NovaUiTest.setNovaContent]. */
const val NOVA_VIEWPORT_TAG = "nova-viewport"

// ---------------------------------------------------------------------------
// D-pad key injection
// ---------------------------------------------------------------------------

/** Wraps an Android [keyCode] press (ACTION_DOWN) as a Compose [KeyEvent]. */
fun dpadKeyEvent(keyCode: Int): ComposeKeyEvent =
    ComposeKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keyCode))

fun dpadDownKeyEvent(): ComposeKeyEvent = dpadKeyEvent(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

fun dpadUpKeyEvent(): ComposeKeyEvent = dpadKeyEvent(AndroidKeyEvent.KEYCODE_DPAD_UP)

/**
 * Sends a D-pad DOWN press to this node via [performKeyPress].
 *
 * Key events are delivered to whichever node currently holds focus, so call
 * this on the focused node (assert with `assertIsFocused()` first). Compose's
 * focus system turns DPAD_DOWN into `FocusDirection.Down` movement between
 * `focusable()` nodes.
 */
fun SemanticsNodeInteraction.pressDpadDown(): SemanticsNodeInteraction =
    performKeyPress(dpadDownKeyEvent())

/** D-pad UP variant of [pressDpadDown]. */
fun SemanticsNodeInteraction.pressDpadUp(): SemanticsNodeInteraction =
    performKeyPress(dpadUpKeyEvent())

// ---------------------------------------------------------------------------
// Scroll gestures
// ---------------------------------------------------------------------------

/**
 * Swipes up across almost the full height of this node (node-local
 * coordinates), the standard gesture for scrolling a list on the Nova.
 *
 * Returns the interaction so calls chain: `onNodeWithTag("list").swipeUpFullHeight()`.
 */
fun SemanticsNodeInteraction.swipeUpFullHeight(
    durationMillis: Long = 300
): SemanticsNodeInteraction {
    performTouchInput {
        swipeUp(
            startY = height * 0.95f,
            endY = height * 0.05f,
            durationMillis = durationMillis
        )
    }
    return this
}

/**
 * The scroll position of a [LazyListState] as a single comparable Int.
 *
 * Snapshot it before a gesture and compare after (reads must happen on the
 * test thread via `composeTestRule.runOnUiThread { state.scrollPositionForTest() }`
 * to observe the settled post-gesture value):
 *
 * ```
 * val before = composeTestRule.runOnUiThread { listState.scrollPositionForTest() }
 * composeTestRule.onNodeWithTag("list").swipeUpFullHeight()
 * val after = composeTestRule.runOnUiThread { listState.scrollPositionForTest() }
 * assertTrue(after > before)
 * ```
 */
fun LazyListState.scrollPositionForTest(): Int =
    firstVisibleItemIndex * 1_000_000 + firstVisibleItemScrollOffset
