package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.scan.DiscoveredSystem
import io.crystalnova.manager.scraper.work.BridgeStatus
import io.crystalnova.manager.scraper.work.IndexEntry
import io.crystalnova.manager.scraper.work.ScraperStats
import io.crystalnova.manager.scraper.work.SystemStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the scraper pass's pure helpers: the finished-state
 * summary line, the Diagnostics representative-game pick, and the
 * theme-bridge status core.
 */
class ScraperPassTest {

    private fun entry(
        key: String,
        title: String,
        platform: String,
        completeness: Completeness = Completeness.COMPLETE_CASE_AND_MEDIA,
    ) = IndexEntry(
        key = key, title = title, platform = platform,
        completeness = completeness, real = 1, generated = 3,
    )

    private fun gbaSystem() = DiscoveredSystem(
        platformSlug = "gba", label = "Game Boy Advance", gameCount = 12,
        sourceFolderName = "gba",
    )

    private fun gbaRow() = SystemStats(
        slug = "gba", label = "Game Boy Advance", games = 12,
        complete = 8, partial = 3, unmatched = 1,
    )

    @Test
    fun `finish summary names the system with persisted stats`() {
        val text = scrapeFinishSummary(
            selectedPlatform = "gba",
            systems = listOf(gbaSystem()),
            stats = ScraperStats(),
            systemStats = mapOf("gba" to gbaRow()),
        )
        assertEquals(
            "SCRAPE FINISHED · GAME BOY ADVANCE · 12 GAMES · " +
                "8 COMPLETE · 3 PARTIAL · 1 UNMATCHED — RETRY INCOMPLETE TO FILL THE GAPS",
            text,
        )
    }

    @Test
    fun `finish summary for all systems uses the aggregate`() {
        val stats = ScraperStats(totalGames = 147, complete = 120, partial = 20, unmatched = 7)
        val text = scrapeFinishSummary(
            selectedPlatform = null,
            systems = listOf(gbaSystem()),
            stats = stats,
            systemStats = emptyMap(),
        )
        assertEquals(
            "SCRAPE FINISHED · ALL SYSTEMS · 147 GAMES · " +
                "120 COMPLETE · 20 PARTIAL · 7 UNMATCHED — RETRY INCOMPLETE TO FILL THE GAPS",
            text,
        )
    }

    @Test
    fun `finish summary omits the retry nudge when everything is complete`() {
        val text = scrapeFinishSummary(
            selectedPlatform = "gba",
            systems = listOf(gbaSystem()),
            stats = ScraperStats(),
            systemStats = mapOf("gba" to gbaRow().copy(partial = 0, unmatched = 0, complete = 12)),
        )
        assertEquals(
            "SCRAPE FINISHED · GAME BOY ADVANCE · 12 GAMES · " +
                "12 COMPLETE · 0 PARTIAL · 0 UNMATCHED",
            text,
        )
    }

    @Test
    fun `representative prefers mario golf on gba`() {
        val entries = listOf(
            entry("psx/doom", "Doom", "psx"),
            entry("gba/mario-golf-advance-tour", "Mario Golf: Advance Tour", "gba"),
        )
        assertEquals("gba/mario-golf-advance-tour", pickRepresentative(entries)!!.key)
    }

    @Test
    fun `representative falls back to the first complete game`() {
        val entries = listOf(
            entry("gba/broken", "Broken", "gba", Completeness.NO_MATCH),
            entry("psx/doom", "Doom", "psx"),
        )
        assertEquals("psx/doom", pickRepresentative(entries)!!.key)
    }

    @Test
    fun `representative falls back to the first entry when nothing is complete`() {
        val entries = listOf(
            entry("gba/broken", "Broken", "gba", Completeness.NO_MATCH),
        )
        assertEquals("gba/broken", pickRepresentative(entries)!!.key)
    }

    @Test
    fun `representative is null with no entries`() {
        assertNull(pickRepresentative(emptyList()))
    }

    @Test
    fun `bridge status core maps the three states`() {
        assertEquals(BridgeStatus.NOT_REQUIRED, bridgeStatusFor(null, false))
        assertEquals(BridgeStatus.NOT_REQUIRED, bridgeStatusFor(null, true))
        assertEquals(BridgeStatus.PRESENT, bridgeStatusFor("content://media", true))
        assertEquals(BridgeStatus.FAILED, bridgeStatusFor("content://media", false))
    }
}
