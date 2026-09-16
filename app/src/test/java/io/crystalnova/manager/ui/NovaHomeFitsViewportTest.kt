@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import io.crystalnova.manager.data.SelfUpdateInfo
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Test

/**
 * Requirement 1 — HOME's important controls are all visible without
 * scrolling.
 *
 * Renders the real [HomeScreen] in the 1280x960 viewport and asserts
 * the four destination tiles, the status strip, and the footer are all
 * inside the viewport with NO scroll interaction anywhere in the test.
 *
 * Two variants: the idle state, and the update-available state where
 * the manager-app banner (which must never be buried) is also shown —
 * the banner shrinks the grid's share of the column, so this is the
 * worst case for the "everything visible at once" contract.
 */
class NovaHomeFitsViewportTest : NovaUiTest() {

    private fun setHome(appUpdate: AppUpdateState) {
        setNovaContent {
            HomeScreen(
                scraperState = fakeReadyScraperState(),
                appVersion = "1.0.2",
                appUpdate = appUpdate,
                pegasusReady = true,
                onUpdateApp = {},
                onLibrary = {},
                onTheme = {},
                onPegasusSetup = {},
                onSettings = {},
                onDiagnostics = {},
                onExit = {},
            )
        }
    }

    @Test
    fun homeControlsVisibleWithoutScrolling_whenIdle() {
        setHome(AppUpdateState.Idle())
        assertHomeFits()
    }

    @Test
    fun homeControlsVisibleWithoutScrolling_whenUpdateAvailable() {
        setHome(
            AppUpdateState.Available(
                SelfUpdateInfo(
                    version = "1.0.3",
                    tag = "v1.0.3",
                    apkUrl = "https://example.com/app.apk",
                )
            )
        )
        assertHomeFits()
        // The update banner must never be buried: its action is in
        // viewport too, without scrolling.
        assertNodeInViewport("home-update-app")
    }

    private fun assertHomeFits() {
        assertNodeInViewport("home-library")
        assertNodeInViewport("home-pegasus")
        assertNodeInViewport("home-theme")
        assertNodeInViewport("home-settings")

        // Status strip: the one-line readiness summary (exact text proves
        // it is the strip, not just any text node).
        val stripText = "ROM ✓   MEDIA ✓   PEGASUS ✓"
        val strip = composeTestRule.onNodeWithText(stripText)
        strip.assertTextEquals(stripText)
        assertInteractionInViewport(strip, "status strip")

        // Pinned footer: A SELECT · B EXIT (keycap letters are their own
        // text nodes; exact match keeps this precise).
        assertInteractionInViewport(
            composeTestRule.onNodeWithText("A"),
            "footer keycap A",
        )
        assertInteractionInViewport(
            composeTestRule.onNodeWithText("B"),
            "footer keycap B",
        )
    }
}
