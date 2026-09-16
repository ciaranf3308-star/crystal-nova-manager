package io.crystalnova.manager.pegasus

import org.junit.Assert.*
import org.junit.Test

class LauncherProfilesTest {

    @Test
    fun retroArchLaunchLines_matchVerifiedShape() {
        val p = LauncherPresets.retroArch(core = "mgba_libretro_android.so")
        assertEquals(
            listOf(
                "am start --user 0",
                "-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture",
                "-e ROM \"{file.path}\"",
                "-e LIBRETRO /data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so",
                "-e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
                "-e QUITFOCUS",
                "--activity-single-top",
            ),
            p.launchLines(),
        )
    }

    @Test
    fun netherSx2Preset_launchLines() {
        assertEquals(
            listOf(
                "am start --user 0",
                "-a android.intent.action.MAIN",
                "-n xyz.aethersx2.android/xyz.aethersx2.android.EmulationActivity",
                "-e bootPath \"{file.path}\"",
            ),
            LauncherPresets.NETHER_SX2.launchLines(),
        )
    }

    @Test
    fun ppssppPreset_launchLines() {
        assertEquals(
            listOf(
                "am start --user 0",
                "-a android.intent.action.VIEW",
                "-n org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity",
                "-d \"file://{file.path}\"",
            ),
            LauncherPresets.PPSSPP.launchLines(),
        )
    }

    @Test
    fun viewIntent_alwaysUsesActionViewAndFileUri() {
        val p = LauncherProfile(
            type = LauncherType.VIEW_INTENT,
            packageName = "com.example.emu",
            activity = "com.example.emu.PlayActivity",
        )
        assertEquals(
            listOf(
                "am start --user 0",
                "-a android.intent.action.VIEW",
                "-n com.example.emu/com.example.emu.PlayActivity",
                "-d \"{file.uri}\"",
            ),
            p.launchLines(),
        )
    }

    @Test
    fun customCommand_storedVerbatim() {
        val cmd = "am start --user 0\n  -n com.example/.Main\n  -e ROM \"{file.path}\""
        val p = LauncherProfile(type = LauncherType.CUSTOM, command = cmd)
        assertEquals(cmd.lines(), p.launchLines())
    }

    @Test
    fun isConfigured_requiresAllFields() {
        assertTrue(LauncherPresets.retroArch(core = "x.so").isConfigured())
        assertFalse(LauncherPresets.retroArch(core = "").isConfigured())
        assertFalse(LauncherProfile(type = LauncherType.RETROARCH, core = "x.so").isConfigured())

        assertTrue(LauncherPresets.NETHER_SX2.isConfigured())
        assertFalse(LauncherPresets.NETHER_SX2.copy(extraKey = "").isConfigured())
        assertFalse(LauncherPresets.NETHER_SX2.copy(action = "").isConfigured())

        val view = LauncherProfile(
            type = LauncherType.VIEW_INTENT, packageName = "a.b", activity = "a.b.C",
        )
        assertTrue(view.isConfigured())
        assertFalse(view.copy(activity = "").isConfigured())

        assertTrue(LauncherProfile(type = LauncherType.CUSTOM, command = "x").isConfigured())
        assertFalse(LauncherProfile(type = LauncherType.CUSTOM).isConfigured())
    }

    @Test
    fun defaultProfile_verifiedSystems() {
        assertEquals(LauncherPresets.NETHER_SX2, LauncherPresets.defaultProfile("ps2"))
        assertEquals(LauncherPresets.PPSSPP, LauncherPresets.defaultProfile("psp"))
    }

    @Test
    fun defaultProfile_retroArchCoreSystems() {
        val gba = LauncherPresets.defaultProfile("gba")
        assertNotNull(gba)
        assertEquals(LauncherType.RETROARCH, gba!!.type)
        assertEquals("mgba_libretro_android.so", gba.core)
        assertEquals("com.retroarch.aarch64", gba.packageName)
    }

    @Test
    fun defaultProfile_notConfiguredSystems() {
        // n3ds / gamecube / saturn / 3do / amiga / c64: no verified core or
        // standalone intent — NOT CONFIGURED, never a fabricated command.
        listOf("n3ds", "gamecube", "saturn", "3do", "amiga", "c64").forEach { slug ->
            assertNull("slug $slug must have no default profile", LauncherPresets.defaultProfile(slug))
        }
    }

    @Test
    fun defaultProfile_arcade_neverSilentlyPicks() {
        // Several equally-valid cores (mame2003-plus, fbneo) — CUSTOM only.
        assertNull(LauncherPresets.defaultProfile("arcade"))
    }

    @Test
    fun standaloneName_knownPackages() {
        assertEquals("NETHERSX2", LauncherPresets.standaloneName("xyz.aethersx2.android"))
        assertEquals("PPSSPP", LauncherPresets.standaloneName("org.ppsspp.ppsspp"))
        assertEquals("PPSSPP GOLD", LauncherPresets.standaloneName("org.ppsspp.ppssppgold"))
    }

    @Test
    fun knownEmulatorPackages_coversAllStandalonePresets() {
        listOf(
            LauncherPresets.NETHER_SX2,
            LauncherPresets.PPSSPP,
            LauncherPresets.PPSSPP_GOLD,
        ).forEach { p ->
            assertTrue(
                "${p.packageName} missing from knownEmulatorPackages",
                LauncherPresets.knownEmulatorPackages.contains(p.packageName),
            )
        }
    }

    @Test
    fun standaloneFor_verifiedSystemsOnly() {
        assertEquals(listOf(LauncherPresets.NETHER_SX2), LauncherPresets.standaloneFor("ps2"))
        assertEquals(
            listOf(LauncherPresets.PPSSPP, LauncherPresets.PPSSPP_GOLD),
            LauncherPresets.standaloneFor("psp"),
        )
        assertTrue(LauncherPresets.standaloneFor("gba").isEmpty())
        assertTrue(LauncherPresets.standaloneFor("n3ds").isEmpty())
    }

    @Test
    fun displayLabel() {
        assertEquals("RETROARCH + mgba_libretro_android.so", LauncherPresets.retroArch(core = "mgba_libretro_android.so").displayLabel())
        assertEquals("NETHERSX2", LauncherPresets.NETHER_SX2.displayLabel())
        assertEquals("VIEW INTENT", LauncherProfile(type = LauncherType.VIEW_INTENT, packageName = "a", activity = "b").displayLabel())
        assertEquals("CUSTOM", LauncherProfile(type = LauncherType.CUSTOM, command = "x").displayLabel())
    }
}
