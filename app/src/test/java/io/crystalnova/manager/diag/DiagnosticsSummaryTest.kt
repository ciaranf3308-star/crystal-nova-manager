package io.crystalnova.manager.diag

import io.crystalnova.manager.data.SelfUpdateInfo
import io.crystalnova.manager.scraper.work.ScrapeProgress
import io.crystalnova.manager.updater.AppUpdateState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * u44: the old theme-updater helpers (themeInstalledSummary,
 * themeLatestSummary, themeBackupSummary, updateNoticeOf) are retired
 * with the strip-back — the Pegasus theme updater is archived. This
 * pins the helpers that remain: the manager self-update summary, the
 * scraper index health, the matcher hit/miss line, and the last-run
 * summary shown on the Diagnostics screen.
 */
class DiagnosticsSummaryTest {

    private fun available(version: String = "1.3.0") = AppUpdateState.Available(
        SelfUpdateInfo(version = version, tag = "v$version", apkUrl = "https://x"),
    )

    @Test
    fun `app update summaries cover every state`() {
        assertEquals("IDLE", appUpdateSummary(AppUpdateState.Idle()))
        assertEquals("CHECK FAILED — will retry", appUpdateSummary(AppUpdateState.Idle(true)))
        assertEquals("CHECKING…", appUpdateSummary(AppUpdateState.Checking))
        assertEquals("AVAILABLE — v1.3.0", appUpdateSummary(available()))
        assertEquals(
            "DOWNLOADING — 12 KB / 100 KB",
            appUpdateSummary(AppUpdateState.Downloading(12 * 1024, 100 * 1024)),
        )
        assertEquals(
            "DOWNLOADING — 12 KB",
            appUpdateSummary(AppUpdateState.Downloading(12 * 1024, null)),
        )
        assertEquals("READY TO INSTALL", appUpdateSummary(AppUpdateState.Downloaded(File("x"))))
        assertEquals("INSTALLING…", appUpdateSummary(AppUpdateState.Installing))
        assertEquals("FAILED — bad zip", appUpdateSummary(AppUpdateState.Failed("bad zip")))
    }

    @Test
    fun `index status covers missing unreadable and ok`() {
        assertEquals(
            IndexHealth.MISSING to "MISSING — no scrape has written it yet",
            indexStatus(found = false, parseOk = false, games = 0),
        )
        assertEquals(
            IndexHealth.UNREADABLE to "UNREADABLE — malformed JSON",
            indexStatus(found = true, parseOk = false, games = 0),
        )
        assertEquals(
            IndexHealth.OK to "OK — 147 games",
            indexStatus(found = true, parseOk = true, games = 147),
        )
    }

    @Test
    fun `resolver summary splits hits and misses`() {
        assertEquals("130 hit · 17 miss", resolverSummary(totalGames = 147, unmatched = 17))
        assertEquals("0 hit · 0 miss", resolverSummary(totalGames = 0, unmatched = 0))
    }

    @Test
    fun `last run summary covers null and cancelled`() {
        assertEquals("no run yet", lastRunSummary(null))
        val done = ScrapeProgress(
            total = 20, done = 10, succeeded = 8, partial = 1, failed = 1,
            unmatched = 0, cancelled = false,
        )
        assertEquals("10/20 — 8 ok · 1 partial · 1 failed · 0 unmatched", lastRunSummary(done))
        val cancelled = done.copy(cancelled = true)
        assertEquals(
            "10/20 — 8 ok · 1 partial · 1 failed · 0 unmatched · CANCELLED",
            lastRunSummary(cancelled),
        )
    }
}
