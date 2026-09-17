package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
 * Plain readout: versions, SAF roots, index status, last error,
 * library counts, resolver hit/miss. No actions except REFRESH and
 * CLOSE — this screen changes nothing.
 *
 * Previously the readout lived in a nested inner scroll column while
 * the title and buttons sat outside it, so drags starting outside the
 * middle region never scrolled. Now the whole screen is one
 * [ControllerList] through the scaffold: one scroll container, and
 * D-pad focus on REFRESH/CLOSE scrolls them into view.
 */
@Composable
fun DiagnosticsScreen(
    info: DiagnosticsInfo,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "diagnostics",
        title = "DIAGNOSTICS",
        onBack = onClose,
        modifier = modifier,
        showMasthead = false,
        fallbackFocusKey = "diag-close",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                CrystalPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagSection("APP")
                        DiagRow("MANAGER", info.managerVersion, Crystal.Good)
                        DiagRow("APP UPDATE", appUpdateSummary(info.appUpdate), null)
                    }
                }
            }
            section {
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
            }
            section {
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
            }
            info.lastCrashTrace?.let { trace ->
                section {
                    CrystalPanel {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DiagSection("LAST CRASH")
                            BasicText(
                                text = trace.lines().take(60).joinToString("\n"),
                                style = TextStyle(
                                    fontFamily = Crystal.Mono,
                                    fontSize = Crystal.SmallSize,
                                    color = Crystal.Bad,
                                ),
                            )
                        }
                    }
                }
            }
            section {
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
            }
            section {
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
            }
            section {
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
            control(
                key = "diag-refresh",
                testTag = "diag-refresh",
                label = "REFRESH",
                onClick = onRefresh,
            )
            control(
                key = "diag-close",
                testTag = "diag-close",
                label = "CLOSE",
                onClick = onClose,
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
