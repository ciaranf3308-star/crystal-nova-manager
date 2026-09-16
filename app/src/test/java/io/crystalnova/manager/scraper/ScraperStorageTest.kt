package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.model.MatchConfidence
import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.model.ScrapedGame
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.storage.InMemoryThemeFs
import org.junit.Assert.*
import org.junit.Test

class ScraperStorageTest {

    private fun storage() = ScraperStorage(InMemoryThemeFs())

    private fun prov(type: SourceType, by: String? = null) = AssetProvenance(
        sourceType = type,
        provider = "test",
        localPath = "games/gba/slug/front.png",
        generatedBy = by,
    )

    @Test fun `first write is saved under crystal-nova-data`() {
        val s = storage()
        val result = s.saveAsset("gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL), byteArrayOf(1, 2, 3), null)
        assertTrue(result is ScraperStorage.SaveResult.Saved)
        assertEquals("games/gba/slug/front.png", s.assetPath("gba", "slug", AssetSlot.BOX_FRONT))
        assertArrayEquals(byteArrayOf(1, 2, 3), s.readBytes("games/gba/slug/front.png"))
    }

    @Test fun `user asset is never overwritten`() {
        val s = storage()
        s.saveAsset("gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.USER), byteArrayOf(9), null)
        val result = s.saveAsset("gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL), byteArrayOf(1, 2, 3), prov(SourceType.USER))
        assertTrue(result is ScraperStorage.SaveResult.Kept)
        assertArrayEquals(byteArrayOf(9), s.readBytes("games/gba/slug/front.png"))
    }

    @Test fun `real asset replaces generated`() {
        val s = storage()
        s.saveAsset("gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.GENERATED, "crystal-spine-v1"), byteArrayOf(9), null)
        val result = s.saveAsset(
            "gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL),
            byteArrayOf(1, 2, 3), prov(SourceType.GENERATED, "crystal-spine-v1"),
        )
        assertTrue(result is ScraperStorage.SaveResult.Saved)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.readBytes("games/gba/slug/front.png"))
    }

    @Test fun `generated never replaces real`() {
        val s = storage()
        s.saveAsset("gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL), byteArrayOf(1, 2, 3), null)
        val result = s.saveAsset(
            "gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.GENERATED, "crystal-spine-v1"),
            byteArrayOf(9), prov(SourceType.REAL),
        )
        assertTrue(result is ScraperStorage.SaveResult.Kept)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.readBytes("games/gba/slug/front.png"))
    }

    @Test fun `manifest round-trips with provenance`() {
        val s = storage()
        val game = ScrapedGame(
            platform = "gba",
            gameId = "mario-golf-advance-tour",
            romRelativePath = "gba/Mario Golf (E).gba",
            fileSize = 1234,
            lastModified = 5678,
            title = "Mario Golf: Advance Tour",
            region = Region.EUROPE,
            provider = "libretro",
            confidence = MatchConfidence.ExactTitle,
            assets = mapOf(
                AssetSlot.BOX_FRONT to prov(SourceType.REAL),
                AssetSlot.BOX_SPINE to prov(SourceType.GENERATED, "crystal-spine-v1"),
            ),
        )
        assertTrue(s.saveManifest(game))
        val loaded = s.loadManifest("gba", "mario-golf-advance-tour")
        assertNotNull(loaded)
        assertEquals("Mario Golf: Advance Tour", loaded!!.title)
        assertEquals(Region.EUROPE, loaded.region)
        assertEquals(SourceType.REAL, loaded.assets[AssetSlot.BOX_FRONT]?.sourceType)
        assertEquals("crystal-spine-v1", loaded.assets[AssetSlot.BOX_SPINE]?.generatedBy)
        assertEquals(Completeness.PARTIAL_CASE, loaded.completeness)
        assertEquals(1, loaded.realAssetCount)
        assertEquals(1, loaded.generatedAssetCount)
    }

    @Test fun `data dir name is the persistent sibling`() {
        assertEquals("crystal-nova-data", ScraperStorage.DATA_DIR_NAME)
    }

    @Test fun `failed final rename restores the original file`() {
        val fs = InMemoryThemeFs()
        val s = ScraperStorage(fs)
        val original = byteArrayOf(1, 2, 3)
        s.saveAsset("gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL), original, null)
        // Fail only the tmp -> final rename; the backup move and the restore succeed.
        fs.renameGate = { node, newName ->
            !(node.name == "front.png.tmp" && newName == "front.png")
        }
        val result = s.saveAsset(
            "gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL),
            byteArrayOf(9, 9), prov(SourceType.REAL),
        )
        assertTrue(result is ScraperStorage.SaveResult.Failed)
        // The original is intact, and no tmp/bak litter remains.
        assertArrayEquals(original, s.readBytes("games/gba/slug/front.png"))
        assertNull(s.readBytes("games/gba/slug/front.png.tmp"))
        assertNull(s.readBytes("games/gba/slug/front.png.bak"))
    }

    @Test fun `failed backup rename keeps the original and cleans up tmp`() {
        val fs = InMemoryThemeFs()
        val s = ScraperStorage(fs)
        val original = byteArrayOf(1, 2, 3)
        s.saveAsset("gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL), original, null)
        fs.renameGate = { _, newName -> !newName.endsWith(".bak") }
        val result = s.saveAsset(
            "gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL),
            byteArrayOf(9, 9), prov(SourceType.REAL),
        )
        assertTrue(result is ScraperStorage.SaveResult.Failed)
        assertArrayEquals(original, s.readBytes("games/gba/slug/front.png"))
        assertNull(s.readBytes("games/gba/slug/front.png.tmp"))
    }

    @Test fun `successful overwrite leaves no tmp or bak litter`() {
        val fs = InMemoryThemeFs()
        val s = ScraperStorage(fs)
        s.saveAsset("gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL), byteArrayOf(1), null)
        val result = s.saveAsset(
            "gba", "slug", AssetSlot.BOX_FRONT, prov(SourceType.REAL),
            byteArrayOf(2), prov(SourceType.REAL),
        )
        assertTrue(result is ScraperStorage.SaveResult.Saved)
        assertArrayEquals(byteArrayOf(2), s.readBytes("games/gba/slug/front.png"))
        assertNull(s.readBytes("games/gba/slug/front.png.tmp"))
        assertNull(s.readBytes("games/gba/slug/front.png.bak"))
    }
}
