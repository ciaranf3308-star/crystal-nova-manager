@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import io.crystalnova.manager.data.SelfUpdateInfo
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Test

/**
 * Requirement 1 — HOME's important controls are all visible without
 * scrolling.
 *
 * Renders the real [HomeScreen] in the 1280x960 viewport and asserts
 * the status band (READY TO PLAY / OPEN PEGASUS), the consolidated
 * action row (LIBRARY / ARTWORK, THEME, SETTINGS — no expander), and
 * the footer are all inside the viewport with NO scroll interaction
 * anywhere in the test.
 *
 * Two variants: the idle ready state, and the update-available state
 * where the manager-app banner (which must never be buried) is also
 * shown — the banner shrinks the actions' share of the column, so this
 * is the worst case for the "everything visible at once" contract.
 *
 * A third variant covers the needs-attention band (FINISH SETUP /
 * MAKE READY / REVIEW ISSUE) to prove it fits the same contract.
 */
class NovaHomeFitsViewportTest : NovaUiTest() {

    private val readyReadiness = HomeReadiness(
        systemCount = 13,
        totalGames = 147,
        configuredCount = 13,
        issueCount = 0,
        pegasusInstalled = true,
        romReady = true,
    )

    private fun setHome(
        appUpdate: AppUpdateState,
        readiness: HomeReadiness = readyReadiness,
    ) {
        setNovaContent {
            HomeScreen(
                readiness = readiness,
                appVersion = "1.2.3-u7",
                appUpdate = appUpdate,
                themeSubtitle = "v1.2.3",
                settingsSubtitle = "DEV CHANNEL",
                onUpdateApp = {},
                onOpenPegasus = {},
                onMakeReady = {},
                onReviewIssues = {},
                onRebuildLibrary = {},
                onLibrary = {},
                onTheme = {},
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

    @Test
    fun homeControlsVisibleWithoutScrolling_whenNeedsAttention() {
        setHome(
            AppUpdateState.Idle(),
            readiness = readyReadiness.copy(issueCount = 1, configuredCount = 12),
        )
        composeTestRule.onNodeWithText("FINISH SETUP").assertExists()
        assertNodeInViewport("home-primary")
        assertNodeInViewport("home-review")
        assertNodeInViewport("home-library")
        assertNodeInViewport("home-theme")
        assertNodeInViewport("home-settings")
        // Pinned footer survives the taller hero too.
        assertInteractionInViewport(
            composeTestRule.onNodeWithText("A"),
            "footer keycap A",
        )
        assertInteractionInViewport(
            composeTestRule.onNodeWithText("B"),
            "footer keycap B",
        )
    }

    private fun assertHomeFits() {
        // Status band: READY TO PLAY headline and the happy-path action.
        composeTestRule.onNodeWithText("READY TO PLAY").assertExists()
        assertNodeInViewport("home-primary")
        // u42: the library rebuild must be one tap away when READY.
        assertNodeInViewport("home-rebuild")
        // Consolidated action row: every destination visible at once,
        // no expander to open.
        assertNodeInViewport("home-library")
        assertNodeInViewport("home-theme")
        assertNodeInViewport("home-settings")

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
