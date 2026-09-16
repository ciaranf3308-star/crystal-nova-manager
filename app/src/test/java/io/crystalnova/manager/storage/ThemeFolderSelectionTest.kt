package io.crystalnova.manager.storage

import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.data.StorageException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class TestStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

/**
 * U1.1 regression tests: the U1 picker wording ("CHOOSE CRYSTAL THEME
 * FOLDER") led real users to select themes/crystal-nova-pegasus-theme/
 * itself, so updates installed one level too deep — reporting success
 * while Pegasus kept seeing the old theme. These tests pin the fix:
 * detection, no-nesting, nested-install repair, and entry-point
 * placement.
 */
class ThemeFolderSelectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fs: InMemoryThemeFs
    private lateinit var prefs: TestStore
    private lateinit var storage: SafThemeStorage

    @Before
    fun setUp() {
        fs = InMemoryThemeFs()
        prefs = TestStore()
        prefs.putString(SafThemeStorage.KEY_TREE_URI, "content://fake/tree")
        storage = SafThemeStorage(fs, prefs)
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

    private fun childNamesOf(dirName: String): Set<String> {
        val dir = fs.find(fs.rootNode, dirName) ?: return emptySet()
        return fs.children(dir).map { it.first }.toSet()
    }

    @Test
    fun `user selects themes parent installs into themes slash crystal-nova-pegasus-theme`() {
        // rootNode is named "themes" — the correct pick.
        assertFalse(storage.isRootThemeFolderItself())
        storage.installValidatedTheme(sourceDir("2.0", "abc1234"))
        val names = childNamesOf("crystal-nova-pegasus-theme")
        assertTrue("theme.cfg" in names)
        assertTrue("theme.qml" in names)
        assertEquals(
            "…/themes/crystal-nova-pegasus-theme/",
            storage.installDestinationLabel(),
        )
    }

    @Test
    fun `user selects the theme folder itself is detected and never nested`() {
        fs.rootNode.name = "crystal-nova-pegasus-theme"
        assertTrue(storage.isRootThemeFolderItself())
        try {
            storage.installValidatedTheme(sourceDir("2.0", "abc1234"))
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertTrue(e.message!!.contains("THEMES FOLDER"))
        }
        // The nested path must never have been created.
        val rootNames = fs.children(fs.rootNode).map { it.first }.toSet()
        assertFalse("crystal-nova-pegasus-theme" in rootNames)
        assertFalse(storage.hasNestedInstall())
    }

    @Test
    fun `nested broken install is detected and repaired`() {
        // Outer = stale 1.0 theme; nested = 2.0 installed one level too deep.
        fs.seedTheme("crystal-nova-pegasus-theme", "1.0", "aaa1111")
        val outer = fs.find(fs.rootNode, "crystal-nova-pegasus-theme")!!
        val nested = fs.mkdir(outer, "crystal-nova-pegasus-theme")
        for (f in listOf("theme.cfg", "theme.qml", "crystal-version.json")) {
            val file = fs.createFile(nested, f)
            val content = if (f == "crystal-version.json") {
                """{"version":"2.0","commit":"bbb2222","channel":"stable"}"""
            } else {
                "stub $f v2"
            }
            fs.openOutput(file).use { it.write(content.toByteArray()) }
        }
        for (d in listOf("components", "screens", "assets", "fonts")) {
            fs.mkdir(nested, d)
        }

        assertTrue(storage.hasNestedInstall())
        assertTrue(storage.fixNestedInstall())
        assertFalse(storage.hasNestedInstall())

        // theme.cfg/theme.qml are now directly in the theme folder.
        val names = childNamesOf("crystal-nova-pegasus-theme")
        assertTrue("theme.cfg" in names)
        assertTrue("theme.qml" in names)
        assertFalse("crystal-nova-pegasus-theme" in names)
        // The live theme is the repaired 2.0.
        assertEquals("2.0", storage.readInstalledVersion()?.version)
        // The stale outer theme was kept as the backup.
        assertTrue(storage.hasBackup())
        assertEquals("1.0", storage.readBackupVersion()?.version)
    }

    @Test
    fun `installed theme always has entry points directly inside`() {
        storage.installValidatedTheme(sourceDir("2.0", "abc1234"))
        val themeDir = fs.find(fs.rootNode, "crystal-nova-pegasus-theme")!!
        val names = fs.children(themeDir).map { it.first }.toSet()
        assertTrue("theme.cfg" in names)
        assertTrue("theme.qml" in names)
        // …and not one level deeper.
        for ((name, child) in fs.children(themeDir)) {
            if (fs.isDirectory(child)) {
                val inner = fs.children(child).map { it.first }.toSet()
                assertFalse(
                    "entry points nested inside $name",
                    "theme.cfg" in inner && "theme.qml" in inner,
                )
            }
        }
    }

    @Test
    fun `root holding theme files directly is detected as theme folder`() {
        // Content-based fallback for a renamed copy.
        fs.rootNode.name = "something-else"
        for (f in listOf("theme.cfg", "theme.qml")) {
            val file = fs.createFile(fs.rootNode, f)
            fs.openOutput(file).use { it.write("x".toByteArray()) }
        }
        assertTrue(storage.isRootThemeFolderItself())
    }

    @Test
    fun `non-nested install is not flagged`() {
        fs.seedTheme("crystal-nova-pegasus-theme", "2.0", "abc1234")
        assertFalse(storage.hasNestedInstall())
        assertFalse(storage.fixNestedInstall())
    }
}
