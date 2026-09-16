@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Test

/**
 * TEMPORARY diagnostic for the step-1 empty-bounds failure. DELETE BEFORE SHIPPING.
 */
class NovaTempDiag2Test : NovaUiTest() {

    @Test
    fun diagStep1Bounds() {
        clearRouteFocusMemoryForTesting()
        val systems = fakePegasusSystems(25)
        setNovaContent {
            PegasusLaunchersScreen(
                systems = systems,
                onSelectSystem = { _, _ -> },
                onBack = {},
            )
        }
        composeTestRule.waitForIdle()

        fun dump(label: String, tag: String) {
            val nodes = composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (nodes.isEmpty()) {
                println("CNM-D2 $label $tag: NOT COMPOSED")
            } else {
                val n = nodes[0]
                println("CNM-D2 $label $tag: boundsInRoot=${n.boundsInRoot}")
            }
        }

        dump("viewport", NOVA_VIEWPORT_TAG)

        dump("after-settle", "launcher-row-sys-0")
        dump("after-settle", "launcher-row-sys-1")

        composeTestRule.onNodeWithTag("launcher-row-sys-0").requestDpadFocus()
        composeTestRule.waitForIdle()
        dump("after-focus-0", "launcher-row-sys-0")
        dump("after-focus-0", "launcher-row-sys-1")

        composeTestRule.onNodeWithTag("launcher-row-sys-0").pressDpadDown()
        composeTestRule.waitForIdle()
        dump("after-dpad-nowait", "launcher-row-sys-1")
        // If the scroll animation runs on a real clock, waitForIdle won't
        // wait for it. Sleep to let real-time animations settle.
        Thread.sleep(1000)
        composeTestRule.waitForIdle()
        dump("after-dpad-sleep", "launcher-row-sys-0")
        dump("after-dpad-sleep", "launcher-row-sys-1")
        dump("after-dpad-sleep", "launcher-row-sys-2")
    }
}
