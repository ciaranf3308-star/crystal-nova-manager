package io.crystalnova.manager.ui

import io.crystalnova.manager.importer.DashboardData
import io.crystalnova.manager.importer.PlaySummary
import io.crystalnova.manager.importer.RomGrantState
import io.crystalnova.manager.importer.formatPlayDuration
import io.crystalnova.manager.importer.formatStatBytes
import io.crystalnova.manager.importer.percentFree

/**
 * Health dashboard (u88, u89 command center): display-only statTiles
 * in the home screen's STATUS zone. Every tile is a grid item (not a
 * full-width panel), so the zone reads as a dense tile grid. Tiles
 * never take D-pad focus (see [ControllerGridContent.statTile]), so
 * existing focus behavior is untouched. All figures come from
 * [DashboardData]; a tile that could not be measured shows its honest
 * fallback, never a fabricated number. All user-facing text is
 * uppercase, per app convention.
 *
 * u89 replaces the u88 panel-stack (healthDashboardPanels) with
 * tiles, preserving every stat and fallback in meaning:
 * - games / library size / daily play (library panel),
 * - internal + SD free bars (storage panel; the SD tile keeps the
 *   volume's own label, e.g. "SHARED STORAGE" when the SD figure is
 *   the shared-storage fallback),
 * - RAM / chip / battery (device panel),
 * - downloads / failed imports / theme / ROM grant (downloads panel).
 *
 * Two lines moved out of this file in u89, not dropped:
 * - the overall status (ALL OK / ATTENTION + reasons) now lives in
 *   the home header band as a StatusStrip, reusing
 *   [DashboardData.attentionReasons] exactly as u88 derived it
 *   (empty = all ok) — it is NOT recomputed here;
 * - the importer live line now lives in the home ACTIVITY strip.
 *
 * Rendered only on the home screen (EsdeThemeScreen gates on isHome);
 * a null [data] renders a single loading tile (the header strip
 * already carries the full "CHECKING DEVICE HEALTH…" line).
 */
fun ControllerGridContent.healthDashboardTiles(
    data: DashboardData?,
) {
    if (data == null) {
        statTile(
            key = "health-loading",
            label = "DEVICE HEALTH",
            value = "CHECKING…",
        )
        return
    }
    // ---- library ----
    if (data.gamesTotal != null) {
        statTile(
            key = "health-games",
            label = "GAMES",
            value = "${data.gamesTotal}",
            subline = "${data.systemsCount} SYSTEMS",
        )
        statTile(
            key = "health-library-size",
            label = "LIBRARY SIZE",
            value = data.libraryBytes?.let { formatStatBytes(it) } ?: "—",
        )
    } else {
        // Unscannable library: the u88 panel showed only the grant
        // state here, never a fabricated games count.
        statTile(
            key = "health-games",
            label = "GAMES",
            value = "—",
            subline = when (data.romGrant) {
                RomGrantState.LOST -> "ROM FOLDER: ACCESS LOST"
                else -> "ROM FOLDER: NOT GRANTED"
            },
        )
    }
    when (val play = data.playSummary) {
        is PlaySummary.DailyTime -> statTile(
            key = "health-play",
            label = "AVG DAILY PLAY",
            value = formatPlayDuration(play.avgSecondsPerDay),
            subline = "/ DAY (OVER ${play.windowDays} DAYS)",
        )
        is PlaySummary.DailySessions -> statTile(
            key = "health-play",
            label = "AVG DAILY PLAY",
            value = "%.1f".format(play.avgPerDay),
            subline = "SESSIONS / DAY (OVER ${play.windowDays} DAYS)",
        )
        is PlaySummary.TotalTime -> statTile(
            key = "health-play",
            label = "TOTAL PLAY TIME",
            value = formatPlayDuration(play.totalSeconds),
            subline = "(NO DATES)",
        )
        PlaySummary.NoData -> statTile(
            key = "health-play",
            label = "AVG DAILY PLAY",
            value = "NO PLAY DATA",
        )
    }
    // ---- storage (bars show the FREE fraction, matching the
    // "X FREE OF Y (pct%)" figures) ----
    val internalPct = percentFree(data.internalFree, data.internalTotal)
    if (data.internalFree != null && data.internalTotal != null && internalPct != null) {
        statTile(
            key = "health-internal",
            label = "INTERNAL",
            value = "${formatStatBytes(data.internalFree)} FREE",
            subline = "OF ${formatStatBytes(data.internalTotal)} ($internalPct%)",
            barFraction = internalPct / 100f,
        )
    } else {
        statTile(
            key = "health-internal",
            label = "INTERNAL",
            value = "—",
        )
    }
    val sdPct = percentFree(data.sd.freeBytes, data.sd.totalBytes)
    if (data.sd.freeBytes != null && data.sd.totalBytes != null && sdPct != null) {
        statTile(
            key = "health-sd",
            // The volume's own label: "SD CARD", "SHARED STORAGE", etc.
            label = data.sd.label,
            value = "${formatStatBytes(data.sd.freeBytes)} FREE",
            subline = "OF ${formatStatBytes(data.sd.totalBytes)} ($sdPct%)",
            barFraction = sdPct / 100f,
        )
    } else {
        statTile(
            key = "health-sd",
            label = data.sd.label,
            value = "—",
        )
    }
    // ---- device ----
    val ramPct = percentFree(data.ramFree, data.ramTotal)
    if (data.ramFree != null && data.ramTotal != null && ramPct != null) {
        statTile(
            key = "health-ram",
            label = "RAM",
            value = "${formatStatBytes(data.ramFree)} FREE",
            subline = "OF ${formatStatBytes(data.ramTotal)} ($ramPct%)",
            barFraction = ramPct / 100f,
        )
    } else {
        statTile(
            key = "health-ram",
            label = "RAM",
            value = "—",
        )
    }
    val loadText = data.chipLoadPct?.let { "LOAD $it%" } ?: "LOAD —"
    statTile(
        key = "health-chip",
        label = "CHIP",
        value = data.chipModel,
        subline = "${data.chipCores} CORES • $loadText",
    )
    val batteryText = data.batteryPct?.let { "$it%" } ?: "—"
    statTile(
        key = "health-battery",
        label = "BATTERY",
        value = batteryText,
        subline = data.batteryTempC?.let { "%.1f°C".format(it) },
    )
    // ---- downloads / theme / grants ----
    statTile(
        key = "health-downloads",
        label = "DOWNLOADS",
        value = if (data.downloadsPending > 0) {
            "${data.downloadsPending} PENDING"
        } else {
            "CLEAR"
        },
    )
    if (data.failedImports > 0) {
        statTile(
            key = "health-failed-imports",
            label = "FAILED IMPORTS",
            value = "${data.failedImports}",
        )
    }
    statTile(
        key = "health-theme",
        label = "THEME",
        value = when {
            !data.themeCatalogReady -> "CATALOG UNKNOWN"
            data.themeOutOfDate -> "UPDATE AVAILABLE"
            else -> "UP TO DATE"
        },
    )
    statTile(
        key = "health-rom-grant",
        label = "ROM FOLDER",
        value = when (data.romGrant) {
            RomGrantState.GRANTED -> "GRANTED"
            RomGrantState.NOT_GRANTED -> "NOT GRANTED"
            RomGrantState.LOST -> "ACCESS LOST"
        },
    )
}
