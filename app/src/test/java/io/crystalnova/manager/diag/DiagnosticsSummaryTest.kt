package io.crystalnova.manager.diag

import io.crystalnova.manager.data.SelfUpdateInfo
import io.crystalnova.manager.scraper.work.ScrapeProgress
import io.crystalnova.manager.updater.AppUpdateState
import io.crystalnova.manager.updater.ManagerState
import io.crystalnova.manager.updater.Stage
import io.crystalnova.manager.updater.VersionDisplay
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DiagnosticsSummaryTest {

    private fun ready(
        installed: VersionDisplay? = VersionDisplay("1.2.0", "abc1234"),
        latest: VersionDisplay? = VersionDisplay("1.3.0", "def5678"),
        backup: VersionDisplay? = null,
        notice: String? = null,
    ) = ManagerState.Ready(
        installed = installed,
        latest = latest,
        updateAvailable = true,
        backup = backup,
        notice = notice,
    )

    @Test
    fun `app update summaries cover every state`() {
        assertEquals("IDLE", appUpdateSummary(AppUpdateState.Idle()))
        assertEquals("CHECK FAILED — will retry", appUpdateSummary(AppUpdateState.Idle(true)))
        assertEquals("CHECKING…", appUpdateSummary(AppUpdateState.Checking))
        assertEquals(
            "AVAILABLE — v1.3.0",
            appUpdateSummary(
                AppUpdateState.Available(
                    SelfUpdateInfo(version = "1.3.0", tag = "v1.3.0", apkUrl = "https://x"),
                ),
            ),
        )
        assertEquals(
            "DOWNLOADING — 2 KB / 4 KB",
            appUpdateSummary(AppUpdateState.Downloading(downloadedBytes = 2048, totalBytes = 4096)),
        )
        assertEquals(
            "DOWNLOADING — 0 KB",
            appUpdateSummary(AppUpdateState.Downloading()),
        )
        assertEquals("READY TO INSTALL", appUpdateSummary(AppUpdateState.Downloaded(File("a.apk"))))
        assertEquals("INSTALLING…", appUpdateSummary(AppUpdateState.Installing))
        assertEquals("FAILED — net down", appUpdateSummary(AppUpdateState.Failed("net down")))
    }

    @Test
    fun `theme installed summary covers every manager state`() {
        assertEquals("v1.2.0 · abc1234", themeInstalledSummary(ready()))
        assertEquals(
            "NO VERSION MARKER (legacy install)",
            themeInstalledSummary(ready(installed = null)),
        )
        assertEquals("— (no themes folder)", themeInstalledSummary(ManagerState.NeedsFolder("pick")))
        assertEquals(
            "v1.3.0 · def5678 (just updated)",
            themeInstalledSummary(ManagerState.UpdateDone(VersionDisplay("1.3.0", "def5678"))),
        )
        assertEquals(
            "— (update failed: bad zip)",
            themeInstalledSummary(ManagerState.UpdateFailed("bad zip", restored = true)),
        )
        assertEquals("— (updating…)", themeInstalledSummary(ManagerState.Updating(Stage.DOWNLOADING)))
        assertEquals(
            "v1.2.0 · abc1234 (rolled back)",
            themeInstalledSummary(
                ManagerState.RollbackDone(VersionDisplay("1.2.0", "abc1234")),
            ),
        )
        assertEquals(
            "— (rolled back)",
            themeInstalledSummary(ManagerState.RollbackDone(null)),
        )
        assertEquals(
            "— (rollback failed: stuck)",
            themeInstalledSummary(ManagerState.RollbackFailed("stuck")),
        )
        assertEquals("— (rolling back…)", themeInstalledSummary(ManagerState.RollingBack(Stage.VERIFYING)))
    }

    @Test
    fun `theme latest and backup summaries`() {
        assertEquals("v1.3.0 · def5678", themeLatestSummary(ready()))
        assertEquals("—", themeLatestSummary(ready(latest = null)))
        assertEquals("—", themeLatestSummary(ManagerState.Updating(Stage.CHECKING)))
        assertEquals("NONE", themeBackupSummary(ready()))
        assertEquals(
            "v1.1.0 · 9990000",
            themeBackupSummary(ready(backup = VersionDisplay("1.1.0", "9990000"))),
        )
    }

    @Test
    fun `update notice picks the right message`() {
        assertEquals("hi", updateNoticeOf(ready(notice = "hi")))
        assertNull(updateNoticeOf(ready()))
        assertEquals("bad zip", updateNoticeOf(ManagerState.UpdateFailed("bad zip", false)))
        assertEquals("stuck", updateNoticeOf(ManagerState.RollbackFailed("stuck")))
        assertNull(updateNoticeOf(ManagerState.NeedsFolder(null)))
    }

    @Test
    fun `index status distinguishes missing malformed and ok`() {
        assertEquals(
            IndexHealth.MISSING to "MISSING — no scrape has written it yet",
            indexStatus(found = false, parseOk = false, games = 0),
        )
        assertEquals(
            IndexHealth.UNREADABLE to "UNREADABLE — malformed JSON",
            indexStatus(found = true, parseOk = false, games = 0),
        )
        assertEquals(
            IndexHealth.OK to "OK — 12 games",
            indexStatus(found = true, parseOk = true, games = 12),
        )
    }

    @Test
    fun `resolver summary is hits and misses`() {
        assertEquals("100 hit · 23 miss", resolverSummary(totalGames = 123, unmatched = 23))
        assertEquals("5 hit · 0 miss", resolverSummary(totalGames = 5, unmatched = 0))
    }

    @Test
    fun `last run summary covers none completed and cancelled`() {
        assertEquals("no run yet", lastRunSummary(null))
        assertEquals(
            "10/10 — 8 ok · 1 partial · 1 failed · 0 unmatched",
            lastRunSummary(ScrapeProgress(10, 10, 8, 1, 1, 0)),
        )
        assertEquals(
            "4/10 — 3 ok · 0 partial · 0 failed · 1 unmatched · CANCELLED",
            lastRunSummary(ScrapeProgress(10, 4, 3, 0, 0, 1, cancelled = true)),
        )
    }
}
