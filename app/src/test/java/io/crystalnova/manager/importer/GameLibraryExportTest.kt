package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.InMemoryThemeFs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Game List Export tests: the scan is a faithful dump of the REAL
 * folder tree — no mapping, no guessing, no filtering beyond hidden
 * entries. Exercised against [InMemoryThemeFs]; nothing here touches
 * disk.
 */
class GameLibraryExportTest {

    private fun romTree(): InMemoryThemeFs {
        val fs = InMemoryThemeFs()
        val n3ds = fs.mkdir(fs.rootNode, "3ds")
        fs.createFile(n3ds, "a.3ds")
        val nested = fs.mkdir(n3ds, "nested")
        fs.createFile(nested, "b.3ds")
        val gc = fs.mkdir(fs.rootNode, "gc")
        fs.createFile(gc, "game.iso")
        fs.createFile(gc, "Celebrity Deathmatch.xiso")
        // Hidden entries never appear: neither the dir nor the file.
        val hidden = fs.mkdir(fs.rootNode, ".hidden")
        fs.createFile(hidden, "secret.3ds")
        fs.createFile(fs.rootNode, ".dotfile")
        fs.createFile(fs.rootNode, "readme.txt")
        // Empty folders are included, not omitted.
        fs.mkdir(fs.rootNode, "empty")
        return fs
    }

    private fun folderOf(scan: LibraryScan, name: String): LibraryFolder =
        scan.folders.first { it.name == name }

    @Test fun `scan dumps the real folder tree as json`() {
        val scan = scanGameLibrary(romTree())!!
        assertEquals(setOf("3ds", "gc", "empty", "_root"), scan.folders.map { it.name }.toSet())
        assertEquals(listOf("a.3ds", "nested/b.3ds"), folderOf(scan, "3ds").files)
        assertEquals(listOf("Celebrity Deathmatch.xiso", "game.iso"), folderOf(scan, "gc").files)
        assertTrue(folderOf(scan, "empty").files.isEmpty())
        assertEquals(listOf("readme.txt"), folderOf(scan, "_root").files)
        assertEquals(5, scan.totalFiles)
        assertEquals(4, scan.folderCount)
        // Full filenames with extensions: nothing stripped, nothing deduped.
        assertTrue(scan.exportText.contains("Celebrity Deathmatch.xiso"))
    }

    @Test fun `hidden entries never appear anywhere`() {
        val scan = scanGameLibrary(romTree())!!
        assertTrue(scan.folders.none { it.name.startsWith('.') })
        assertTrue(scan.exportText.lines().none { it.contains("hidden") || it.contains("dotfile") })
    }

    @Test fun `folders and files are sorted so the text is deterministic`() {
        val scan = scanGameLibrary(romTree())!!
        assertEquals(scan.folders.map { it.name }, scan.folders.map { it.name }.sorted())
        for (folder in scan.folders) {
            assertEquals(folder.files, folder.files.sorted())
        }
    }

    @Test fun `two scans produce byte-identical text`() {
        val first = scanGameLibrary(romTree())!!.exportText
        val second = scanGameLibrary(romTree())!!.exportText
        assertEquals(first, second)
    }

    @Test fun `export text matches the exact json format`() {
        val scan = LibraryScan(
            listOf(
                LibraryFolder("3ds", listOf("a.3ds", "sub/b.3ds")),
                LibraryFolder("_root", listOf("bios.bin")),
            ),
        )
        assertEquals(
            "{\n" +
                "  \"3ds\": [\n" +
                "    \"a.3ds\",\n" +
                "    \"sub/b.3ds\"\n" +
                "  ],\n" +
                "  \"_root\": [\n" +
                "    \"bios.bin\"\n" +
                "  ]\n" +
                "}",
            scan.exportText,
        )
    }

    @Test fun `empty folder renders as an empty array`() {
        val text = LibraryScan(listOf(LibraryFolder("empty", emptyList()))).exportText
        assertEquals("{\n  \"empty\": [\n  ]\n}", text)
        assertEquals(0, JSONObject(text).getJSONArray("empty").length())
    }

    @Test fun `tricky filenames round-trip through a json parse`() {
        val tricky = "He said \"hi\" \\ bye.3ds"
        val fs = InMemoryThemeFs()
        val n3ds = fs.mkdir(fs.rootNode, "3ds")
        fs.createFile(n3ds, tricky)
        val parsed = JSONObject(scanGameLibrary(fs)!!.exportText)
        assertEquals(tricky, parsed.getJSONArray("3ds").getString(0))
    }

    @Test fun `scan returns null without a roms fs`() {
        assertNull(scanGameLibrary(null))
    }

    @Test fun `scan returns null when the root is gone`() {
        val fs = romTree()
        fs.rootAvailable = false
        assertNull(scanGameLibrary(fs))
    }

    @Test fun `m3u directory exports as exactly one entry`() {
        // The required regression: psx/Metal Gear Solid.m3u/ exports
        // ONLY "Metal Gear Solid.m3u" — the child disc CHDs and the
        // internal playlist never appear.
        val fs = InMemoryThemeFs()
        val psx = fs.mkdir(fs.rootNode, "psx")
        // Multi-disc layout installed by the PS1 pipeline: ES-DE's
        // "directories interpreted as files" -> one frontend entry.
        val mgs = fs.mkdir(psx, "Metal Gear Solid.m3u")
        fs.createFile(mgs, "Metal Gear Solid (Disc 1).chd")
        fs.createFile(mgs, "Metal Gear Solid (Disc 2).chd")
        fs.createFile(mgs, "Metal Gear Solid.m3u")
        fs.createFile(psx, "Crash Bandicoot.chd")

        assertEquals(
            listOf("Crash Bandicoot.chd", "Metal Gear Solid.m3u"),
            folderOf(scanGameLibrary(fs)!!, "psx").files,
        )
    }

    @Test fun `m3u directory rule is case-insensitive and depth-safe without over-collapsing`() {
        val fs = InMemoryThemeFs()
        val psx = fs.mkdir(fs.rootNode, "psx")
        fs.createFile(psx, "Crash Bandicoot.chd")
        // A normal multi-file folder still lists every file: the m3u
        // rule must not over-collapse ordinary directories.
        val doom = fs.mkdir(psx, "Doom")
        fs.createFile(doom, "doom.wad")
        fs.createFile(doom, "readme.txt")
        // The rule applies at any depth, case-insensitively.
        val sub = fs.mkdir(psx, "sub")
        val ff = fs.mkdir(sub, "Final Fantasy VII.M3U")
        fs.createFile(ff, "Final Fantasy VII (Disc 1).chd")
        fs.createFile(ff, "Final Fantasy VII.m3u")

        val psxFiles = folderOf(scanGameLibrary(fs)!!, "psx").files
        assertEquals(
            listOf(
                "Crash Bandicoot.chd",
                "Doom/doom.wad",
                "Doom/readme.txt",
                "sub/Final Fantasy VII.M3U",
            ),
            psxFiles,
        )
        // Exactly one entry per game: no child CHDs anywhere.
        assertEquals(1, psxFiles.count { it.contains("Final Fantasy VII") })
        assertTrue(psxFiles.none { it.endsWith(".chd") && it.contains("(Disc") })
    }

    @Test fun `scan of an empty tree has only the empty root bucket`() {
        val scan = scanGameLibrary(InMemoryThemeFs())!!
        assertEquals(listOf(LIBRARY_ROOT_KEY), scan.folders.map { it.name })
        assertEquals(0, scan.totalFiles)
    }
}
