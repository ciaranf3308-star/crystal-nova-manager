package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.scan.LibraryScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the scanner's multi-disc dedup ([LibraryScanner.skipNames]).
 * The SAF walk itself needs a device; the filename decisions don't.
 */
class LibraryScannerDedupeTest {

    private fun skip(
        names: List<String>,
        m3uRefs: Set<String> = emptySet(),
        parent: String = "psx",
    ): Set<String> =
        LibraryScanner.skipNames(mapOf(parent to names), mapOf(parent to m3uRefs))[parent].orEmpty()

    @Test fun `cue wins over same-basename bin`() {
        val skipped = skip(listOf("Game.cue", "Game.bin", "Other.bin"))
        assertEquals(setOf("Game.bin"), skipped)
    }

    @Test fun `cue wins over numbered track bins`() {
        val skipped = skip(listOf("Game.cue", "Game (Track 1).bin", "Game (Track 2).bin"))
        assertEquals(setOf("Game (Track 1).bin", "Game (Track 2).bin"), skipped)
    }

    @Test fun `cue wins over disc-suffixed bins`() {
        val skipped = skip(listOf("Game.cue", "Game (Disc 1).bin"))
        assertEquals(setOf("Game (Disc 1).bin"), skipped)
    }

    @Test fun `bin without a matching cue is kept`() {
        val skipped = skip(listOf("Lone.bin", "Game.cue"))
        assertTrue(skipped.isEmpty())
    }

    @Test fun `game named like a track is not skipped without digits`() {
        // "Trackmania" has no track number suffix -> not a track file.
        val skipped = skip(listOf("Trackmania.cue", "Trackmania.bin"))
        assertEquals(setOf("Trackmania.bin"), skipped)
        val kept = skip(listOf("Trackmania.bin"))
        assertTrue(kept.isEmpty())
    }

    @Test fun `gdi wins over trackNN files`() {
        val skipped = skip(
            listOf("game.gdi", "track01.bin", "track02.raw", "track03.img", "game.bin"),
        )
        assertEquals(setOf("track01.bin", "track02.raw", "track03.img", "game.bin"), skipped)
    }

    @Test fun `trackNN files without a gdi are kept`() {
        val skipped = skip(listOf("track01.bin"))
        assertTrue(skipped.isEmpty())
    }

    @Test fun `m3u wins over the discs it references`() {
        val names = listOf(
            "Final Fantasy VII.m3u",
            "Final Fantasy VII (Disc 1).cue",
            "Final Fantasy VII (Disc 2).cue",
            "Final Fantasy VII (Disc 1).bin",
            "Final Fantasy VII (Disc 2).bin",
        )
        val refs = setOf(
            "final fantasy vii (disc 1).cue",
            "final fantasy vii (disc 2).cue",
        )
        val skipped = skip(names, m3uRefs = refs)
        // The cues lose to the m3u's reference list; the bins lose to the cues.
        assertEquals(
            setOf(
                "Final Fantasy VII (Disc 1).cue",
                "Final Fantasy VII (Disc 2).cue",
                "Final Fantasy VII (Disc 1).bin",
                "Final Fantasy VII (Disc 2).bin",
            ),
            skipped,
        )
    }

    @Test fun `m3u itself is never skipped`() {
        val skipped = skip(
            listOf("game.m3u"),
            m3uRefs = setOf("game.m3u"),
        )
        assertTrue(skipped.isEmpty())
    }

    @Test fun `dedup is scoped per directory`() {
        val result = LibraryScanner.skipNames(
            mapOf(
                "psx/a" to listOf("Game.cue", "Game.bin"),
                "psx/b" to listOf("Game.bin"),
            ),
        )
        assertEquals(setOf("Game.bin"), result["psx/a"])
        assertTrue(result["psx/b"].isNullOrEmpty())
    }

    @Test fun `dedup is case-insensitive`() {
        val skipped = skip(listOf("GAME.CUE", "game.BIN"))
        assertEquals(setOf("game.BIN"), skipped)
    }
}
