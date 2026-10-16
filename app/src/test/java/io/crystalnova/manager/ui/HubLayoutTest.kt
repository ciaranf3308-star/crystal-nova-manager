package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * u70 regression guard: the u69 importer hub rendered its action
 * buttons (SCAN DOWNLOADS, RESUME QUEUE, DISMISS n MISSING) in a
 * 2-column grid on the Nova and the photo showed them clipped and
 * overlapping with cut-off, unreadable text. The hub is now a
 * single-column [ControllerList] with full-width actions.
 *
 * These tests render the hub's real structural pattern — status
 * panels, the long-sublabel action buttons, the danger DISMISS
 * control — inside the real [ScreenScaffold] at the 1280x960 Nova
 * viewport and fail if any text overlaps or any button's text paints
 * outside the button's own bounds (the cut-off-text signature).
 */
class HubLayoutTest : NovaUiTest() {

    @Test
    fun hubPattern_noTextOverlap() {
        setNovaContent { HubRepro(fontScale = 1f) }
        composeTestRule.waitForIdle()
        val count = assertNoTextOverlap()
        assertTrue("Expected text nodes in the hub repro, found $count", count >= 10)
    }

    /**
     * The Nova lets users crank the font scale up; the hub must not
     * clip or overlap at 2x either.
     */
    @Test
    fun hubPattern_largeFontScale_noTextOverlap() {
        setNovaContent { HubRepro(fontScale = 2f) }
        composeTestRule.waitForIdle()
        val count = assertNoTextOverlap()
        assertTrue("Expected text nodes in the hub repro, found $count", count >= 10)
    }

    /**
     * Direct guard for the u69 photo bug: every action button's text
     * must be fully inside the button's own bounds. A squeezed or
     * half-measured button lets its label/sublabel paint outside —
     * exactly the cut-off text the user photographed.
     */
    @Test
    fun hubButtons_textFullyInsideButtonBounds() {
        setNovaContent { HubRepro(fontScale = 1f) }
        composeTestRule.waitForIdle()
        assertButtonTextContained("hub-scan")
        assertButtonTextContained("hub-resume")
        assertButtonTextContained("hub-dismiss-missing")
        assertButtonTextContained("hub-settings")
    }

    @Test
    fun hubButtons_largeFontScale_textFullyInsideButtonBounds() {
        setNovaContent { HubRepro(fontScale = 2f) }
        composeTestRule.waitForIdle()
        // At 2x the lower buttons sit below the fold (lazy list); the
        // scan button is always composed — it must never clip.
        assertButtonTextContained("hub-scan")
    }

    /** The u70 hub structure: status panels + full-width actions. */
    @Composable
    private fun HubRepro(fontScale: Float) {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = fontScale)) {
            ScreenScaffold(
                routeKey = "test-hub",
                title = "GAME IMPORTER",
                onBack = {},
                fallbackFocusKey = "scan",
            ) {
                ControllerList(
                    state = listState,
                    dispatcher = dispatcher,
                    initialFocus = ::isInitialFocus,
                ) {
                    section {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusLine("SOURCE — DOWNLOADS")
                                StatusLine("GRANTED — DIRECT SCAN", Crystal.Good)
                                CrystalDivider()
                                StatusLine("DESTINATION — ROM ROOT")
                                StatusLine("GRANTED", Crystal.Good)
                            }
                        }
                    }
                    control(
                        key = "scan",
                        testTag = "hub-scan",
                        label = "SCAN DOWNLOADS",
                        subLabel = "ZIP + 7Z + LOOSE GAME FILES — RAR REPORTED, NOT SUPPORTED",
                        onClick = {},
                    )
                    section {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusLine("QUEUE — 2 WAITING")
                                StatusLine("2 SOURCE MISSING — FILE IS GONE", Crystal.Bad)
                            }
                        }
                    }
                    control(
                        key = "resume",
                        testTag = "hub-resume",
                        label = "RESUME QUEUE (2)",
                        subLabel = "CLASSIFICATION AND PLANS SURVIVED THE RESTART",
                        onClick = {},
                    )
                    control(
                        key = "dismiss-missing",
                        testTag = "hub-dismiss-missing",
                        label = "DISMISS 2 MISSING",
                        subLabel = "DROPS THEM FROM THE QUEUE — NOTHING IS DELETED",
                        onClick = {},
                        danger = true,
                    )
                    control(
                        key = "settings",
                        testTag = "hub-settings",
                        label = "IMPORTER SETTINGS",
                        subLabel = "GRANTS · PLATFORM FOLDERS · DEFAULTS",
                        onClick = {},
                    )
                }
            }
        }
    }

    private fun assertNoTextOverlap(): Int {
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
                if (inter.width <= 1f || inter.height <= 1f) continue
                if (nearIdentical(a, b)) {
                    violations += "STACKED: \"${textA.take(48)}\" $a vs \"${textB.take(48)}\" $b"
                    continue
                }
                if (strictlyContains(a, b) || strictlyContains(b, a)) continue
                violations += "overlap: \"${textA.take(48)}\" $a vs \"${textB.take(48)}\" $b"
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "Text nodes overlap on the 1280x960 hub layout " +
                    "(${violations.size} violations):\n" + violations.take(12).joinToString("\n"),
            )
        }
        return texts.size
    }

    private fun assertButtonTextContained(tag: String) {
        val button = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode()
        val bb = button.boundsInRoot
        assertTrue(
            "Button '$tag' has empty bounds ($bb) — not laid out",
            bb.width > 0 && bb.height > 0,
        )
        // u70: buttons are roomy rows, never squeezed slivers.
        assertTrue(
            "Button '$tag' is only ${bb.height.toInt()}px tall — squeezed/clipped",
            bb.height >= 48f,
        )
        val texts = composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .filter { n ->
                val b = n.boundsInRoot
                b.width > 0 && b.height > 0 && bb.contains(b.center)
            }
        assertTrue("Expected text inside button '$tag', found none", texts.isNotEmpty())
        val tol = 2f
        for (n in texts) {
            val b = n.boundsInRoot
            val label = n.config[SemanticsProperties.Text].joinToString("|").take(48)
            assertTrue(
                "Text \"$label\" overflows button '$tag' (text $b vs button $bb) — cut-off text",
                b.left >= bb.left - tol && b.top >= bb.top - tol &&
                    b.right <= bb.right + tol && b.bottom <= bb.bottom + tol,
            )
        }
    }

    /** All four edges within 2px: two texts drawn at the same position. */
    private fun nearIdentical(
        a: androidx.compose.ui.geometry.Rect,
        b: androidx.compose.ui.geometry.Rect,
    ): Boolean {
        val t = 2f
        return kotlin.math.abs(a.left - b.left) <= t &&
            kotlin.math.abs(a.top - b.top) <= t &&
            kotlin.math.abs(a.right - b.right) <= t &&
            kotlin.math.abs(a.bottom - b.bottom) <= t
    }

    private fun strictlyContains(
        a: androidx.compose.ui.geometry.Rect,
        b: androidx.compose.ui.geometry.Rect,
    ): Boolean =
        a.left <= b.left && a.top <= b.top && a.right >= b.right && a.bottom >= b.bottom &&
            (a.left < b.left || a.top < b.top || a.right > b.right || a.bottom > b.bottom)
}
