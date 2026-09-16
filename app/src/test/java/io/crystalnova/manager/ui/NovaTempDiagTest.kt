@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Test

/**
 * TEMPORARY diagnostic: dump the semantics tree's test tags for the
 * 25-system launchers screen to find out why launcher-row-sys-0 is not
 * found. DELETE BEFORE SHIPPING.
 */
class NovaTempDiagTest : NovaUiTest() {

    @Test
    fun dumpLauncherTags25() {
        val systems = fakePegasusSystems(25)
        setNovaContent {
            PegasusLaunchersScreen(
                systems = systems,
                onSelectSystem = { _, _ -> },
                onBack = {},
            )
        }
        composeTestRule.waitForIdle()
        val tree = composeTestRule.onRoot().printToString()
        val tags = Regex("TestTag = '([^']+)'").findAll(tree).map { it.groupValues[1] }.toList()
        println("CNM-DIAG 25-system tags (${tags.size}): $tags")
        println("CNM-DIAG tree length: ${tree.length}")
        tree.chunked(1500).forEachIndexed { i, chunk ->
            println("CNM-TREE[$i]: $chunk")
        }
    }

    @Test
    fun dumpLauncherTags6() {
        val systems = fakePegasusSystems(6)
        setNovaContent {
            PegasusLaunchersScreen(
                systems = systems,
                onSelectSystem = { _, _ -> },
                onBack = {},
            )
        }
        composeTestRule.waitForIdle()
        val tree = composeTestRule.onRoot().printToString()
        val tags = Regex("TestTag = '([^']+)'").findAll(tree).map { it.groupValues[1] }.toList()
        println("CNM-DIAG 6-system tags (${tags.size}): $tags")
    }
}
