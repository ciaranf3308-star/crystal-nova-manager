package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.InMemoryThemeFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the u75 3DS folder correction ("3ds" -> "n3ds"):
 * nested moves preserve structure, collisions are skipped never
 * overwritten, the empty old folder is removed, and an ungranted ROM
 * root skips cleanly without marking the migration done.
 */
class N3dsFolderMigrationTest {

    private fun writeFile(fs: InMemoryThemeFs, parent: FsNode, name: String, content: String) {
        val file = fs.createFile(parent, name)
        fs.openOutput(file).use { it.write(content.toByteArray()) }
    }

    private fun readFile(fs: InMemoryThemeFs, node: FsNode): String =
        fs.openInput(node).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun run(fs: InMemoryThemeFs, prefs: MapKeyValueStore = MapKeyValueStore()) =
        N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { fs }

    @Test fun `nested files move preserving structure`() {
        val fs = InMemoryThemeFs()
        val src = fs.mkdir(fs.rootNode, "3ds")
        val sub = fs.mkdir(src, "sub")
        writeFile(fs, src, "a.3ds", "aaa")
        writeFile(fs, sub, "b.3ds", "bbb")
        val prefs = MapKeyValueStore()

        val result = N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { fs }

        assertTrue(result.done)
        assertEquals(2, result.movedFiles)
        assertEquals(0, result.skippedFiles)
        val dst = fs.find(fs.rootNode, "n3ds")
        assertNotNull(dst)
        assertTrue(fs.isDirectory(dst!!))
        assertEquals("aaa", readFile(fs, fs.find(dst, "a.3ds")!!))
        val dstSub = fs.find(dst, "sub")!!
        assertTrue(fs.isDirectory(dstSub))
        assertEquals("bbb", readFile(fs, fs.find(dstSub, "b.3ds")!!))
        // Old folder removed once empty; flag stored.
        assertNull(fs.find(fs.rootNode, "3ds"))
        assertNotNull(prefs.getString(N3dsFolderMigration.FLAG_KEY))
    }

    @Test fun `colliding files are skipped never overwritten`() {
        val fs = InMemoryThemeFs()
        val src = fs.mkdir(fs.rootNode, "3ds")
        writeFile(fs, src, "same.3ds", "old")
        writeFile(fs, src, "unique.3ds", "u")
        val dst = fs.mkdir(fs.rootNode, "n3ds")
        writeFile(fs, dst, "same.3ds", "new")
        val prefs = MapKeyValueStore()

        val result = N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { fs }

        assertTrue(result.done)
        assertEquals(1, result.movedFiles)
        assertEquals(1, result.skippedFiles)
        // Destination untouched by the collision.
        assertEquals("new", readFile(fs, fs.find(dst, "same.3ds")!!))
        assertEquals("u", readFile(fs, fs.find(dst, "unique.3ds")!!))
        // Collision survivor stays safe in the old folder.
        val leftover = fs.find(fs.rootNode, "3ds")!!
        assertEquals("old", readFile(fs, fs.find(leftover, "same.3ds")!!))
    }

    @Test fun `ungranted rom root skips cleanly without marking done`() {
        val fs = InMemoryThemeFs()
        fs.rootAvailable = false
        val prefs = MapKeyValueStore()

        val result = N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { fs }

        assertFalse(result.done)
        assertEquals(0, result.movedFiles)
        assertNull(prefs.getString(N3dsFolderMigration.FLAG_KEY))
    }

    @Test fun `no 3ds folder marks done without moving`() {
        val fs = InMemoryThemeFs()
        val prefs = MapKeyValueStore()

        val result = N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { fs }

        assertTrue(result.done)
        assertEquals(0, result.movedFiles)
        assertNull(fs.find(fs.rootNode, "n3ds"))
        assertNotNull(prefs.getString(N3dsFolderMigration.FLAG_KEY))
    }

    @Test fun `second run is a no-op`() {
        val fs = InMemoryThemeFs()
        val src = fs.mkdir(fs.rootNode, "3ds")
        writeFile(fs, src, "a.3ds", "aaa")
        val prefs = MapKeyValueStore()

        val first = N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { fs }
        assertTrue(first.done)
        assertEquals(1, first.movedFiles)

        val second = N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { fs }
        assertTrue(second.done)
        assertEquals(0, second.movedFiles)
        assertEquals(0, second.skippedFiles)
    }

    @Test fun `stored 3ds mapping migrates during run`() {
        val prefs = MapKeyValueStore()
        prefs.putString(PlatformMapping.KEY, """{"N3DS":"3ds"}""")
        val fs = InMemoryThemeFs()

        N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { fs }

        assertEquals("n3ds", PlatformMapping(prefs).folderFor(PlatformId.N3DS))
    }

    @Test fun `null fs skips cleanly without marking done`() {
        val prefs = MapKeyValueStore()
        val result = N3dsFolderMigration.run(prefs, PlatformMapping(prefs)) { null }
        assertFalse(result.done)
        assertNull(prefs.getString(N3dsFolderMigration.FLAG_KEY))
    }
}
