package io.crystalnova.manager.pegasus

import org.junit.Assert.*
import org.junit.Test

class PegasusConfigTest {

    private fun config(prefs: FakePrefs = FakePrefs()): PegasusConfig =
        PegasusConfig(null, prefs, { _, _, _ -> })

    @Test
    fun adoptTreeUriString_persistsAndReads() {
        val prefs = FakePrefs()
        val c = config(prefs)
        assertNull(c.treeUri())
        assertTrue(c.adoptTreeUriString("content://com.example/tree/pegasus"))
        assertEquals("content://com.example/tree/pegasus", c.treeUri())
        // Survives a new instance over the same prefs (separate SAF grant).
        assertEquals("content://com.example/tree/pegasus", config(prefs).treeUri())
    }

    @Test
    fun clear_removesGrant() {
        val prefs = FakePrefs()
        val c = config(prefs)
        c.adoptTreeUriString("content://com.example/tree/pegasus")
        c.clear()
        assertNull(c.treeUri())
    }

    @Test
    fun keys_doNotCollideWithExistingPrefs() {
        val prefs = FakePrefs()
        prefs.putString("themes_tree_uri", "content://themes")
        prefs.putString("rom_tree_uri", "content://roms")
        config(prefs).adoptTreeUriString("content://com.example/tree/pegasus")
        assertEquals("content://themes", prefs.getString("themes_tree_uri"))
        assertEquals("content://roms", prefs.getString("rom_tree_uri"))
    }
}
