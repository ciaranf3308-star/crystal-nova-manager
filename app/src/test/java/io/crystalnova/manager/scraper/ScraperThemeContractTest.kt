package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.match.TitleNormalizer
import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.model.ScrapedGame
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.scraper.store.ScraperJson
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.storage.InMemoryThemeFs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Producer side of the scraper -> Pegasus theme media contract (v23).
 *
 * The golden fixture at
 * `app/src/test/resources/scraper-theme-contract/mario-golf-gba.json`
 * is the shared contract: the theme's `crystal_assets_driver.js`
 * consumes the same file and asserts its resolver requests the exact
 * URLs recorded there. This test pins every fixture value against the
 * REAL production code — [TitleNormalizer], [ScraperStorage.assetPath]
 * and [ScraperJson.indexEntryToJson] — so any production drift fails
 * loudly here instead of silently orphaning artwork on the Nova.
 *
 * This is not a duplicate-implementation test: the expected values
 * live in the committed fixture, and the assertions run the actual
 * production functions.
 */
class ScraperThemeContractTest {

    private fun fixture(): JSONObject {
        val text = javaClass
            .getResourceAsStream("/scraper-theme-contract/mario-golf-gba.json")!!
            .bufferedReader().readText()
        return JSONObject(text)
    }

    /** The exact game the v22 hardware gate scrapes: GBA Mario Golf. */
    private fun marioGolf(): ScrapedGame {
        val gameId = TitleNormalizer.slugify("Mario Golf - Advance Tour (E).gba")
        fun prov(slot: AssetSlot, type: SourceType) = AssetProvenance(
            sourceType = type,
            provider = if (type == SourceType.REAL) "libretro" else "crystal",
            localPath = "games/gba/$gameId/${slot.fileName}",
            generatedBy = if (type == SourceType.GENERATED) "crystal-test-v1" else null,
        )
        return ScrapedGame(
            platform = "gba",
            gameId = gameId,
            romRelativePath = "gba/Mario Golf - Advance Tour (E).gba",
            fileSize = 123456,
            lastModified = 1726400000000,
            title = "Mario Golf: Advance Tour",
            region = Region.EUROPE,
            provider = "libretro",
            assets = mapOf(
                AssetSlot.BOX_FRONT to prov(AssetSlot.BOX_FRONT, SourceType.REAL),
                AssetSlot.BOX_SPINE to prov(AssetSlot.BOX_SPINE, SourceType.GENERATED),
                AssetSlot.BOX_BACK to prov(AssetSlot.BOX_BACK, SourceType.GENERATED),
                AssetSlot.PHYSICAL_MEDIA to prov(AssetSlot.PHYSICAL_MEDIA, SourceType.GENERATED),
            ),
        )
    }

    @Test fun `mario golf gameId matches the golden fixture`() {
        val f = fixture()
        val rom = f.getString("romFileName")
        assertEquals("Mario Golf - Advance Tour (E).gba", rom)
        assertEquals(f.getString("gameId"), TitleNormalizer.slugify(rom))
        assertEquals("mario-golf-advance-tour", TitleNormalizer.slugify(rom))
        assertEquals(f.getString("key"), "gba/${TitleNormalizer.slugify(rom)}")
    }

    @Test fun `mario golf asset paths match the golden fixture`() {
        val f = fixture()
        val storage = ScraperStorage(InMemoryThemeFs())
        val platform = f.getString("platform")
        val gameId = f.getString("gameId")
        val paths = f.getJSONObject("assetPaths")
        assertEquals(paths.getString("front"), storage.assetPath(platform, gameId, AssetSlot.BOX_FRONT))
        assertEquals(paths.getString("spine"), storage.assetPath(platform, gameId, AssetSlot.BOX_SPINE))
        assertEquals(paths.getString("back"), storage.assetPath(platform, gameId, AssetSlot.BOX_BACK))
        assertEquals(paths.getString("media"), storage.assetPath(platform, gameId, AssetSlot.PHYSICAL_MEDIA))
        assertEquals(f.getString("manifestPath"), storage.manifestPath(platform, gameId))
    }

    @Test fun `mario golf index entry matches the golden fixture`() {
        val f = fixture()
        val entry = ScraperJson.indexEntryToJson(marioGolf())
        val want = JSONObject(f.getString("indexJson"))
            .getJSONObject("games")
            .getJSONObject(f.getString("key"))
        // similar() is order-independent for objects; the assets array is
        // sorted by production code and in the fixture.
        assertTrue(
            "index entry drifted from the golden fixture:\n got=$entry\nwant=$want",
            want.similar(entry),
        )
    }

    @Test fun `empty-slug fallback is pinned for the theme mirror`() {
        // Pure-CJK / punctuation-only names hash to game-<fnv1a>; the
        // theme's CrystalAssets.js must mirror these exact values
        // (u2_gameid_fixtures.json pins the JS side).
        assertEquals("game-c6b2a7b6", TitleNormalizer.slugify("ポケットモンスター.gba"))
        assertEquals("game-2d53a722", TitleNormalizer.slugify("!!! (E).gba"))
    }
}
