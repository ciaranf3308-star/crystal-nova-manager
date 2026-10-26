package io.crystalnova.manager.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Portrait/landscape grid adaptivity (u87).
 *
 * The app used to pin every grid to a fixed column count tuned for
 * the handheld's landscape viewport. Once rotation is unlocked, a
 * narrow portrait viewport would squeeze those same columns. This
 * file is the single rule every grid screen uses: full column
 * count on a wide viewport, a single comfortable column below it.
 *
 * The width being judged is the AVAILABLE width of the scaffold
 * content area (see [LocalContentWidth]), not the raw screen width:
 * the content area is what the grid actually lays out in, and it is
 * what the Nova contract tests model (their fixed 1280dp viewport).
 * Reading the screen configuration instead would collapse grids in
 * environments where the window configuration is narrower than the
 * laid-out content (e.g. Robolectric), breaking the home-screen
 * contract suite for no on-device benefit.
 *
 * [gridColumnsForWidth] itself is a pure function of its inputs —
 * unit-tested without Android.
 *
 * The breakpoint is the classic 600dp wide threshold: a portrait
 * phone/handheld viewport (typically 360–480dp) gets 1 column, while
 * a landscape viewport (600dp and up) keeps the screen's designed
 * column count. The boundary is inclusive: exactly 600dp counts as
 * wide.
 */

/** Minimum viewport width in dp that still gets the full column count. */
const val GRID_WIDE_MIN_WIDTH_DP = 600

/**
 * Available width of the scaffold content area, provided by
 * [ScreenScaffold] via [CompositionLocalProvider]. Defaults to 0.dp
 * (no provider in scope) which [gridColumnsForWidth] treats as
 * narrow — a grid rendered outside the scaffold safely collapses to
 * one column instead of guessing.
 */
val LocalContentWidth = compositionLocalOf<Dp> { 0.dp }

/**
 * Column count for a grid at the given available width.
 *
 * @param widthDp available width in dp (e.g. `LocalContentWidth.current.value.toInt()`).
 * @param wideColumns column count on a wide viewport; coerced to at least 1.
 * @return [wideColumns] when [widthDp] is at least [GRID_WIDE_MIN_WIDTH_DP], else 1.
 */
fun gridColumnsForWidth(widthDp: Int, wideColumns: Int): Int {
    val wide = wideColumns.coerceAtLeast(1)
    return if (widthDp >= GRID_WIDE_MIN_WIDTH_DP) wide else 1
}
