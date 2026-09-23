package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import org.junit.Assert.fail
import org.junit.Test

/**
 * u58 regression guard: on the Nova (1280x960 4:3) the grid layout pass
 * shipped with "loads of overlays on top of each other so nothing is able
 * to be read". This test renders the u58 structural patterns — a
 * [ControllerGrid] with full-span panels plus fixed-height
 * (`Modifier.height(88.dp)`) controls carrying long labels/sublabels —
 * inside the real [ScreenScaffold], and fails if any two text nodes'
 * bounds overlap without containment.
 *
 * Rationale: [CrystalButton] does not clip its content, so a fixed-height
 * tile whose label+sublabel column measures taller than the fixed height
 * draws its overflowing text over the grid row below. Long platform names
 * and sublabels (e.g. "RETRIES THIS GAME AS NINTENDO GAMECUBE") wrap to
 * multiple lines inside the ~410dp grid cell and exceed 88dp.
 */
class GridOverlapTest : NovaUiTest() {

    @Test
    fun gridPanelsAndFixedHeightControls_noTextOverlap() {
        setNovaContent {
            ScreenScaffold(
                routeKey = "test-overlap",
                title = "OVERLAP TEST",
                onBack = {},
                fallbackFocusKey = "done",
            ) {
                ControllerGrid(
                    state = gridState,
                    dispatcher = dispatcher,
                    columns = GridCells.Fixed(3),
                    initialFocus = ::isInitialFocus,
                ) {
                    panel {
                        StatusLine("RUN FINISHED", Crystal.Joystick)
                        StatusLine("3 IMPORTED · 2 FAILED · 0 SKIPPED")
                        StatusLine(
                            "FAILED SOURCES WERE KEPT — RETRY OR SKIP BELOW",
                            Crystal.Bad,
                        )
                    }
                    // Failed-row pattern from the results screen: one
                    // full-width detail panel, then three action tiles.
                    panel {
                        StatusLine("✗ Super Mario Sunshine (USA).zip", Crystal.Bad)
                        StatusLine(
                            "NINTENDO GAMECUBE · EXTRACTION FAILED: " +
                                "ARCHIVE APPEARS TO BE TRUNCATED OR CORRUPT",
                            Crystal.InkDim,
                        )
                    }
                    control(
                        key = "retry-1",
                        label = "RETRY",
                        subLabel = "RETRIES THIS GAME AS NINTENDO GAMECUBE EVEN IF THE " +
                            "ARCHIVE LOOKS CORRUPT OR TRUNCATED ON FIRST INSPECTION",
                        modifier = Modifier.height(88.dp),
                        onClick = {},
                    )
                    control(
                        key = "platform-1",
                        label = "PLATFORM: GAMECUBE",
                        subLabel = "TAP TO CYCLE THROUGH EVERY SUPPORTED PLATFORM IN " +
                            "THE FULL LIST THEN PRESS RETRY TO TRY THE IMPORT AGAIN",
                        modifier = Modifier.height(88.dp),
                        onClick = {},
                    )
                    control(
                        key = "skip-1",
                        label = "SKIP",
                        subLabel = "LEAVES THE SOURCE ARCHIVE IN DOWNLOADS UNTOUCHED " +
                            "AND MOVES ON TO THE NEXT GAME IN THE QUEUE",
                        modifier = Modifier.height(88.dp),
                        onClick = {},
                    )
                    // Hub pattern: tall action tiles with sublabels.
                    control(
                        key = "scan",
                        label = "SCAN DOWNLOADS",
                        subLabel = "FIND NEW ARCHIVES IN THE DOWNLOADS FOLDER RECURSIVELY " +
                            "INCLUDING NESTED SUBFOLDERS UP TO FOUR LEVELS DEEP",
                        modifier = Modifier.height(88.dp),
                        onClick = {},
                    )
                    control(
                        key = "done",
                        label = "DONE",
                        onClick = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertNoTextOverlap()
    }

    /**
     * Fails when two text nodes' bounds intersect by more than a rounding
     * epsilon without one containing the other. Nested text (a label drawn
     * inside its own tile) is containment, not overlap; two sibling texts
     * painting over each other is the u58 bug.
     */
    private fun assertNoTextOverlap() {
        val nodes = composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
        val texts = nodes.map { node ->
            node.config[SemanticsProperties.Text].joinToString("|") to node.boundsInRoot
        }.filter { (_, bounds) -> bounds.width > 0 && bounds.height > 0 }

        val violations = mutableListOf<String>()
        for (i in texts.indices) {
            for (j in i + 1 until texts.size) {
                val (textA, a) = texts[i]
                val (textB, b) = texts[j]
                if (!a.overlaps(b)) continue
                val inter = a.intersect(b)
                // 1px tolerance for rounding; both axes must meaningfully cross.
                if (inter.width <= 1f || inter.height <= 1f) continue
                if (containsWithTolerance(a, b) || containsWithTolerance(b, a)) continue
                violations += "overlap: \"${textA.take(48)}\" $a vs \"${textB.take(48)}\" $b"
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "Text nodes overlap on the 1280x960 grid layout " +
                    "(${violations.size} violations):\n" + violations.take(12).joinToString("\n"),
            )
        }
    }

    private fun containsWithTolerance(
        outer: androidx.compose.ui.geometry.Rect,
        inner: androidx.compose.ui.geometry.Rect,
    ): Boolean {
        val t = 1f
        return outer.left - t <= inner.left &&
            outer.top - t <= inner.top &&
            outer.right + t >= inner.right &&
            outer.bottom + t >= inner.bottom
    }
}
