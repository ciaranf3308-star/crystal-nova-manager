@file:OptIn(ExperimentalTestApi::class)

package io.crystalnova.manager.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.crystalnova.manager.data.InstalledPack
import io.crystalnova.manager.data.PackCatalogState
import io.crystalnova.manager.data.PackDownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Requirement 5 — no essential action can remain permanently below the
 * viewport.
 *
 * The SYSTEM hub and the THEME pack screen are rendered and focus is
 * traversed through EVERY focusable control to the last one, asserting
 * each is in-viewport when focused. If any focusable could never be
 * brought into view, the traversal fails — that is the point of this
 * test.
 *
 * Notes on the fixtures:
 * - The System hub's three rows (DIAGNOSTICS / BIOS / INSTALLED
 *   EMULATORS) plus the scaffold BACK are all focusable; the walk
 *   covers the three rows.
 * - Theme renders two catalog packs: each pack card carries exactly
 *   one live action (DOWNLOAD here), so the walk covers both actions
 *   plus BACK. The catalog-unavailable and manual-import states get
 *   their own walks — every button on the screen must be reachable.
 *
 * u44: the archived PEGASUS SETUP walk is retired with the strip-back.
 * u45: the pack cards grew real actions with the catalog; the walks
 * now cover them.
 */
class NovaNoStrandedControlTest : NovaUiTest() {

    @Test
    fun everySystemHubControlReachableByDpad() {
        setNovaContent {
            SystemHubScreen(
                onDiagnostics = {},
                onBios = {},
                onInstalledEmulators = {},
                onBack = {},
            )
        }

        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("system-diagnostics") },
                { composeTestRule.onNodeWithTag("system-bios") },
                { composeTestRule.onNodeWithTag("system-emulators") },
            ),
        )
    }

    @Test
    fun everyThemeControlReachableByDpad() {
        setNovaContent {
            ThemeScreen(
                catalogState = PackCatalogState.Ready(fakeCrystalPacks(2)),
                downloadState = PackDownloadState.Idle,
                installed = null,
                onBack = {},
            )
        }

        // Pack cards render their names; each pack's action button and
        // BACK are focusable — the walk proves all three reachable.
        composeTestRule.onNodeWithText("CRYSTAL PACK 0").assertExists()
        composeTestRule.onNodeWithText("CRYSTAL PACK 1").assertExists()
        // u47: the card shows the ZIP size next to coverage (both fake
        // packs are 1024 bytes, so two nodes match — plural query).
        assertEquals(
            2,
            composeTestRule.onAllNodesWithText("SIZE: 1.0 KB", substring = true)
                .fetchSemanticsNodes().size,
        )
        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("pack-action-pack-0") },
                { composeTestRule.onNodeWithTag("pack-action-pack-1") },
                { composeTestRule.onNodeWithTag("theme-back") },
            ),
        )
    }

    @Test
    fun themeUnavailableRetryReachableByDpad() {
        setNovaContent {
            ThemeScreen(
                catalogState = PackCatalogState.Unavailable("OFFLINE"),
                downloadState = PackDownloadState.Idle,
                installed = null,
                onBack = {},
            )
        }

        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("packs-retry") },
                { composeTestRule.onNodeWithTag("theme-back") },
            ),
        )
    }

    @Test
    fun themeManualImportControlsReachableByDpad() {
        setNovaContent {
            ThemeScreen(
                catalogState = PackCatalogState.Ready(fakeCrystalPacks(1)),
                downloadState = PackDownloadState.Idle,
                installed = null,
                manualImport = ManualImport(
                    pack = fakePackEntry(0),
                    zipName = "pack-pack-0-10.zip",
                ),
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("FINISH THE IMPORT IN iiSU").assertExists()
        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("manual-import-open-files") },
                { composeTestRule.onNodeWithTag("manual-import-back") },
                { composeTestRule.onNodeWithTag("theme-back") },
            ),
        )
    }

    @Test
    fun themeInstallActionReachableWhenDownloaded() {        val zip = java.io.File("pack-pack-0-10.zip")
        setNovaContent {
            ThemeScreen(
                catalogState = PackCatalogState.Ready(fakeCrystalPacks(1)),
                downloadState = PackDownloadState.ReadyToInstall("pack-0", zip),
                installed = InstalledPack("pack-0", "Crystal Pack 0", "1.0"),
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("INSTALLED", substring = true).assertExists()
        assertDpadTraversalInViewport(
            listOf(
                { composeTestRule.onNodeWithTag("pack-action-pack-0") },
                { composeTestRule.onNodeWithTag("theme-back") },
            ),
        )
    }

    @Test
    fun formatPackBytesRendersHumanSizes() {
        assertEquals("0 B", formatPackBytes(0))
        assertEquals("0 B", formatPackBytes(-5))
        assertEquals("1.0 KB", formatPackBytes(1024))
        assertEquals("3.5 MB", formatPackBytes(3661364))
        assertEquals("1.0 GB", formatPackBytes(1024L * 1024 * 1024))
    }
}
