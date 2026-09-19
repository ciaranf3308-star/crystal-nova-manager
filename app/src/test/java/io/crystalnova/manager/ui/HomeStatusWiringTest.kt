package io.crystalnova.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * u44 wiring regression: HOME's inventory must come from the
 * authoritative discovered ROM library ([buildHomeStatus] inputs) —
 * never from the scraper/artwork index — and iiSU presence must gate
 * only the LAUNCH iiSU action, never the inventory lines.
 *
 * The scraper index counts *indexed artwork*, not ROMs: deriving HOME's
 * count from it would show "13 SYSTEMS · 0 GAMES" on a fully-scanned
 * but unscraped library — false. Artwork statistics live on the ROMS
 * and artwork screens only.
 */
class HomeStatusWiringTest {

    /**
     * Canonical fixture: 13 populated systems, 147 ROMs — and an EMPTY
     * artwork index (nothing scraped yet).
     */
    private fun librarySystems(): List<Pair<String, Int>> = listOf(
        "NES" to 12,
        "SNES" to 18,
        "N64" to 10,
        "GameCube" to 8,
        "GB" to 15,
        "GBC" to 14,
        "GBA" to 22,
        "NDS" to 9,
        "Genesis" to 11,
        "Master System" to 6,
        "PSX" to 13,
        "PS2" to 5,
        "Arcade" to 4,
    )

    private fun status(
        systems: List<Pair<String, Int>> = librarySystems(),
        romReady: Boolean = true,
        iisuInstalled: Boolean = true,
        iisuVersion: String? = "0.0.7.4",
    ) = buildHomeStatus(
        systems = systems,
        romReady = romReady,
        iisuInstalled = iisuInstalled,
        iisuVersion = iisuVersion,
    )

    @Test
    fun libraryCount_comesFromRomLibrary_notArtworkIndex() {
        // Library = 13 systems / 147 ROMs; artwork index = 0 scraped.
        val s = status()
        assertEquals(13, s.systemCount)
        assertEquals(147, s.totalGames)
        assertEquals("13 SYSTEMS · 147 GAMES", homeRomsLine(s))
    }

    @Test
    fun iisuAbsent_inventoryLines_unchanged() {
        // iiSU not installed: the inventory is still the library's own
        // truth; only the iiSU line (and the LAUNCH button's enabled
        // state, which the screen owns) may change.
        val s = status(iisuInstalled = false, iisuVersion = null)
        assertFalse(s.iisuInstalled)
        assertEquals("iiSU NOT INSTALLED", homeIisuLine(s))
        assertEquals(147, s.totalGames)
        assertEquals("13 SYSTEMS · 147 GAMES", homeRomsLine(s))
    }

    @Test
    fun emptyLibrary_reportsEmpty_notReadyNoise() {
        val s = status(systems = emptyList())
        assertEquals(0, s.totalGames)
        assertEquals("LIBRARY EMPTY — SCAN YOUR ROMS", homeRomsLine(s))
        assertTrue(s.iisuInstalled)
    }

    @Test
    fun noRomFolder_reportsPickerPrompt() {
        val s = status(romReady = false)
        assertFalse(s.romReady)
        assertEquals("NO LIBRARY — PICK YOUR ROMS FOLDER", homeRomsLine(s))
    }
}
