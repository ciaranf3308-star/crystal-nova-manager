package io.crystalnova.manager.ui

/**
 * Portrait/landscape grid adaptivity (u87).
 *
 * The app used to pin every grid to a fixed column count tuned for
 * the handheld's landscape viewport. Once rotation is unlocked, a
 * narrow portrait viewport would squeeze those same columns. This
 * helper is the single rule every grid screen uses: full column
 * count on a wide viewport, a single comfortable column below it.
 *
 * Pure function of its inputs — unit-tested without Android.
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
 * Column count for a grid at the given viewport width.
 *
 * @param widthDp viewport width in dp (e.g. `LocalConfiguration.current.screenWidthDp`).
 * @param wideColumns column count on a wide viewport; coerced to at least 1.
 * @return [wideColumns] when [widthDp] is at least [GRID_WIDE_MIN_WIDTH_DP], else 1.
 */
fun gridColumnsForWidth(widthDp: Int, wideColumns: Int): Int {
    val wide = wideColumns.coerceAtLeast(1)
    return if (widthDp >= GRID_WIDE_MIN_WIDTH_DP) wide else 1
}
