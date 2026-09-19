package io.crystalnova.manager.launcher

import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.pegasus.LauncherProfile
import io.crystalnova.manager.pegasus.LauncherProfileStore
import io.crystalnova.manager.pegasus.LauncherSource
import io.crystalnova.manager.pegasus.LauncherType
import io.crystalnova.manager.pegasus.PathHandoff
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.storage.InMemoryThemeFs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 1 launcher bridge: the Manager → Crystal Launcher data contract
 * (crystal-launcher/docs/contract.md §§3,7) is covered by JVM unit tests.
 * The launcher side is a tolerant reader (ignores unknown fields), so
 * these tests pin the exact schema we emit.
 */
class LauncherExportTest {

    private class FakePrefs : KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
    }

    // ------------------------------------------------------------------
    // config.json (contract §3)
    // ------------------------------------------------------------------

    @Test fun `config json carries the exact contract schema`() {
        val json = LauncherExport.buildConfigJson(
            romRoot = "/storage/1234-ABCD/roms",
            dataRoot = "/storage/1234-ABCD/crystal-nova-data",
            updatedEpochSeconds = 1720000000L,
        )
        val o = JSONObject(json)
        assertEquals(1, o.getInt("version"))
        assertEquals("/storage/1234-ABCD/roms", o.getString("romRoot"))
        assertEquals("/storage/1234-ABCD/crystal-nova-data", o.getString("dataRoot"))
        assertEquals(
            "/storage/1234-ABCD/crystal-nova-data/index.json",
            o.getString("indexPath"),
        )
        assertEquals(1720000000L, o.getLong("updated"))
    }

    // ------------------------------------------------------------------
    // launcher/profiles.json (contract §7)
    // ------------------------------------------------------------------

    @Test fun `profiles json mirrors LauncherProfile field for field`() {
        val entries = listOf(
            LauncherExport.ExportedProfile(
                slug = "ps2",
                profile = LauncherProfile(
                    type = LauncherType.STANDALONE,
                    packageName = "xyz.aethersx2.android",
                    activity = "xyz.aethersx2.android.EmulationActivity",
                    action = "android.intent.action.MAIN",
                    handoff = PathHandoff.EXTRA,
                    extraKey = "bootPath",
                ),
                source = LauncherSource.AUTO,
            ),
            LauncherExport.ExportedProfile(
                slug = "gba",
                profile = LauncherProfile(
                    type = LauncherType.RETROARCH,
                    packageName = "com.retroarch.aarch64",
                    core = "mgba_libretro_android.so",
                ),
                source = LauncherSource.USER,
            ),
        )
        val o = JSONObject(LauncherExport.buildProfilesJson(entries))
        assertEquals(1, o.getInt("version"))
        val profiles = o.getJSONObject("profiles")

        val ps2 = profiles.getJSONObject("ps2")
        assertEquals("STANDALONE", ps2.getString("type"))
        assertEquals("xyz.aethersx2.android", ps2.getString("package"))
        assertEquals("xyz.aethersx2.android.EmulationActivity", ps2.getString("activity"))
        assertEquals("android.intent.action.MAIN", ps2.getString("action"))
        assertEquals("EXTRA", ps2.getString("handoff"))
        assertEquals("bootPath", ps2.getString("extraKey"))
        assertEquals("AUTO", ps2.getString("source"))
        // Every LauncherProfile field is emitted explicitly.
        assertTrue(ps2.has("core"))
        assertTrue(ps2.has("dataPrefix"))
        assertTrue(ps2.has("grantUriPermission"))
        assertTrue(ps2.has("command"))

        val gba = profiles.getJSONObject("gba")
        assertEquals("RETROARCH", gba.getString("type"))
        assertEquals("mgba_libretro_android.so", gba.getString("core"))
        assertEquals("USER", gba.getString("source"))
    }

    @Test fun `collectProfiles prefers stored choice and keeps its source flag`() {
        val prefs = FakePrefs()
        val store = LauncherProfileStore(prefs)
        val userPick = LauncherProfile(
            type = LauncherType.RETROARCH,
            packageName = "com.retroarch",
            core = "mgba_libretro_android.so",
        )
        store.set("gba", userPick, LauncherSource.USER)

        val bySlug = LauncherExport.collectProfiles(store).associateBy { it.slug }
        val gba = bySlug.getValue("gba")
        assertEquals(userPick, gba.profile)
        assertEquals(LauncherSource.USER, gba.source)
    }

    @Test fun `collectProfiles falls back to curated defaults as AUTO`() {
        val store = LauncherProfileStore(FakePrefs())
        val bySlug = LauncherExport.collectProfiles(store).associateBy { it.slug }
        val ps2 = bySlug.getValue("ps2")
        assertEquals("xyz.aethersx2.android", ps2.profile.packageName)
        assertEquals(LauncherSource.AUTO, ps2.source)
        // A RetroArch default is included too.
        assertTrue(bySlug.containsKey("gba"))
    }

    @Test fun `collectProfiles omits slugs with no usable recipe`() {
        val prefs = FakePrefs()
        val store = LauncherProfileStore(prefs)
        // Stored but unconfigured (blank CUSTOM command): not a recipe.
        store.set(
            "saturn",
            LauncherProfile(type = LauncherType.CUSTOM, command = ""),
            LauncherSource.USER,
        )
        val slugs = LauncherExport.collectProfiles(store).map { it.slug }
        assertFalse(slugs.contains("saturn"))
        // No default and no stored choice: omitted as well.
        assertFalse(slugs.contains("3do"))
    }

    @Test fun `collectProfiles keeps an explicitly stored AUTO entry as AUTO`() {
        val prefs = FakePrefs()
        val store = LauncherProfileStore(prefs)
        val auto = LauncherProfile(
            type = LauncherType.STANDALONE,
            packageName = "org.ppsspp.ppsspp",
            activity = "org.ppsspp.ppsspp.PpssppActivity",
            action = "android.intent.action.VIEW",
            handoff = PathHandoff.DATA,
            dataPrefix = "file://",
        )
        store.set("psp", auto, LauncherSource.AUTO)
        val psp = LauncherExport.collectProfiles(store).associateBy { it.slug }.getValue("psp")
        assertEquals(LauncherSource.AUTO, psp.source)
        assertEquals("DATA", psp.profile.handoff.name)
        assertEquals("file://", psp.profile.dataPrefix)
    }

    // ------------------------------------------------------------------
    // Root resolution
    // ------------------------------------------------------------------

    @Test fun `resolveDataRoot prefers the dedicated media folder`() {
        val root = LauncherExport.resolveDataRoot(
            mediaTreeUri = "media-uri",
            themesTreeUri = "themes-uri",
            canonicalPath = { uri ->
                when (uri) {
                    "media-uri" -> "/storage/1234-ABCD/Media"
                    "themes-uri" -> "/storage/1234-ABCD/Themes"
                    else -> null
                }
            },
        )
        assertEquals("/storage/1234-ABCD/Media", root)
    }

    @Test fun `resolveDataRoot falls back to crystal-nova-data under the themes tree`() {
        val root = LauncherExport.resolveDataRoot(
            mediaTreeUri = null,
            themesTreeUri = "themes-uri",
            canonicalPath = { uri ->
                if (uri == "themes-uri") "/storage/1234-ABCD/Themes" else null
            },
        )
        assertEquals("/storage/1234-ABCD/Themes/crystal-nova-data", root)
    }

    @Test fun `resolveDataRoot is null when no root is usable`() {
        assertNull(
            LauncherExport.resolveDataRoot(null, null) { null },
        )
        assertNull(
            LauncherExport.resolveDataRoot(null, "themes-uri") { null },
        )
    }

    // ------------------------------------------------------------------
    // Storage round-trip
    // ------------------------------------------------------------------

    @Test fun `config json round-trips through ScraperStorage`() {
        val s = ScraperStorage(InMemoryThemeFs())
        val json = LauncherExport.buildConfigJson("/r", "/d", 42L)
        assertTrue(s.saveLauncherConfigJson(json))
        assertEquals(json, s.readBytes("config.json")?.toString(Charsets.UTF_8))
    }

    @Test fun `profiles json round-trips under launcher dir`() {
        val s = ScraperStorage(InMemoryThemeFs())
        val json = LauncherExport.buildProfilesJson(emptyList())
        assertTrue(s.saveLauncherProfilesJson(json))
        assertEquals(json, s.readBytes("launcher/profiles.json")?.toString(Charsets.UTF_8))
        // The export does not disturb the index.
        assertNull(s.readBytes("index.json"))
    }
}
