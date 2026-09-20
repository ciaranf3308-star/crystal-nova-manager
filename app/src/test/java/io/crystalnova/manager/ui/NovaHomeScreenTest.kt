@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.crystalnova.manager.data.EsdeThemeCatalog
import io.crystalnova.manager.data.EsdeThemeCatalogState
import io.crystalnova.manager.data.EsdeThemeDownloadState
import io.crystalnova.manager.data.EsdeThemeEntry
import io.crystalnova.manager.data.EsdeThemeInstalled
import io.crystalnova.manager.data.SelfUpdateInfo
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Test

/**
 * u50: THE STRIP — the app is one screen. This is the contract suite
 * for the new HOME:
 *
 * - every control is reachable by D-pad and lands in the 1280x960
 *   viewport when focused (no stranded control);
 * - the manager self-update route (CHECK FOR UPDATE → DOWNLOAD →
 *   INSTALL) is always present and never buried;
 * - the theme updater actions (GRANT/CHANGE FOLDER, UPDATE/INSTALL,
 *   OPEN ES-DE, rollback) all render with live actions.
 */
class NovaHomeScreenTest : NovaUiTest() {

    private fun fakeThemeEntry(version: String, versionCode: Int) =
        EsdeThemeEntry(
            version = version,
            versionCode = versionCode,
            zipUrl = "https://example.com/crystal-$version.zip",
            zipSha256 = "a".repeat(64),
            zipBytes = 1024L,
        )

    private fun fakeThemeCatalog(
        version: String = "1.3.0",
        versionCode: Int = 5,
        history: List<EsdeThemeEntry> = listOf(fakeThemeEntry("1.2.0", 4)),
    ) = EsdeThemeCatalog(
        id = "crystal",
        version = version,
        versionCode = versionCode,
        zipUrl = "https://example.com/crystal.zip",
        zipSha256 = "b".repeat(64),
        zipBytes = 4096L,
        minManagerVersion = null,
        history = history,
    )

    private fun setHome(
        catalogState: EsdeThemeCatalogState = EsdeThemeCatalogState.Ready(fakeThemeCatalog()),
        downloadState: EsdeThemeDownloadState = EsdeThemeDownloadState.Idle,
        installed: EsdeThemeInstalled? = EsdeThemeInstalled("1.3.0", 5),
        folderGranted: Boolean = true,
        esdeInstalled: Boolean = true,
        appUpdate: AppUpdateState? = AppUpdateState.Idle(),
    ) {
        setNovaContent {
            HomeScreen(
                catalogState = catalogState,
                downloadState = downloadState,
                installed = installed,
                installedOnDisk = installed != null,
                diskVersion = installed?.version,
                folderGranted = folderGranted,
                folderLabel = "themes",
                folderNotice = null,
                installState = EsdeInstallUiState.Idle,
                esdeInstalled = esdeInstalled,
                esdeNotice = null,
                minManagerNotice = null,
                managerVersionLabel = "1.2.4-u50-stripped (65)",
                appUpdate = appUpdate,
                onUpdateApp = {},
                onRefresh = {},
                onGrantFolder = {},
                onDownload = {},
                onInstall = { _, _ -> },
                onLaunchEsde = {},
                onDismissInstall = {},
                onExit = {},
            )
        }
    }

    @Test
    fun everyHomeControlReachableByDpadAndInViewport() {
        // Granted, ES-DE installed, catalog ready with a rollback
        // entry, theme up to date, manager idle.
        setHome()

        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("esde-grant-folder-repick") },
                { composeTestRule.onNodeWithTag("esde-launch") },
                { composeTestRule.onNodeWithTag("esde-current-download") },
                { composeTestRule.onNodeWithTag("esde-rollback-download") },
                { composeTestRule.onNodeWithTag("home-check-update") },
                { composeTestRule.onNodeWithTag("home-exit") },
            ),
        )
    }

    @Test
    fun grantAndRetryPathReachableByDpad() {
        // No grant, catalog down: the grant CTA and the catalog retry
        // are the screen's two recovery actions.
        setHome(
            catalogState = EsdeThemeCatalogState.Unavailable("OFFLINE"),
            installed = null,
            folderGranted = false,
        )

        composeTestRule.onNodeWithText("GRANT THEMES FOLDER").assertExists()
        composeTestRule.onNodeWithText("CHECK AGAIN").assertExists()
        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("esde-grant-folder") },
                { composeTestRule.onNodeWithTag("esde-launch") },
                { composeTestRule.onNodeWithTag("esde-retry") },
                { composeTestRule.onNodeWithTag("home-check-update") },
                { composeTestRule.onNodeWithTag("home-exit") },
            ),
        )
    }

    @Test
    fun themeUpdateAvailable_showsUpdateAction() {
        setHome(installed = EsdeThemeInstalled("1.2.0", 4))

        composeTestRule.onNodeWithText("UPDATE v1.3.0").assertExists()
        composeTestRule.onNodeWithText(
            "LATEST: v1.3.0 — UPDATE AVAILABLE",
            substring = true,
        ).assertExists()
        assertNodeInViewport("esde-current-download")
    }

    @Test
    fun themeUpToDate_showsHonestStatus() {
        setHome()

        composeTestRule.onNodeWithText(
            "LATEST: v1.3.0 — UP TO DATE",
            substring = true,
        ).assertExists()
        composeTestRule.onNodeWithText("INSTALLED: v1.3.0").assertExists()
    }

    @Test
    fun managerUpdateAvailable_showsDownloadUpdate() {
        setHome(
            appUpdate = AppUpdateState.Available(
                SelfUpdateInfo(
                    version = "1.2.4-u51-stripped (66)",
                    tag = "dev-latest",
                    apkUrl = "https://example.com/manager.apk",
                ),
            ),
        )

        // The self-update route must never be buried: its action is in
        // viewport without any scrolling.
        assertNodeInViewport("home-update-app")
        composeTestRule.onNodeWithText("DOWNLOAD UPDATE").assertExists()
    }

    @Test
    fun managerUpdateDownloaded_showsInstallUpdate() {
        setHome(
            appUpdate = AppUpdateState.Downloaded(
                java.io.File("/tmp/manager-update-dev-66.apk"),
            ),
        )

        assertNodeInViewport("home-update-app")
        composeTestRule.onNodeWithText("INSTALL UPDATE").assertExists()
    }

    @Test
    fun exitControlPresent() {
        setHome()

        composeTestRule.onNodeWithText("EXIT").assertExists()
        assertNodeInViewport("home-exit")
    }
}
