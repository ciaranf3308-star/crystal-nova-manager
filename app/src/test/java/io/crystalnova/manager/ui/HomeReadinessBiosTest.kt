package io.crystalnova.manager.ui

import io.crystalnova.manager.bios.BiosIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v24: firmware problems feed the existing HOME readiness issue model.
 * Only genuinely required firmware for systems with games present
 * (PS2) can block READY — other platforms' firmware is out of scope
 * for v24, artwork
 * completeness never gate, and the library count still comes from the
 * ROM library, never the scraper index.
 */
class HomeReadinessBiosTest {

    private fun libraryRows(): List<PegasusSystemRow> {
        val counts = listOf(
            "nes" to 12, "snes" to 18, "n64" to 10, "gamecube" to 8,
            "gb" to 15, "gbc" to 14, "gba" to 22, "nds" to 9,
            "genesis" to 11, "mastersystem" to 6, "psx" to 13,
            "ps2" to 5, "arcade" to 4,
        )
        return counts.map { (slug, gameCount) ->
            PegasusSystemRow(
                slug = slug,
                label = slug.uppercase(),
                gameCount = gameCount,
                launcherStatus = "RetroArch (AUTO)",
                launcherInstalled = true,
                isDefault = true,
            )
        }
    }

    private fun readiness(biosIssues: List<BiosIssue> = emptyList()) =
        buildHomeReadiness(
            rows = libraryRows(),
            pegasusInstalled = true,
            romReady = true,
            biosIssues = biosIssues,
        )

    @Test
    fun ready_whenNoBiosIssues() {
        val r = readiness()
        assertTrue(r.ready)
        assertEquals("13 SYSTEMS · 147 GAMES", homeStatsLine(r))
        assertEquals("", homeBiosLine(r))
    }

    @Test
    fun biosIssue_blocksReady_andCounts() {
        val r = readiness(listOf(BiosIssue("PS2", "BIOS REQUIRED")))
        assertFalse(r.ready)
        assertEquals(1, r.issueCount)
        assertEquals("PS2 · BIOS REQUIRED", homeBiosLine(r))
        // The library count is untouched by firmware state.
        assertEquals("13 SYSTEMS · 147 GAMES", homeStatsLine(r))
        assertEquals(147, r.totalGames)
    }

    @Test
    fun biosIssue_addsToLauncherIssues() {
        val rows = libraryRows().map {
            if (it.slug == "gba") it.copy(launcherStatus = "NOT CONFIGURED", launcherInstalled = false)
            else it
        }
        val r = buildHomeReadiness(
            rows = rows,
            pegasusInstalled = true,
            romReady = true,
            biosIssues = listOf(BiosIssue("PS2", "BIOS MISSING")),
        )
        assertFalse(r.ready)
        assertEquals(2, r.issueCount)
        // Launcher line still counts launcher issues only.
        assertEquals("12 CONFIGURED · 1 NEEDS ATTENTION", homeLauncherLine(r))
        assertEquals("PS2 · BIOS MISSING", homeBiosLine(r))
    }

    @Test
    fun biosLine_joinsMultipleIssues() {
        val r = readiness(
            listOf(BiosIssue("PS2", "BIOS REQUIRED"), BiosIssue("PS2", "BIOS UNVERIFIED")),
        )
        assertEquals("PS2 · BIOS REQUIRED / PS2 · BIOS UNVERIFIED", homeBiosLine(r))
    }
}
