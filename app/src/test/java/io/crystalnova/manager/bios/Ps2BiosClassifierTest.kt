package io.crystalnova.manager.bios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PS2 BIOS candidate classifier — the hardware-truth regression
 * suite. A working EmuDeck BIOS tree (copied from a ROG Ally, ~20
 * PS2 dump-related files, all kinds of names) was reported MISSING
 * by the old `SCPH*.bin`-at-exactly-4-MiB rule. The classifier must
 * be generous about finding candidates and strict about trusting
 * them: candidates are never READY without import attestation.
 */
class Ps2BiosClassifierTest {

    private fun f(name: String, size: Long, rel: String = name) =
        BiosFile(name, size, rel)

    // ---------- main candidates ----------

    @Test
    fun scphBin_at4MiB_isStrong() {
        val d = Ps2BiosClassifier.classify(listOf(f("scph39001.bin", 4_194_304L)))
        assertEquals(1, d.candidates.size)
        assertEquals(Ps2CandidateConfidence.STRONG, d.candidates[0].confidence)
        assertTrue(d.ancillary.isEmpty())
        assertTrue(d.misSized.isEmpty())
    }

    @Test
    fun scphBin_between4And8MiB_isStrong() {
        // A 6 MiB dump is inside the sane PCSX2 range — not exactly
        // 4 MiB, still a strong candidate.
        val d = Ps2BiosClassifier.classify(listOf(f("SCPH-70012.bin", 6_291_456L)))
        assertEquals(1, d.candidates.size)
        assertEquals(Ps2CandidateConfidence.STRONG, d.candidates[0].confidence)
    }

    @Test
    fun scphBin_atExactly8MiB_isCandidate_at8MiBPlus1_isMisSized() {
        val atMax = Ps2BiosClassifier.classify(listOf(f("scph39001.bin", 8_388_608L)))
        assertEquals(1, atMax.candidates.size)
        val pastMax = Ps2BiosClassifier.classify(listOf(f("scph39001.bin", 8_388_609L)))
        assertTrue(pastMax.candidates.isEmpty())
        assertEquals(1, pastMax.misSized.size)
    }

    @Test
    fun nonScphBin_atBiosSize_isCandidate() {
        // The hardware case: dumps named all kinds of things.
        val d = Ps2BiosClassifier.classify(listOf(f("ps2_bios_dump.bin", 4_194_304L)))
        assertEquals(1, d.candidates.size)
        assertEquals(Ps2CandidateConfidence.CANDIDATE, d.candidates[0].confidence)
    }

    @Test
    fun rom0Dump_isCandidate() {
        val d = Ps2BiosClassifier.classify(
            listOf(f("rom0", 4_194_304L, "dumps/ps2/rom0")),
        )
        assertEquals(1, d.candidates.size)
        assertEquals(Ps2CandidateConfidence.CANDIDATE, d.candidates[0].confidence)
        assertEquals("dumps/ps2/rom0", d.candidates[0].file.relativePath)
    }

    @Test
    fun nestedCandidates_areFound() {
        // The copied EmuDeck tree nests — bare names match at any depth.
        val d = Ps2BiosClassifier.classify(
            listOf(f("mybios.bin", 5_000_000L, "EmuDeck/bios/ps2/mybios.bin")),
        )
        assertEquals(1, d.candidates.size)
        assertEquals("EmuDeck/bios/ps2/mybios.bin", d.candidates[0].file.relativePath)
    }

    @Test
    fun strongBeatsCandidate_regardlessOfScanOrder() {
        val generic = f("dump.bin", 4_194_304L)
        val strong = f("scph39001.bin", 4_194_304L)
        val d = Ps2BiosClassifier.classify(listOf(generic, strong))
        assertEquals(2, d.candidates.size)
        assertEquals(strong, d.candidates[0].file)
        assertEquals(generic, d.candidates[1].file)
    }

    @Test
    fun tinyBin_isIgnored() {
        // Not BIOS-shaped by name, not sane by size: nothing.
        val d = Ps2BiosClassifier.classify(listOf(f("random.bin", 12_345L)))
        assertTrue(d.candidates.isEmpty())
        assertTrue(d.ancillary.isEmpty())
        assertTrue(d.misSized.isEmpty())
    }

    // ---------- ancillary ----------

    @Test
    fun ancillaryAlone_isNeverAMainCandidate() {
        val files = listOf(
            f("dump.nvm", 1_024L),
            f("dump.rom1", 4_194_304L), // even at a BIOS-like size…
            f("dump.rom2", 2_048L),
            f("erom", 1_024L),
            f("erom.bin", 512L),
        )
        val d = Ps2BiosClassifier.classify(files)
        assertTrue("ancillary must never be a main candidate", d.candidates.isEmpty())
        assertTrue(d.misSized.isEmpty())
        assertEquals(5, d.ancillary.size)
    }

    @Test
    fun nvmExtension_matchesBareName() {
        val d = Ps2BiosClassifier.classify(listOf(f("SCPH-70012.nvm", 4_096L)))
        assertTrue(d.candidates.isEmpty())
        assertEquals(1, d.ancillary.size)
    }

    // ---------- mis-sized ----------

    @Test
    fun scphBin_tiny_isMisSized() {
        val d = Ps2BiosClassifier.classify(listOf(f("scph39001.bin", 12_345L)))
        assertTrue(d.candidates.isEmpty())
        assertEquals(1, d.misSized.size)
    }

    @Test
    fun rom0_tiny_isMisSized() {
        val d = Ps2BiosClassifier.classify(listOf(f("rom0", 512L)))
        assertTrue(d.candidates.isEmpty())
        assertEquals(1, d.misSized.size)
    }

    // ---------- empty / unrelated ----------

    @Test
    fun unrelatedFolder_isEmpty() {
        val d = Ps2BiosClassifier.classify(
            listOf(
                f("notes.txt", 100L),
                f("cover.png", 50_000L),
                f("random.bin", 12_345L),
            ),
        )
        assertTrue(d.candidates.isEmpty())
        assertTrue(d.ancillary.isEmpty())
        assertTrue(d.misSized.isEmpty())
    }

    @Test
    fun emptyList_isEmpty() {
        val d = Ps2BiosClassifier.classify(emptyList())
        assertTrue(d.candidates.isEmpty())
        assertTrue(d.ancillary.isEmpty())
        assertTrue(d.misSized.isEmpty())
    }
}
