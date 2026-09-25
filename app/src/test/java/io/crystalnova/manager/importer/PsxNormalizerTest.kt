package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PS1 normalizer tests with a fake [ChdConverter]: conversion logic,
 * grouping, validation, and layout assembly without the real binary.
 * The real-binary round-trips live in [PsxChdmanIntegrationTest].
 */
class PsxNormalizerTest {

    private class FakeChdConverter : ChdConverter {
        val created = mutableListOf<Pair<File, File>>()
        var failCreate = false
        var failVerify = false
        private var availableFlag = true
        fun setAvailable(v: Boolean) {
            availableFlag = v
        }

        override val available: Boolean get() = availableFlag

        override fun createcd(cueFile: File, outChd: File, onProgress: (Float) -> Unit): Boolean {
            created += cueFile to outChd
            if (failCreate) return false
            onProgress(1f)
            outChd.writeBytes(ByteArray(64) { 7 })
            return true
        }

        override fun verify(chdFile: File): Boolean {
            if (failVerify) return false
            return chdFile.isFile && chdFile.length() > 0
        }

        override fun cancel() {}
    }

    private fun tempDir(): File =
        Files.createTempDirectory("psx-norm-test").toFile()

    private fun writeCue(dir: File, cueName: String, vararg bins: String): File {
        for (bin in bins) {
            File(dir, bin).writeBytes(ByteArray(2352 * 2) { 1 })
        }
        val text = bins.joinToString("\n") { "FILE \"$it\" BINARY" } +
            "\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n"
        return File(dir, cueName).also { it.writeText(text) }
    }

    private fun successOf(outcome: PsxNormalizeOutcome): PsxNormalizeOutcome.Success {
        assertTrue("expected Success, got $outcome", outcome is PsxNormalizeOutcome.Success)
        return outcome as PsxNormalizeOutcome.Success
    }

    private fun failureOf(outcome: PsxNormalizeOutcome): PsxNormalizeOutcome.Failure {
        assertTrue("expected Failure, got $outcome", outcome is PsxNormalizeOutcome.Failure)
        return outcome as PsxNormalizeOutcome.Failure
    }

    @Test fun `test A - single-disc multi-track cue becomes one chd`() {
        val dir = tempDir()
        writeCue(dir, "Game.cue", "Game (Track 01).bin", "Game (Track 02).bin", "Game (Track 03).bin")
        val conv = FakeChdConverter()
        val result = successOf(PsxNormalizer.normalize(dir, "Game", conv))
        assertEquals(listOf("Game.chd"), result.files.map { it.destRelPath })
        assertEquals(1, conv.created.size)
    }

    @Test fun `test E - fifteen tracks are one disc`() {
        val dir = tempDir()
        val bins = (1..15).map { "Game (Track %02d).bin".format(it) }.toTypedArray()
        writeCue(dir, "Game.cue", *bins)
        val conv = FakeChdConverter()
        val result = successOf(PsxNormalizer.normalize(dir, "Game", conv))
        assertEquals(listOf("Game.chd"), result.files.map { it.destRelPath })
        assertEquals(1, conv.created.size)
    }

    @Test fun `test B - two discs produce the m3u directory layout`() {
        val dir = tempDir()
        writeCue(dir, "Game (Disc 1).cue", "Game (Disc 1).bin")
        writeCue(dir, "Game (Disc 2).cue", "Game (Disc 2).bin")
        val result = successOf(PsxNormalizer.normalize(dir, "Game", FakeChdConverter()))
        assertEquals(
            listOf(
                "Game.m3u/Game.m3u",
                "Game.m3u/Game (Disc 1).chd",
                "Game.m3u/Game (Disc 2).chd",
            ),
            result.files.map { it.destRelPath },
        )
        val m3u = result.files.first { it.destRelPath.endsWith(".m3u/Game.m3u") }
        assertEquals(
            "Game (Disc 1).chd\nGame (Disc 2).chd\n",
            m3u.source.readText(),
        )
        // The ES-DE simulation agrees: exactly one frontend entry.
        assertEquals(listOf("Game"), esdePsxEntries(emptyList(), listOf("Game.m3u")))
    }

    @Test fun `test C - existing chd passes through untouched`() {
        val dir = tempDir()
        val src = File(dir, "Game.chd").also { it.writeBytes(ByteArray(128) { 9 }) }
        val conv = FakeChdConverter()
        val result = successOf(PsxNormalizer.normalize(dir, "Game", conv))
        assertEquals(listOf("Game.chd"), result.files.map { it.destRelPath })
        // No pointless recompression: createcd never ran, same file reused.
        assertTrue(conv.created.isEmpty())
        assertEquals(src, result.files.single().source)
    }

    @Test fun `test D - broken cue is rejected with a typed reason`() {
        val dir = tempDir()
        File(dir, "Game.cue").writeText(
            "FILE \"Game (Track 01).bin\" BINARY\n" +
                "FILE \"Game (Track 02).bin\" BINARY\n" +
                "  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n",
        )
        File(dir, "Game (Track 01).bin").writeBytes(ByteArray(16))
        val conv = FakeChdConverter()
        val failure = failureOf(PsxNormalizer.normalize(dir, "Game", conv))
        assertEquals(ImportFailureReason.PSX_CUE_TRACKS_MISSING, failure.reason)
        assertTrue(conv.created.isEmpty())
        assertTrue(failure.detail!!.contains("Game (Track 02).bin"))
    }

    @Test fun `pbp installs as-is without conversion`() {
        val dir = tempDir()
        File(dir, "Game.pbp").writeBytes(ByteArray(32) { 3 })
        val conv = FakeChdConverter()
        val result = successOf(PsxNormalizer.normalize(dir, "Game", conv))
        assertEquals(listOf("Game.pbp"), result.files.map { it.destRelPath })
        assertTrue(conv.created.isEmpty())
    }

    @Test fun `lone iso installs as-is`() {
        val dir = tempDir()
        File(dir, "Game.iso").writeBytes(ByteArray(32) { 3 })
        val result = successOf(PsxNormalizer.normalize(dir, "Game", FakeChdConverter()))
        assertEquals(listOf("Game.iso"), result.files.map { it.destRelPath })
    }

    @Test fun `sbi rides along next to its chd`() {
        val dir = tempDir()
        writeCue(dir, "Game.cue", "Game.bin")
        File(dir, "Game.sbi").writeBytes(ByteArray(8) { 5 })
        val result = successOf(PsxNormalizer.normalize(dir, "Game", FakeChdConverter()))
        assertEquals(listOf("Game.chd", "Game.sbi"), result.files.map { it.destRelPath })
    }

    @Test fun `case-insensitive track match patches the temp cue copy`() {
        val dir = tempDir()
        File(dir, "game.bin").writeBytes(ByteArray(16))
        val cue = File(dir, "Game.cue")
        cue.writeText("FILE \"GAME.BIN\" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n")
        val result = successOf(PsxNormalizer.normalize(dir, "Game", FakeChdConverter()))
        assertEquals(listOf("Game.chd"), result.files.map { it.destRelPath })
        assertTrue(cue.readText().contains("game.bin"))
    }

    @Test fun `cue input without a converter fails cleanly`() {
        val dir = tempDir()
        writeCue(dir, "Game.cue", "Game.bin")
        val failure = failureOf(PsxNormalizer.normalize(dir, "Game", null))
        assertEquals(ImportFailureReason.PSX_CHDMAN_MISSING, failure.reason)
    }

    @Test fun `chd input without a converter still installs`() {
        val dir = tempDir()
        File(dir, "Game.chd").writeBytes(ByteArray(64) { 9 })
        val result = successOf(PsxNormalizer.normalize(dir, "Game", null))
        assertEquals(listOf("Game.chd"), result.files.map { it.destRelPath })
    }

    @Test fun `empty temp dir is a typed failure`() {
        val failure = failureOf(PsxNormalizer.normalize(tempDir(), "Game", FakeChdConverter()))
        assertEquals(ImportFailureReason.PSX_NO_VALID_DISCS, failure.reason)
    }

    @Test fun `failed conversion is a typed failure`() {
        val dir = tempDir()
        writeCue(dir, "Game.cue", "Game.bin")
        val conv = FakeChdConverter().also { it.failCreate = true }
        val failure = failureOf(PsxNormalizer.normalize(dir, "Game", conv))
        assertEquals(ImportFailureReason.PSX_CHDMAN_FAILED, failure.reason)
    }

    @Test fun `failed verification is a typed failure`() {
        val dir = tempDir()
        writeCue(dir, "Game.cue", "Game.bin")
        val conv = FakeChdConverter().also { it.failVerify = true }
        val failure = failureOf(PsxNormalizer.normalize(dir, "Game", conv))
        assertEquals(ImportFailureReason.PSX_CHDMAN_FAILED, failure.reason)
    }
}
