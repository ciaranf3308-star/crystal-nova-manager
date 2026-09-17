package io.crystalnova.manager.bios

import io.crystalnova.manager.data.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory KeyValueStore for the BIOS tests. */
private class FakeBiosPrefs : KeyValueStore {
    private val strings = mutableMapOf<String, String>()
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }
    override fun remove(key: String) { strings.remove(key) }
}

/**
 * PS2 firmware state machine + inventory rules. PS2 is the only
 * platform that can gate READY: optional/HLE platforms always report
 * READY, everything else NOT_REQUIRED.
 */
class BiosInventoryTest {

    private val romUri = "content://com.android.externalstorage.documents/tree/1234-ABCD%3Aroms"
    private val biosUri = "content://com.android.externalstorage.documents/tree/1234-ABCD%3Abios"
    private val goodBios = BiosFile("scph39001.bin", 4_194_304L, "ps2/scph39001.bin")

    private fun inventory(
        prefs: KeyValueStore = FakeBiosPrefs(),
        romTreeUri: String? = romUri,
        files: List<BiosFile>? = null,
    ) = BiosInventory(
        context = null,
        prefs = prefs,
        romTreeUriProvider = { romTreeUri },
        lister = { files },
    )

    // ---------- PS2 status ----------

    @Test
    fun ps2_notRequired_whenNoGames() {
        val inv = inventory()
        assertEquals(BiosStatus.NOT_REQUIRED, inv.ps2Status(0, null))
        assertEquals(BiosStatus.NOT_REQUIRED, inv.ps2Status(0, listOf(goodBios)))
        assertNull(inv.ps2Issue(0, null))
    }

    @Test
    fun ps2_requiredMissing_whenNoGrant() {
        val inv = inventory()
        assertEquals(BiosStatus.REQUIRED_MISSING, inv.ps2Status(5, null))
        assertEquals(BiosIssue("PS2", "BIOS MISSING"), inv.ps2Issue(5, null))
    }

    @Test
    fun ps2_requiredMissing_whenFolderEmpty() {
        val inv = inventory(files = emptyList())
        assertEquals(BiosStatus.REQUIRED_MISSING, inv.ps2Status(5, emptyList()))
    }

    @Test
    fun ps2_importRequired_whenValidBiosFound() {
        val inv = inventory(files = listOf(goodBios))
        assertEquals(BiosStatus.IMPORT_REQUIRED, inv.ps2Status(5, listOf(goodBios)))
        assertEquals(BiosIssue("PS2", "BIOS REQUIRED"), inv.ps2Issue(5, listOf(goodBios)))
    }

    @Test
    fun ps2_ready_whenImportConfirmed() {
        val prefs = FakeBiosPrefs()
        val inv = inventory(prefs = prefs, files = listOf(goodBios))
        inv.setPs2ImportConfirmed(true)
        assertTrue(inv.isPs2ImportConfirmed())
        assertEquals(BiosStatus.READY, inv.ps2Status(5, listOf(goodBios)))
        assertNull(inv.ps2Issue(5, listOf(goodBios)))
    }

    @Test
    fun ps2_unverified_whenSizeMismatch() {
        val wrong = BiosFile("scph39001.bin", 12345L, "scph39001.bin")
        val inv = inventory(files = listOf(wrong))
        assertEquals(BiosStatus.FOUND_UNVERIFIED, inv.ps2Status(5, listOf(wrong)))
        assertEquals(BiosIssue("PS2", "BIOS UNVERIFIED"), inv.ps2Issue(5, listOf(wrong)))
    }

    @Test
    fun ps2_requiredMissing_whenRandomBin() {
        // A random .bin must never read as a BIOS.
        val random = BiosFile("random.bin", 4_194_304L, "random.bin")
        val inv = inventory(files = listOf(random))
        assertEquals(BiosStatus.REQUIRED_MISSING, inv.ps2Status(5, listOf(random)))
    }

    @Test
    fun ps2_detection_isCaseInsensitive_andNested() {
        val upper = BiosFile("SCPH70012.BIN", 4_194_304L, "EmuDeck/bios/SCPH70012.BIN")
        val inv = inventory(files = listOf(upper))
        assertEquals(upper, inv.detectPs2Bios(listOf(upper)))
        assertEquals(BiosStatus.IMPORT_REQUIRED, inv.ps2Status(5, listOf(upper)))
    }

    @Test
    fun ps2_importConfirmation_resetsOnNewAdopt() {
        val prefs = FakeBiosPrefs()
        prefs.putString(BiosInventory.KEY_BIOS_TREE_URI, biosUri)
        prefs.putString(BiosInventory.KEY_PS2_IMPORT_CONFIRMED, "1")
        val inv = inventory(prefs = prefs)
        inv.clearBiosFolder()
        assertFalse(inv.isPs2ImportConfirmed())
        assertNull(inv.biosTreeUri())
    }

    // ---------- other platforms never gate ----------

    @Test
    fun optionalPlatforms_reportReady() {
        val inv = inventory()
        assertEquals(BiosStatus.READY, inv.statusForPlatform("psx"))
        assertEquals(BiosStatus.READY, inv.statusForPlatform("saturn"))
        assertEquals(BiosStatus.READY, inv.statusForPlatform("dreamcast"))
    }

    @Test
    fun irrelevantPlatforms_reportNotRequired() {
        val inv = inventory()
        assertEquals(BiosStatus.NOT_REQUIRED, inv.statusForPlatform("gba"))
        assertEquals(BiosStatus.NOT_REQUIRED, inv.statusForPlatform("nope"))
    }

    // ---------- root probe ----------

    @Test
    fun probe_noRomRoot() {
        val inv = inventory(romTreeUri = null)
        assertEquals(BiosRootState.NoRomRoot, inv.probeRoot())
    }

    @Test
    fun probe_notGranted_derivesCandidate() {
        val inv = inventory()
        val state = inv.probeRoot()
        assertTrue(state is BiosRootState.NotGranted)
        state as BiosRootState.NotGranted
        assertEquals(biosUri, state.candidateTreeUri)
        assertEquals("SD CARD /bios", state.displayPath)
    }

    @Test
    fun probe_grantedPref_readsDisplayPath() {
        val prefs = FakeBiosPrefs()
        prefs.putString(BiosInventory.KEY_BIOS_TREE_URI, biosUri)
        val inv = inventory(prefs = prefs)
        // context == null in JVM tests, so hasBiosAccess() is false and
        // the probe falls back to re-pick at the preferred location.
        val state = inv.probeRoot()
        assertTrue(state is BiosRootState.NotGranted)
    }

    @Test
    fun displayPathFor_biosTree() {
        val inv = inventory()
        assertEquals("SD CARD /bios", inv.displayPathFor(biosUri))
        assertEquals(
            "INTERNAL STORAGE /bios",
            inv.displayPathFor("content://com.android.externalstorage.documents/tree/primary%3Abios"),
        )
        assertEquals("LOCATION UNAVAILABLE", inv.displayPathFor("bogus"))
    }

    @Test
    fun scan_nullWithoutGrant() {
        // No persisted BIOS URI: nothing to scan, never an exception.
        assertNull(inventory().scan())
    }
}
