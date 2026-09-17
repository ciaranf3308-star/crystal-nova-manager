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
    fun decide_gamecube_onlyWhenDolphinInstalled() {
        val dolphin = "org.dolphinemu.dolphinemu"
        assertEquals(LauncherPresets.DOLPHIN, LauncherAutoConfig.decide("gamecube", setOf(dolphin)))
        assertNull(LauncherAutoConfig.decide("gamecube", setOf(retroArch64)))
        assertNull(LauncherAutoConfig.decide("gamecube", emptySet()))
    }

    @Test
    fun decide_n3ds_prefersAzaharVanilla() {
        val azahar = "org.azahar_emu.azahar"
        val azaharPlay = "io.github.lime3ds.android"
        val pick = LauncherAutoConfig.decide("n3ds", setOf(azaharPlay, azahar))
        assertEquals(LauncherPresets.AZAHAR, pick)
        assertEquals(
            LauncherPresets.AZAHAR_PLAY,
            LauncherAutoConfig.decide("n3ds", setOf(azaharPlay)),
        )
        assertNull(LauncherAutoConfig.decide("n3ds", setOf(retroArch64)))
        assertNull(LauncherAutoConfig.decide("n3ds", emptySet()))
    }

    @Test
    fun decide_unsupportedSystem_needsAttention() {
        // No fabricated launcher for wii / arcade, even with emulators installed.
        assertNull(LauncherAutoConfig.decide("wii", setOf(retroArch64)))
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
        assertTrue(outcome.alreadyReady.isEmpty())
        assertTrue(outcome.repaired.isEmpty())
        assertEquals(LauncherSource.AUTO, store.getSource("gba"))
        assertEquals(
            "0 ALREADY READY · 0 REPAIRED · 1 CONFIGURED · 2 NEED ATTENTION: PS2, WII",
            outcome.summary(),
        )
    }

    @Test
    fun apply_neverOverwritesValidUserChoice() {
        val store = LauncherProfileStore(FakePrefs())
        val custom = LauncherProfile(type = LauncherType.CUSTOM, command = "mine")
        store.set("gba", custom) // USER by default
        val outcome = LauncherAutoConfig.apply(store, listOf("gba"), setOf(retroArch64))
        assertEquals(custom, store.get("gba"))
        assertEquals(listOf("gba"), outcome.alreadyReady)
        assertTrue(outcome.repaired.isEmpty())
        assertTrue(outcome.configured.isEmpty())
        assertTrue(outcome.needAttention.isEmpty())
    }

    @Test
    fun apply_validUserRetroArchChoice_preservedUntouched() {
        val store = LauncherProfileStore(FakePrefs())
        val userPick = LauncherPresets.retroArch(retroArch32, "mgba_libretro_android.so")
        store.set("gba", userPick) // legacy entry: reads back as USER
        // Even though the 64-bit build is the preferred pick, the valid
        // manual 32-bit choice must not be touched.
        val outcome = LauncherAutoConfig.apply(
            store,
            listOf("gba"),
            setOf(retroArch32, retroArch64),
        )
        assertEquals(userPick, store.get("gba"))
        assertEquals(LauncherSource.USER, store.getSource("gba"))
        assertEquals(listOf("gba"), outcome.alreadyReady)
    }

    @Test
    fun apply_brokenLegacyProfile_repairsToSafeInstalledPick() {
        val store = LauncherProfileStore(FakePrefs())
        // The v10-era GBA profile: names the 32-bit RetroArch, which is
        // not installed; the 64-bit build is. Reads back as USER.
        store.set("gba", LauncherPresets.retroArch(retroArch32, "mgba_libretro_android.so"))
        val outcome = LauncherAutoConfig.apply(store, listOf("gba"), setOf(retroArch64))
        val repaired = store.get("gba")!!
        assertEquals(retroArch64, repaired.packageName)
        assertEquals("mgba_libretro_android.so", repaired.core)
        assertEquals(LauncherSource.AUTO, store.getSource("gba"))
        assertEquals(listOf("gba"), outcome.repaired)
        assertEquals("0 ALREADY READY · 1 REPAIRED · 0 CONFIGURED · 0 NEED ATTENTION", outcome.summary())
    }

    @Test
    fun apply_brokenProfile_noSafeAlternative_staysNeedsAttention() {
        val store = LauncherProfileStore(FakePrefs())
        val broken = LauncherPresets.retroArch(retroArch32, "mgba_libretro_android.so")
        store.set("wii", broken) // wii has no safe pick at all
        val outcome = LauncherAutoConfig.apply(store, listOf("wii"), setOf(retroArch64))
        // The broken profile is left as-is; the system needs attention.
        assertEquals(broken, store.get("wii"))
        assertEquals(listOf("wii"), outcome.needAttention)
        assertTrue(outcome.repaired.isEmpty())
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
    fun apply_stableAutoEntry_countsAsAlreadyReady() {
        val store = LauncherProfileStore(FakePrefs())
        store.set("gba", LauncherPresets.retroArch(retroArch64, "mgba_libretro_android.so"), LauncherSource.AUTO)
        val outcome = LauncherAutoConfig.apply(store, listOf("gba"), setOf(retroArch64))
        assertEquals(listOf("gba"), outcome.alreadyReady)
        assertTrue(outcome.configured.isEmpty())
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
        assertEquals("0 ALREADY READY · 0 REPAIRED · 1 CONFIGURED · 0 NEED ATTENTION", outcome.summary())
    }
}
