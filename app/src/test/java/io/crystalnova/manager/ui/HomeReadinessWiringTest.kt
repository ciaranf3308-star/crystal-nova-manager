package io.crystalnova.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v23 regression: HOME's game count must come from the authoritative
 * discovered ROM library ([PegasusSystemRow.gameCount] sums) — never from
 * the scraper/artwork index.
 *
 * The scraper index counts *indexed artwork*, not ROMs: deriving HOME's
 * count from it showed "13 SYSTEMS · 0 GAMES" on a fully-ready but
 * unscraped library while READY TO PLAY — false. Artwork statistics live
 * on the LIBRARY / ARTWORK screens only.
 */
class HomeReadinessWiringTest {

    /**
     * Canonical fixture: 13 populated systems, 147 ROMs, every launcher
     * configured and installed, Pegasus installed, ROM folder ready —
     * and an EMPTY artwork index (nothing scraped yet).
     */
    private fun libraryRows(
        launcherIssueSlug: String? = null,
    ): List<PegasusSystemRow> {
        val counts = listOf(
            "nes" to 12,
            "snes" to 18,
            "n64" to 10,
            "gamecube" to 8,
            "gb" to 15,
            "gbc" to 14,
            "gba" to 22,
            "nds" to 9,
            "genesis" to 11,
            "mastersystem" to 6,
            "psx" to 13,
            "ps2" to 5,
            "arcade" to 4,
        )
        return counts.map { (slug, gameCount) ->
            val broken = slug == launcherIssueSlug
            PegasusSystemRow(
                slug = slug,
                label = slug.uppercase(),
                gameCount = gameCount,
                launcherStatus = if (broken) "NOT CONFIGURED" else "RetroArch (AUTO)",
                launcherInstalled = !broken,
                isDefault = true,
            )
        }
    }

    @Test
    fun readyState_showsLibraryCount_evenWithEmptyArtworkIndex() {
        // Library = 13 systems / 147 ROMs; artwork index = 0 scraped;
        // all 13 launchers valid; Pegasus installed; ROM folder ready.
        val readiness = buildHomeReadiness(
            rows = libraryRows(),
            pegasusInstalled = true,
            romReady = true,
        )
        assertTrue("must be READY TO PLAY", readiness.ready)
        assertEquals(13, readiness.systemCount)
        assertEquals(147, readiness.totalGames)
        assertEquals(13, readiness.configuredCount)
        assertEquals(0, readiness.issueCount)
        assertEquals("13 SYSTEMS · 147 GAMES", homeStatsLine(readiness))
        assertEquals("13 LAUNCHERS CONFIGURED", homeLauncherLine(readiness))
        assertEquals("LIBRARY BUILT", homeLibraryLine(readiness))
    }

    @Test
    fun scrapingOneGame_doesNotChangeDisplayedGameCount() {
        // The artwork index now holds 1 scraped game. It is not an input
        // to buildHomeReadiness at all, so the displayed count cannot move.
        val readiness = buildHomeReadiness(
            rows = libraryRows(),
            pegasusInstalled = true,
            romReady = true,
        )
        assertEquals(147, readiness.totalGames)
        assertEquals("13 SYSTEMS · 147 GAMES", homeStatsLine(readiness))
    }

    @Test
    fun deletedOrEmptyScraperIndex_doesNotChangeLibraryGameCount() {
        // Artwork index deleted/empty: HOME still reports the library.
        val readiness = buildHomeReadiness(
            rows = libraryRows(),
            pegasusInstalled = true,
            romReady = true,
        )
        assertEquals(147, readiness.totalGames)
        assertTrue(readiness.ready)
    }

    @Test
    fun launcherIssue_stillFlipsReadyToFinishSetup() {
        val readiness = buildHomeReadiness(
            rows = libraryRows(launcherIssueSlug = "ps2"),
            pegasusInstalled = true,
            romReady = true,
        )
        assertFalse("must drop out of READY TO PLAY", readiness.ready)
        assertEquals(1, readiness.issueCount)
        assertEquals(12, readiness.configuredCount)
        // The game count is honest even in the needs-attention state.
        assertEquals(147, readiness.totalGames)
        assertEquals("13 SYSTEMS · 147 GAMES", homeStatsLine(readiness))
        assertEquals("12 CONFIGURED · 1 NEEDS ATTENTION", homeLauncherLine(readiness))
    }

    @Test
    fun emptyLibrary_isNotReady() {
        val readiness = buildHomeReadiness(
            rows = emptyList(),
            pegasusInstalled = true,
            romReady = true,
        )
        assertFalse(readiness.ready)
        assertEquals(0, readiness.totalGames)
        assertEquals("LIBRARY EMPTY — SCAN YOUR ROMS", homeLibraryLine(readiness))
    }
}
