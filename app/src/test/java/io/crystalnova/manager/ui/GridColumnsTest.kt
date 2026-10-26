package io.crystalnova.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grid column helper tests (u87): the column rule is a pure function
 * of viewport width — no Android needed.
 */
class GridColumnsTest {

    @Test fun `narrow portrait width yields one column`() {
        assertEquals(1, gridColumnsForWidth(360, 2))
        assertEquals(1, gridColumnsForWidth(360, 3))
        assertEquals(1, gridColumnsForWidth(480, 2))
    }

    @Test fun `just below the breakpoint still yields one column`() {
        assertEquals(1, gridColumnsForWidth(GRID_WIDE_MIN_WIDTH_DP - 1, 2))
        assertEquals(1, gridColumnsForWidth(GRID_WIDE_MIN_WIDTH_DP - 1, 3))
    }

    @Test fun `breakpoint width is inclusive and yields the wide count`() {
        assertEquals(2, gridColumnsForWidth(GRID_WIDE_MIN_WIDTH_DP, 2))
        assertEquals(3, gridColumnsForWidth(GRID_WIDE_MIN_WIDTH_DP, 3))
    }

    @Test fun `wide landscape width yields the wide count`() {
        assertEquals(2, gridColumnsForWidth(700, 2))
        assertEquals(3, gridColumnsForWidth(900, 3))
    }

    @Test fun `wide column count is coerced to at least one`() {
        assertEquals(1, gridColumnsForWidth(800, 0))
        assertEquals(1, gridColumnsForWidth(800, -2))
        assertEquals(1, gridColumnsForWidth(360, 0))
    }

    @Test fun `non-positive width yields one column`() {
        assertEquals(1, gridColumnsForWidth(0, 2))
        assertEquals(1, gridColumnsForWidth(-100, 3))
    }
}
