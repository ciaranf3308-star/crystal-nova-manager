package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueParserTest {

    @Test fun `quoted file lines are extracted in order`() {
        val cue = """
            FILE "Game (Track 01).bin" BINARY
              TRACK 01 MODE1/2352
                INDEX 01 00:00:00
            FILE "Game (Track 02).bin" BINARY
              TRACK 02 AUDIO
                INDEX 00 00:00:00
                INDEX 01 00:02:00
        """.trimIndent()
        assertEquals(
            listOf("Game (Track 01).bin", "Game (Track 02).bin"),
            CueParser.referencedFiles(cue),
        )
    }

    @Test fun `unquoted file lines are extracted`() {
        val cue = "FILE game.bin BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n"
        assertEquals(listOf("game.bin"), CueParser.referencedFiles(cue))
    }

    @Test fun `directive matching is case-insensitive`() {
        val cue = "file \"a.bin\" binary\nFILE \"b.bin\" BINARY\n  track 01 MODE1/2352\n"
        assertEquals(listOf("a.bin", "b.bin"), CueParser.referencedFiles(cue))
    }

    @Test fun `non-file lines are ignored`() {
        val cue = """
            REM Single-Disc
            TRACK 01 MODE1/2352
              INDEX 01 00:00:00
            POSTGAP 00:02:00
        """.trimIndent()
        assertTrue(CueParser.referencedFiles(cue).isEmpty())
    }

    @Test fun `empty sheet yields no files`() {
        assertTrue(CueParser.referencedFiles("").isEmpty())
    }
}
