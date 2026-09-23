package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformMappingTest {

    @Test fun `defaults follow ES-DE conventions`() {
        val mapping = PlatformMapping(MapKeyValueStore())
        assertEquals("gba", mapping.folderFor(PlatformId.GBA))
        assertEquals("ps2", mapping.folderFor(PlatformId.PS2))
        assertEquals("gc", mapping.folderFor(PlatformId.GAMECUBE))
        assertEquals("3ds", mapping.folderFor(PlatformId.N3DS))
        // Genesis and Mega Drive share one default folder.
        assertEquals("genesis", mapping.folderFor(PlatformId.GENESIS))
        assertEquals("genesis", mapping.folderFor(PlatformId.MEGADRIVE))
    }

    @Test fun `custom folder persists across instances`() {
        val prefs = MapKeyValueStore()
        val first = PlatformMapping(prefs)
        assertTrue(first.setFolder(PlatformId.PS2, "ps2games"))
        val second = PlatformMapping(prefs)
        assertEquals("ps2games", second.folderFor(PlatformId.PS2))
        // Untouched platforms keep defaults.
        assertEquals("gba", second.folderFor(PlatformId.GBA))
    }

    @Test fun `unsafe folder names are rejected`() {
        val mapping = PlatformMapping(MapKeyValueStore())
        assertFalse(mapping.setFolder(PlatformId.PS2, "../evil"))
        assertFalse(mapping.setFolder(PlatformId.PS2, "a/b"))
        assertFalse(mapping.setFolder(PlatformId.PS2, ""))
        assertFalse(mapping.setFolder(PlatformId.PS2, ".hidden"))
        assertFalse(mapping.setFolder(PlatformId.PS2, ".."))
        assertEquals("ps2", mapping.folderFor(PlatformId.PS2))
    }

    @Test fun `resetDefaults restores everything`() {
        val prefs = MapKeyValueStore()
        val mapping = PlatformMapping(prefs)
        mapping.setFolder(PlatformId.PS2, "custom")
        mapping.resetDefaults()
        assertEquals("ps2", mapping.folderFor(PlatformId.PS2))
        assertEquals("ps2", PlatformMapping(prefs).folderFor(PlatformId.PS2))
    }

    @Test fun `corrupt stored JSON falls back to defaults`() {
        val prefs = MapKeyValueStore()
        prefs.putString(PlatformMapping.KEY, "{not valid json")
        val mapping = PlatformMapping(prefs)
        assertEquals("gba", mapping.folderFor(PlatformId.GBA))
    }

    @Test fun `snapshot is in grid order`() {
        val mapping = PlatformMapping(MapKeyValueStore())
        val snapshot = mapping.snapshot()
        assertEquals(PlatformMapping.ORDERED.size, snapshot.size)
        assertEquals(PlatformId.GBA, snapshot.first().first)
        assertEquals(PlatformId.XBOX, snapshot.last().first)
    }

    @Test fun `isValidFolder accepts sane names`() {
        val mapping = PlatformMapping(MapKeyValueStore())
        assertTrue(mapping.isValidFolder("gba"))
        assertTrue(mapping.isValidFolder("ps2-games"))
        assertTrue(mapping.isValidFolder("n64_v2"))
    }
}
