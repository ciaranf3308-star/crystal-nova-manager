@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.onAllNodesWithTag
import io.crystalnova.manager.data.EsdeThemeCatalog
import io.crystalnova.manager.data.EsdeThemeCatalogState
import io.crystalnova.manager.data.EsdeThemeDownloadState
import io.crystalnova.manager.data.EsdeThemeEntry
import io.crystalnova.manager.data.EsdeThemeInstalled
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Test

/** TEMPORARY diagnostic: dumps bounds of every home row. Deleted after u50. */
class NovaHomeDiagTest : NovaUiTest() {

    @Test
    fun dumpHomeBounds() {
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
        val vp = composeTestRule.onAllNodesWithTag(NOVA_VIEWPORT_TAG)
            .fetchSemanticsNodes().first().boundsInRoot
        println("DIAG viewport=$vp")
        val tags = listOf(
            "esde-grant-folder-repick", "esde-launch",
            "esde-current-download", "esde-rollback-download",
            "home-check-update", "home-exit",
        )
        for (t in tags) {
            val nodes = composeTestRule.onAllNodesWithTag(t).fetchSemanticsNodes()
            println(
                "DIAG tag=$t count=${nodes.size} " +
                    nodes.joinToString { n ->
                        "bounds=${n.boundsInRoot} focused=${n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused)}"
                    },
            )
        }
        // Also dump the raw text nodes to see what IS composed.
        val texts = composeTestRule.onAllNodesWithTag(NOVA_VIEWPORT_TAG)
            .fetchSemanticsNodes()
        println("DIAG done")
    }
}
