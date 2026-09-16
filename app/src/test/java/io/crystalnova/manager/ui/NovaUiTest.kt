package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for Nova UI tests running on the JVM under Robolectric.
 *
 * The annotations are inherited by subclasses ([RunWith] and [Config] are both
 * `@Inherited`), so every `NovaUiTest` subclass automatically runs with
 * [RobolectricTestRunner] at API 33 (Android 13 — what the Retroid Pocket Nova
 * ships) and gets a [ComposeContentTestRule] via [createComposeRule].
 *
 * The rule launches a real `ComponentActivity` through Robolectric's
 * `ActivityScenario` support, so `composeTestRule` behaves like the on-device
 * rule (layout, semantics, focus, gestures) without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
abstract class NovaUiTest {

    @get:Rule
    val composeTestRule: ComposeContentTestRule = createComposeRule()

    /**
     * The scaffold's per-route focus memory is process lifetime; without
     * this, one test's last-focused row becomes the next test's initial
     * focus (and scroll position), so a test that expects to start at row
     * 0 can find row 0 already disposed by the lazy list.
     */
    @Before
    fun clearRouteFocusMemory() {
        clearRouteFocusMemoryForTesting()
    }

    /**
     * Sets [content] inside a fixed 1280x960 logical viewport, matching the
     * Nova's 4:3 screen.
     *
     * The viewport is a [Box] with `Modifier.requiredSize(1280.dp, 960.dp)`
     * (required — not `size` — so the Robolectric window's own dimensions can't
     * shrink it) at density 1, so 1 dp == 1 px and every bound measured in the
     * test is directly comparable against the 1280x960 design space.
     *
     * The viewport node carries [NOVA_VIEWPORT_TAG]; [viewportBounds] reads its
     * [Rect] back so assertions never hard-code an origin.
     */
    fun setNovaContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(
                    Modifier
                        .requiredSize(NOVA_VIEWPORT_WIDTH_DP.dp, NOVA_VIEWPORT_HEIGHT_DP.dp)
                        .testTag(NOVA_VIEWPORT_TAG)
                ) {
                    content()
                }
            }
        }
    }

    /**
     * The 1280x960 viewport rect in root coordinates (px == dp here because
     * [setNovaContent] forces density 1).
     */
    fun viewportBounds(): Rect =
        composeTestRule.onNodeWithTag(NOVA_VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot

    /**
     * Asserts the node with [tag] is actually visible inside the 1280x960
     * viewport: it must exist, have non-empty bounds, and overlap the viewport
     * rect. This is strictly stronger than `assertIsDisplayed()`, which reasons
     * about ancestor clipping but not about our logical viewport.
     */
    fun assertNodeInViewport(tag: String) {
        val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Node '$tag' has empty bounds ($bounds) — nothing is displayed",
            bounds.width > 0 && bounds.height > 0
        )
        val viewport = viewportBounds()
        assertTrue(
            "Node '$tag' (bounds $bounds) does not overlap the 1280x960 viewport ($viewport)",
            viewport.overlaps(bounds)
        )
    }

    /**
     * Asserts the node with [tag] is NOT visible inside the 1280x960 viewport.
     *
     * A node that exists but sits fully outside the viewport fails this; a
     * node that a lazy list hasn't even composed yet (e.g. far below the fold)
     * counts as outside, which is what the D-pad/scroll tests need.
     */
    fun assertNodeOutsideViewport(tag: String) {
        val nodes = composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        if (nodes.isEmpty()) return // Not composed => cannot be on screen.
        val viewport = viewportBounds()
        nodes.forEach { node ->
            val bounds = node.boundsInRoot
            assertFalse(
                "Node '$tag' (bounds $bounds) unexpectedly overlaps " +
                    "the 1280x960 viewport ($viewport)",
                viewport.overlaps(bounds)
            )
        }
    }
}
