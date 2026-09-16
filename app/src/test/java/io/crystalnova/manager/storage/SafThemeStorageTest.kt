package io.crystalnova.manager.storage

import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.data.StorageException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class MapStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

class SafThemeStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fs: InMemoryThemeFs
    private lateinit var prefs: MapStore
    private lateinit var storage: SafThemeStorage

    @Before
    fun setUp() {
        fs = InMemoryThemeFs()
        prefs = MapStore()
        prefs.putString(SafThemeStorage.KEY_TREE_URI, "content://fake/tree")
        storage = SafThemeStorage(fs, prefs)

        // Neighbor data the updater must NEVER touch.
        val roms = fs.mkdir(fs.rootNode, "ROMs")
        val rom = fs.createFile(roms, "game.zip")
        fs.openOutput(rom).use { it.write("rom-bytes".toByteArray()) }
        val meta = fs.createFile(fs.rootNode, "metadata.pegasus.txt")
        fs.openOutput(meta).use { it.write("collection. Game".toByteArray()) }
    }

    private fun sourceDir(version: String, commit: String): File {
        val dir = tmp.newFolder("source-$version")
        File(dir, "theme.cfg").writeText("[theme]")
        File(dir, "theme.qml").writeText("import QtQuick")
        File(dir, "crystal-version.json").writeText(
            """{"version":"$version","commit":"$commit","channel":"stable"}""",
        )
        for (d in listOf("components", "screens", "assets", "fonts")) {
            File(dir, d).mkdirs()
            File(dir, "$d/file.txt").writeText("v$version")
        }
        return dir
    }

    private fun readInstalledFile(name: String): String? {
        val theme = fs.find(fs.rootNode, SafThemeStorage.THEME_DIR_NAME) ?: return null
        val file = fs.find(theme, name) ?: return null
        return fs.openInput(file).readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `successful install replaces the theme and keeps one backup`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")

        val report = storage.installValidatedTheme(sourceDir("2.1.0", "abc1234"))

        assertEquals("2.1.0", storage.readInstalledVersion()!!.version)
        assertEquals("2.1.0", report.version!!.version)
        assertTrue(report.fileCount > 0)
        // Backup holds the previous working version.
        assertTrue(storage.hasBackup())
        assertEquals("2.0.0", storage.readBackupVersion()!!.version)
        // Staging is gone; live dir present.
        assertNull(fs.find(fs.rootNode, SafThemeStorage.STAGING_DIR_NAME))
        assertNotNull(fs.find(fs.rootNode, SafThemeStorage.THEME_DIR_NAME))
    }

    @Test
    fun `failed final rename restores the backup`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")

        // Fail the staging→live rename specifically (the last step).
        fs.renameGate = { node, newName ->
            !(node.name == SafThemeStorage.STAGING_DIR_NAME &&
                newName == SafThemeStorage.THEME_DIR_NAME)
        }

        try {
            storage.installValidatedTheme(sourceDir("2.1.0", "abc1234"))
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertTrue(e.message!!.contains("restored", ignoreCase = true))
        }

        // Previous working theme is live again, untouched.
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
        assertEquals("stub theme.cfg", readInstalledFile("theme.cfg"))
        // No leftover staging dir.
        assertNull(fs.find(fs.rootNode, SafThemeStorage.STAGING_DIR_NAME))
    }

    @Test
    fun `failed backup rename aborts before touching the live theme`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")

        fs.renameGate = { node, newName ->
            !(node.name == SafThemeStorage.THEME_DIR_NAME &&
                newName == SafThemeStorage.BACKUP_DIR_NAME)
        }

        assertThrows(StorageException::class.java) {
            storage.installValidatedTheme(sourceDir("2.1.0", "abc1234"))
        }
        // Live theme untouched, no backup created, staging cleaned.
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
        assertFalse(storage.hasBackup())
        assertNull(fs.find(fs.rootNode, SafThemeStorage.STAGING_DIR_NAME))
    }

    @Test
    fun `fresh install with no current theme needs no backup`() {
        val report = storage.installValidatedTheme(sourceDir("2.0.0", "8a86a06"))
        assertEquals("2.0.0", report.version!!.version)
        assertFalse(storage.hasBackup())
    }

    @Test
    fun `rollback restores the previous working version`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.1.0", "abc1234")
        fs.seedTheme(SafThemeStorage.BACKUP_DIR_NAME, "2.0.0", "8a86a06")

        val report = storage.rollback()

        assertEquals("2.0.0", report.restoredVersion!!.version)
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
        // The version rolled back from becomes the new backup: a rollback
        // is itself undoable, and exactly one backup generation is kept.
        assertTrue(storage.hasBackup())
        assertEquals("2.1.0", storage.readBackupVersion()!!.version)
        assertNull(fs.find(fs.rootNode, SafThemeStorage.STAGING_DIR_NAME))
    }

    @Test
    fun `rollback clears the recorded install SHA`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.1.0", "abc1234")
        fs.seedTheme(SafThemeStorage.BACKUP_DIR_NAME, "2.0.0", "8a86a06")
        storage.recordInstalled(
            storage.readInstalledVersion()!!,
            "abc1234def5678abc1234def5678abc1234def56",
        )

        storage.rollback()

        // The recorded SHA described the version we rolled back from;
        // it must not leak into the restored version's display.
        assertNull(storage.installedSha())
    }

    @Test
    fun `legacy backup without a version marker is restorable`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.1.0", "abc1234")
        fs.seedLegacyTheme(SafThemeStorage.BACKUP_DIR_NAME)

        assertTrue(storage.hasBackup())
        assertNull(storage.readBackupVersion())

        val report = storage.rollback()
        assertNull(report.restoredVersion)
        assertNull(storage.readInstalledVersion())
        assertTrue(storage.hasThemeDir())
    }

    @Test
    fun `install over a legacy theme keeps it as a restorable backup`() {
        fs.seedLegacyTheme(SafThemeStorage.THEME_DIR_NAME)

        val report = storage.installValidatedTheme(sourceDir("2.0.0", "8a86a06"))

        assertEquals("2.0.0", report.version!!.version)
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
        // The legacy install is kept as the one previous working version.
        assertTrue(storage.hasBackup())
        assertNull(storage.readBackupVersion())
    }

    @Test
    fun `rollback with no backup throws`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.1.0", "abc1234")
        assertThrows(StorageException::class.java) { storage.rollback() }
        assertEquals("2.1.0", storage.readInstalledVersion()!!.version)
    }

    @Test
    fun `rollback with an invalid backup refuses`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.1.0", "abc1234")
        // Backup dir exists but is missing required files.
        fs.mkdir(fs.rootNode, SafThemeStorage.BACKUP_DIR_NAME)
        assertThrows(StorageException::class.java) { storage.rollback() }
        assertEquals("2.1.0", storage.readInstalledVersion()!!.version)
    }

    @Test
    fun `neighbor data is never touched by install or rollback`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")
        storage.installValidatedTheme(sourceDir("2.1.0", "abc1234"))
        storage.rollback()

        val roms = fs.find(fs.rootNode, "ROMs")!!
        val rom = fs.find(roms, "game.zip")!!
        assertEquals("rom-bytes", fs.openInput(rom).readBytes().toString(Charsets.UTF_8))
        val meta = fs.find(fs.rootNode, "metadata.pegasus.txt")!!
        assertEquals("collection. Game", fs.openInput(meta).readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun `second install drops the stale backup, keeping exactly one`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")
        storage.installValidatedTheme(sourceDir("2.1.0", "abc1234"))
        storage.installValidatedTheme(sourceDir("2.2.0", "def5678"))

        assertEquals("2.2.0", storage.readInstalledVersion()!!.version)
        assertEquals("2.1.0", storage.readBackupVersion()!!.version)
    }

    @Test
    fun `persisted tree URI round-trips through preferences`() {
        assertEquals("content://fake/tree", storage.treeUri)
        assertTrue(storage.hasFolderAccess())
        storage.treeUri = "content://fake/other"
        assertEquals("content://fake/other", prefs.map[SafThemeStorage.KEY_TREE_URI])
        storage.treeUri = null
        assertNull(prefs.map[SafThemeStorage.KEY_TREE_URI])
        assertFalse(storage.hasFolderAccess())
    }

    @Test
    fun `lost SAF permission surfaces as storage access lost`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")
        fs.rootAvailable = false

        assertFalse(storage.hasFolderAccess())
        assertNull(storage.readInstalledVersion())
        assertThrows(StorageException::class.java) {
            storage.installValidatedTheme(sourceDir("2.1.0", "abc1234"))
        }
    }

    @Test
    fun `recorded SHA is returned for update comparison`() {
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")
        val v = storage.readInstalledVersion()!!
        storage.recordInstalled(v, "c58df4ca079cd4d8be28a45b38182a14a714dbfe")
        assertEquals(
            "c58df4ca079cd4d8be28a45b38182a14a714dbfe",
            storage.installedSha(),
        )
    }
}
