package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.generate.ArtworkPalette
import io.crystalnova.manager.scraper.generate.BackCoverGenerator
import io.crystalnova.manager.scraper.generate.MediaGenerator
import io.crystalnova.manager.scraper.generate.SpineGenerator
import io.crystalnova.manager.scraper.generate.TextWrap
import io.crystalnova.manager.scraper.match.PlatformTable
import io.crystalnova.manager.scraper.model.MediaKind
import io.crystalnova.manager.scraper.model.Region
import org.junit.Assert.*
import org.junit.Test

class GeneratorSpecTest {

    @Test fun `spine spec carries title, region code and platform palette`() {
        val spec = SpineGenerator.layout(
            title = "Mario Golf: Advance Tour",
            platformSlug = "gba",
            platformLabel = "Game Boy Advance",
            region = Region.EUROPE,
        )
        assertEquals("Mario Golf: Advance Tour", spec.title)
        assertEquals("EUR", spec.regionCode)
        assertEquals("Game Boy Advance", spec.platformLabel)
        // GBA brand purple; the palette is metadata, the PNG stays watermark-free.
        assertEquals(0xFF4A3B8C.toInt(), spec.palette.primary)
    }

    @Test fun `spine spec falls back for blank titles`() {
        val spec = SpineGenerator.layout("", "gba", "Game Boy Advance", Region.UNKNOWN)
        assertEquals("Unknown Title", spec.title)
        assertEquals("UNK", spec.regionCode)
    }

    @Test fun `back spec builds metadata rows with description fallback`() {
        val spec = BackCoverGenerator.layout(
            title = "TOCA Race Driver 3",
            description = null,
            developer = "Codemasters",
            publisher = null,
            releaseYear = "2006",
            genre = "Racing",
            players = null,
            platformLabel = "PlayStation 2",
            platformSlug = "ps2",
            hasScreenshot = false,
        )
        assertTrue(spec.description.isNotBlank())
        val labels = spec.rows.map { it.label }
        assertTrue(labels.containsAll(listOf("DEVELOPER", "RELEASED", "GENRE", "PLATFORM")))
        assertFalse(labels.contains("PUBLISHER")) // blank fields are omitted, not rendered empty
        assertEquals("Codemasters", spec.rows.first { it.label == "DEVELOPER" }.value)
    }

    @Test fun `media spec maps unknown kind to generic, never cartridge`() {
        val spec = MediaGenerator.layout(
            kind = MediaKind.UNKNOWN,
            title = "Mystery Game",
            platformSlug = "hypothetical",
            platformLabel = "Hypothetical",
            hasLogo = false,
        )
        assertEquals(MediaKind.GENERIC, spec.kind)
    }

    @Test fun `media spec keeps known kinds`() {
        assertEquals(
            MediaKind.CARTRIDGE,
            MediaGenerator.layout(MediaKind.CARTRIDGE, "t", "gba", "GBA", false).kind,
        )
        assertEquals(
            MediaKind.DISC,
            MediaGenerator.layout(MediaKind.DISC, "t", "ps2", "PS2", false).kind,
        )
        assertEquals(
            MediaKind.UMD,
            MediaGenerator.layout(MediaKind.UMD, "t", "psp", "PSP", false).kind,
        )
    }

    @Test fun `platform table assigns media kinds, unknown stays unknown`() {
        assertEquals(MediaKind.CARTRIDGE, PlatformTable.bySlug("gba")?.mediaKind)
        assertEquals(MediaKind.DISC, PlatformTable.bySlug("psx")?.mediaKind)
        assertEquals(MediaKind.DISC, PlatformTable.bySlug("ps2")?.mediaKind)
        assertEquals(MediaKind.UMD, PlatformTable.bySlug("psp")?.mediaKind)
        assertEquals(MediaKind.GENERIC, PlatformTable.bySlug("arcade")?.mediaKind)
        assertNull(PlatformTable.bySlug("not-a-real-platform")?.mediaKind)
    }

    @Test fun `unknown platform palette is neutral`() {
        val palette = ArtworkPalette.forPlatform("not-a-real-platform")
        assertEquals(0xFF4A4238.toInt(), palette.primary)
    }

    @Test fun `generator ids are recorded as metadata constants`() {
        // Provenance lives in metadata (generatedBy), never as a visible watermark.
        assertEquals("crystal-spine-v1", SpineGenerator.GENERATOR_ID)
        assertEquals("crystal-back-v1", BackCoverGenerator.GENERATOR_ID)
        assertEquals("crystal-media-v1", MediaGenerator.GENERATOR_ID)
    }

    @Test fun `text wrap breaks long titles within max lines`() {
        val text = "The Legend of Zelda: A Link to the Past"
        val measure: (String) -> Float = { it.length.toFloat() }
        // maxLines truncates: 9 words need 4 lines at width 10, only 3 kept.
        val truncated = TextWrap.wrap(text = text, maxWidth = 10f, maxLines = 3, measure = measure)
        assertEquals(3, truncated.size)
        // When the text fits, nothing is lost.
        val full = TextWrap.wrap(text = text, maxWidth = 10f, maxLines = 10, measure = measure)
        assertTrue(full.size <= 10)
        assertEquals(
            text.split(" ").filter { it.isNotEmpty() }.size,
            full.joinToString(" ").split(" ").size,
        )
    }
}
