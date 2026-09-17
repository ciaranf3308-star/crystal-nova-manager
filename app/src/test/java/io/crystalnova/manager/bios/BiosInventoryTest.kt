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
 * platform whose firmware is tracked and the only one that can gate
 * READY; other platforms are out of scope for v24 and never gate.
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
    fun ps2_nonScphBin_atBiosSize_isNowACandidate() {
        // Hardware truth (2026-09-17): a working EmuDeck tree names
        // dumps all kinds of things. A BIOS-sized .bin is honestly a
        // candidate — NetherSX2 validates the pick, and attestation
        // still gates READY. This supersedes the old SCPH-only rule.
        val random = BiosFile("random.bin", 4_194_304L, "random.bin")
        val inv = inventory(files = listOf(random))
        assertEquals(BiosStatus.IMPORT_REQUIRED, inv.ps2Status(5, listOf(random)))
        assertEquals(BiosIssue("PS2", "BIOS REQUIRED"), inv.ps2Issue(5, listOf(random)))
        val detection = inv.detectPs2(listOf(random))
        assertEquals(1, detection.candidates.size)
        assertEquals(
            Ps2CandidateConfidence.CANDIDATE,
            detection.candidates[0].confidence,
        )
    }

    @Test
    fun ps2_requiredMissing_whenTinyRandomBin() {
        // Not BIOS-shaped by name and not sane by size: still missing.
        val random = BiosFile("random.bin", 12345L, "random.bin")
        val inv = inventory(files = listOf(random))
        assertEquals(BiosStatus.REQUIRED_MISSING, inv.ps2Status(5, listOf(random)))
    }

    @Test
    fun ps2_importRequired_whenRom0DumpFound() {
        val rom0 = BiosFile("rom0", 4_194_304L, "bios/dump/rom0")
        val inv = inventory(files = listOf(rom0))
        assertEquals(BiosStatus.IMPORT_REQUIRED, inv.ps2Status(5, listOf(rom0)))
        assertEquals(BiosIssue("PS2", "BIOS REQUIRED"), inv.ps2Issue(5, listOf(rom0)))
    }

    @Test
    fun ps2_importRequired_whenBiosBetween4And8MiB() {
        // Sane PCSX2 range, not exactly 4 MiB.
        val big = BiosFile("scph70012.bin", 6_291_456L, "scph70012.bin")
        val inv = inventory(files = listOf(big))
        assertEquals(BiosStatus.IMPORT_REQUIRED, inv.ps2Status(5, listOf(big)))
    }

    @Test
    fun ps2_unverified_whenAncillaryOnly() {
        // Dump artifacts prove PS2 firmware files exist, but none is
        // the importable BIOS — FOUND_UNVERIFIED, never MISSING and
        // never a verified main BIOS.
        val files = listOf(
            BiosFile("nvm.bin", 1024L, "ps2/nvm.bin"),
            BiosFile("rom1.bin", 2048L, "ps2/rom1.bin"),
        )
        val inv = inventory(files = files)
        assertEquals(BiosStatus.FOUND_UNVERIFIED, inv.ps2Status(5, files))
        assertEquals(BiosIssue("PS2", "BIOS UNVERIFIED"), inv.ps2Issue(5, files))
        assertTrue(inv.detectPs2(files).candidates.isEmpty())
    }

    @Test
    fun ps2_requiredMissing_whenUnrelatedFolder() {
        val files = listOf(
            BiosFile("notes.txt", 100L, "notes.txt"),
            BiosFile("cover.png", 50_000L, "art/cover.png"),
        )
        val inv = inventory(files = files)
        assertEquals(BiosStatus.REQUIRED_MISSING, inv.ps2Status(5, files))
        assertEquals(BiosIssue("PS2", "BIOS MISSING"), inv.ps2Issue(5, files))
    }

    @Test
    fun ps2_multipleCandidates_strongestFirst() {
        val generic = BiosFile("dump.bin", 4_194_304L, "dump.bin")
        val strong = BiosFile("scph39001.bin", 4_194_304L, "ps2/scph39001.bin")
        val inv = inventory(files = listOf(generic, strong))
        assertEquals(BiosStatus.IMPORT_REQUIRED, inv.ps2Status(5, listOf(generic, strong)))
        val candidates = inv.detectPs2(listOf(generic, strong)).candidates
        assertEquals(listOf(strong, generic), candidates.map { it.file })
    }

    @Test
    fun ps2_ready_whenCandidateImportConfirmed() {
        // Attestation still gates READY for plausible candidates too.
        val prefs = FakeBiosPrefs()
        val dump = BiosFile("mydump.bin", 5_000_000L, "mydump.bin")
        val inv = inventory(prefs = prefs, files = listOf(dump))
        assertEquals(BiosStatus.IMPORT_REQUIRED, inv.ps2Status(5, listOf(dump)))
        inv.setPs2ImportConfirmed(true)
        assertEquals(BiosStatus.READY, inv.ps2Status(5, listOf(dump)))
        assertNull(inv.ps2Issue(5, listOf(dump)))
    }

    @Test
    fun ps2_detection_isCaseInsensitive_andNested() {
        val upper = BiosFile("SCPH70012.BIN", 4_194_304L, "EmuDeck/bios/SCPH70012.BIN")
        val inv = inventory(files = listOf(upper))
        val detection = inv.detectPs2(listOf(upper))
        assertEquals(upper, detection.candidates.firstOrNull()?.file)
        assertEquals(
            Ps2CandidateConfidence.STRONG,
            detection.candidates.firstOrNull()?.confidence,
        )
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

    // ---------- firmware table honesty (v24 is PS2-first) ----------

    @Test
    fun firmwareTable_isPs2Only() {
        // No other platform may claim a verified firmware status:
        // Saturn has no configured launcher, and PS1/Dreamcast
        // firmware is out of scope for v24 — nothing optional is
        // presented as READY without being scanned.
        assertEquals(
            listOf("ps2"),
            BiosFirmwareTable.statusScreenPlatforms().map { it.platformSlug },
        )
        assertNull(BiosFirmwareTable.forPlatform("psx"))
        assertNull(BiosFirmwareTable.forPlatform("saturn"))
        assertNull(BiosFirmwareTable.forPlatform("dreamcast"))
        assertNull(BiosFirmwareTable.forPlatform("gba"))
        assertNull(BiosFirmwareTable.forPlatform("nope"))
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
