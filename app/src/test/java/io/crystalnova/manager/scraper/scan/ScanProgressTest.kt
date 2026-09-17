package io.crystalnova.manager.scraper.scan

import org.junit.Assert.*
import org.junit.Test

class ScanProgressTest {

    @Test
    fun displayLine_formatsCountersTruthfully() {
        val p = ScanProgress(
            currentFolder = "gba",
            systemsDone = 7,
            systemsTotal = 18,
            gamesFound = 1284,
        )
        assertEquals("SCANNING GBA · 7/18 SYSTEMS · 1,284 GAMES FOUND", p.displayLine())
    }

    @Test
    fun displayLine_uppercasesFolder() {
        val p = ScanProgress("PS1", 1, 3, 42)
        assertTrue(p.displayLine().startsWith("SCANNING PS1 · 1/3 SYSTEMS"))
    }
}
