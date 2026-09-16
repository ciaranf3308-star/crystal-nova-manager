package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.model.ScrapedGame
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.scraper.store.ScraperJson
import org.junit.Assert.*
import org.junit.Test

/**
 * The Pegasus theme consumes index.json to resolve the deterministic
 * game identity (platform/gameId) and the stored asset slots. These
 * fields are the cross-repo contract — they must always be present.
 */
class ScraperJsonTest {

    private fun game() = ScrapedGame(
        platform = "gba",
        gameId = "mario-golf-advance-tour",
        romRelativePath = "gba/Mario Golf - Advance Tour (E).gba",
        title = "Mario Golf: Advance Tour",
        region = Region.EUROPE,
        provider = "libretro",
        assets = mapOf(
            AssetSlot.BOX_FRONT to AssetProvenance(
                SourceType.REAL, "libretro",
                localPath = "games/gba/mario-golf-advance-tour/front.png",
            ),
            AssetSlot.BOX_SPINE to AssetProvenance(
                SourceType.GENERATED, "crystal",
                localPath = "games/gba/mario-golf-advance-tour/spine.png",
                generatedBy = "crystal-spine-v1",
            ),
        ),
    )

    @Test
    fun indexEntryCarriesIdentityFields() {
        val o = ScraperJson.indexEntryToJson(game())
        assertEquals("gba", o.getString("platform"))
        assertEquals("mario-golf-advance-tour", o.getString("gameId"))
        assertEquals("Mario Golf - Advance Tour (E).gba", o.getString("fileName"))
        assertEquals("gba/Mario Golf - Advance Tour (E).gba", o.getString("romRelativePath"))
    }

    @Test
    fun indexEntryListsStoredAssetSlots() {
        val o = ScraperJson.indexEntryToJson(game())
        val assets = o.getJSONArray("assets")
        val slots = (0 until assets.length()).map { assets.getString(it) }.toSet()
        // File stems only — the theme builds "games/<platform>/<gameId>/<stem>.png".
        assertEquals(setOf("front", "spine"), slots)
    }

    @Test
    fun indexEntryOmitsAbsentSlots() {
        val o = ScraperJson.indexEntryToJson(game().copy(assets = emptyMap()))
        assertEquals(0, o.getJSONArray("assets").length())
    }
}
