package io.crystalnova.manager.storage

import android.content.Context
import android.content.Intent
import io.crystalnova.manager.data.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory KeyValueStore; no Android, no persistence. */
private class FakeStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

/**
 * Pure-JVM tests for [StorageLocations]: URI parsing, friendly paths,
 * bridge JSON, and the adopt/clear persistence round-trip. No
 * Robolectric; the ContentResolver permission take is exercised only
 * through [StorageLocations.adoptTreeUri] on device, so the tests use
 * [StorageLocations.adoptTreeUriString]. Context is never touched here.
 */
class StorageLocationsTest {

    private val internalMediaTree =
        "content://com.android.externalstorage.documents/tree/primary%3ACrystalNova%2FMedia"
    private val sdMediaTree =
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMedia"
    private val themesTree =
        "content://com.android.externalstorage.documents/tree/primary%3Athemes"

    private fun locations(
        store: KeyValueStore = FakeStore(),
        writer: ((String, ByteArray) -> Boolean)? = null,
        deleter: ((String) -> Boolean)? = null,
        // android.util.Log throws under JVM unit tests; swallow it here.
        logger: (String, String, Throwable?) -> Unit = { _, _, _ -> },
    ) = StorageLocations(null as Context?, store, writer, deleter, logger)

    // ---------- canonicalPath ----------

    @Test
    fun `canonicalPath maps primary volume to emulated storage`() {
        assertEquals(
            "/storage/emulated/0/CrystalNova/Media",
            locations().canonicalPath(internalMediaTree),
        )
    }

    @Test
    fun `canonicalPath maps UUID volume to removable storage`() {
        assertEquals(
            "/storage/1234-ABCD/Media",
            locations().canonicalPath(sdMediaTree),
        )
    }

    @Test
    fun `canonicalPath returns null for garbage`() {
        assertNull(locations().canonicalPath("not-a-uri"))
    }

    @Test
    fun `canonicalPath returns null for empty string`() {
        assertNull(locations().canonicalPath(""))
    }

    @Test
    fun `canonicalPath returns null for tree uri without document id`() {
        assertNull(
            locations().canonicalPath("content://com.android.externalstorage.documents/tree/"),
        )
    }

    @Test
    fun `canonicalPath returns null for non-tree content uri`() {
        assertNull(
            locations().canonicalPath("content://com.android.externalstorage.documents/document/primary%3Afoo"),
        )
    }

    // ---------- isRemovable ----------

    @Test
    fun `isRemovable false for primary`() {
        assertFalse(locations().isRemovable(internalMediaTree))
    }

    @Test
    fun `isRemovable true for UUID volume`() {
        assertTrue(locations().isRemovable(sdMediaTree))
    }

    @Test
    fun `isRemovable false for garbage`() {
        assertFalse(locations().isRemovable("garbage"))
    }

    // ---------- displayPath ----------

    @Test
    fun `displayPath labels internal storage`() {
        assertEquals(
            "INTERNAL STORAGE /CrystalNova/Media",
            locations().displayPath(internalMediaTree),
        )
    }

    @Test
    fun `displayPath labels SD card`() {
        assertEquals("SD CARD /Media", locations().displayPath(sdMediaTree))
    }

    @Test
    fun `displayPath reports unavailable for unparseable uri`() {
        assertEquals("LOCATION UNAVAILABLE", locations().displayPath("garbage"))
    }

    @Test
    fun `displayPath never leaks a raw content uri`() {
        val shown = locations().displayPath(internalMediaTree)
        assertFalse(shown.startsWith("content://"))
    }

    // ---------- bridgeJson ----------

    @Test
    fun `bridgeJson has exact format`() {
        assertEquals(
            "{\"version\":1,\"mediaRoot\":\"/storage/emulated/0/CrystalNova/Media\",\"updated\":1700000000}",
            locations().bridgeJson("/storage/emulated/0/CrystalNova/Media", 1700000000),
        )
    }

    @Test
    fun `bridgeJson escapes quotes in path`() {
        val json = locations().bridgeJson("/sd/odd\"name", 1)
        assertTrue(json.contains("\"mediaRoot\":\"/sd/odd\\\"name\""))
    }

    // ---------- userColorsJson ----------

    @Test
    fun `userColorsJson has exact format`() {
        assertEquals(
            "{\"version\":1,\"background\":\"#0a1929\",\"accent\":\"#7ba7d9\"," +
                "\"cream\":\"#f0ebdc\",\"joystick\":\"#ffc93c\",\"updated\":1700000000}",
            locations().userColorsJson("#0a1929", "#7ba7d9", "#f0ebdc", "#ffc93c", 1700000000),
        )
    }

    @Test
    fun `user colors file name and min theme version are stable`() {
        assertEquals("crystal-user-colors.json", StorageLocations.USER_COLORS_FILE_NAME)
        assertEquals("2026.09.18.11-user-colors", StorageLocations.USER_COLORS_MIN_THEME_VERSION)
    }

    // ---------- adopt / clear round-trip ----------

    @Test
    fun `rom reuses legacy games_tree_uri key`() {
        assertEquals("games_tree_uri", StorageLocations.KEY_ROM_TREE_URI)
        assertEquals("media_tree_uri", StorageLocations.KEY_MEDIA_TREE_URI)
    }

    @Test
    fun `adopt rom persists without touching the bridge`() {
        val store = FakeStore()
        val loc = locations(
            store,
            writer = { _, _ -> fail("bridge must not be written for ROM"); false },
        )
        assertTrue(loc.adoptTreeUriString("content://x/tree/primary%3AROMs", LocationKind.ROM, null))
        assertEquals("content://x/tree/primary%3AROMs", loc.romTreeUri())
        assertNull(loc.mediaTreeUri())
    }

    @Test
    fun `adopt media persists uri and writes bridge with canonical path`() {
        val store = FakeStore()
        var written: Pair<String, String>? = null
        val loc = locations(
            store,
            writer = { themes, bytes ->
                written = themes to bytes.toString(Charsets.UTF_8)
                true
            },
        )
        assertTrue(loc.adoptTreeUriString(internalMediaTree, LocationKind.MEDIA, themesTree))
        assertEquals(internalMediaTree, loc.mediaTreeUri())

        val pair = written ?: throw AssertionError("bridge was not written")
        val (themes, json) = pair
        assertEquals(themesTree, themes)
        assertTrue(
            json.startsWith(
                "{\"version\":1,\"mediaRoot\":\"/storage/emulated/0/CrystalNova/Media\",\"updated\":",
            ),
        )
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun `adopt media with unparseable uri persists but leaves bridge alone`() {
        val store = FakeStore()
        val loc = locations(
            store,
            writer = { _, _ -> fail("bridge must be skipped when path unavailable"); false },
        )
        assertTrue(loc.adoptTreeUriString("garbage", LocationKind.MEDIA, themesTree))
        assertEquals("garbage", loc.mediaTreeUri())
    }

    @Test
    fun `clear media removes pref and deletes bridge`() {
        val store = FakeStore()
        var deleted: String? = null
        val loc = locations(
            store,
            writer = { _, _ -> true },
            deleter = { themes -> deleted = themes; true },
        )
        loc.adoptTreeUriString(internalMediaTree, LocationKind.MEDIA, themesTree)
        assertEquals(internalMediaTree, loc.mediaTreeUri())

        loc.clearLocation(LocationKind.MEDIA, themesTree)
        assertNull(loc.mediaTreeUri())
        assertEquals(themesTree, deleted)
    }

    @Test
    fun `clear rom removes pref without touching bridge`() {
        val store = FakeStore()
        val loc = locations(
            store,
            writer = { _, _ -> fail("bridge must not be written for ROM"); false },
            deleter = { _ -> fail("bridge must not be deleted for ROM"); false },
        )
        loc.adoptTreeUriString("content://x/tree/primary%3AROMs", LocationKind.ROM, null)
        loc.clearLocation(LocationKind.ROM, null)
        assertNull(loc.romTreeUri())
    }

    @Test
    fun `summary reports not configured when prefs empty`() {
        val summary = locations().summary()
        assertEquals(LocationState.NotConfigured, summary.rom)
        assertEquals(LocationState.NotConfigured, summary.media)
    }

    @Test
    fun `displayPathFor reports not configured when pref missing`() {
        val loc = locations()
        assertEquals("NOT CONFIGURED", loc.displayPathFor(LocationKind.ROM))
        assertEquals("NOT CONFIGURED", loc.displayPathFor(LocationKind.MEDIA))
    }

    // ---------- ESDE (read-only import root) ----------

    private val sdEsdeTree =
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3ACrystal%2Fimports%2Fesde"

    @Test
    fun `canonicalPath resolves the ES-DE export root on the SD card`() {
        assertEquals(
            "/storage/1234-ABCD/Crystal/imports/esde",
            locations().canonicalPath(sdEsdeTree),
        )
    }

    @Test
    fun `adopt and clear round-trip the ES-DE tree URI`() {
        val loc = locations()
        assertTrue(loc.adoptTreeUriString(sdEsdeTree, LocationKind.ESDE))
        assertEquals(sdEsdeTree, loc.esdeTreeUri())
        loc.clearLocation(LocationKind.ESDE)
        assertNull(loc.esdeTreeUri())
    }

    @Test
    fun `adopting ES-DE never touches ROM or MEDIA prefs`() {
        val loc = locations()
        assertTrue(loc.adoptTreeUriString(sdEsdeTree, LocationKind.ESDE))
        assertNull(loc.romTreeUri())
        assertNull(loc.mediaTreeUri())
    }

    @Test
    fun `displayPathFor ES-DE shows the SD card path`() {
        val loc = locations()
        loc.adoptTreeUriString(sdEsdeTree, LocationKind.ESDE)
        assertEquals(
            "SD CARD /Crystal/imports/esde",
            loc.displayPath(sdEsdeTree),
        )
    }

    @Test
    fun `grantFlags takes read-only for the ES-DE export`() {
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            StorageLocations.grantFlags(readOnly = true),
        )
    }

    @Test
    fun `grantFlags takes read-write by default`() {
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            StorageLocations.grantFlags(readOnly = false),
        )
    }
}
