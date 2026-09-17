package io.crystalnova.manager.pegasus

import io.crystalnova.manager.storage.StorageLocations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PegasusLibraryTest {

    private val romUri = "content://com.example/tree/roms"
    private val romRoot = "/storage/emulated/0/ROMs"

    /**
     * In-memory fixture for the v20 per-system seams: each system folder
     * is a key in [disk]; the ROM-root and legacy trees are separate
     * maps so tests can assert exactly where every byte landed.
     */
    private class Fixture(val prefs: FakePrefs = FakePrefs()) {
        val lib: PegasusLibrary = PegasusLibrary(
            context = null,
            prefs = prefs,
            locations = StorageLocations(null, prefs) { _, _, _ -> },
            logger = { _, _, _ -> },
        )
        /** folder -> metafile bytes, mirroring <romRoot>/<folder>/. */
        val disk = mutableMapOf<String, ByteArray>()
        /** Names deleted at the ROM root top level (v19 stale global). */
        val romRootDeleted = mutableListOf<String>()
        /** Relative paths deleted under the legacy tree (v18 stale global). */
        val legacyDeleted = mutableListOf<String>()
        /** relativePath -> bytes under the legacy Pegasus tree. */
        val legacyDisk = mutableMapOf<String, ByteArray>()
        /** relativePaths passed to the legacy writer. */
        val legacyWrites = mutableListOf<String>()
        var legacyPicked = false

        init {
            lib.checkRomAccess = { true }
            lib.checkRomWritable = { true }
            lib.describeUri = { "DISPLAY/$it" }
            lib.romRootCanonical = { romRoot }
            lib.systemWriter = { folder, bytes -> disk[folder] = bytes; true }
            lib.systemReader = { folder -> disk[folder] }
            lib.systemDeleter = { folder -> disk.remove(folder) != null }
            lib.romRootDeleter = { name -> romRootDeleted += name; true }
            lib.legacyTreePicked = { legacyPicked }
            lib.legacyRead = { rel -> legacyDisk[rel] }
            lib.legacyWrite = { rel, bytes -> legacyWrites += rel; legacyDisk[rel] = bytes; true }
            lib.legacyDelete = { rel -> legacyDeleted += rel; true }
        }
    }

    private fun library(prefs: FakePrefs = FakePrefs()): PegasusLibrary = Fixture(prefs).lib

    /** v20: the ROM root is the SAF tree we traverse; folders are written per system. */
    private fun adoptRomRoot(prefs: FakePrefs) {
        prefs.putString(StorageLocations.KEY_ROM_TREE_URI, romUri)
    }

    private fun game(slug: String, label: String, title: String, path: String) =
        PegasusLibrary.ScannedGame(slug, label, title, path)

    @Test
    fun metafileFileName_matchesPegasusGameDirScannerPattern() {
        // Pegasus's is_metadata_file() accepts `metadata.pegasus.txt` /
        // `*.metadata.pegasus.txt` at the top level of a registered game
        // dir — our name is in that family, and the swap scratch names
        // are in none of them.
        assertEquals("crystal-nova.metadata.pegasus.txt", MetafileGenerator.FILE_NAME)
        assertTrue(
            "crystal-nova.metadata.pegasus.txt".matches(Regex(""".*\.metadata\.pegasus\.txt""")),
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
        assertEquals("DISPLAY/$romUri · WRITABLE · PER-SYSTEM METAFILES", lib.metafileTargetDisplay())

        lib.checkRomWritable = { false }
        assertEquals(
            "DISPLAY/$romUri · NOT WRITABLE — RE-PICK ROM ROOT · PER-SYSTEM METAFILES",
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
            lib.gamePath("/storage/1A2B-3C4D/ROMs", "psx/game.cue"),
        )
    }

    @Test
    fun systemFolder_derivesExactScannedFolderNotSlug() {
        // Folder aliases must resolve to the PHYSICAL directory: a ps1
        // folder stays "ps1" even though its platform slug is psx.
        assertEquals("ps1", systemFolder("$romRoot", "$romRoot/ps1/game.cue"))
        assertEquals("gba", systemFolder("$romRoot", "$romRoot/gba/mario.gba"))
        assertEquals("gba", systemFolder("$romRoot/", "$romRoot/gba/mario.gba"))
        assertNull("outside the ROM root", systemFolder("$romRoot", "/storage/emulated/0/Other/gba/x.gba"))
        assertNull("bare file at the root has no system folder", systemFolder("$romRoot", "$romRoot/stray.gba"))
    }

    @Test
    fun buildCollections_emitsConfiguredSystemsWithCanonicalPaths() {
        val lib = library()
        val built = lib.buildCollections(
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"),
            ),
        )
        assertEquals(1, built.collections.size)
        val c = built.collections[0]
        assertEquals("Game Boy Advance", c.name)
        assertEquals("gba", c.shortname)
        assertTrue(c.launchLines.any { it.contains("mgba_libretro_android.so") })
        assertEquals(1, c.games.size)
        assertEquals("$romRoot/gba/mario.gba", c.games[0].files[0])
        assertTrue(built.unconfiguredSystems.isEmpty())
        assertTrue(built.unknownFolders.isEmpty())
    }

    @Test
    fun buildCollections_groupsMultiDiscIntoFilesList() {
        val lib = library()
        val built = lib.buildCollections(
            listOf(
                game("psx", "PlayStation", "Final Fantasy VII (Disc 2)", "$romRoot/psx/ff7-2.cue"),
                game("psx", "PlayStation", "Final Fantasy VII (Disc 1)", "$romRoot/psx/ff7-1.cue"),
                game("psx", "PlayStation", "Doom", "$romRoot/psx/doom.cue"),
            ),
        )
        assertEquals(1, built.collections.size)
        val games = built.collections[0].games
        assertEquals(2, games.size)
        // Sorted by title; the multi-disc set is one game with a files: list.
        assertEquals("Doom", games[0].title)
        assertEquals(listOf("$romRoot/psx/doom.cue"), games[0].files)
        assertEquals("Final Fantasy VII", games[1].title)
        assertEquals(
            listOf("$romRoot/psx/ff7-1.cue", "$romRoot/psx/ff7-2.cue"),
            games[1].files,
        )
    }

    @Test
    fun buildCollections_listsUnconfiguredSystems() {
        val lib = library()
        val built = lib.buildCollections(
            listOf(
                game("saturn", "Sega Saturn", "Some Game", "$romRoot/saturn/game.iso"),
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
                game("unknown", "Mystery Folder", "Mystery", "$romRoot/mystery/game.zip"),
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
            listOf(game("gba", "Game Boy Advance", "Game", "$romRoot/gba/game.gba")),
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
            listOf(game("gba", "Game Boy Advance", "Game", "$romRoot/gba/game.gba")),
        )
        assertTrue(built.collections.isEmpty())
        assertEquals(listOf("Game Boy Advance"), built.unconfiguredSystems)
    }

    @Test
    fun inject_gatedOnRomWritability_refusesWithRepickMessage() {
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        lib.checkRomWritable = { false }
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"))
        }
        var wrote = false
        lib.systemWriter = { _, _ -> wrote = true; true }
        var read = false
        lib.systemReader = { read = true; null }

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
    fun inject_writesOneMetafilePerSystemFolder_neverAtRomRoot() {
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"))
        }

        val outcome = runBlocking { lib.inject() }

        assertTrue(outcome is PegasusLibrary.InjectOutcome.Ok)
        // The per-system writer is the ONLY write path: it is never
        // called with the ROM root itself ("") — no global file.
        assertEquals(setOf("gba"), f.disk.keys)
    }

    @Test
    fun inject_deletesStaleManagerOwnedGlobals() {
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        // v18's legacy pref is still stored (never silently deleted).
        f.prefs.putString(PegasusConfig.KEY_TREE_URI, "content://com.example/tree/legacy")
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"))
        }

        val outcome = runBlocking { lib.inject() }

        assertTrue(outcome is PegasusLibrary.InjectOutcome.Ok)
        assertEquals(
            "the v19 ROM-root global must be retired",
            listOf(MetafileGenerator.FILE_NAME),
            f.romRootDeleted,
        )
        assertEquals(
            "the v18 legacy metafiles/ global must be retired",
            listOf("metafiles/${MetafileGenerator.FILE_NAME}"),
            f.legacyDeleted,
        )
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
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(game("saturn", "Sega Saturn", "Some Game", "$romRoot/saturn/game.iso"))
        }
        var wrote = false
        lib.systemWriter = { _, _ -> wrote = true; true }
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
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        // gba has a curated default launcher; saturn is populated but
        // unconfigured — progressive setup must not block the GBA inject.
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"),
                game("saturn", "Sega Saturn", "Some Game", "$romRoot/saturn/game.iso"),
            )
        }

        val outcome = runBlocking { lib.inject() }

        val ok = outcome as PegasusLibrary.InjectOutcome.Ok
        assertEquals(1, ok.collections)
        assertEquals(1, ok.games)
        assertEquals(1, ok.metafiles)
        assertEquals(listOf("Sega Saturn"), ok.skippedNoLauncher)
        assertTrue(ok.unknownFolders.isEmpty())

        assertEquals(setOf("gba"), f.disk.keys)
        val text = f.disk["gba"]!!.toString(Charsets.UTF_8)
        assertTrue("gba game must be in the metafile", text.contains("mario.gba"))
        assertFalse("unconfigured saturn game must not be in the metafile", text.contains("game.iso"))
    }

    @Test
    fun inject_ok_writesOneMetafilePerSystemFolder() {
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        // n3ds has no curated default: the user configures it explicitly.
        lib.profiles.set(
            "n3ds",
            LauncherProfile(type = LauncherType.CUSTOM, command = "citra {file.path}"),
        )
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"),
                game("psx", "PlayStation", "Final Fantasy VII (Disc 1)", "$romRoot/psx/ff7-1.cue"),
                game("psx", "PlayStation", "Final Fantasy VII (Disc 2)", "$romRoot/psx/ff7-2.cue"),
                game("n3ds", "Nintendo 3DS", "Some Game", "$romRoot/n3ds/game.3ds"),
                game("unknown", "Mystery Folder", "Mystery", "$romRoot/mystery/game.zip"),
            )
        }

        val outcome = runBlocking { lib.inject() }

        val ok = outcome as PegasusLibrary.InjectOutcome.Ok
        assertEquals(3, ok.collections)
        assertEquals(3, ok.games) // 1 gba + 1 grouped psx multi-disc game + 1 n3ds
        assertEquals(3, ok.metafiles)
        assertEquals(listOf("Mystery Folder"), ok.unknownFolders)

        // v20: one file per populated system folder, never the ROM root.
        assertEquals(setOf("gba", "psx", "n3ds"), f.disk.keys)
        val gba = f.disk["gba"]!!.toString(Charsets.UTF_8)
        val psx = f.disk["psx"]!!.toString(Charsets.UTF_8)
        val n3ds = f.disk["n3ds"]!!.toString(Charsets.UTF_8)

        // Every file holds exactly one collection — its own.
        assertEquals(1, "collection: ".toRegex().findAll(gba).count())
        assertEquals(1, "collection: ".toRegex().findAll(psx).count())
        assertEquals(1, "collection: ".toRegex().findAll(n3ds).count())
        assertTrue(gba.contains("collection: Game Boy Advance"))
        assertTrue(psx.contains("collection: PlayStation"))
        assertTrue(n3ds.contains("collection: Nintendo 3DS"))

        // No cross-contamination and no unknown folders anywhere.
        assertFalse(gba.contains("PlayStation"))
        assertFalse(psx.contains("Game Boy Advance"))
        assertFalse(gba.contains("citra"))
        for (text in listOf(gba, psx, n3ds)) {
            assertFalse("no content:// may leak into a metafile", text.contains("content://"))
            assertFalse("unknown folders are excluded", text.contains("Mystery"))
        }

        // Per-system content: launch command, file paths, grouped discs.
        assertTrue(gba.contains("shortname: gba"))
        assertTrue(gba.contains("file: $romRoot/gba/mario.gba"))
        assertTrue(psx.contains("game: Final Fantasy VII"))
        assertTrue(psx.contains("  $romRoot/psx/ff7-1.cue"))
        assertTrue(psx.contains("  $romRoot/psx/ff7-2.cue"))
        assertTrue(n3ds.contains("citra {file.path}"))
    }

    @Test
    fun inject_aliasFolder_resolvesToPhysicalFolder() {
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        // The folder is ps1 on disk; the platform slug is psx. The
        // metadata file must land in the physical ps1 folder.
        lib.gameSource = {
            listOf(game("psx", "PlayStation", "Doom", "$romRoot/ps1/doom.cue"))
        }

        val outcome = runBlocking { lib.inject() }

        assertTrue(outcome is PegasusLibrary.InjectOutcome.Ok)
        assertEquals(setOf("ps1"), f.disk.keys)
    }

    @Test
    fun inject_badGamePath_reportsFailed() {
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Game", "content://com.example/tree/roms/game.gba"))
        }
        var wrote = false
        lib.systemWriter = { _, _ -> wrote = true; true }
        val outcome = runBlocking { lib.inject() }
        assertTrue(outcome is PegasusLibrary.InjectOutcome.Failed)
        assertTrue((outcome as PegasusLibrary.InjectOutcome.Failed).message.startsWith("BAD GAME PATH"))
        assertFalse("nothing may be written when a path is bad", wrote)
    }

    @Test
    fun inject_writerFailure_reportsFailedWithFolder() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Game", "$romRoot/gba/game.gba"))
        }
        lib.systemWriter = { _, _ -> throw RuntimeException("disk gone") }
        val outcome = runBlocking { lib.inject() }
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("WRITE FAILED — gba — CHECK THE ROM ROOT GRANT"),
            outcome,
        )
    }

    @Test
    fun inject_readbackMismatch_failsWithFolder() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Game", "$romRoot/gba/game.gba"))
        }
        lib.systemReader = { "tampered".toByteArray() }
        val outcome = runBlocking { lib.inject() }
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("METAFILE VERIFY FAILED — READBACK DID NOT MATCH (gba)"),
            outcome,
        )
        assertEquals("NONE", lib.lastInjectSummary())
    }

    @Test
    fun inject_readbackMissing_failsWithFolder() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Game", "$romRoot/gba/game.gba"))
        }
        lib.systemReader = { null }
        val outcome = runBlocking { lib.inject() }
        assertEquals(
            PegasusLibrary.InjectOutcome.Failed("METAFILE VERIFY FAILED — READBACK DID NOT MATCH (gba)"),
            outcome,
        )
        assertEquals("NONE", lib.lastInjectSummary())
    }

    @Test
    fun inject_removesPhantomSystemMetafiles() {
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"),
                game("psx", "PlayStation", "Doom", "$romRoot/psx/doom.cue"),
            )
        }
        runBlocking { lib.inject() }
        assertEquals(setOf("gba", "psx"), f.disk.keys)

        // psx loses its games: the next BUILD must forget its metafile so
        // no phantom PlayStation collection lingers in Pegasus.
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"))
        }
        val outcome = runBlocking { lib.inject() }
        assertTrue(outcome is PegasusLibrary.InjectOutcome.Ok)
        assertEquals(setOf("gba"), f.disk.keys)
        assertFalse(f.disk.containsKey("psx"))
    }

    @Test
    fun inject_success_persistsLastInjectCountsAndSystemReport() {
        val f = Fixture()
        val lib = f.lib
        assertEquals("NONE", lib.lastInjectSummary())

        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"),
                game("psx", "PlayStation", "Doom", "$romRoot/psx/doom.cue"),
            )
        }
        val outcome = runBlocking { lib.inject() }
        val ok = outcome as PegasusLibrary.InjectOutcome.Ok
        assertEquals(2, ok.collections)
        assertEquals(2, ok.games)
        assertEquals(2, ok.metafiles)

        // Only a VERIFIED inject persists counts.
        assertEquals("2 SYSTEM METAFILES · 2 GAMES", lib.lastInjectSummary())

        val report = lib.systemMetafileReport()
        assertEquals("2 SYSTEM METAFILES · 2 GAMES", report.aggregate)
        assertEquals(2, report.systems.size)
        val gba = report.systems.first { it.folder == "gba" }
        assertTrue(gba.present)
        assertTrue("metafile must be non-empty", (gba.bytes ?: 0) > 0)
        assertEquals(1, gba.games)
        val psx = report.systems.first { it.folder == "psx" }
        assertTrue(psx.present)
        assertEquals(1, psx.games)
    }

    @Test
    fun systemMetafileReport_absentWhenNoBuild() {
        val prefs = FakePrefs()
        val lib = library(prefs)
        adoptRomRoot(prefs)
        val report = lib.systemMetafileReport()
        assertTrue(report.systems.isEmpty())
        assertEquals("0 SYSTEM METAFILES · 0 GAMES", report.aggregate)
        assertEquals(GameDirsOutcome.SkippedNoGrant, report.gameDirs)
    }

    @Test
    fun systemMetafileReport_marksMissingFileNotFound() {
        val f = Fixture()
        val lib = f.lib
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"),
                game("psx", "PlayStation", "Doom", "$romRoot/psx/doom.cue"),
            )
        }
        runBlocking { lib.inject() }
        // The psx metafile vanishes from disk after the build.
        f.disk.remove("psx")

        val report = lib.systemMetafileReport()
        assertEquals("1 SYSTEM METAFILES · 1 GAMES", report.aggregate)
        val psx = report.systems.first { it.folder == "psx" }
        assertFalse(psx.present)
        assertNull(psx.bytes)
    }

    // ------------------------------------------------------------------
    // game_dirs.txt merge
    // ------------------------------------------------------------------

    @Test
    fun mergeGameDirs_createsFileWithHeaderWhenAbsent() {
        val merged = mergeGameDirs(
            null,
            listOf("$romRoot/gba", "$romRoot/psx"),
        ) as GameDirsMerge.Changed
        assertEquals(2, merged.added)
        assertTrue(merged.content.startsWith("# Pegasus game directories"))
        assertTrue(merged.content.contains("$romRoot/gba\n"))
        assertTrue(merged.content.contains("$romRoot/psx\n"))
    }

    @Test
    fun mergeGameDirs_preservesForeignEntriesCommentsAndOrder() {
        val existing = "# my hand-written dirs\n" +
            "\n" +
            "/storage/emulated/0/OtherRoms\n" +
            "$romRoot/gba\n"
        val merged = mergeGameDirs(
            existing,
            listOf("$romRoot/gba", "$romRoot/psx"),
        ) as GameDirsMerge.Changed
        assertEquals(1, merged.added)
        val lines = merged.content.lines()
        // Foreign entries, comments, blank lines, and order preserved;
        // only the missing Crystal path is appended, exactly once.
        assertEquals("# my hand-written dirs", lines[0])
        assertEquals("", lines[1])
        assertEquals("/storage/emulated/0/OtherRoms", lines[2])
        assertEquals("$romRoot/gba", lines[3])
        assertEquals("$romRoot/psx", lines[4])
        assertEquals(1, lines.count { it == "$romRoot/gba" })
    }

    @Test
    fun mergeGameDirs_isIdempotent() {
        val first = mergeGameDirs(null, listOf("$romRoot/gba", "$romRoot/psx"))
        assertTrue(first is GameDirsMerge.Changed)
        val second = mergeGameDirs(
            (first as GameDirsMerge.Changed).content,
            listOf("$romRoot/gba", "$romRoot/psx"),
        )
        assertEquals(GameDirsMerge.Unchanged, second)
    }

    @Test
    fun mergeGameDirs_unchangedWhenAllPresent() {
        val merged = mergeGameDirs(
            "$romRoot/gba\n$romRoot/psx\n",
            listOf("$romRoot/gba", "$romRoot/psx"),
        )
        assertEquals(GameDirsMerge.Unchanged, merged)
    }

    @Test
    fun mergeGameDirs_ignoresWhitespaceAndCommentsWhenMatching() {
        val merged = mergeGameDirs(
            "# comment\n  $romRoot/gba  \n",
            listOf("$romRoot/gba"),
        )
        assertEquals(GameDirsMerge.Unchanged, merged)
    }

    @Test
    fun inject_gameDirsMerge_skippedHonestlyWhenNoLegacyGrant() {
        val f = Fixture()
        val lib = f.lib
        f.legacyPicked = false
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"))
        }

        val outcome = runBlocking { lib.inject() }

        val ok = outcome as PegasusLibrary.InjectOutcome.Ok
        assertEquals(GameDirsOutcome.SkippedNoGrant, ok.gameDirs)
        assertTrue("no legacy write may be attempted without a grant", f.legacyWrites.isEmpty())
        // The metadata files are still the deliverable.
        assertEquals(setOf("gba"), f.disk.keys)
    }

    @Test
    fun inject_gameDirsMerge_appendsSystemPathsAndIsIdempotent() {
        val f = Fixture()
        val lib = f.lib
        f.legacyPicked = true
        f.legacyDisk["game_dirs.txt"] =
            "# my dirs\n/storage/emulated/0/OtherRoms\n".toByteArray()
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(
                game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"),
                game("psx", "PlayStation", "Doom", "$romRoot/psx/doom.cue"),
            )
        }

        val first = runBlocking { lib.inject() } as PegasusLibrary.InjectOutcome.Ok
        assertEquals(GameDirsOutcome.Updated(2), first.gameDirs)
        val text = f.legacyDisk["game_dirs.txt"]!!.toString(Charsets.UTF_8)
        assertTrue("foreign entry preserved", text.contains("/storage/emulated/0/OtherRoms"))
        assertTrue("comment preserved", text.contains("# my dirs"))
        assertTrue(text.contains("$romRoot/gba"))
        assertTrue(text.contains("$romRoot/psx"))

        // A second BUILD adds nothing: idempotent across repeated runs.
        val second = runBlocking { lib.inject() } as PegasusLibrary.InjectOutcome.Ok
        assertEquals(GameDirsOutcome.Unchanged, second.gameDirs)
        val again = f.legacyDisk["game_dirs.txt"]!!.toString(Charsets.UTF_8)
        assertEquals(1, again.lines().count { it == "$romRoot/gba" })
    }

    @Test
    fun inject_gameDirsMerge_failureDoesNotFailBuild() {
        val f = Fixture()
        val lib = f.lib
        f.legacyPicked = true
        lib.legacyWrite = { _, _ -> false }
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"))
        }

        val outcome = runBlocking { lib.inject() }

        val ok = outcome as PegasusLibrary.InjectOutcome.Ok
        assertEquals(GameDirsOutcome.Failed("WRITE FAILED"), ok.gameDirs)
        // The metadata files are still written and verified.
        assertEquals(setOf("gba"), f.disk.keys)
    }

    @Test
    fun inject_gameDirsOutcome_isPersistedForDiagnostics() {
        val f = Fixture()
        val lib = f.lib
        f.legacyPicked = true
        adoptRomRoot(f.prefs)
        lib.gameSource = {
            listOf(game("gba", "Game Boy Advance", "Mario Golf", "$romRoot/gba/mario.gba"))
        }

        runBlocking { lib.inject() }

        val report = lib.systemMetafileReport()
        assertEquals(GameDirsOutcome.Updated(1), report.gameDirs)
    }
}
