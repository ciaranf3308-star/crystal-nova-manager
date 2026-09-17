package io.crystalnova.manager.diag

import io.crystalnova.manager.scraper.work.ScrapeProgress
import io.crystalnova.manager.scraper.work.ScraperDiagnostics
import io.crystalnova.manager.updater.AppUpdateState
import io.crystalnova.manager.updater.ManagerState
import io.crystalnova.manager.updater.VersionDisplay

/**
 * Everything the hidden Diagnostics screen needs, assembled once when
 * opened. All values are plain data so the screen itself never touches
 * storage, network, or SAF — it can only ever render.
 *
 * Android-free: the summary helpers below are covered by JVM unit tests.
 */
data class DiagnosticsInfo(
    val managerVersion: String,
    val managerState: ManagerState,
    val appUpdate: AppUpdateState,
    val themesRoot: String?,
    val scraper: ScraperDiagnostics,
    /** Most recent uncaught-crash trace, null when there hasn't been one. */
    val lastCrashTrace: String? = null,
    /** Every known emulator candidate package with its install state. */
    val emulatorPackages: List<EmulatorPackageStatus> = emptyList(),
    /** v18: the Pegasus config root Pegasus actually reads. */
    val pegasusConfig: PegasusConfigDiag? = null,
)

/** One known emulator candidate and whether PackageManager sees it. */
data class EmulatorPackageStatus(
    val packageName: String,
    val installed: Boolean,
)

/**
 * v18: the Pegasus config root Pegasus actually reads (or doesn't).
 * Plain data; the Diagnostics screen only renders it.
 */
data class PegasusConfigDiag(
    /** Friendly display path of the selected config root, or NOT SELECTED. */
    val displayPath: String,
    /** [io.crystalnova.manager.pegasus.ConfigValidity] name, e.g. VALID / WRONG_FOLDER. */
    val validity: String,
    /** Whether the Manager-owned metafile could be read back. */
    val metafilePresent: Boolean,
    /** Its byte size, null when unreadable. */
    val metafileBytes: Long?,
    /** "n SYSTEMS · m GAMES" from the last verified inject, or "NONE". */
    val lastInjected: String,
)

/** One-line summary of the manager self-update state. */
internal fun appUpdateSummary(u: AppUpdateState): String = when (u) {
    is AppUpdateState.Idle ->
        if (u.lastCheckFailed) "CHECK FAILED — will retry" else "IDLE"
    is AppUpdateState.Checking -> "CHECKING…"
    is AppUpdateState.Available -> "AVAILABLE — v${u.info.version}"
    is AppUpdateState.Downloading ->
        "DOWNLOADING — ${u.downloadedBytes / 1024} KB" +
            (u.totalBytes?.let { " / ${it / 1024} KB" } ?: "")
    is AppUpdateState.Downloaded -> "READY TO INSTALL"
    is AppUpdateState.Installing -> "INSTALLING…"
    is AppUpdateState.Failed -> "FAILED — ${u.message}"
}

internal fun versionText(v: VersionDisplay?): String =
    v?.let { "v${it.version} · ${it.shortCommit}" } ?: "—"

internal fun themeInstalledSummary(s: ManagerState): String = when (s) {
    is ManagerState.Ready -> s.installed?.let { "v${it.version} · ${it.shortCommit}" }
        ?: "NO VERSION MARKER (legacy install)"
    is ManagerState.NeedsFolder -> "— (no themes folder)"
    is ManagerState.UpdateDone -> "v${s.version.version} · ${s.version.shortCommit} (just updated)"
    is ManagerState.UpdateFailed -> "— (update failed: ${s.message})"
    is ManagerState.RollbackDone -> s.version?.let { "v${it.version} · ${it.shortCommit} (rolled back)" }
        ?: "— (rolled back)"
    is ManagerState.RollbackFailed -> "— (rollback failed: ${s.message})"
    is ManagerState.Updating -> "— (updating…)"
    is ManagerState.RollingBack -> "— (rolling back…)"
}

internal fun themeLatestSummary(s: ManagerState): String = when (s) {
    is ManagerState.Ready -> versionText(s.latest)
    else -> "—"
}

internal fun themeBackupSummary(s: ManagerState): String = when (s) {
    is ManagerState.Ready -> s.backup?.let { "v${it.version} · ${it.shortCommit}" } ?: "NONE"
    else -> "—"
}

internal fun updateNoticeOf(s: ManagerState): String? = when (s) {
    is ManagerState.Ready -> s.notice
    is ManagerState.UpdateFailed -> s.message
    is ManagerState.RollbackFailed -> s.message
    else -> null
}

/** Health of the scraper index.json, without any UI types. */
enum class IndexHealth { OK, MISSING, UNREADABLE }

internal fun indexStatus(found: Boolean, parseOk: Boolean, games: Int): Pair<IndexHealth, String> =
    when {
        !found -> IndexHealth.MISSING to "MISSING — no scrape has written it yet"
        !parseOk -> IndexHealth.UNREADABLE to "UNREADABLE — malformed JSON"
        else -> IndexHealth.OK to "OK — $games games"
    }

internal fun resolverSummary(totalGames: Int, unmatched: Int): String =
    "${totalGames - unmatched} hit · $unmatched miss"

internal fun lastRunSummary(p: ScrapeProgress?): String {
    if (p == null) return "no run yet"
    val tail = if (p.cancelled) " · CANCELLED" else ""
    return "${p.done}/${p.total} — ${p.succeeded} ok · ${p.partial} partial · " +
        "${p.failed} failed · ${p.unmatched} unmatched$tail"
}
