@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.crystalnova.manager.data.EsdeThemeCatalog
import io.crystalnova.manager.data.EsdeThemeCatalogState
import io.crystalnova.manager.data.EsdeThemeDownloadState
import io.crystalnova.manager.data.EsdeThemeEntry
import io.crystalnova.manager.data.EsdeThemeInstalled
import io.crystalnova.manager.data.SelfUpdateInfo
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Ignore
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
        onOpenImporter: (() -> Unit)? = null,
        importerStatusLine: String = "",
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
                onOpenImporter = onOpenImporter,
                importerStatusLine = importerStatusLine,
                onExit = {},
            )
        }
    }

    @Test
    fun everyHomeControlReachableByDpadAndInViewport() {
        // Granted, ES-DE installed, catalog ready with a rollback
        // entry, theme up to date, manager idle. The home is a 3-column
        // tile grid in COMMAND/STATUS zones; the walk below uses only
        // geometrically certain moves — same-row horizontals and
        // same-column verticals to the next focusable below (the
        // full-span panels and non-focusable statTiles between tile
        // rows are skipped by D-pad focus).
        setHome()

        val tile = { tag: String -> { composeTestRule.onNodeWithTag(tag) } }
        assertDpadGridTraversalInViewport(
            first = tile("esde-current-download"),
            moves = listOf(
                DpadMove(SemanticsNodeInteraction::pressDpadRight, tile("esde-launch")),
                DpadMove(SemanticsNodeInteraction::pressDpadRight, tile("home-check-update")),
                DpadMove(SemanticsNodeInteraction::pressDpadLeft, tile("esde-launch")),
                DpadMove(SemanticsNodeInteraction::pressDpadLeft, tile("esde-current-download")),
                DpadMove(SemanticsNodeInteraction::pressDpadDown, tile("esde-grant-folder-repick")),
                DpadMove(SemanticsNodeInteraction::pressDpadDown, tile("esde-rollback-download")),
            ),
        )
        // EXIT is the last step, handled separately: it sits just below
        // the fold, and focusing it from rollback would ask the scroll
        // engine to scroll during the test frame's layout pass, which
        // crashes LazyGridState.scrollToItem under Robolectric's
        // frame-deferring interceptor. Swipe it into comfortable view
        // first (a slow, short drag, so the focused rollback tile
        // stays composed and focused); the step itself is still a
        // real D-pad move from rollback to EXIT.
        composeTestRule.onNodeWithTag(NOVA_VIEWPORT_TAG).performTouchInput {
            swipeUp(
                startY = height * 0.55f,
                endY = height * 0.35f,
                durationMillis = 1000,
            )
        }
        composeTestRule.waitForIdle()
        val rollback = tile("esde-rollback-download")
        rollback.requestDpadFocus()
        composeTestRule.waitForIdle()
        rollback.assertIsFocused()
        rollback.pressDpadDown()
        composeTestRule.waitForIdle()
        val exit = tile("home-exit")
        exit.assertIsFocused()
        assertInteractionInViewport(exit, "grid traversal exit")
    }

    @Test
    fun grantAndRetryPathReachableByDpad() {
        // No grant, catalog down: the grant CTA and the catalog retry
        // are the screen's two recovery actions. Tile rows here:
        // (retry / launch / check-update), (grant-folder), then the
        // STATUS tiles and EXIT. The walk uses only geometrically
        // certain moves — same-row horizontals and same-column
        // verticals to the next focusable below.
        setHome(
            catalogState = EsdeThemeCatalogState.Unavailable("OFFLINE"),
            installed = null,
            folderGranted = false,
        )

        composeTestRule.onNodeWithText("GRANT THEMES FOLDER").assertExists()
        composeTestRule.onNodeWithText("CHECK AGAIN").assertExists()
        val tile = { tag: String -> { composeTestRule.onNodeWithTag(tag) } }
        assertDpadGridTraversalInViewport(
            first = tile("esde-grant-folder"),
            moves = listOf(
                DpadMove(SemanticsNodeInteraction::pressDpadUp, tile("esde-retry")),
                DpadMove(SemanticsNodeInteraction::pressDpadRight, tile("esde-launch")),
                DpadMove(SemanticsNodeInteraction::pressDpadRight, tile("home-check-update")),
                DpadMove(SemanticsNodeInteraction::pressDpadLeft, tile("esde-launch")),
                DpadMove(SemanticsNodeInteraction::pressDpadLeft, tile("esde-retry")),
                DpadMove(SemanticsNodeInteraction::pressDpadDown, tile("esde-grant-folder")),
                DpadMove(SemanticsNodeInteraction::pressDpadDown, tile("home-exit")),
            ),
        )
    }

    @Test
    fun themeUpdateAvailable_showsUpdateAction() {
        setHome(installed = EsdeThemeInstalled("1.2.0", 4))

        composeTestRule.onNodeWithText("UPDATE v1.3.0").assertExists()
        // u89: the theme state is split across tile nodes — the
        // current tile's sublabel and the LATEST status tile — so the
        // old single-line "LATEST: v1.3.0 — UPDATE AVAILABLE" string
        // no longer exists as one node.
        composeTestRule.onNodeWithText("v1.3.0 — UPDATE AVAILABLE").assertExists()
        composeTestRule.onNodeWithText("LATEST").assertExists()
        assertNodeInViewport("esde-current-download")
    }

    @Test
    fun themeUpToDate_showsHonestStatus() {
        setHome()

        // u89: the theme state is split across tile nodes — the
        // current tile's label/sublabel and the INSTALLED status
        // tile — so the old single-line "LATEST: … — UP TO DATE" /
        // "INSTALLED: …" strings no longer exist as one node each.
        composeTestRule.onNodeWithText("REINSTALL v1.3.0").assertExists()
        composeTestRule.onNodeWithText("v1.3.0 — UP TO DATE").assertExists()
        composeTestRule.onNodeWithText("INSTALLED").assertExists()
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

        // u89: EXIT sits below the fold in the tile grid, so the lazy
        // grid has not composed it yet — no text/tag assertion can
        // find it before it is scrolled into range. Walk to rollback
        // by D-pad, swipe EXIT into comfortable view (see
        // everyHomeControlReachableByDpadAndInViewport for why the
        // swipe precedes the final D-pad step), then prove EXIT by
        // D-pad: it exists, takes focus, and lands in viewport.
        val tile = { tag: String -> { composeTestRule.onNodeWithTag(tag) } }
        assertDpadGridTraversalInViewport(
            first = tile("esde-current-download"),
            moves = listOf(
                DpadMove(SemanticsNodeInteraction::pressDpadDown, tile("esde-grant-folder-repick")),
                DpadMove(SemanticsNodeInteraction::pressDpadDown, tile("esde-rollback-download")),
            ),
        )
        composeTestRule.onNodeWithTag(NOVA_VIEWPORT_TAG).performTouchInput {
            swipeUp(
                startY = height * 0.55f,
                endY = height * 0.35f,
                durationMillis = 1000,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("EXIT").assertExists()
        val rollback = tile("esde-rollback-download")
        rollback.requestDpadFocus()
        composeTestRule.waitForIdle()
        rollback.assertIsFocused()
        rollback.pressDpadDown()
        composeTestRule.waitForIdle()
        val exit = tile("home-exit")
        exit.assertIsFocused()
        assertInteractionInViewport(exit, "exit control")
    }

    // Non-gating per user 2026-09-23: D-pad order verified fine on Nova
    // hardware; Robolectric two-line-button mis-measure does not
    // reproduce on device.
    @Ignore
    @Test
    fun importerEntryBetweenUpdateAndExit() {
        // The u52 game importer is a single-line control between the
        // manager update control and EXIT; the live status rides in the
        // MANAGER panel.
        setHome(
            onOpenImporter = {},
            importerStatusLine = "2 IN QUEUE — TAP TO RESUME",
        )

        composeTestRule.onNodeWithText("OPEN GAME IMPORTER").assertExists()
        composeTestRule.onNodeWithText("IMPORTER: 2 IN QUEUE — TAP TO RESUME")
            .assertExists()
        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("esde-grant-folder-repick") },
                { composeTestRule.onNodeWithTag("esde-launch") },
                { composeTestRule.onNodeWithTag("esde-current-download") },
                { composeTestRule.onNodeWithTag("esde-rollback-download") },
                { composeTestRule.onNodeWithTag("home-check-update") },
                { composeTestRule.onNodeWithTag("home-open-importer") },
                { composeTestRule.onNodeWithTag("home-exit") },
            ),
        )
    }



}
