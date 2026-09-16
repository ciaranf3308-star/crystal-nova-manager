package io.crystalnova.manager.pegasus

import org.junit.Assert.*
import org.junit.Test

class MetafileGeneratorTest {

    private fun retroArchGba() = listOf(
        "am start --user 0",
        "-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture",
        "-e ROM \"{file.path}\"",
        "-e LIBRETRO /data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so",
        "-e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
        "-e QUITFOCUS",
        "--activity-single-top",
    )

    @Test
    fun generate_exactFormatMatchesSpec() {
        val out = MetafileGenerator.generate(
            listOf(
                MetafileGenerator.Collection(
                    name = "Game Boy Advance",
                    shortname = "gba",
                    launchLines = retroArchGba(),
                    games = listOf(
                        MetafileGenerator.Game(
                            "Mario Golf - Advance Tour (E)",
                            listOf("/storage/emulated/0/ROMs/gba/Mario Golf - Advance Tour (E).gba"),
                        ),
                        MetafileGenerator.Game(
                            "Multi File Game",
                            listOf(
                                "/storage/emulated/0/ROMs/psx/game.cue",
                                "/storage/emulated/0/ROMs/psx/game2.cue",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val expected = """
            collection: Game Boy Advance
            shortname: gba
            launch: am start --user 0
              -n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture
              -e ROM "{file.path}"
              -e LIBRETRO /data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so
              -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg
              -e QUITFOCUS
              --activity-single-top

            game: Mario Golf - Advance Tour (E)
            file: /storage/emulated/0/ROMs/gba/Mario Golf - Advance Tour (E).gba

            game: Multi File Game
            files:
              /storage/emulated/0/ROMs/psx/game.cue
              /storage/emulated/0/ROMs/psx/game2.cue
            """.trimIndent() + "\n"
        assertEquals(expected, out)
    }

    @Test
    fun generate_collectionsSortedCaseInsensitive_andGamesSorted() {
        val out = MetafileGenerator.generate(
            listOf(
                MetafileGenerator.Collection(
                    name = "Super Nintendo", shortname = "snes",
                    launchLines = listOf("x"), games = listOf(MetafileGenerator.Game("zeta", listOf("/storage/emulated/0/roms/snes/zeta.smc"))),
                ),
                MetafileGenerator.Collection(
                    name = "Game Boy Advance", shortname = "gba",
                    launchLines = listOf("x"),
                    games = listOf(
                        MetafileGenerator.Game("Mario", listOf("/storage/emulated/0/roms/gba/mario.gba")),
                        MetafileGenerator.Game("zelda", listOf("/storage/emulated/0/roms/gba/zelda.gba")),
                    ),
                ),
            ),
        )
        val collIdx = out.indexOf("collection: Game Boy Advance")
        val snesIdx = out.indexOf("collection: Super Nintendo")
        assertTrue(collIdx in 0 until snesIdx)
        val marioIdx = out.indexOf("game: Mario")
        val zeldaIdx = out.indexOf("game: zelda")
        assertTrue(marioIdx in 0 until zeldaIdx)
        // Blank line between collections and between games.
        assertTrue(out.contains("file: /storage/emulated/0/roms/gba/zelda.gba\n\ncollection: Super Nintendo"))
        assertTrue(out.contains("file: /storage/emulated/0/roms/gba/mario.gba\n\ngame: zelda"))
    }

    @Test
    fun generate_removableStoragePathsAreCanonical() {
        val out = MetafileGenerator.generate(
            listOf(
                MetafileGenerator.Collection(
                    name = "PlayStation",
                    shortname = "psx",
                    launchLines = listOf("am start --user 0"),
                    games = listOf(
                        MetafileGenerator.Game(
                            "Game",
                            listOf("/storage/1A2B-3C4D/ROMs/psx/game.cue"),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(out.contains("file: /storage/1A2B-3C4D/ROMs/psx/game.cue"))
        assertFalse(out.contains("content://"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun generate_collectionWithoutLaunch_fails() {
        MetafileGenerator.generate(
            listOf(
                MetafileGenerator.Collection(
                    name = "X", shortname = "x", launchLines = emptyList(),
                    games = listOf(MetafileGenerator.Game("G", listOf("/a"))),
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun generate_gameWithoutFiles_fails() {
        MetafileGenerator.generate(
            listOf(
                MetafileGenerator.Collection(
                    name = "X", shortname = "x", launchLines = listOf("x"),
                    games = listOf(MetafileGenerator.Game("G", emptyList())),
                ),
            ),
        )
    }

    @Test
    fun displayTitle() {
        assertEquals("Mario Golf - Advance Tour (E)", MetafileGenerator.displayTitle("Mario Golf - Advance Tour (E).gba"))
        assertEquals("Sonic The Hedgehog", MetafileGenerator.displayTitle("Sonic_The_Hedgehog.gen"))
        assertEquals("Doom", MetafileGenerator.displayTitle("Doom.zip"))
        assertEquals("Final Fantasy VII", MetafileGenerator.displayTitle("  Final   Fantasy_VII .iso"))
    }

    @Test
    fun shortnameFor_officialShortnames() {
        assertEquals("gba", MetafileGenerator.shortnameFor("gba"))
        assertEquals("3ds", MetafileGenerator.shortnameFor("n3ds"))
        assertEquals("gc", MetafileGenerator.shortnameFor("gamecube"))
        assertEquals("atarilynx", MetafileGenerator.shortnameFor("lynx"))
        assertEquals("psx", MetafileGenerator.shortnameFor("psx"))
        assertEquals("ps2", MetafileGenerator.shortnameFor("ps2"))
        assertEquals("psp", MetafileGenerator.shortnameFor("psp"))
    }

    @Test
    fun shortnameFor_unknownSlug_fallsBackToSlug() {
        assertEquals("newconsole", MetafileGenerator.shortnameFor("newconsole"))
    }

    @Test
    fun baseTitle_stripsDiscMarkers() {
        assertEquals("Final Fantasy VII", MetafileGenerator.baseTitle("Final Fantasy VII (Disc 1)"))
        assertEquals("Final Fantasy VII", MetafileGenerator.baseTitle("Final Fantasy VII (Disc 2)"))
        assertEquals("Metal Gear Solid", MetafileGenerator.baseTitle("Metal Gear Solid - Disk 1"))
        assertEquals("Game", MetafileGenerator.baseTitle("Game CD3"))
        assertEquals("Game", MetafileGenerator.baseTitle("Game (CD 1)"))
        assertEquals("Game", MetafileGenerator.baseTitle("Game [Side 2]"))
    }

    @Test
    fun baseTitle_noMarker_unchanged() {
        assertEquals("Doom", MetafileGenerator.baseTitle("Doom"))
        assertEquals("Mario Golf - Advance Tour (E)", MetafileGenerator.baseTitle("Mario Golf - Advance Tour (E)"))
        // A bare number that is not a disc marker stays.
        assertEquals("1942", MetafileGenerator.baseTitle("1942"))
    }

    private fun generateWithPath(path: String): String =
        MetafileGenerator.generate(
            listOf(
                MetafileGenerator.Collection(
                    name = "X", shortname = "x", launchLines = listOf("x"),
                    games = listOf(MetafileGenerator.Game("G", listOf(path))),
                ),
            ),
        )

    @Test(expected = IllegalArgumentException::class)
    fun generate_rejectsContentUri() {
        generateWithPath("content://com.example/tree/roms/game.gba")
    }

    @Test(expected = IllegalArgumentException::class)
    fun generate_rejectsNonStorageRoot() {
        generateWithPath("/sdcard/roms/game.gba")
    }

    @Test(expected = IllegalArgumentException::class)
    fun generate_rejectsRelativePath() {
        generateWithPath("roms/game.gba")
    }

    @Test(expected = IllegalArgumentException::class)
    fun generate_rejectsTraversal() {
        generateWithPath("/storage/emulated/0/../etc/passwd")
    }

    @Test(expected = IllegalArgumentException::class)
    fun generate_rejectsLineBreakInjection() {
        generateWithPath("/storage/emulated/0/roms/bad\nfile: injected")
    }

    @Test(expected = IllegalArgumentException::class)
    fun generate_rejectsBlankPath() {
        generateWithPath("   ")
    }

    @Test
    fun organizeGames_cueBin_yieldsCueOnly() {
        val games = MetafileGenerator.organizeGames(
            listOf(
                MetafileGenerator.RawEntry("Game", "/storage/emulated/0/roms/psx/game.cue"),
                MetafileGenerator.RawEntry("Game", "/storage/emulated/0/roms/psx/game.bin"),
            ),
        )
        assertEquals(1, games.size)
        assertEquals(listOf("/storage/emulated/0/roms/psx/game.cue"), games[0].files)
    }

    @Test
    fun organizeGames_loneBinWithoutCue_staysAGame() {
        val games = MetafileGenerator.organizeGames(
            listOf(MetafileGenerator.RawEntry("Game", "/storage/emulated/0/roms/psx/game.bin")),
        )
        assertEquals(1, games.size)
        assertEquals(listOf("/storage/emulated/0/roms/psx/game.bin"), games[0].files)
    }

    @Test
    fun organizeGames_m3u_suppressesListedDiscs() {
        val games = MetafileGenerator.organizeGames(
            listOf(
                MetafileGenerator.RawEntry("RPG", "/storage/emulated/0/roms/psx/rpg.m3u"),
                MetafileGenerator.RawEntry("RPG (Disc 1)", "/storage/emulated/0/roms/psx/rpg (disc 1).cue"),
                MetafileGenerator.RawEntry("RPG (Disc 1)", "/storage/emulated/0/roms/psx/rpg (disc 1).bin"),
            ),
        )
        assertEquals(1, games.size)
        assertEquals(listOf("/storage/emulated/0/roms/psx/rpg.m3u"), games[0].files)
    }

    @Test
    fun organizeGames_m3u_doesNotSuppressUnrelatedPrefixMatch() {
        val games = MetafileGenerator.organizeGames(
            listOf(
                MetafileGenerator.RawEntry("Mega Man", "/storage/emulated/0/roms/psx/mega man.m3u"),
                MetafileGenerator.RawEntry("Mega Man X", "/storage/emulated/0/roms/psx/mega man x.cue"),
            ),
        )
        assertEquals(2, games.size)
    }

    @Test
    fun organizeGames_gdi_suppressesNumberedTracks() {
        val games = MetafileGenerator.organizeGames(
            listOf(
                MetafileGenerator.RawEntry("Crazy Taxi", "/storage/emulated/0/roms/dc/crazy.gdi"),
                MetafileGenerator.RawEntry("track01", "/storage/emulated/0/roms/dc/track01.bin"),
                MetafileGenerator.RawEntry("track02", "/storage/emulated/0/roms/dc/track02.bin"),
            ),
        )
        assertEquals(1, games.size)
        assertEquals(listOf("/storage/emulated/0/roms/dc/crazy.gdi"), games[0].files)
    }

    @Test
    fun organizeGames_numberedTracksNeverBecomeGames() {
        val games = MetafileGenerator.organizeGames(
            listOf(
                MetafileGenerator.RawEntry(
                    "Game (Track 1)",
                    "/storage/emulated/0/roms/psx/game (Track 1).bin",
                ),
            ),
        )
        assertTrue(games.isEmpty())
    }

    @Test
    fun organizeGames_trackInNameOnly_survives() {
        val games = MetafileGenerator.organizeGames(
            listOf(MetafileGenerator.RawEntry("Soundtrack 5", "/storage/emulated/0/roms/psx/soundtrack 5.bin")),
        )
        assertEquals(1, games.size)
    }

    @Test
    fun organizeGames_multiDisc_yieldsOneGameWithFilesList() {
        val games = MetafileGenerator.organizeGames(
            listOf(
                MetafileGenerator.RawEntry(
                    "Final Fantasy VII (Disc 2)",
                    "/storage/emulated/0/roms/psx/ff7-2.cue",
                ),
                MetafileGenerator.RawEntry(
                    "Final Fantasy VII (Disc 1)",
                    "/storage/emulated/0/roms/psx/ff7-1.cue",
                ),
            ),
        )
        assertEquals(1, games.size)
        assertEquals("Final Fantasy VII", games[0].title)
        assertEquals(
            listOf(
                "/storage/emulated/0/roms/psx/ff7-1.cue",
                "/storage/emulated/0/roms/psx/ff7-2.cue",
            ),
            games[0].files,
        )
        val out = MetafileGenerator.generate(
            listOf(
                MetafileGenerator.Collection(
                    name = "PlayStation", shortname = "psx",
                    launchLines = listOf("launch psx"), games = games,
                ),
            ),
        )
        assertTrue(out.contains("files:\n  /storage/emulated/0/roms/psx/ff7-1.cue\n  /storage/emulated/0/roms/psx/ff7-2.cue\n"))
    }
}
