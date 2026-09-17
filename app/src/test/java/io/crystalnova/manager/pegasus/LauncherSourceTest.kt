package io.crystalnova.manager.pegasus

import org.junit.Assert.*
import org.junit.Test

class LauncherSourceTest {

    private fun store(): LauncherProfileStore = LauncherProfileStore(FakePrefs())

    @Test
    fun defaultWrite_isUserSourced() {
        val s = store()
        s.set("gba", LauncherPresets.retroArch(core = "mgba_libretro_android.so"))
        assertEquals(LauncherSource.USER, s.getSource("gba"))
    }

    @Test
    fun autoWrite_roundTrips() {
        val s = store()
        val profile = LauncherPresets.retroArch(core = "mgba_libretro_android.so")
        s.set("gba", profile, LauncherSource.AUTO)
        assertEquals(profile, s.get("gba"))
        assertEquals(LauncherSource.AUTO, s.getSource("gba"))
    }

    @Test
    fun legacyEntryWithoutSource_readsAsUser() {
        // Entries written before the flag existed must never be treated
        // as auto-configured: the assistant must not overwrite them.
        val prefs = FakePrefs()
        prefs.putString(
            LauncherProfileStore.KEY,
            """{"gba":{"type":"RETROARCH","package":"com.retroarch","core":"mgba_libretro_android.so"}}""",
        )
        val s = LauncherProfileStore(prefs)
        assertNotNull(s.get("gba"))
        assertEquals(LauncherSource.USER, s.getSource("gba"))
    }

    @Test
    fun unknownSlug_defaultsToUser() {
        assertEquals(LauncherSource.USER, store().getSource("gba"))
    }

    @Test
    fun shortLabel_trimsLibretroSuffix() {
        assertEquals(
            "RETROARCH · MGBA",
            LauncherPresets.retroArch(core = "mgba_libretro_android.so").shortLabel(),
        )
        assertEquals("NETHERSX2", LauncherPresets.NETHER_SX2.shortLabel())
        assertEquals("PPSSPP", LauncherPresets.PPSSPP.shortLabel())
        assertEquals(
            "CUSTOM",
            LauncherProfile(type = LauncherType.CUSTOM, command = "x").shortLabel(),
        )
    }
}
