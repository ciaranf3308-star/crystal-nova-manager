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

    /** JVM seams: DocumentsContract / persistedUriPermissions need Android. */
    private fun validConfig(prefs: FakePrefs = FakePrefs()): PegasusConfig {
        val c = config(prefs)
        c.documentIdOf = { "primary:pegasus-frontend" }
        c.grantStillHeld = { true }
        return c
    }

    @Test
    fun validity_notSelected_whenNothingPicked() {
        assertEquals(ConfigValidity.NOT_SELECTED, config().validity())
    }

    @Test
    fun validity_valid_forLegacyRootWithGrant() {
        val c = validConfig()
        c.adoptTreeUriString("content://com.android.externalstorage.documents/tree/primary%3Apegasus-frontend")
        assertEquals(ConfigValidity.VALID, c.validity())
    }

    @Test
    fun validity_valid_forAppSpecificRoot() {
        val c = validConfig()
        c.documentIdOf = {
            "primary:Android/data/org.pegasus_frontend.android/files/pegasus-frontend"
        }
        c.adoptTreeUriString("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Forg.pegasus_frontend.android%2Ffiles%2Fpegasus-frontend")
        assertEquals(ConfigValidity.VALID, c.validity())
    }

    @Test
    fun validity_wrongFolder_forNestedPegasusFrontend() {
        // The real Nova bug: /storage/emulated/0/Emulation/pegasus-frontend.
        val c = config()
        c.documentIdOf = { "primary:Emulation/pegasus-frontend" }
        c.grantStillHeld = { true }
        c.adoptTreeUriString("content://com.android.externalstorage.documents/tree/primary%3AEmulation%2Fpegasus-frontend")
        assertEquals(ConfigValidity.WRONG_FOLDER, c.validity())
        // The bad selection is NOT deleted — only a valid replacement
        // overwrites it.
        assertNotNull(c.treeUri())
    }

    @Test
    fun validity_wrongFolder_whenDocumentIdUnparseable() {
        val c = config()
        c.documentIdOf = { null }
        c.grantStillHeld = { true }
        c.adoptTreeUriString("content://com.example/tree/1")
        assertEquals(ConfigValidity.WRONG_FOLDER, c.validity())
    }

    @Test
    fun validity_accessLost_whenGrantGone() {
        val c = validConfig()
        c.grantStillHeld = { false }
        c.adoptTreeUriString("content://com.android.externalstorage.documents/tree/primary%3Apegasus-frontend")
        assertEquals(ConfigValidity.ACCESS_LOST, c.validity())
    }

    @Test
    fun takeGrantAndAdoptString_roundTrip() {
        val c = config()
        assertTrue(c.adoptTreeUriString("content://x/tree/primary%3Apegasus-frontend"))
        assertEquals("content://x/tree/primary%3Apegasus-frontend", c.treeUri())
    }
}
