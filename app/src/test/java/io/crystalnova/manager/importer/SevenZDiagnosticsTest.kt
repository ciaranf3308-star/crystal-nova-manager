package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * u78 on-device diagnostics: the 7zz backend status line and the
 * per-item scan diagnostic. The user's 17 7z files refuse to import
 * and static analysis can't explain it — these give us on-device
 * truth from one hub photo.
 */
class SevenZDiagnosticsTest {

    private fun externalFor(runner: SevenZRunner) = ExternalSevenZ(
        runner = runner,
        archiveFile = { null },
        scratchDir = File("/tmp"),
    )

    // ---- describeSevenZStatus ----

    @Test fun `status is READY when the backend is wired`() {
        val runner = FakeSevenZRunner(isAvailable = true)
        assertEquals(
            "7ZZ: READY",
            describeSevenZStatus(externalFor(runner), runner),
        )
    }

    @Test fun `status is NO BINARY when there is no backend at all`() {
        val runner = FakeSevenZRunner(isAvailable = false)
        assertEquals(
            "7ZZ: OFF — NO BINARY",
            describeSevenZStatus(null, runner),
        )
    }

    @Test fun `status is NOT EXECUTABLE when the binary cannot run`() {
        // arm64 device, but lib7zz.so missing/not executable.
        val runner = AndroidSevenZRunner(
            File("/nonexistent-lib-dir"),
            listOf("arm64-v8a"),
        )
        assertEquals(
            "7ZZ: OFF — NOT EXECUTABLE",
            describeSevenZStatus(null, runner),
        )
    }

    @Test fun `status is WRONG ABI on a non-arm64 device`() {
        val runner = AndroidSevenZRunner(
            File("/nonexistent-lib-dir"),
            listOf("x86_64"),
        )
        assertEquals(
            "7ZZ: OFF — WRONG ABI",
            describeSevenZStatus(null, runner),
        )
    }

    // ---- scanDiagnosticLine ----

    @Test fun `diagnostic names file count, extensions and 7zz backend`() {
        val entries = listOf(
            ArchiveEntryInfo("Mario Golf.bin", 1_000_000L, false),
            ArchiveEntryInfo("Vimm's Lair.txt", 303L, false),
            ArchiveEntryInfo("docs", 0L, true),
        )
        assertEquals(
            "2 FILES [bin, txt] VIA 7ZZ",
            scanDiagnosticLine(entries, InspectionBackend.EXTERNAL_7ZZ),
        )
    }

    @Test fun `diagnostic marks the in-app backend`() {
        val entries = listOf(
            ArchiveEntryInfo("game.iso", 500L, false),
        )
        assertEquals(
            "1 FILES [iso] VIA INAPP",
            scanDiagnosticLine(entries, InspectionBackend.INAPP),
        )
    }

    @Test fun `diagnostic handles extensionless entries`() {
        val entries = listOf(
            ArchiveEntryInfo("README", 10L, false),
        )
        assertEquals(
            "1 FILES [] VIA INAPP",
            scanDiagnosticLine(entries, InspectionBackend.INAPP),
        )
    }

    // ---- shortUnreadableReason surfaces 7zz failures ----

    @Test fun `short reason surfaces the 7zz failure instead of the OOM`() {
        val detail = "7ZZ FAILED: 7zz list failed (exit 1) | IN-APP: " +
            "Failed to allocate a 268435472 byte allocation with 25165824 free bytes"
        val short = shortUnreadableReason(detail)
        assertTrue(short.startsWith("7ZZ FAILED:"))
        assertTrue(short.contains("EXIT 1"))
    }
}
