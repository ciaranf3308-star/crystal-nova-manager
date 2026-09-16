package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.match.RegionDetector
import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.provider.LibretroProvider
import org.junit.Assert.*
import org.junit.Test

class RegionDetectorTest {
    private val detector = RegionDetector()
    private val noHeader = RegionDetector.HeaderReader { _, _ -> null }

    @Test fun `modern filename tags are detected`() {
        assertEquals(Region.USA, detector.detect("psx", "TOCA Race Driver 3 (USA).iso", noHeader))
        assertEquals(Region.EUROPE, detector.detect("snes", "game (Europe).smc", noHeader))
        assertEquals(Region.JAPAN, detector.detect("gba", "game (Japan).gba", noHeader))
        assertEquals(Region.WORLD, detector.detect("genesis", "game (World).md", noHeader))
    }

    @Test fun `legacy single-letter tags are detected`() {
        assertEquals(Region.EUROPE, detector.detect("gba", "Mario Golf (E).gba", noHeader))
        assertEquals(Region.USA, detector.detect("snes", "Super Mario World (U).smc", noHeader))
        assertEquals(Region.JAPAN, detector.detect("gb", "game (J).gb", noHeader))
    }

    @Test fun `no tag means UNKNOWN, never a guess`() {
        assertEquals(Region.UNKNOWN, detector.detect("gba", "homebrew.gba", noHeader))
        assertEquals(Region.UNKNOWN, detector.detect("psx", "disc.cue", noHeader))
    }

    @Test fun `gb header japan vs non-japan`() {
        val japan = ByteArray(0x200).also { it[0x14A] = 0x00 }
        val other = ByteArray(0x200).also { it[0x14A] = 0x01 }
        val reader: (ByteArray) -> RegionDetector.HeaderReader =
            { bytes -> RegionDetector.HeaderReader { _, _ -> bytes } }
        assertEquals(Region.JAPAN, detector.detect("gb", "game.gb", reader(japan)))
        // 0x01 = non-Japanese; header cannot split USA/Europe, so UNKNOWN wins over a guess.
        assertEquals(Region.UNKNOWN, detector.detect("gb", "game.gb", reader(other)))
    }

    @Test fun `gba game code territory suffix is detected`() {
        val rom = ByteArray(0xC0)
        rom[0xAC] = 'A'.code.toByte()
        rom[0xAD] = 'M'.code.toByte()
        rom[0xAE] = 'G'.code.toByte()
        rom[0xAF] = 'P'.code.toByte() // 'P' -> Europe
        rom[0xB2] = 0x96.toByte() // fixed header byte validates the ROM
        val reader = RegionDetector.HeaderReader { offset, length ->
            rom.copyOfRange(offset, (offset + length).coerceAtMost(rom.size))
        }
        assertEquals(Region.EUROPE, detector.detect("gba", "game.gba", reader))
    }

    @Test fun `snes destination code is detected with checksum validation`() {
        val rom = ByteArray(0x10040)
        val h = 0x7FC0
        rom[h + 0x29] = 0x02 // Europe
        rom[h + 0x2C] = 0x34; rom[h + 0x2D] = 0x12 // complement
        rom[h + 0x2E] = 0xCB.toByte(); rom[h + 0x2F] = 0xED.toByte() // checksum ^ complement = FFFF
        val reader = RegionDetector.HeaderReader { _, _ -> rom }
        assertEquals(Region.EUROPE, detector.detect("snes", "game.smc", reader))
    }

    @Test fun `n64 z64 country code is detected`() {
        val rom = ByteArray(0x40)
        rom[0] = 0x80.toByte(); rom[1] = 0x37; rom[2] = 0x12; rom[3] = 0x40 // z64 magic
        rom[0x3E] = 'E'.code.toByte() // USA
        val reader = RegionDetector.HeaderReader { _, _ -> rom }
        assertEquals(Region.USA, detector.detect("n64", "game.z64", reader))
    }

    @Test fun `header beats filename tag`() {
        val rom = ByteArray(0x200).also { it[0x14A] = 0x00 } // Japan in header
        val reader = RegionDetector.HeaderReader { _, _ -> rom }
        assertEquals(Region.JAPAN, detector.detect("gb", "game (USA).gb", reader))
    }

    @Test fun `region preference is exact, europe, world, usa, then other`() {
        val provider = LibretroProvider()
        assertEquals(
            listOf(Region.JAPAN, Region.EUROPE, Region.WORLD, Region.USA, Region.ASIA),
            provider.preferredRegions(Region.JAPAN),
        )
        assertEquals(
            listOf(Region.EUROPE, Region.WORLD, Region.USA, Region.JAPAN, Region.ASIA),
            provider.preferredRegions(Region.UNKNOWN),
        )
        assertEquals(Region.EUROPE, provider.preferredRegions(Region.USA)[1])
    }
}
