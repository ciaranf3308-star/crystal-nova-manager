package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PsxDiscGroupingTest {

    // --- discNumberOf -------------------------------------------------

    @Test fun `disc markers are detected`() {
        assertEquals(1, discNumberOf("Metal Gear Solid (Disc 1).cue"))
        assertEquals(2, discNumberOf("Metal Gear Solid (Disc 2).cue"))
        assertEquals(2, discNumberOf("game_Disk 2.cue"))
        assertEquals(1, discNumberOf("game CD1.cue"))
        assertEquals(2, discNumberOf("game (CD 2).cue"))
        assertEquals(1, discNumberOf("game (D1).cue"))
        assertEquals(2, discNumberOf("game[d2].cue"))
        assertEquals(1, discNumberOf("Final Fantasy VII Disc 1 of 4.cue"))
    }

    @Test fun `track markers are never disc markers`() {
        assertNull(discNumberOf("Game (Track 01).bin"))
        assertNull(discNumberOf("Game Track 15.bin"))
        assertNull(discNumberOf("game.cue"))
        assertNull(discNumberOf("game.bin"))
    }

    // --- stripDiscMarker ----------------------------------------------

    @Test fun `disc markers are stripped from titles`() {
        assertEquals("Metal Gear Solid", stripDiscMarker("Metal Gear Solid (Disc 1)"))
        assertEquals("Game", stripDiscMarker("Game CD2"))
        assertEquals("Final Fantasy VII", stripDiscMarker("Final Fantasy VII (D1)"))
        assertEquals("Crash Bandicoot", stripDiscMarker("Crash Bandicoot"))
    }

    // --- groupPsxSources -----------------------------------------------

    @Test fun `fifteen tracks on one cue are one disc - test E`() {
        val bins = (1..15).map { "Game (Track %02d).bin".format(it) }
        val input = groupPsxSources(listOf("Game.cue") + bins, "Game")
        val discs = (input as PsxGameInput.CueDiscs).discs
        assertEquals(1, discs.size)
        assertEquals("Game.cue", discs.single().cueName)
    }

    @Test fun `two cue discs are two physical discs sorted by number`() {
        val input = groupPsxSources(
            listOf(
                "Game (Disc 2).cue", "Game (Disc 2).bin",
                "Game (Disc 1).cue", "Game (Disc 1).bin",
            ),
            "Game",
        ) as PsxGameInput.CueDiscs
        assertEquals(2, input.discs.size)
        assertEquals(listOf(1, 2), input.discs.map { it.discNumber })
        assertEquals("Game (Disc 1).cue", input.discs[0].cueName)
    }

    @Test fun `all disc number spellings group correctly`() {
        val input = groupPsxSources(
            listOf("G (D1).cue", "G CD2.cue", "G_Disk 3.cue"),
            "G",
        ) as PsxGameInput.CueDiscs
        assertEquals(listOf(1, 2, 3), input.discs.map { it.discNumber })
    }

    @Test fun `chd inputs group as discs without conversion`() {
        val input = groupPsxSources(
            listOf("Game (Disc 2).chd", "Game (Disc 1).chd"),
            "Game",
        ) as PsxGameInput.Chds
        assertEquals(2, input.discs.size)
        assertTrue(input.discs.all { it.cueName == null })
    }

    @Test fun `pbp installs as-is`() {
        val input = groupPsxSources(listOf("Game.pbp"), "Game")
        assertEquals(PsxGameInput.SinglePbp("Game", "Game.pbp"), input)
    }

    @Test fun `lone iso installs as-is`() {
        val input = groupPsxSources(listOf("Game.iso"), "Game")
        assertEquals(PsxGameInput.LoneImage("Game", "Game.iso"), input)
    }

    @Test fun `sbi is associated with its disc, never a game`() {
        val input = groupPsxSources(
            listOf("Game.cue", "Game.bin", "Game.sbi", "readme.txt"),
            "Game",
        ) as PsxGameInput.CueDiscs
        assertEquals("Game.sbi", input.discs.single().sbiName)
        // An unrelated text file does not become a disc or a game.
        assertEquals(1, input.discs.size)
    }

    @Test fun `cue wins over a stray bin`() {
        val input = groupPsxSources(listOf("Game.cue", "Game.bin"), "Game")
        assertTrue(input is PsxGameInput.CueDiscs)
    }

    @Test fun `nothing usable is null`() {
        assertNull(groupPsxSources(listOf("readme.txt", "cover.jpg"), "Game"))
        assertNull(groupPsxSources(emptyList(), "Game"))
    }

    // --- esdePsxEntries -------------------------------------------------

    @Test fun `single chd is one frontend entry`() {
        assertEquals(listOf("Crash Bandicoot"), esdePsxEntries(listOf("Crash Bandicoot.chd"), emptyList()))
    }

    @Test fun `m3u directory is one frontend entry`() {
        assertEquals(
            listOf("Metal Gear Solid"),
            esdePsxEntries(emptyList(), listOf("Metal Gear Solid.m3u")),
        )
    }

    @Test fun `existing chd library is one entry per game`() {
        assertEquals(
            listOf("Crash Bandicoot", "Silent Hill"),
            esdePsxEntries(listOf("Crash Bandicoot.chd", "Silent Hill.chd"), emptyList()),
        )
    }

    @Test fun `raw cue plus track bins are many entries - the bug being fixed`() {
        val entries = esdePsxEntries(
            listOf("Game.cue", "Game (Track 01).bin", "Game (Track 02).bin", "Game (Track 03).bin"),
            emptyList(),
        )
        // This documents WHY the pipeline normalizes: the old layout
        // showed every track as its own game.
        assertEquals(4, entries.size)
    }
}
