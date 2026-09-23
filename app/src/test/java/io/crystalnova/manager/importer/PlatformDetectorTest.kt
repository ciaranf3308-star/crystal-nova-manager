package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure detection tiers: extension -> CONFIRMED,
 * header magic -> CONFIRMED, structures/serials, filename aliases ->
 * LIKELY, and UNKNOWN / NOT_RECOGNIZED fallbacks.
 */
class PlatformDetectorTest {

    private val detector = PlatformDetector()
    private val noHeader = PlatformDetector.HeaderReader { _, _, _ -> null }

    private fun entry(path: String, size: Long = 1024): ArchiveEntryInfo =
        ArchiveEntryInfo(path, size, isDirectory = false)

    private fun dir(path: String): ArchiveEntryInfo =
        ArchiveEntryInfo(path, 0, isDirectory = true)

    // ---- Tier 1: direct extensions -----------------------------------

    @Test fun `gba extension confirms GBA`() {
        val d = detector.detect(
            "Pokemon Emerald.zip",
            listOf(entry("Pokemon Emerald.gba", 16_777_216)),
            noHeader,
        )
        assertEquals(PlatformId.GBA, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
        assertTrue(d.recognizedAsGame)
        assertTrue(d.signals.any { it.contains("gba") })
    }

    @Test fun `nds extension confirms NDS`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("rom.nds")),
            noHeader,
        )
        assertEquals(PlatformId.NDS, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `cia extension confirms N3DS`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.cia")),
            noHeader,
        )
        assertEquals(PlatformId.N3DS, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `z64 extension confirms N64`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.z64")),
            noHeader,
        )
        assertEquals(PlatformId.N64, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `smc extension confirms SNES`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.smc")),
            noHeader,
        )
        assertEquals(PlatformId.SNES, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `md extension confirms Genesis`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.md")),
            noHeader,
        )
        assertEquals(PlatformId.GENESIS, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `gdi extension confirms Dreamcast`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("track.gdi")),
            noHeader,
        )
        assertEquals(PlatformId.DREAMCAST, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `gcm extension confirms GameCube`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.gcm")),
            noHeader,
        )
        assertEquals(PlatformId.GAMECUBE, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `wbfs extension confirms Wii`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.wbfs")),
            noHeader,
        )
        assertEquals(PlatformId.WII, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    // ---- Tier 2: header magic -----------------------------------------

    @Test fun `gamecube disc magic confirms GameCube over ambiguous iso`() {
        // A realistic disc window: zeros up front, magic at 0x1C.
        val window = ByteArray(64).also {
            it[0x1C] = 0xC2.toByte()
            it[0x1D] = 0x33
            it[0x1E] = 0x9F.toByte()
            it[0x1F] = 0x3D
        }
        val header = PlatformDetector.HeaderReader { _, offset, length ->
            window.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.iso", 1_459_978_240)),
            header,
        )
        assertEquals(PlatformId.GAMECUBE, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `magic at offset zero is ignored - real magic lives at 0x1C`() {
        // Regression: a reader window with the magic at offset 0 must
        // NOT confirm, proving we read the real disc offset.
        val window = ByteArray(64).also {
            it[0] = 0xC2.toByte()
            it[1] = 0x33
            it[2] = 0x9F.toByte()
            it[3] = 0x3D
        }
        val header = PlatformDetector.HeaderReader { _, offset, length ->
            window.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.iso", 1_459_978_240)),
            header,
        )
        assertEquals(Confidence.UNKNOWN, d.confidence)
    }

    @Test fun `wii disc magic confirms Wii over ambiguous iso`() {
        val window = ByteArray(64).also {
            it[0x1C] = 0x5D
            it[0x1D] = 0x1C
            it[0x1E] = 0x9E.toByte()
            it[0x1F] = 0xA3.toByte()
        }
        val header = PlatformDetector.HeaderReader { _, offset, length ->
            window.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        val d = detector.detect(
            "Mario Kart Wii.zip",
            listOf(entry("game.iso", 4_000_000_000L)),
            header,
        )
        assertEquals(PlatformId.WII, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `rvz without readable header falls back to alias`() {
        val d = detector.detect(
            "Mario Kart Double Dash [GC].zip",
            listOf(entry("game.rvz", 1_000_000_000L)),
            noHeader,
        )
        assertEquals(PlatformId.GAMECUBE, d.platform)
        assertEquals(Confidence.LIKELY, d.confidence)
    }

    // ---- Tier 3: structures and serials -------------------------------

    @Test fun `1st_read_bin confirms Dreamcast`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("IP.BIN"), entry("1ST_READ.BIN", 2_000_000)),
            noHeader,
        )
        assertEquals(PlatformId.DREAMCAST, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `psp_game folder confirms PSP`() {
        val d = detector.detect(
            "game.zip",
            listOf(dir("PSP_GAME/"), entry("PSP_GAME/USRDIR/boot.bin", 500_000_000L)),
            noHeader,
        )
        assertEquals(PlatformId.PSP, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `ps2 serial range confirms PS2`() {
        val d = detector.detect(
            "Metal Gear Solid 2 Substance.zip",
            listOf(entry("SLUS-20554.iso", 4_300_000_000L)),
            noHeader,
        )
        assertEquals(PlatformId.PS2, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
        assertTrue(d.signals.any { it.contains("SLUS-20554") })
    }

    @Test fun `ps1 serial range confirms PSX not PS2`() {
        val d = detector.detect(
            "Final Fantasy VII.zip",
            listOf(
                entry("Final Fantasy VII (Disc 1) [SCUS-94163].bin", 700_000_000L),
                entry("Final Fantasy VII (Disc 1) [SCUS-94163].cue", 2_000),
            ),
            noHeader,
        )
        assertEquals(PlatformId.PSX, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `psp serial confirms PSP`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("ULUS-10001.iso", 1_200_000_000L)),
            noHeader,
        )
        assertEquals(PlatformId.PSP, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `serial in archive name is honored`() {
        val d = detector.detect(
            "Gran Turismo 4 (SCES-51719).7z",
            listOf(entry("game.iso", 4_500_000_000L)),
            noHeader,
        )
        assertEquals(PlatformId.PS2, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `eboot is only likely PSP`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("EBOOT.PBP", 600_000_000L)),
            noHeader,
        )
        assertEquals(PlatformId.PSP, d.platform)
        assertEquals(Confidence.LIKELY, d.confidence)
    }

    // ---- Tier 4: filename aliases -------------------------------------

    @Test fun `gba alias in archive name is likely`() {
        val d = detector.detect(
            "Pokemon Emerald (USA) (GBA).zip",
            listOf(entry("rom.bin", 16_777_216)),
            noHeader,
        )
        assertEquals(PlatformId.GBA, d.platform)
        assertEquals(Confidence.LIKELY, d.confidence)
    }

    @Test fun `gc bracket alias is likely GameCube`() {
        val d = detector.detect(
            "Mario Kart Double Dash [GC].zip",
            listOf(entry("game.iso", 1_459_978_240)),
            noHeader,
        )
        assertEquals(PlatformId.GAMECUBE, d.platform)
        assertEquals(Confidence.LIKELY, d.confidence)
    }

    @Test fun `ps2 beats psx alias ordering`() {
        val d = detector.detect(
            "God of War II (PS2).7z",
            listOf(entry("game.iso", 4_000_000_000L)),
            noHeader,
        )
        assertEquals(PlatformId.PS2, d.platform)
        assertEquals(Confidence.LIKELY, d.confidence)
    }

    @Test fun `wii u alias beats wii`() {
        val d = detector.detect(
            "game (Wii U).zip",
            listOf(entry("game.wud", 8_000_000_000L)),
            noHeader,
        )
        // wud is a direct extension -> CONFIRMED Wii U.
        assertEquals(PlatformId.WIIU, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `strong signal beats alias`() {
        val d = detector.detect(
            "game (PS2).zip",
            listOf(entry("game.gba", 16_777_216)),
            noHeader,
        )
        assertEquals(PlatformId.GBA, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    // ---- Conflicts and fallbacks --------------------------------------

    @Test fun `conflicting extensions go to the largest file`() {
        val d = detector.detect(
            "mixed.zip",
            listOf(
                entry("tiny.gba", 1_000),
                entry("huge.nes", 1_000_000),
            ),
            noHeader,
        )
        assertEquals(PlatformId.NES, d.platform)
        assertEquals(Confidence.CONFIRMED, d.confidence)
    }

    @Test fun `bare iso with no signal is unknown but recognized`() {
        val d = detector.detect(
            "Game.iso.zip",
            listOf(entry("game.iso", 700_000_000L)),
            noHeader,
        )
        assertNull(d.platform)
        assertEquals(Confidence.UNKNOWN, d.confidence)
        assertTrue(d.recognizedAsGame)
    }

    @Test fun `docs-only archive is not recognized as a game`() {
        val d = detector.detect(
            "invoice.zip",
            listOf(entry("invoice.pdf", 50_000), entry("notes.txt", 1_000)),
            noHeader,
        )
        assertNull(d.platform)
        assertEquals(Confidence.UNKNOWN, d.confidence)
        assertFalse(d.recognizedAsGame)
    }

    @Test fun `empty archive is not recognized`() {
        val d = detector.detect(
            "empty.zip",
            emptyList(),
            noHeader,
        )
        assertFalse(d.recognizedAsGame)
    }

    @Test fun `failed header read yields no header signal`() {
        val d = detector.detect(
            "game.zip",
            listOf(entry("game.iso", 700_000_000L)),
            noHeader,
        )
        assertNull(d.platform)
        assertEquals(Confidence.UNKNOWN, d.confidence)
    }

    // ---- Title helpers -------------------------------------------------

    @Test fun `cleanDisplayTitle strips tags and dashes`() {
        assertEquals(
            "Pokemon Emerald Version",
            cleanDisplayTitle("Pokemon - Emerald Version (USA) (Rev 1).zip"),
        )
        assertEquals(
            "Metal Gear Solid 2 Substance",
            cleanDisplayTitle("Metal Gear Solid 2 Substance.7z"),
        )
        assertEquals(
            "Mario Kart Double Dash",
            cleanDisplayTitle("Mario Kart Double Dash [GC].zip"),
        )
    }

    @Test fun `sanitizeFileName removes separators`() {
        assertEquals("a_b_c_d", sanitizeFileName("a/b\\c:d"))
        assertEquals("game", sanitizeFileName("..."))
    }

    @Test fun `formatBytes renders human sizes`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("4.3 GB", formatBytes(4_618_000_000L))
        assertEquals("1.5 MB", formatBytes(1_572_864L))
    }
}
