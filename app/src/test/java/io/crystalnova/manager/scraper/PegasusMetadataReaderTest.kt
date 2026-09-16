package io.crystalnova.manager.scraper

import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.provider.PegasusFileMetadataProvider
import io.crystalnova.manager.scraper.provider.PegasusMetadataReader
import io.crystalnova.manager.scraper.provider.ScrapeQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Round-trip tests for the Manager's Pegasus metadata generator output:
 * official syntax (`game:` = title, `file:` / `files:` = ROM files,
 * collection header, `launch:`), multiple collections, special-character
 * titles, and the preserved legacy/simple forms.
 */
class PegasusMetadataReaderTest {

    private fun generatedSingleFile() = """
        collection: Game Boy Advance
        shortname: gba
        launch: am start -n io.crystalnova.pegasus/.GameActivity --es romFile {file.path}

        game: Mario Golf: Advance Tour
        file: /storage/XXXX/ROMs/gba/Mario Golf (E).gba
        developer: Camelot Software Planning
        publisher: Nintendo
        genre: Sports
        players: 1-4
        release: 2004-07-22
        description: Tee off on the fairway in this handheld golf RPG.

        game: Pokémon "FireRed" Version — Special Édition?
        file: /storage/XXXX/ROMs/gba/Pokemon FireRed (U).gba
        publisher: Nintendo

    """.trimIndent()

    @Test fun `round trip single-file generated metadata`() {
        val entries = PegasusMetadataReader.parse(generatedSingleFile())
        assertEquals(2, entries.size)

        val first = entries[0]
        assertEquals("Mario Golf: Advance Tour", first.title)
        assertEquals(listOf("/storage/XXXX/ROMs/gba/Mario Golf (E).gba"), first.files)
        assertEquals("Camelot Software Planning", first.developer)
        assertEquals("Nintendo", first.publisher)
        assertEquals("Sports", first.genre)
        assertEquals("1-4", first.players)
        assertEquals("2004-07-22", first.release)
        assertEquals("Tee off on the fairway in this handheld golf RPG.", first.description)
        assertEquals("Game Boy Advance", first.collection)
        assertEquals("gba", first.shortname)
        assertEquals(
            "am start -n io.crystalnova.pegasus/.GameActivity --es romFile {file.path}",
            first.launch,
        )
    }

    @Test fun `round trip multi-file files list`() {
        val text = """
            collection: PlayStation
            shortname: psx

            game: Final Fantasy VII
            files:
              /storage/XXXX/ROMs/psx/Final Fantasy VII (Disc 1).cue
              /storage/XXXX/ROMs/psx/Final Fantasy VII (Disc 2).cue
              /storage/XXXX/ROMs/psx/Final Fantasy VII (Disc 3).cue
            publisher: Square

            game: Single Disc Game
            file: /storage/XXXX/ROMs/psx/single.cue

        """.trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(2, entries.size)
        val multi = entries[0]
        assertEquals("Final Fantasy VII", multi.title)
        assertEquals(
            listOf(
                "/storage/XXXX/ROMs/psx/Final Fantasy VII (Disc 1).cue",
                "/storage/XXXX/ROMs/psx/Final Fantasy VII (Disc 2).cue",
                "/storage/XXXX/ROMs/psx/Final Fantasy VII (Disc 3).cue",
            ),
            multi.files,
        )
        assertEquals("psx", multi.shortname)

        // Matching works against any disc of a multi-file game.
        assertSame(multi, PegasusMetadataReader.match(entries, "Final Fantasy VII (Disc 2).cue"))
        assertSame(multi, PegasusMetadataReader.match(entries, "/other/path/Final Fantasy VII (Disc 3).cue"))
        // ...and the single-file neighbour still matches.
        val single = entries[1]
        assertSame(single, PegasusMetadataReader.match(entries, "single.cue"))
    }

    @Test fun `round trip multiple collections in one file`() {
        val text = """
            collection: Game Boy Advance
            shortname: gba
            launch: gba-launch

            game: GBA Game One
            file: gba/one.gba

            collection: Super Nintendo
            shortname: snes
            launch: snes-launch

            game: SNES Game One
            file: snes/one.smc

            game: SNES Game Two
            file: snes/two.smc
            launch: per-game-launch

        """.trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(3, entries.size)

        assertEquals("gba", entries[0].shortname)
        assertEquals("Game Boy Advance", entries[0].collection)
        assertEquals("gba-launch", entries[0].launch)

        assertEquals("snes", entries[1].shortname)
        assertEquals("Super Nintendo", entries[1].collection)
        assertEquals("snes-launch", entries[1].launch)

        // Game-level launch overrides the collection launch.
        assertEquals("snes", entries[2].shortname)
        assertEquals("per-game-launch", entries[2].launch)
    }

    @Test fun `titles with special characters survive`() {
        val text = """
            game: Pokémon: "FireRed" — Édition Spéciale (100%!)
            file: fr.gba

            game: It's a "Test": C++ & C# <tag>
            file: t.gba

        """.trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(2, entries.size)
        assertEquals("Pokémon: \"FireRed\" — Édition Spéciale (100%!)", entries[0].title)
        assertEquals("It's a \"Test\": C++ & C# <tag>", entries[1].title)
        // Colons inside titles don't confuse the key parser.
        assertEquals(listOf("fr.gba"), entries[0].files)
    }

    @Test fun `legacy game-as-filename with title field still parses`() {
        val text = """
            game: Mario Golf (E).gba
            title: Mario Golf: Advance Tour
            developer: Camelot

        """.trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("Mario Golf: Advance Tour", e.title)
        assertEquals(listOf("Mario Golf (E).gba"), e.files)
        assertEquals("Camelot", e.developer)
        // The ROM still matches by filename.
        assertSame(e, PegasusMetadataReader.match(entries, "Mario Golf (E).gba"))
    }

    @Test fun `legacy game-as-filename without title uses the filename`() {
        val entries = PegasusMetadataReader.parse("game: some game.gba\n\n")
        assertEquals(1, entries.size)
        assertEquals("some game.gba", entries[0].title)
        assertEquals(listOf("some game.gba"), entries[0].files)
    }

    @Test fun `comments and missing trailing blank line are fine`() {
        val text = """
            # generated by Crystal Nova Manager
            collection: GBA
            shortname: gba
            # a game follows
            game: Commented Game
            file: c.gba
            # trailing comment, no blank line at end""".trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(1, entries.size)
        assertEquals("Commented Game", entries[0].title)
        assertEquals("gba", entries[0].shortname)
    }

    @Test fun `multi-line description continuation joins lines`() {
        val text = """
            game: Story Game
            file: s.gba
            description: Line one of the story.
              Line two continues here.
              Line three ends it.

        """.trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(1, entries.size)
        assertEquals(
            "Line one of the story.\nLine two continues here.\nLine three ends it.",
            entries[0].description,
        )
    }

    @Test fun `no files and no legacy filename yields empty file list`() {
        val entries = PegasusMetadataReader.parse("game: Fileless Wonder\ndeveloper: Nobody\n\n")
        assertEquals(1, entries.size)
        assertEquals("Fileless Wonder", entries[0].title)
        assertTrue(entries[0].files.isEmpty())
        assertNull(PegasusMetadataReader.match(entries, "fileless.gba"))
    }

    @Test fun `match is case-insensitive and normalized`() {
        val entries = PegasusMetadataReader.parse(
            "game: Normal Match\nfile: /roms/gba/MARIO GOLF (e).GBA\n\n",
        )
        assertNotNull(PegasusMetadataReader.match(entries, "mario golf (E).gba"))
        // Normalized fallback: punctuation differences still match.
        assertNotNull(PegasusMetadataReader.match(entries, "mario_golf__e.gba"))
    }

    @Test fun `provider lookup returns generated metadata titles`() = runBlocking {
        val provider = PegasusFileMetadataProvider(mapOf("gba" to PegasusMetadataReader.parse(generatedSingleFile())))
        val meta = provider.lookup(
            ScrapeQuery("gba", "Mario Golf Advance Tour", "gba/Mario Golf (E).gba", Region.EUROPE),
        )
        assertNotNull(meta)
        assertEquals("Mario Golf: Advance Tour", meta!!.title)
        assertEquals("Camelot Software Planning", meta.developer)
        assertEquals("Nintendo", meta.publisher)
        assertEquals("Sports", meta.genre)
        assertEquals("1-4", meta.players)
        assertEquals("2004", meta.releaseYear)
        assertEquals("Tee off on the fairway in this handheld golf RPG.", meta.description)
    }

    @Test fun `provider lookup matches a disc of a multi-file game`() = runBlocking {
        val text = """
            game: Final Fantasy VII
            files:
              /roms/psx/Final Fantasy VII (Disc 1).cue
              /roms/psx/Final Fantasy VII (Disc 2).cue

        """.trimIndent()
        val provider = PegasusFileMetadataProvider(mapOf("" to PegasusMetadataReader.parse(text)))
        val meta = provider.lookup(
            ScrapeQuery("psx", "Final Fantasy VII", "Final Fantasy VII (Disc 2).cue", Region.USA),
        )
        assertNotNull(meta)
        assertEquals("Final Fantasy VII", meta!!.title)
    }

    @Test fun `toMetadata maps all structured fields`() {
        val entries = PegasusMetadataReader.parse(generatedSingleFile())
        val meta = PegasusMetadataReader.toMetadata(entries[0])
        assertEquals("Mario Golf: Advance Tour", meta.title)
        assertEquals("Camelot Software Planning", meta.developer)
        assertEquals("Nintendo", meta.publisher)
        assertEquals("2004", meta.releaseYear)
        assertEquals("Sports", meta.genre)
        assertEquals("1-4", meta.players)
        assertNull(meta.providerGameId)
    }

    @Test fun `collection header after games only affects later games`() {
        val text = """
            game: First Game
            file: f.gba

            collection: New Collection
            shortname: nc

            game: Second Game
            file: s.gba

        """.trimIndent()
        val entries = PegasusMetadataReader.parse(text)
        assertEquals(2, entries.size)
        assertNull(entries[0].collection)
        assertNull(entries[0].shortname)
        assertEquals("New Collection", entries[1].collection)
        assertEquals("nc", entries[1].shortname)
    }
}
