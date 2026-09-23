package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.InMemoryThemeFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Game List Export tests: extension stripping, .bin/.cue dedup,
 * grouping/order, and the exact export-text format. The scan glue is
 * exercised against [InMemoryThemeFs]; nothing here touches disk.
 */
class GameLibraryExportTest {

    @Test fun `display name strips only the last extension`() {
        assertEquals("Pokemon Emerald", GameLibraryExport.displayName("Pokemon Emerald.zip"))
        assertEquals(
            "Mario Golf - Advance Tour",
            GameLibraryExport.displayName("Mario Golf - Advance Tour.gba"),
        )
        assertEquals("archive.tar", GameLibraryExport.displayName("archive.tar.gz"))
        assertEquals("noextension", GameLibraryExport.displayName("noextension"))
    }

    @Test fun `bin is dropped when a matching cue exists`() {
        val names = GameLibraryExport.gameNames(
            listOf("Dark Cloud 2.bin", "Dark Cloud 2.cue", "Gran Turismo 4.iso"),
        )
        assertEquals(listOf("Dark Cloud 2", "Gran Turismo 4"), names)
    }

    @Test fun `bin dedup is case-insensitive`() {
        val names = GameLibraryExport.gameNames(listOf("Rogue Disc.BIN", "rogue disc.CUE"))
        assertEquals(listOf("rogue disc"), names)
    }

    @Test fun `lone bin and lone cue are both kept`() {
        assertEquals(listOf("Rogue Disc"), GameLibraryExport.gameNames(listOf("Rogue Disc.bin")))
        assertEquals(listOf("Rogue Disc"), GameLibraryExport.gameNames(listOf("Rogue Disc.cue")))
    }

    @Test fun `render matches the exact export format`() {
        val systems = listOf(
            LibrarySystem(
                PlatformId.GBA,
                listOf("Pokemon Emerald", "Mario Golf - Advance Tour", "Golden Sun"),
            ),
            LibrarySystem(
                PlatformId.PS2,
                listOf("Metal Gear Solid 2 - Sons of Liberty", "Dark Cloud 2", "Gran Turismo 4"),
            ),
            LibrarySystem(
                PlatformId.GAMECUBE,
                listOf("Mario Kart - Double Dash", "Resident Evil 4"),
            ),
        )
        assertEquals(
            "GAME BOY ADVANCE\n" +
                "Pokemon Emerald\n" +
                "Mario Golf - Advance Tour\n" +
                "Golden Sun\n" +
                "\n" +
                "PLAYSTATION 2\n" +
                "Metal Gear Solid 2 - Sons of Liberty\n" +
                "Dark Cloud 2\n" +
                "Gran Turismo 4\n" +
                "\n" +
                "GAMECUBE\n" +
                "Mario Kart - Double Dash\n" +
                "Resident Evil 4",
            GameLibraryExport.render(systems),
        )
    }

    @Test fun `render of an empty library is empty`() {
        assertEquals("", GameLibraryExport.render(emptyList()))
        assertEquals("", LibraryScan(emptyList()).exportText)
    }

    private fun romTree(): InMemoryThemeFs {
        val fs = InMemoryThemeFs()
        val gba = fs.mkdir(fs.rootNode, "gba")
        fs.createFile(gba, "Pokemon Emerald.zip")
        fs.createFile(gba, "Golden Sun.gba")
        fs.createFile(gba, ".hidden.zip")
        val sub = fs.mkdir(gba, "subfolder")
        fs.createFile(sub, "nested.zip")
        val ps2 = fs.mkdir(fs.rootNode, "ps2")
        fs.createFile(ps2, "Dark Cloud 2.bin")
        fs.createFile(ps2, "Dark Cloud 2.cue")
        fs.createFile(ps2, "Gran Turismo 4.iso")
        // Genesis/Mega Drive share one default folder: counted once.
        val genesis = fs.mkdir(fs.rootNode, "genesis")
        fs.createFile(genesis, "Sonic.zip")
        return fs
    }

    @Test fun `scan groups by console in mapping order and skips empties`() {
        val scan = scanGameLibrary(PlatformMapping(MapKeyValueStore()), romTree())!!
        assertEquals(
            listOf(PlatformId.GBA, PlatformId.GENESIS, PlatformId.PS2),
            scan.systems.map { it.platform },
        )
        assertEquals(listOf("Pokemon Emerald", "Golden Sun"), scan.systems[0].games)
        assertEquals(listOf("Sonic"), scan.systems[1].games)
        assertEquals(listOf("Dark Cloud 2", "Gran Turismo 4"), scan.systems[2].games)
        assertEquals(5, scan.totalGames)
        assertEquals(3, scan.systemCount)
        // Hidden files and subdirectory contents never appear.
        assertTrue(scan.exportText.lines().none { it.contains("hidden") || it.contains("nested") })
    }

    @Test fun `scan returns null without a roms fs`() {
        assertNull(scanGameLibrary(PlatformMapping(MapKeyValueStore()), null))
    }

    @Test fun `scan returns null when the root is gone`() {
        val fs = romTree()
        fs.rootAvailable = false
        assertNull(scanGameLibrary(PlatformMapping(MapKeyValueStore()), fs))
    }

    @Test fun `scan of an empty tree finds nothing`() {
        val scan = scanGameLibrary(PlatformMapping(MapKeyValueStore()), InMemoryThemeFs())!!
        assertTrue(scan.systems.isEmpty())
        assertEquals(0, scan.totalGames)
    }
}
