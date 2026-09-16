package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.crystalnova.manager.diag.DiagnosticsInfo
import io.crystalnova.manager.diag.IndexHealth
import io.crystalnova.manager.diag.appUpdateSummary
import io.crystalnova.manager.diag.indexStatus
import io.crystalnova.manager.diag.lastRunSummary
import io.crystalnova.manager.diag.resolverSummary
import io.crystalnova.manager.diag.themeBackupSummary
import io.crystalnova.manager.diag.themeInstalledSummary
import io.crystalnova.manager.diag.themeLatestSummary
import io.crystalnova.manager.diag.updateNoticeOf

/**
 * Hidden diagnostics screen (open via 5 taps on the version label).
 * Plain scrollable readout: versions, SAF roots, index status, last
 * error, library counts, resolver hit/miss. No actions except REFRESH
 * and CLOSE — this screen changes nothing.
 */
@Composable
fun DiagnosticsScreen(
    info: DiagnosticsInfo,
    dispatcher: FocusDispatcher,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Crystal.Background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        dispatcher.activateFocused()
                        true
                    }
                    Key.ButtonB -> {
                        onClose()
                        true
                    }
                    else -> false
                }
            },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(
            text = "DIAGNOSTICS",
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = Crystal.TitleSize,
                color = Crystal.Ink,
                letterSpacing = 2.sp,
            ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CrystalPanel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagSection("APP")
                    DiagRow("MANAGER", info.managerVersion, Crystal.Good)
                    DiagRow("APP UPDATE", appUpdateSummary(info.appUpdate), null)
                }
            }
            CrystalPanel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagSection("THEME")
                    val s = info.managerState
                    DiagRow("INSTALLED", themeInstalledSummary(s), null)
                    DiagRow("LATEST KNOWN", themeLatestSummary(s), null)
                    DiagRow("BACKUP", themeBackupSummary(s), null)
                    DiagRow("UPDATER STATE", s.javaClass.simpleName, null)
                    updateNoticeOf(s)?.let { DiagRow("NOTICE", it, Crystal.Bad) }
                }
            }
            CrystalPanel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagSection("STORAGE")
                    DiagRow(
                        "THEMES ROOT",
                        info.themesRoot ?: "NOT SELECTED",
                        if (info.themesRoot == null) Crystal.Bad else null,
                    )
                    DiagRow(
                        "GAMES ROOT",
                        info.scraper.gamesFolderUri ?: "NOT SELECTED",
                        if (info.scraper.gamesFolderUri == null) Crystal.Bad else null,
                    )
                    DiagRow("DATA DIR", "crystal-nova-data/ (under themes root)", null)
                }
            }
            CrystalPanel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagSection("SCRAPER INDEX")
                    val d = info.scraper
                    val (indexHealth, indexText) =
                        indexStatus(d.indexFound, d.indexParseOk, d.indexGames)
                    val indexColor = when (indexHealth) {
                        IndexHealth.OK -> Crystal.Good
                        IndexHealth.MISSING -> Crystal.InkDim
                        IndexHealth.UNREADABLE -> Crystal.Bad
                    }
                    DiagRow("INDEX.JSON", indexText, indexColor)
                    DiagRow(
                        "LAST ERROR",
                        d.lastError ?: "NONE",
                        if (d.lastError == null) Crystal.InkDim else Crystal.Bad,
                    )
                    d.notice?.let { DiagRow("NOTICE", it, Crystal.Divider) }
                }
            }
            CrystalPanel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagSection("LIBRARY")
                    val d = info.scraper
                    DiagRow(
                        "SCANNED",
                        "${d.scannedGames} games · ${d.systems.size} systems",
                        null,
                    )
                    for (sys in d.systems.sortedByDescending { it.gameCount }) {
                        DiagRow(
                            sys.platformSlug.uppercase(),
                            "${sys.label} — ${sys.gameCount}",
                            Crystal.InkDim,
                        )
                    }
                }
            }
            CrystalPanel {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagSection("RESOLVER")
                    val st = info.scraper.stats
                    DiagRow(
                        "HIT / MISS",
                        resolverSummary(st.totalGames, st.unmatched),
                        if (st.unmatched > 0) Crystal.Divider else Crystal.Good,
                    )
                    DiagRow(
                        "COVERAGE",
                        "${st.complete} complete · ${st.partial} partial · ${st.unmatched} unmatched",
                        null,
                    )
                    DiagRow(
                        "ASSETS",
                        "${st.realAssets} real · ${st.generatedAssets} generated",
                        null,
                    )
                    DiagRow("LAST RUN", lastRunSummary(info.scraper.lastProgress), Crystal.InkDim)
                }
            }
        }
        CrystalButton(
            key = "diag-refresh",
            label = "REFRESH",
            onClick = onRefresh,
            dispatcher = dispatcher,
        )
        CrystalButton(
            key = "diag-close",
            label = "CLOSE",
            onClick = onClose,
            dispatcher = dispatcher,
            requestInitialFocus = true,
        )
    }
    }
}

@Composable
private fun DiagSection(title: String) {
    SectionLabel(title)
    CrystalDivider()
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.SmallSize,
                color = Crystal.Divider,
                letterSpacing = 2.sp,
            ),
        )
        BasicText(
            text = value,
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.BodySize,
                color = valueColor ?: Crystal.Ink,
            ),
        )
    }
}

