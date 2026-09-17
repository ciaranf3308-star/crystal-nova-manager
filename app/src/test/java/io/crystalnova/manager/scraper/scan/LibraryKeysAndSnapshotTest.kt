package io.crystalnova.manager.scraper.scan

import io.crystalnova.manager.scraper.match.PlatformTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the real-device crash after a library scan:
 * several unrecognised ROM folders all got platformSlug "unknown" and
 * the grid built its Compose keys from the slug alone, producing
 * duplicate keys (`library-card-unknown`) that crashed composition.
 * Keys now come from the stable source-folder identity.
 */
class LibraryKeysAndSnapshotTest {

    private fun system(slug: String, folder: String, games: Int = 3) =
        DiscoveredSystem(
            platformSlug = slug,
            label = folder,
            gameCount = games,
            sourceFolderName = folder,
        )

    @Test fun `multiple unknown folders produce distinct grid keys`() {
        val keys = listOf("Wii", "Xbox", "Atari Jaguar")
            .map { libraryCardKey(system("unknown", it)) }
        assertEquals(3, keys.toSet().size)
    }

    @Test fun `two folders resolving to the same platform produce distinct grid keys`() {
        val a = libraryCardKey(system("psx", "ps1"))
        val b = libraryCardKey(system("psx", "PSX"))
        assertNotEquals(a, b)
    }

    @Test fun `a folder named all cannot collide with the all-systems card`() {
        val key = libraryCardKey(system("unknown", "all"))
        assertNotEquals("library-card-all", key)
    }

    @Test fun `grid keys are stable across constructions`() {
        val a = libraryCardKey(system("unknown", "Wii"))
        val b = libraryCardKey(system("unknown", "Wii"))
        assertEquals(a, b)
    }

    @Test fun `ps1 and ds folder aliases resolve to known platforms`() {
        assertEquals("psx", PlatformTable.byFolderName("PS1")?.slug)
        assertEquals("nds", PlatformTable.byFolderName("DS")?.slug)
        assertEquals("psx", PlatformTable.byFolderName("ps1")?.slug)
        assertEquals("nds", PlatformTable.byFolderName("ds")?.slug)
    }

    @Test fun `snapshot roundtrip preserves every field`() {
        val systems = listOf(
            system("gba", "gba", 12),
            system("unknown", "Wii", 4),
            system("psx", "ps1", 7),
        )
        val decoded = SystemSnapshot.decode(SystemSnapshot.encode(systems))
        assertEquals(systems, decoded)
    }

    @Test fun `snapshot decode rejects garbage`() {
        assertNull(SystemSnapshot.decode("not json"))
        assertNull(SystemSnapshot.decode("{\"folder\":\"x\"}"))
        assertNull(SystemSnapshot.decode(""))
    }

    @Test fun `snapshot decode of an empty array is empty`() {
        assertEquals(emptyList<DiscoveredSystem>(), SystemSnapshot.decode("[]"))
    }

    @Test fun `restoredSystems restores the cached summary when live is empty`() {
        val cached = listOf(system("gba", "gba", 12), system("unknown", "Wii", 4))
        val json = SystemSnapshot.encode(cached)
        assertEquals(cached, restoredSystems(emptyList(), true, json))
    }

    @Test fun `restoredSystems never clobbers live scan data`() {
        val live = listOf(system("gba", "gba", 1))
        val json = SystemSnapshot.encode(listOf(system("snes", "snes", 99)))
        assertEquals(live, restoredSystems(live, true, json))
    }

    @Test fun `restoredSystems restores nothing without a picked games folder`() {
        val json = SystemSnapshot.encode(listOf(system("gba", "gba", 12)))
        assertTrue(restoredSystems(emptyList(), false, json).isEmpty())
    }

    @Test fun `restoredSystems tolerates a corrupt snapshot`() {
        assertTrue(restoredSystems(emptyList(), true, "garbage").isEmpty())
        assertTrue(restoredSystems(emptyList(), true, null).isEmpty())
    }
}
