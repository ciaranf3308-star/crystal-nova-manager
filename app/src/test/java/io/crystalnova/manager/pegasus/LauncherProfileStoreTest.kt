package io.crystalnova.manager.pegasus

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LauncherProfileStoreTest {

    private fun store(): LauncherProfileStore = LauncherProfileStore(FakePrefs())

    @Test
    fun roundTrip_allProfileTypes() {
        val s = store()
        val profiles = mapOf(
            "gba" to LauncherPresets.retroArch(core = "mgba_libretro_android.so"),
            "ps2" to LauncherPresets.NETHER_SX2,
            "psp" to LauncherPresets.PPSSPP,
            "arcade" to LauncherProfile(
                type = LauncherType.VIEW_INTENT,
                packageName = "com.example.emu",
                activity = "com.example.emu.Play",
            ),
            "dreamcast" to LauncherProfile(
                type = LauncherType.CUSTOM,
                command = "am start --user 0\n  -n com.example/.Main",
            ),
        )
        profiles.forEach { (slug, p) -> s.set(slug, p) }
        profiles.forEach { (slug, p) -> assertEquals("round trip for $slug", p, s.get(slug)) }
    }

    @Test
    fun effectiveProfile_appliesDefaultWithoutStoring() {
        val s = store()
        // Nothing stored, but the curated default applies.
        assertEquals(LauncherPresets.NETHER_SX2, s.effectiveProfile("ps2"))
        assertEquals(LauncherPresets.retroArch(core = "mgba_libretro_android.so"), s.effectiveProfile("gba"))
        // And nothing was written to prefs.
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun effectiveProfile_userChoiceWinsOverDefault() {
        val s = store()
        val custom = LauncherProfile(type = LauncherType.CUSTOM, command = "my command")
        s.set("ps2", custom)
        assertEquals(custom, s.effectiveProfile("ps2"))
    }

    @Test
    fun effectiveProfile_unknownSystem_isNull() {
        assertNull(store().effectiveProfile("n3ds"))
        assertNull(store().effectiveProfile("arcade"))
    }

    @Test
    fun clear_restoresDefault() {
        val s = store()
        s.set("ps2", LauncherProfile(type = LauncherType.CUSTOM, command = "x"))
        assertEquals(LauncherProfile(type = LauncherType.CUSTOM, command = "x"), s.get("ps2"))
        s.clear("ps2")
        assertNull(s.get("ps2"))
        assertEquals(LauncherPresets.NETHER_SX2, s.effectiveProfile("ps2"))
    }

    @Test
    fun corruptedRecord_failsSafe() {
        val prefs = FakePrefs()
        prefs.putString(LauncherProfileStore.KEY, "not-json{{")
        assertNull(LauncherProfileStore(prefs).get("ps2"))
        // The default still applies even with a corrupt record store.
        assertEquals(LauncherPresets.NETHER_SX2, LauncherProfileStore(prefs).effectiveProfile("ps2"))
    }

    @Test
    fun unknownTypeString_failsSafeToCustom() {
        val prefs = FakePrefs()
        val root = JSONObject().put("ps2", JSONObject().put("type", "NEVEREXISTED"))
        prefs.putString(LauncherProfileStore.KEY, root.toString())
        val got = LauncherProfileStore(prefs).get("ps2")
        assertNotNull(got)
        assertEquals(LauncherType.CUSTOM, got!!.type)
    }

    @Test
    fun platformsStayIndependent() {
        val s = store()
        s.set("gba", LauncherPresets.retroArch(core = "mgba_libretro_android.so"))
        s.set("ps2", LauncherPresets.NETHER_SX2)
        assertEquals(LauncherType.RETROARCH, s.get("gba")!!.type)
        assertEquals(LauncherType.STANDALONE, s.get("ps2")!!.type)
        s.clear("gba")
        assertNull(s.get("gba"))
        assertEquals(LauncherPresets.NETHER_SX2, s.get("ps2"))
    }

    @Test
    fun get_missingSlug_isNull() {
        assertNull(store().get("gba"))
    }
}
