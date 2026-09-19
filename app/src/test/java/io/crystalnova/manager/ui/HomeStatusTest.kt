package io.crystalnova.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * u44: HOME's status is built from the discovered ROM library counts,
 * the iiSU package check, and the (currently empty) installed-pack
 * list — never invented. The old Pegasus-readiness model (launchers
 * configured, Pegasus installed) is gone with the strip-back.
 */
class HomeStatusTest {

    private fun status(
        systems: List<Pair<String, Int>> = listOf("Super Nintendo" to 12, "Game Boy Advance" to 8),
        romReady: Boolean = true,
        iisuInstalled: Boolean = true,
        iisuVersion: String? = "0.0.7.4",
        packName: String? = null,
        packVersion: String? = null,
    ) = buildHomeStatus(
        systems = systems,
        romReady = romReady,
        iisuInstalled = iisuInstalled,
        iisuVersion = iisuVersion,
        packName = packName,
        packVersion = packVersion,
    )

    @Test
    fun counts_comeFromTheLibrary() {
        val s = status()
        assertEquals(2, s.systemCount)
        assertEquals(20, s.totalGames)
    }

    @Test
    fun emptyLibrary_reportsZero() {
        val s = status(systems = emptyList())
        assertEquals(0, s.systemCount)
        assertEquals(0, s.totalGames)
        assertEquals("LIBRARY EMPTY — SCAN YOUR ROMS", homeRomsLine(s))
    }

    @Test
    fun noRomFolder_reportsMissingLibrary() {
        val s = status(romReady = false)
        assertFalse(s.romReady)
        assertEquals("NO LIBRARY — PICK YOUR ROMS FOLDER", homeRomsLine(s))
    }

    @Test
    fun populatedLibrary_reportsCounts() {
        val s = status()
        assertEquals("2 SYSTEMS · 20 GAMES", homeRomsLine(s))
    }

    @Test
    fun iisuLine_showsVersionWhenInstalled() {
        assertEquals("iiSU v0.0.7.4 · INSTALLED", homeIisuLine(status()))
    }

    @Test
    fun iisuLine_withoutVersion_stillSaysInstalled() {
        assertEquals("iiSU · INSTALLED", homeIisuLine(status(iisuVersion = null)))
    }

    @Test
    fun iisuLine_whenNotInstalled_isHonest() {
        val s = status(iisuInstalled = false, iisuVersion = null)
        assertFalse(s.iisuInstalled)
        assertEquals("iiSU NOT INSTALLED", homeIisuLine(s))
    }

    @Test
    fun packLine_emptyLibrary_saysNoneInstalled() {
        assertEquals("CRYSTAL PACK: NONE INSTALLED", homePackLine(status()))
    }

    @Test
    fun packLine_withPack_showsNameAndVersion() {
        val s = status(packName = "Crystal Dawn", packVersion = "1.0")
        assertEquals("CRYSTAL PACK: CRYSTAL DAWN v1.0", homePackLine(s))
    }

    @Test
    fun packLine_missingVersion_showsQuestionMark() {
        val s = status(packName = "Crystal Dawn", packVersion = null)
        assertEquals("CRYSTAL PACK: CRYSTAL DAWN v?", homePackLine(s))
    }
}
