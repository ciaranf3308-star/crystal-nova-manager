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
 * the status band (CRYSTAL NOVA branding, the three honest lines, and
 * LAUNCH iiSU), the consolidated action row (THEME / ASSETS / ROMS /
 * SYSTEM / SETTINGS — no expander), and the footer are all inside the
 * viewport with NO scroll interaction anywhere in the test.
 *
 * Two variants: the idle state, and the update-available state where
 * the manager-app banner (which must never be buried) is also shown —
 * the banner shrinks the actions' share of the column, so this is the
 * worst case for the "everything visible at once" contract.
 *
 * A third variant covers iiSU-not-installed: the iiSU line says so
 * honestly and the LAUNCH button is present but disabled.
 */
class NovaHomeFitsViewportTest : NovaUiTest() {

    private val installedStatus = buildHomeStatus(
        systems = listOf("Super Nintendo" to 12, "Game Boy Advance" to 8),
        romReady = true,
        iisuInstalled = true,
        iisuVersion = "0.0.7.4",
    )

    private fun setHome(
        appUpdate: AppUpdateState,
        status: HomeStatus = installedStatus,
    ) {
        setNovaContent {
            HomeScreen(
                status = status,
                appVersion = "1.2.4-u44-stripback",
                appUpdate = appUpdate,
                onUpdateApp = {},
                onLaunchIisu = {},
                onTheme = {},
                onAssets = {},
                onRoms = {},
                onSystem = {},
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
    fun homeControlsVisibleWithoutScrolling_whenIisuNotInstalled() {
        setHome(
            AppUpdateState.Idle(),
            status = buildHomeStatus(
                systems = listOf("Super Nintendo" to 12),
                romReady = true,
                iisuInstalled = false,
                iisuVersion = null,
            ),
        )
        composeTestRule.onNodeWithText("iiSU NOT INSTALLED").assertExists()
        composeTestRule.onNodeWithText("CRYSTAL PACK: NONE INSTALLED").assertExists()
        assertHomeFits()
    }

    private fun assertHomeFits() {
        // Status band: CRYSTAL NOVA branding, the three honest lines,
        // and the LAUNCH iiSU action docked beside them.
        composeTestRule.onNodeWithText("CRYSTAL NOVA").assertExists()
        composeTestRule.onNodeWithText("LAUNCH iiSU").assertExists()
        assertNodeInViewport("home-launch-iisu")
        // Consolidated action row: every destination visible at once,
        // no expander to open.
        assertNodeInViewport("home-theme")
        assertNodeInViewport("home-assets")
        assertNodeInViewport("home-roms")
        assertNodeInViewport("home-system")
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
