@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import io.crystalnova.manager.data.EsdeThemeCatalog
import io.crystalnova.manager.data.EsdeThemeCatalogState
import io.crystalnova.manager.data.EsdeThemeDownloadState
import io.crystalnova.manager.data.EsdeThemeEntry
import io.crystalnova.manager.data.EsdeThemeInstalled
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Assert.fail
import org.junit.Test

/**
 * TEMPORARY diagnostic probe for the u50 gate failures. Dumps the real
 * composed tree (tags, bounds, focus, texts) plus a traversal probe, all
 * inside the failure message so it lands in the CI test XML. DELETED
 * before the final u50 gate.
 */
class NovaHomeDiagTest : NovaUiTest() {

    private val tags = listOf(
        "esde-grant-folder-repick",
        "esde-launch",
        "esde-current-download",
        "esde-rollback-download",
        "home-check-update",
        "home-exit",
    )

    private fun isFocused(n: SemanticsNode): Boolean =
        n.config.getOrElse(SemanticsProperties.Focused) { false }

    private fun nodeDesc(n: SemanticsNode): String {
        val tt = n.config.getOrElse(SemanticsProperties.TestTag) { "" }
        val tx = n.config.getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString("|") { it.text }
        return "tag='$tt' text='$tx' bounds=${n.boundsInRoot} focused=${isFocused(n)}"
    }

    @Test
    fun dumpHomeState() {
        val entry = EsdeThemeEntry("1.2.0", 4, "https://example.com/a.zip", "a".repeat(64), 1024L)
        val catalog = EsdeThemeCatalog(
            id = "crystal", version = "1.3.0", versionCode = 5,
            zipUrl = "https://example.com/crystal.zip", zipSha256 = "b".repeat(64),
            zipBytes = 4096L, minManagerVersion = null, history = listOf(entry),
        )
        setNovaContent {
            HomeScreen(
                catalogState = EsdeThemeCatalogState.Ready(catalog),
                downloadState = EsdeThemeDownloadState.Idle,
                installed = EsdeThemeInstalled("1.3.0", 5),
                installedOnDisk = true,
                diskVersion = "1.3.0",
                folderGranted = true,
                folderLabel = "themes",
                folderNotice = null,
                installState = EsdeInstallUiState.Idle,
                esdeInstalled = true,
                esdeNotice = null,
                minManagerNotice = null,
                managerVersionLabel = "1.2.4-u50-stripped (65)",
                appUpdate = AppUpdateState.Idle(),
                onExit = {},
            )
        }
        composeTestRule.waitForIdle()
        val sb = StringBuilder()
        fun log(s: String) {
            sb.appendLine(s)
            println("DIAG $s")
        }

        log("viewport=${viewportBounds()}")

        // 1. Every expected control: count, bounds, focus.
        for (t in tags) {
            val nodes = composeTestRule.onAllNodesWithTag(t, useUnmergedTree = true)
                .fetchSemanticsNodes()
            log("tag=$t count=${nodes.size} " + nodes.joinToString(" ; ") { nodeDesc(it) })
        }

        // 2. Every text node in the tree (solves the EXIT mystery).
        val texts = mutableListOf<String>()
        fun walkText(n: SemanticsNode) {
            n.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .forEach { texts += it.text }
            n.children.forEach { walkText(it) }
        }
        walkText(composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        log("texts(${texts.size})=" + texts.joinToString(" | "))

        // 3. Traversal probe: focus rollback-download, press DOWN, find focus.
        val rb = composeTestRule.onNodeWithTag("esde-rollback-download")
        rb.requestDpadFocus()
        composeTestRule.waitForIdle()
        log(
            "probe: rollback-download focused after request=" +
                isFocused(rb.fetchSemanticsNode()),
        )
        rb.pressDpadDown()
        composeTestRule.waitForIdle()
        var focusedDesc = "NONE"
        fun walkFocus(n: SemanticsNode) {
            if (isFocused(n)) focusedDesc = nodeDesc(n)
            n.children.forEach { walkFocus(it) }
        }
        walkFocus(composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        log("probe: focused node after DOWN from rollback-download: $focusedDesc")

        fail("DIAG DUMP:\n$sb")
    }
}
