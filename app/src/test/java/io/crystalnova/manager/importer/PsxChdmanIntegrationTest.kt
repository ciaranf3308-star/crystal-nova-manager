package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PS1 pipeline tests against the REAL chdman binary
 * (`tools/chdman-linux-x64/chdman`, located via `CHDMAN_X64` or the
 * repo-relative fallback). These skip when the binary is absent — but
 * CI asserts the binary exists (see the `Assert chdman x86_64 test
 * binary exists` step in build-release.yml), so a silent skip on CI
 * is a build failure, not a pass.
 *
 * Synthetic CUE/BIN discs: deterministic 2352-byte sectors; chdman
 * does not validate ECC, so generated data converts fine.
 */
class PsxChdmanIntegrationTest {

    private fun chdmanExe(): File {
        val fromEnv = System.getenv("CHDMAN_X64")?.takeIf { it.isNotBlank() }?.let { File(it) }
        val exe = if (fromEnv != null && fromEnv.canExecute()) {
            fromEnv
        } else {
            // Gradle unit tests run with user.dir = the app/ module dir.
            File(System.getProperty("user.dir"), "../tools/chdman-linux-x64/chdman")
        }
        assumeTrue("chdman x86_64 binary not present at ${exe.absolutePath}", exe.canExecute())
        return exe
    }

    private fun tempDir(): File =
        Files.createTempDirectory("psx-chdman-it").toFile()

    /**
     * One synthetic disc: [tracks] BINs of 64 deterministic 2352-byte
     * sectors + its CUE. 64 sectors/track keeps the images well above
     * the size where chdman's own verifier rejects absurdly tiny
     * inputs (upstream chdman 0.264 does the same on an 8-sector
     * image); real PS1 discs are orders of magnitude larger still.
     * chdman does not validate ECC, so generated data converts fine.
     */
    private fun writeSyntheticDisc(dir: File, base: String, tracks: Int) {
        for (t in 1..tracks) {
            val binName = "$base (Track %02d).bin".format(t)
            val sectors = 64
            val bytes = ByteArray(sectors * 2352) { i -> ((i * 31 + t * 7) % 256).toByte() }
            File(dir, binName).writeBytes(bytes)
        }
        val cue = buildString {
            for (t in 1..tracks) {
                val binName = "$base (Track %02d).bin".format(t)
                val mode = if (t == 1) "MODE1/2352" else "AUDIO"
                append("FILE \"$binName\" BINARY\n")
                append("  TRACK %02d %s\n".format(t, mode))
                append("    INDEX 01 00:00:00\n")
            }
        }
        File(dir, "$base.cue").writeText(cue)
    }

    @Test fun `test A - real chdman converts a synthetic 3-track disc`() {
        val exe = chdmanExe()
        val dir = tempDir()
        writeSyntheticDisc(dir, "Game", 3)
        val conv = ProcessChdConverter(exe)
        assertTrue("chdman binary not executable", conv.available)

        val outcome = PsxNormalizer.normalize(dir, "Game", conv)
        assertTrue("expected Success, got $outcome", outcome is PsxNormalizeOutcome.Success)
        val result = outcome as PsxNormalizeOutcome.Success
        assertEquals(listOf("Game.chd"), result.files.map { it.destRelPath })

        val chd = result.files.single().source
        assertTrue(chd.isFile && chd.length() > 0)
        assertTrue("chdman verify failed on the converted image", conv.verify(chd))
    }

    @Test fun `test B - real chdman builds the exact m3u directory structure`() {
        val exe = chdmanExe()
        val dir = tempDir()
        writeSyntheticDisc(dir, "Game (Disc 1)", 2)
        writeSyntheticDisc(dir, "Game (Disc 2)", 2)
        val conv = ProcessChdConverter(exe)

        val outcome = PsxNormalizer.normalize(dir, "Game", conv)
        assertTrue("expected Success, got $outcome", outcome is PsxNormalizeOutcome.Success)
        val result = outcome as PsxNormalizeOutcome.Success
        assertEquals(
            listOf(
                "Game.m3u/Game.m3u",
                "Game.m3u/Game (Disc 1).chd",
                "Game.m3u/Game (Disc 2).chd",
            ),
            result.files.map { it.destRelPath },
        )
        val m3u = result.files.first { it.destRelPath.endsWith("/Game.m3u") }
        assertEquals("Game (Disc 1).chd\nGame (Disc 2).chd\n", m3u.source.readText())
        for (disc in result.files.filter { it.destRelPath.endsWith(".chd") }) {
            assertTrue("verify failed for ${disc.destRelPath}", conv.verify(disc.source))
        }
        // One ES-DE entry for the whole multi-disc game.
        assertEquals(listOf("Game"), esdePsxEntries(emptyList(), listOf("Game.m3u")))
    }

    @Test fun `test C - existing chd passes through the real pipeline untouched`() {
        val exe = chdmanExe()
        val dir = tempDir()
        writeSyntheticDisc(dir, "Game", 1)
        val maker = ProcessChdConverter(exe)
        val made = PsxNormalizer.normalize(dir, "Game", maker)
        assertTrue(made is PsxNormalizeOutcome.Success)
        val chdBytes = (made as PsxNormalizeOutcome.Success).files.single().source.readBytes()

        // Now the CHD is the input: no createcd may run.
        val dir2 = tempDir()
        val input = File(dir2, "Game.chd").also { it.writeBytes(chdBytes) }
        var creates = 0
        val spy = object : ProcessChdConverter(exe) {
            override fun createcd(cueFile: File, outChd: File, onProgress: (Float) -> Unit): Boolean {
                creates++
                return super.createcd(cueFile, outChd, onProgress)
            }
        }
        val outcome = PsxNormalizer.normalize(dir2, "Game", spy)
        assertTrue("expected Success, got $outcome", outcome is PsxNormalizeOutcome.Success)
        val result = outcome as PsxNormalizeOutcome.Success
        assertEquals(listOf("Game.chd"), result.files.map { it.destRelPath })
        assertEquals("no createcd should run for an existing CHD", 0, creates)
        assertEquals(input, result.files.single().source)
        assertTrue(spy.verify(result.files.single().source))
    }

    @Test fun `test D - broken cue is rejected before any conversion`() {
        val exe = chdmanExe()
        val dir = tempDir()
        writeSyntheticDisc(dir, "Game", 1)
        // Corrupt the CUE: reference a track that was never written.
        File(dir, "Game.cue").appendText("FILE \"Game (Track 99).bin\" BINARY\n")
        var creates = 0
        val spy = object : ProcessChdConverter(exe) {
            override fun createcd(cueFile: File, outChd: File, onProgress: (Float) -> Unit): Boolean {
                creates++
                return super.createcd(cueFile, outChd, onProgress)
            }
        }
        val outcome = PsxNormalizer.normalize(dir, "Game", spy)
        assertTrue("expected Failure, got $outcome", outcome is PsxNormalizeOutcome.Failure)
        val failure = outcome as PsxNormalizeOutcome.Failure
        assertEquals(ImportFailureReason.PSX_CUE_TRACKS_MISSING, failure.reason)
        assertEquals(0, creates)
        assertTrue("no CHD debris may be left behind", dir.listFiles()!!.none { it.extension == "chd" })
    }
}
