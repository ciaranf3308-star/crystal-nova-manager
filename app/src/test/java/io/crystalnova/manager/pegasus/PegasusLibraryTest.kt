package io.crystalnova.manager.pegasus

import io.crystalnova.manager.storage.StorageLocations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PegasusLibraryTest {

    private val romUri = "content://com.example/tree/roms"

    private fun library(prefs: FakePrefs = FakePrefs()): PegasusLibrary {
        val lib = PegasusLibrary(
            context = null,
            prefs = prefs,
            locations = StorageLocations(null, prefs) { _, _, _ -> },
            logger = { _, _, _ -> },
        )
        // JVM-test seams: DocumentFile / PackageManager are unavailable here.
        lib.checkRomAccess = { true }
        lib.checkRomWritable = { true }
        lib.describeUri = { "DISPLAY/$it" }
        // In-memory stand-in for the SAF ROM root: writer stores,
        // reader reads back — the same round trip the real SAF path
        // must survive.
        val disk = mutableMapOf<String, ByteArray>()
        lib.writer = { uri, bytes -> disk[uri] = bytes; true }
        lib.metafileReader = { uri -> disk[uri] }
        return lib
    }

    /** v19: the ROM root is the write target, persisted as the ROM tree URI. */
    private fun adoptRomRoot(prefs: FakePrefs) {
        prefs.putString(StorageLocations.KEY_ROM_TREE_URI, romUri)
    }

    private fun game(slug: String, label: String, title: String, path: String) =
        PegasusLibrary.ScannedGame(slug, label, title, path)

    @Test
    fun metafileFileName_matchesPegasusGameDirScannerPattern() {
        // Pegasus's is_metadata_file() accepts `*.metadata.pegasus.txt`
        // at the top level of a registered game dir.
        assertEquals("crystal-nova.metadata.pegasus.txt", MetafileGenerator.FILE_NAME)
        assertTrue(
            "crystal-nova.metadata.pegasus.txt".matches(Regex(""".*\.metadata\.pegasus\.txt""")),
        )
        assertTrue(
            "crystal-nova.metadata.pegasus.txt".matches(Regex(""".*\.metadata\.txt""")),
        )
        assertFalse("swap scratch must never look like a metadata file",
            PegasusLibrary.TMP_NAME.matches(Regex(""".*\.metadata\.pegasus\.txt""")),
        )
        assertFalse("backup must never look like a metadata file",
            PegasusLibrary.BACKUP_NAME.matches(Regex(""".*\.metadata\.pegasus\.txt""")),
        )
    }

    @Test
    fun metafileTargetDisplay_showsRomRootPathAndWritability() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        assertEquals("NOT SELECTED", lib.metafileTargetDisplay())

        adoptRomRoot(prefs)
        assertEquals("DISPLAY/$romUri · WRITABLE", lib.metafileTargetDisplay())

        lib.checkRomWritable = { false }
        assertEquals(
            "DISPLAY/$romUri · NOT WRITABLE — RE-PICK ROM ROOT",
            lib.metafileTargetDisplay(),
        )
    }

    @Test
    fun gamePath_joinsRomRootAndRelativePath() {
        val lib = library()
        assertEquals(
            "/storage/emulated/0/ROMs/gba/game.gba",
            lib.gamePath("/storage/emulated/0/ROMs", "gba/game.gba"),
        )
        assertEquals(
            "/storage/1A2B-3C4D/ROMs/psx/game.cue",
            lib.gamePath("/storage/1A2B-3C4D/ROMs", "gba/game.gba"),
        )
    }

    @Test
    fun buildCollections_emitsConfiguredSystemsWithCanonicalPaths() {
        val lib = library()
        val built = lib.buildCollections(
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "/storage/emulated/0/ROMs/gba/mario.gba"),
            ),
        )
        assertEquals(1, built.collections.size)
        val c = built.collections[0]
        assertEquals("Game Boy Advance", c.name)
        assertEquals("gba", c.shortname)
        assertTrue(c.launchLines.any { it.contains("mgba_libretro_android.so") })
        assertEquals(1, c.games.size)
        assertEquals("/storage/emulated/0/ROMs/gba/mario.gba", c.games[0].files[0])
        assertTrue(built.unconfiguredSystems.isEmpty())
        assertTrue(built.unknownFolders.isEmpty())
    }

    @Test
    fun buildCollections_groupsMultiDiscIntoFilesList() {
        val lib = library()
        val built = lib.buildCollections(
            listOf(
                game("psx", "PlayStation", "Final Fantasy VII (Disc 2)", "/storage/emulated/0/ROMs/psx/ff7-2.cue"),
                game("psx", "PlayStation", "Final Fantasy VII (Disc 1)", "/storage/emulated/0/ROMs/psx/ff7-1.cue"),
                game("psx", "PlayStation", "Doom", "/storage/emulated/0/ROMs/psx/doom.cue"),
            ),
        )
        assertEquals(1, built.collections.size)
        val games = built.collections[0].games
        assertEquals(2, games.size)
        // Sorted by title; the multi-disc set is one game with a files: list.
        assertEquals("Doom", games[0].title)
        assertEquals(listOf("/storage/emulated/0/ROMs/psx/doom.cue"), games[0].files)
        assertEquals("Final Fantasy VII", games[1].title)
        assertEquals(
            listOf("/storage/emulated/0/ROMs/psx/ff7-1.cue", "/storage/emulated/0/ROMs/psx/ff7-2.cue"),
            games[1].files,
        )
    }

    @Test
    fun buildCollections_listsUnconfiguredSystems() {
        val lib = library()
        val built = lib.buildCollections(
            listOf(
                game("saturn", "Sega Saturn", "Some Game", "/storage/emulated/0/ROMs/saturn/game.iso"),
            ),
        )
        assertTrue(built.collections.isEmpty())
        assertEquals(listOf("Sega Saturn"), built.unconfiguredSystems)
    }

    @Test
    fun buildCollections_surfacesUnknownFolders() {
        val lib = library()
        val built = lib.buildCollections(
            listOf(
                game("unknown", "Mystery Folder", "Mystery", "/storage/emulated/0/ROMs/mystery/game.zip"),
            ),
        )
        assertTrue(built.collections.isEmpty())
        assertTrue(built.unconfiguredSystems.isEmpty())
        assertEquals(listOf("Mystery Folder"), built.unknownFolders)
    }

    @Test
    fun buildCollections_userOverrideWinsOverDefault() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        lib.profiles.set("gba", LauncherProfile(type = LauncherType.CUSTOM, command = "my-launch"))
        val built = lib.buildCollections(
            listOf(game("gba", "Game Boy Advance", "Game", "/storage/emulated/0/ROMs/gba/game.gba")),
        )
        assertEquals(listOf("my-launch"), built.collections[0].launchLines)
    }

    @Test
    fun buildCollections_userOverrideCanUnconfigureADefault() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        // An unconfigured CUSTOM override suppresses the curated default.
        lib.profiles.set("gba", LauncherProfile(type = LauncherType.CUSTOM))
        val built = lib.buildCollections(
            listOf(game("gba", "Game Boy Advance", "Game", "/storage/emulated/0/ROMs/gba/game.gba")),
        )
        assertTrue(built.collections.isEmpty())
        assertEquals(listOf("Game Boy Advance"), built.unconfiguredSystems)
    }

    @Test
    fun inject_gatedOnRomWritability_refusesWithRepickMessage() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.checkRomWritable = { false }
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "/storage/emulated/0/ROMs/gba/mario.gba"))
        }
        var wrote = false
        lib.writer = { _, _ -> wrote = true; true }
        var read = false
        lib.metafileReader = { read = true; null }

        val outcome = runBlocking { lib.inject() }

        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("ROM ROOT NOT WRITABLE — RE-PICK ROM ROOT TO GRANT WRITE ACCESS"),
            outcome,
        )
        assertFalse("a non-writable ROM root must refuse before any write", wrote)
        assertFalse("a non-writable ROM root must refuse before any read", read)
        assertEquals("NONE", lib.lastInjectSummary())
    }

    @Test
    fun inject_ignoresLegacyConfigPref_writesOnlyToRomRoot() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        // v18's legacy pref is still stored (never silently deleted),
        // but the v19 inject never consults or writes to it.
        prefs.putString("pegasus_config_tree_uri", "content://com.example/tree/legacy")
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "/storage/emulated/0/ROMs/gba/mario.gba"))
        }
        var writtenUri: String? = null
        lib.writer = { uri, bytes -> writtenUri = uri; true }
        lib.metafileReader = { null } // no readback yet: verification fails, but the URI is what matters

        runBlocking { lib.inject() }

        assertEquals("the write target is the ROM root, never the legacy tree", romUri, writtenUri)
    }

    @Test
    fun inject_failsWhenRomAccessLost() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.checkRomAccess = { false }
        val outcome = runBlocking { lib.inject() }
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("ROM LIBRARY NOT AVAILABLE"),
            outcome,
        )
    }

    @Test
    fun inject_failsWhenScanLosesRomGrant() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = { throw SecurityException("revoked") }
        val outcome = runBlocking { lib.inject() }
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("ROM LIBRARY ACCESS LOST — PLEASE RESELECT"),
            outcome,
        )
    }

    @Test
    fun inject_failsWhenNothingConfigured() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("saturn", "Sega Saturn", "Some Game", "/storage/emulated/0/ROMs/saturn/game.iso"))
        }
        var wrote = false
        lib.writer = { _, _ -> wrote = true; true }
        val outcome = runBlocking { lib.inject() }
        // Zero injectable systems: fail rather than writing an empty library.
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed(
                "NO LAUNCHER: SEGA SATURN — CONFIGURE LAUNCHERS FIRST",
            ),
            outcome,
        )
        assertFalse("nothing may be written when a launcher is missing", wrote)
    }

    @Test
    fun inject_skipsUnconfiguredSystemsAndInjectsConfigured() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        // gba has a curated default launcher; saturn is populated but
        // unconfigured — progressive setup must not block the GBA inject.
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "/storage/emulated/0/ROMs/gba/mario.gba"),
                game("saturn", "Sega Saturn", "Some Game", "/storage/emulated/0/ROMs/saturn/game.iso"),
            )
        }
        var writtenBytes: ByteArray? = null
        lib.writer = { _, bytes -> writtenBytes = bytes; true }
        lib.metafileReader = { writtenBytes } // read back what the writer wrote

        val outcome = runBlocking { lib.inject() }

        val ok = outcome as PegasusLibrary.InjectOutcome.Ok
        assertEquals(1, ok.collections)
        assertEquals(1, ok.games)
        assertEquals(listOf("Sega Saturn"), ok.skippedNoLauncher)
        assertTrue(ok.unknownFolders.isEmpty())

        val text = writtenBytes!!.toString(Charsets.UTF_8)
        assertTrue("gba game must be in the metafile", text.contains("mario.gba"))
        assertFalse("unconfigured saturn game must not be in the metafile", text.contains("game.iso"))
    }

    @Test
    fun inject_ok_writesToRomRootTopLevelWithCanonicalMetafile() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        // n3ds has no curated default: the user configures it explicitly.
        lib.profiles.set(
            "n3ds",
            LauncherProfile(type = LauncherType.CUSTOM, command = "citra {file.path}"),
        )
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "/storage/emulated/0/ROMs/gba/mario.gba"),
                game("psx", "PlayStation", "Final Fantasy VII (Disc 1)", "/storage/emulated/0/ROMs/psx/ff7-1.cue"),
                game("psx", "PlayStation", "Final Fantasy VII (Disc 2)", "/storage/emulated/0/ROMs/psx/ff7-2.cue"),
                game("n3ds", "Nintendo 3DS", "Some Game", "/storage/emulated/0/ROMs/n3ds/game.3ds"),
                game("unknown", "Mystery Folder", "Mystery", "/storage/emulated/0/ROMs/mystery/game.zip"),
            )
        }
        var writtenUri: String? = null
        var writtenBytes: ByteArray? = null
        lib.writer = { uri, bytes -> writtenUri = uri; writtenBytes = bytes; true }
        lib.metafileReader = { writtenBytes } // read back what the writer wrote

        val outcome = runBlocking { lib.inject() }

        val ok = outcome as PegasusLibrary.InjectOutcome.Ok
        assertEquals(3, ok.collections)
        assertEquals(3, ok.games) // 1 gba + 1 grouped psx multi-disc game + 1 n3ds
        assertEquals(listOf("Mystery Folder"), ok.unknownFolders)

        // v19: the single write goes to the ROM root (top level), never
        // to a legacy <config>/metafiles/ dir.
        assertEquals(romUri, writtenUri)
        val text = writtenBytes!!.toString(Charsets.UTF_8)
        assertFalse("no content:// may leak into the metafile", text.contains("content://"))
        assertFalse("unknown folders are excluded", text.contains("Mystery"))
        assertTrue(text.contains("collection: Game Boy Advance"))
        assertTrue(text.contains("shortname: gba"))
        assertTrue(text.contains("file: /storage/emulated/0/ROMs/gba/mario.gba"))
        assertTrue(text.contains("collection: PlayStation"))
        assertTrue(text.contains("shortname: psx"))
        assertTrue(text.contains("game: Final Fantasy VII"))
        assertTrue(text.contains("  /storage/emulated/0/ROMs/psx/ff7-1.cue"))
        assertTrue(text.contains("citra {file.path}"))
        // Blank line between collections (sorted: Game Boy Advance, then Nintendo 3DS, then PlayStation).
        assertTrue(text.contains("file: /storage/emulated/0/ROMs/gba/mario.gba\n\ncollection: Nintendo 3DS"))
    }

    @Test
    fun inject_badGamePath_reportsFailed() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Game", "content://com.example/tree/roms/game.gba"))
        }
        var wrote = false
        lib.writer = { _, _ -> wrote = true; true }
        val outcome = runBlocking { lib.inject() }
        assertTrue(outcome is PegasusLibrary.InjectOutcome.Failed)
        assertTrue((outcome as PegasusLibrary.InjectOutcome.Failed).message.startsWith("BAD GAME PATH"))
        assertFalse("nothing may be written when a path is bad", wrote)
    }

    @Test
    fun inject_writerFailure_reportsFailed() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Game", "/storage/emulated/0/ROMs/gba/game.gba"))
        }
        lib.writer = { _, _ -> throw RuntimeException("disk gone") }
        val outcome = runBlocking { lib.inject() }
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("WRITE FAILED — CHECK THE ROM ROOT GRANT"),
            outcome,
        )
    }

    @Test
    fun inject_readbackMismatch_fails() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Game", "/storage/emulated/0/ROMs/gba/game.gba"))
        }
        lib.metafileReader = { "tampered".toByteArray() }
        val outcome = runBlocking { lib.inject() }
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("METAFILE VERIFY FAILED — READBACK DID NOT MATCH"),
            outcome,
        )
        assertEquals("NONE", lib.lastInjectSummary())
    }

    @Test
    fun inject_readbackMissing_fails() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Game", "/storage/emulated/0/ROMs/gba/game.gba"))
        }
        lib.metafileReader = { null }
        val outcome = runBlocking { lib.inject() }
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("METAFILE VERIFY FAILED — READBACK DID NOT MATCH"),
            outcome,
        )
        assertEquals("NONE", lib.lastInjectSummary())
    }

    @Test
    fun inject_success_persistsLastInjectCountsAndMetafileStatus() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        assertEquals("NONE", lib.lastInjectSummary())
        assertEquals(PegasusLibrary.MetafileStatus(false, null), lib.metafileStatus())

        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "/storage/emulated/0/ROMs/gba/mario.gba"),
                game("psx", "PlayStation", "Doom", "/storage/emulated/0/ROMs/psx/doom.cue"),
            )
        }
        val outcome = runBlocking { lib.inject() }
        val ok = outcome as PegasusLibrary.InjectOutcome.Ok
        assertEquals(2, ok.collections)
        assertEquals(2, ok.games)

        // Only a VERIFIED inject persists counts.
        assertEquals("2 SYSTEMS · 2 GAMES", lib.lastInjectSummary())
        val status = lib.metafileStatus()
        assertTrue(status.present)
        assertTrue("metafile must be non-empty", (status.bytes ?: 0) > 0)
    }

    @Test
    fun parseMetafileSummary_countsCollectionsAndGames() {
        val summary = parseMetafileSummary(
            "collection: Game Boy Advance\n" +
                "shortname: gba\n" +
                "launch: am start --user 0\n" +
                "  -n com.example/.Main\n" +
                "\n" +
                "game: Mario Golf\n" +
                "file: /storage/emulated/0/ROMs/gba/mario.gba\n" +
                "\n" +
                "collection: PlayStation\n" +
                "shortname: psx\n" +
                "launch: am start --user 0\n" +
                "\n" +
                "game: Final Fantasy VII\n" +
                "files:\n" +
                "  /storage/emulated/0/ROMs/psx/ff7-1.cue\n" +
                "  /storage/emulated/0/ROMs/psx/ff7-2.cue\n",
        )
        assertEquals(2, summary.collections)
        assertEquals(2, summary.games)
        assertEquals("Game Boy Advance", summary.firstCollection)
        assertEquals("/storage/emulated/0/ROMs/gba/mario.gba", summary.firstRomPath)
    }

    @Test
    fun parseMetafileSummary_firstRomPathFromFilesList() {
        // A multi-disc-first library: the first game emits a files: list.
        val summary = parseMetafileSummary(
            "collection: PlayStation\n" +
                "shortname: psx\n" +
                "launch: am start --user 0\n" +
                "\n" +
                "game: Final Fantasy VII\n" +
                "files:\n" +
                "  /storage/emulated/0/ROMs/psx/ff7-1.cue\n" +
                "  /storage/emulated/0/ROMs/psx/ff7-2.cue\n",
        )
        assertEquals(1, summary.collections)
        assertEquals(1, summary.games)
        assertEquals("PlayStation", summary.firstCollection)
        assertEquals("/storage/emulated/0/ROMs/psx/ff7-1.cue", summary.firstRomPath)
    }

    @Test
    fun metafileSummary_parsesVerifiedBuildForDiagnostics() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "/storage/emulated/0/ROMs/gba/mario.gba"),
                game("psx", "PlayStation", "Doom", "/storage/emulated/0/ROMs/psx/doom.cue"),
            )
        }
        runBlocking { lib.inject() }

        val summary = lib.metafileSummary()
        assertNotNull(summary)
        assertEquals(2, summary!!.collections)
        assertEquals(2, summary.games)
        assertEquals("Game Boy Advance", summary.firstCollection)
        assertEquals("/storage/emulated/0/ROMs/gba/mario.gba", summary.firstRomPath)
    }

    @Test
    fun metafileSummary_nullWhenMetafileAbsent() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.metafileReader = { null }
        assertNull(lib.metafileSummary())
    }
}
