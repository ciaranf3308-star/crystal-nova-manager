package io.crystalnova.manager.pegasus

import org.junit.Assert.*
import org.junit.Test

class LauncherAutoConfigTest {

    private val retroArch64 = LauncherPresets.RETROARCH_AARCH64
    private val retroArch32 = LauncherPresets.RETROARCH_32
    private val nether = LauncherPresets.NETHER_SX2.packageName
    private val ppsspp = LauncherPresets.PPSSPP.packageName
    private val ppssppGold = LauncherPresets.PPSSPP_GOLD.packageName

    @Test
    fun decide_gba_prefersAarch64RetroArch() {
        val pick = LauncherAutoConfig.decide("gba", setOf(retroArch32, retroArch64))
        assertNotNull(pick)
        assertEquals(retroArch64, pick!!.packageName)
        assertEquals("mgba_libretro_android.so", pick.core)
    }

    @Test
    fun decide_gba_fallsBackTo32BitRetroArch() {
        val pick = LauncherAutoConfig.decide("gba", setOf(retroArch32))
        assertNotNull(pick)
        assertEquals(retroArch32, pick!!.packageName)
        assertEquals("mgba_libretro_android.so", pick.core)
    }

    @Test
    fun decide_gba_noRetroArch_needsAttention() {
        assertNull(LauncherAutoConfig.decide("gba", setOf(nether, ppsspp)))
    }

    @Test
    fun decide_ps2_onlyWhenNetherInstalled() {
        assertEquals(LauncherPresets.NETHER_SX2, LauncherAutoConfig.decide("ps2", setOf(nether)))
        assertNull(LauncherAutoConfig.decide("ps2", setOf(retroArch64)))
    }

    @Test
    fun decide_psp_prefersStandardOverGold() {
        val pick = LauncherAutoConfig.decide("psp", setOf(ppssppGold, ppsspp))
        assertEquals(LauncherPresets.PPSSPP, pick)
        assertEquals(
            LauncherPresets.PPSSPP_GOLD,
            LauncherAutoConfig.decide("psp", setOf(ppssppGold)),
        )
        assertNull(LauncherAutoConfig.decide("psp", setOf(retroArch64)))
    }

    @Test
    fun decide_unsupportedSystem_needsAttention() {
        // No fabricated launcher for wii / n3ds / arcade, even with emulators installed.
        assertNull(LauncherAutoConfig.decide("wii", setOf(retroArch64)))
        assertNull(LauncherAutoConfig.decide("n3ds", setOf(retroArch64)))
        assertNull(LauncherAutoConfig.decide("arcade", setOf(retroArch64)))
        assertNull(LauncherAutoConfig.decide("unknown", setOf(retroArch64)))
    }

    @Test
    fun apply_writesAutoOnlyForSafePicks() {
        val store = LauncherProfileStore(FakePrefs())
        val outcome = LauncherAutoConfig.apply(
            store,
            listOf("gba", "ps2", "wii"),
            setOf(retroArch64),
        )
        assertEquals(listOf("gba"), outcome.configured)
        assertEquals(listOf("ps2", "wii"), outcome.needAttention)
        assertEquals(LauncherSource.AUTO, store.getSource("gba"))
        assertEquals("AUTO-CONFIGURED 1 · 2 NEED ATTENTION: PS2, WII", outcome.summary())
    }

    @Test
    fun apply_neverOverwritesUserChoice() {
        val store = LauncherProfileStore(FakePrefs())
        val custom = LauncherProfile(type = LauncherType.CUSTOM, command = "mine")
        store.set("gba", custom) // USER by default
        val outcome = LauncherAutoConfig.apply(store, listOf("gba"), setOf(retroArch64))
        assertEquals(custom, store.get("gba"))
        assertTrue(outcome.configured.isEmpty())
        assertTrue(outcome.needAttention.isEmpty())
    }

    @Test
    fun apply_refreshesAutoSourcedEntries() {
        val store = LauncherProfileStore(FakePrefs())
        store.set("gba", LauncherPresets.retroArch(retroArch32, "mgba_libretro_android.so"), LauncherSource.AUTO)
        // Only the 32-bit build was present at first; now the 64-bit build is installed.
        val outcome = LauncherAutoConfig.apply(store, listOf("gba"), setOf(retroArch64, retroArch32))
        assertEquals(listOf("gba"), outcome.configured)
        assertEquals(retroArch64, store.get("gba")!!.packageName)
    }

    @Test
    fun apply_ignoresZeroGameSystems() {
        // Callers pass only slugs with games; duplicates collapse to one write.
        val store = LauncherProfileStore(FakePrefs())
        val outcome = LauncherAutoConfig.apply(
            store,
            listOf("gba", "gba", "psx"),
            setOf(retroArch64),
        )
        assertEquals(listOf("gba", "psx"), outcome.configured)
    }

    @Test
    fun apply_summary_noIssues() {
        val store = LauncherProfileStore(FakePrefs())
        val outcome = LauncherAutoConfig.apply(store, listOf("gba"), setOf(retroArch64))
        assertEquals("AUTO-CONFIGURED 1", outcome.summary())
    }
}
