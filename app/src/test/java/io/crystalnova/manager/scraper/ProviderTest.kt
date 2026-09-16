package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.provider.ArtworkCandidate
import io.crystalnova.manager.scraper.provider.LibretroProvider
import io.crystalnova.manager.scraper.provider.PegasusFileMetadataProvider
import io.crystalnova.manager.scraper.provider.PegasusMetadataReader
import io.crystalnova.manager.scraper.provider.ScrapeQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderTest {

    @Test fun `candidate ladder covers modernized, raw and sanitized names`() {
        val names = LibretroProvider().candidateNames("Super Mario World (USA).smc")
        assertTrue(names.contains("Super Mario World (USA)"))
        // Short region-agnostic name is always in the ladder.
        assertTrue(names.contains("Super Mario World"))
        assertEquals(names.size, names.distinct().size) // no duplicates
    }

    @Test fun `candidate ladder modernizes legacy region tags`() {
        val names = LibretroProvider().candidateNames("Mario Golf (E).gba")
        assertTrue(names.any { "(Europe)" in it })
    }

    @Test fun `artwork urls hit both libretro endpoints as png`() = runBlocking {
        val provider = LibretroProvider()
        val query = ScrapeQuery("snes", "Super Mario World", "Super Mario World (USA).smc", Region.USA)
        val map = provider.artworkFor(query)
        val fronts = map[io.crystalnova.manager.scraper.model.AssetSlot.BOX_FRONT].orEmpty()
        assertTrue(fronts.isNotEmpty())
        assertTrue(fronts.any { it.url.startsWith("https://thumbnails.libretro.com/") })
        assertTrue(fronts.any { it.url.startsWith("https://raw.githubusercontent.com/libretro-thumbnails/") })
        assertTrue(fronts.all { it.url.endsWith(".png") })
        // Boxart, snaps and clear logos are all offered; backs come from generated art.
        assertTrue(map[io.crystalnova.manager.scraper.model.AssetSlot.SCREENSHOT].orEmpty().isNotEmpty())
        assertTrue(map[io.crystalnova.manager.scraper.model.AssetSlot.CLEAR_LOGO].orEmpty().isNotEmpty())
        assertNull(map[io.crystalnova.manager.scraper.model.AssetSlot.BOX_BACK])
    }

    @Test fun `unknown platform yields no candidates instead of garbage`() = runBlocking {
        val provider = LibretroProvider()
        val query = ScrapeQuery("not-a-platform", "Game", "game.bin", Region.UNKNOWN)
        assertTrue(provider.artworkFor(query).isEmpty())
    }

    @Test fun `provider needs no api key`() {
        val provider = LibretroProvider()
        assertFalse(provider.requiresApiKey)
        assertEquals("libretro", provider.id)
    }

    @Test fun `pegasus parser reads game entries`() {
        val text = """
            collection: GBA
            shortname: gba

            game: Mario Golf: Advance Tour
            file: Mario Golf (E).gba
            developer: Camelot
            publisher: Nintendo
            genre: Sports
            players: 1-4
            release: 2004
            description: Tee off on the GBA.

            game: Other Game
            file: other.gba

        """.trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(2, entries.size)
        val first = entries[0]
        assertEquals("Mario Golf: Advance Tour", first.title)
        assertEquals(listOf("Mario Golf (E).gba"), first.files)
        assertEquals("Camelot", first.developer)
        assertEquals("Tee off on the GBA.", first.description)
        assertEquals("GBA", first.collection)
        assertEquals("gba", first.shortname)
    }

    @Test fun `pegasus parser keeps legacy game-as-filename form working`() {
        val text = """
            game: Mario Golf (E).gba
            title: Mario Golf: Advance Tour
            developer: Camelot

        """.trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(1, entries.size)
        assertEquals("Mario Golf: Advance Tour", entries[0].title)
        assertEquals(listOf("Mario Golf (E).gba"), entries[0].files)
    }

    @Test fun `pegasus file provider matches rom filename first`() = runBlocking {
        val entries = PegasusMetadataReader.parse(
            "game: Mario Golf: Advance Tour\nfile: Mario Golf (E).gba\ndeveloper: Camelot\n",
        )
        // Provider is keyed by ROM directory; "" covers bare filenames.
        val provider = PegasusFileMetadataProvider(mapOf("" to entries))
        val meta = provider.lookup(ScrapeQuery("gba", "Mario Golf Advance Tour", "Mario Golf (E).gba", Region.EUROPE))
        assertNotNull(meta)
        assertEquals("Mario Golf: Advance Tour", meta!!.title)
        assertEquals("Camelot", meta.developer)
    }

    @Test fun `pegasus file provider returns null when nothing matches`() = runBlocking {
        val provider = PegasusFileMetadataProvider(emptyMap())
        assertNull(provider.lookup(ScrapeQuery("gba", "Nope", "nope.gba", Region.UNKNOWN)))
    }

    @Test fun `artwork candidate carries an optional region`() {
        val c = ArtworkCandidate("https://example.com/a.png", Region.EUROPE)
        assertEquals(Region.EUROPE, c.region)
        assertNull(ArtworkCandidate("https://example.com/a.png").region)
    }
}
