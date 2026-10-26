package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.importer.DashboardData
import io.crystalnova.manager.importer.PlaySummary
import io.crystalnova.manager.importer.RomGrantState
import io.crystalnova.manager.importer.formatPlayDuration
import io.crystalnova.manager.importer.formatStatBytes
import io.crystalnova.manager.importer.percentFree

/**
 * Health dashboard (u88): display-only panels at the TOP of the home
 * screen. Every tile is a [panel] — panels never take D-pad focus
 * (see [ControllerGridContent.panel]), so existing focus behavior is
 * untouched. All figures come from [DashboardData]; a tile that could
 * not be measured shows its honest fallback, never a fabricated
 * number. All user-facing text is uppercase, per app convention.
 *
 * Rendered only on the home screen (EsdeThemeScreen gates on isHome);
 * a null [data] renders the loading panel.
 */
fun ControllerGridContent.healthDashboardPanels(
    data: DashboardData?,
    importerStatusLine: String,
    importerAttention: Boolean,
) {
    if (data == null) {
        panel(key = "health-loading") {
            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                StatusLine("CHECKING DEVICE HEALTH…", Crystal.Joystick)
            }
        }
        return
    }
    // ---- overall status (first) ----
    panel(key = "health-status") {
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.attentionReasons.isEmpty()) {
                    StatusLine("ALL OK — DEVICE HEALTHY", Crystal.Good)
                } else {
                    StatusLine("ATTENTION", Crystal.Bad)
                    data.attentionReasons.forEach { reason ->
                        DimLine(reason)
                    }
                }
            }
        }
    }
    // ---- library ----
    panel(key = "health-library") {
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusLine("LIBRARY", Crystal.Cream)
                if (data.gamesTotal != null) {
                    StatusLine(
                        "GAMES INSTALLED: ${data.gamesTotal} " +
                            "(${data.systemsCount} SYSTEMS)",
                    )
                    data.libraryBytes?.let {
                        DimLine("LIBRARY SIZE: ${formatStatBytes(it)}")
                    } ?: DimLine("LIBRARY SIZE: —")
                } else {
                    StatusLine(
                        when (data.romGrant) {
                            RomGrantState.LOST -> "ROM FOLDER: ACCESS LOST"
                            else -> "ROM FOLDER: NOT GRANTED"
                        },
                        Crystal.Bad,
                    )
                }
                when (val play = data.playSummary) {
                    is PlaySummary.DailyTime -> DimLine(
                        "AVG DAILY PLAY: ${formatPlayDuration(play.avgSecondsPerDay)} " +
                            "/ DAY (OVER ${play.windowDays} DAYS)",
                    )
                    is PlaySummary.DailySessions -> DimLine(
                        "AVG DAILY PLAY: %.1f SESSIONS / DAY ".format(play.avgPerDay) +
                            "(OVER ${play.windowDays} DAYS)",
                    )
                    is PlaySummary.TotalTime -> DimLine(
                        "TOTAL PLAY TIME: ${formatPlayDuration(play.totalSeconds)} " +
                            "(NO DATES)",
                    )
                    PlaySummary.NoData -> DimLine("AVG DAILY PLAY: NO PLAY DATA")
                }
            }
        }
    }
    // ---- storage ----
    panel(key = "health-storage") {
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusLine("STORAGE", Crystal.Cream)
                val internalPct = percentFree(data.internalFree, data.internalTotal)
                if (data.internalFree != null && data.internalTotal != null && internalPct != null) {
                    StatusLine(
                        "INTERNAL: ${formatStatBytes(data.internalFree)} FREE OF " +
                            "${formatStatBytes(data.internalTotal)} ($internalPct%)",
                        if (internalPct < 10) Crystal.Bad else Crystal.Ink,
                    )
                } else {
                    DimLine("INTERNAL: —")
                }
                val sdPct = percentFree(data.sd.freeBytes, data.sd.totalBytes)
                if (data.sd.freeBytes != null && data.sd.totalBytes != null && sdPct != null) {
                    StatusLine(
                        "${data.sd.label}: ${formatStatBytes(data.sd.freeBytes)} FREE OF " +
                            "${formatStatBytes(data.sd.totalBytes)} ($sdPct%)",
                        if (sdPct < 10) Crystal.Bad else Crystal.Ink,
                    )
                } else {
                    DimLine("${data.sd.label}: —")
                }
            }
        }
    }
    // ---- device ----
    panel(key = "health-device") {
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusLine("DEVICE", Crystal.Cream)
                val ramPct = percentFree(data.ramFree, data.ramTotal)
                if (data.ramFree != null && data.ramTotal != null && ramPct != null) {
                    DimLine(
                        "RAM: ${formatStatBytes(data.ramFree)} FREE OF " +
                            "${formatStatBytes(data.ramTotal)} ($ramPct%)",
                    )
                } else {
                    DimLine("RAM: —")
                }
                val loadText = data.chipLoadPct?.let { "LOAD $it%" } ?: "LOAD —"
                DimLine("CHIP: ${data.chipModel} • ${data.chipCores} CORES • $loadText")
                val batteryText = when {
                    data.batteryPct != null && data.batteryTempC != null ->
                        "BATTERY: ${data.batteryPct}% • ${"%.1f".format(data.batteryTempC)}°C"
                    data.batteryPct != null -> "BATTERY: ${data.batteryPct}%"
                    else -> "BATTERY: —"
                }
                DimLine(batteryText)
            }
        }
    }
    // ---- downloads / importer / theme / grants ----
    panel(key = "health-downloads") {
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusLine("DOWNLOADS & STATUS", Crystal.Cream)
                if (data.downloadsPending > 0) {
                    StatusLine("DOWNLOADS: ${data.downloadsPending} PENDING")
                } else {
                    StatusLine("DOWNLOADS: CLEAR", Crystal.Good)
                }
                if (data.failedImports > 0) {
                    StatusLine("FAILED IMPORTS: ${data.failedImports}", Crystal.Bad)
                }
                if (importerStatusLine.isNotEmpty()) {
                    StatusLine(
                        "IMPORTER: $importerStatusLine",
                        if (importerAttention) Crystal.Joystick else Crystal.InkDim,
                    )
                }
                when {
                    !data.themeCatalogReady -> DimLine("THEME: CATALOG UNKNOWN")
                    data.themeOutOfDate -> StatusLine("THEME: UPDATE AVAILABLE", Crystal.Joystick)
                    else -> StatusLine("THEME: UP TO DATE", Crystal.Good)
                }
                when (data.romGrant) {
                    RomGrantState.GRANTED -> DimLine("ROM FOLDER: GRANTED")
                    RomGrantState.NOT_GRANTED ->
                        StatusLine("ROM FOLDER: NOT GRANTED", Crystal.Bad)
                    RomGrantState.LOST ->
                        StatusLine("ROM FOLDER: ACCESS LOST", Crystal.Bad)
                }
            }
        }
    }
}
