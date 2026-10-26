package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.importer.ImportUiState
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.RowState
import io.crystalnova.manager.importer.label
import io.crystalnova.manager.importer.labels

/**
 * Live import run: current game + stage + byte progress, N of M, the
 * queue counts, and failed titles. CANCEL AFTER CURRENT is the
 * only stop control — backing out of this screen leaves the run going
 * in the foreground service; the hub offers a way back in.
 *
 * u89 command-center layout (layout only): ZoneHeader + progress tile
 * + count statTiles + a readable failed-titles panel. The per-row
 * list and technical log tail stay out (u77) — what matters is how
 * many are done, what's active, what's left, what failed.
 */
@Composable
fun ImporterProgressScreen(
    graph: ImporterGraph,
    onBack: () -> Unit,
) {
    val engine = graph.engine
    val uiState by engine.uiState.collectAsState()
    val state = uiState as? ImportUiState.Importing

    ScreenScaffold(
        routeKey = "import-progress",
        title = "IMPORTING",
        onBack = onBack,
        fallbackFocusKey = "cancel-after-current",
    ) {
        ControllerGrid(
            state = gridState,
            dispatcher = dispatcher,
            columns = GridCells.Fixed(gridColumnsForWidth(LocalContentWidth.current.value.toInt(), 2)),
            initialFocus = ::isInitialFocus,
        ) {
            if (state == null) {
                panel {
                    StatusStrip("STARTING…")
                }
                return@ControllerGrid
            }
            val current = state.current
            if (current != null) {
                panel {
                    ZoneHeader(
                        "GAME ${(state.index + 1).coerceAtMost(state.total)} OF ${state.total}",
                    )
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusLine(current.title)
                            StatusLine(
                                "${current.platform.labels().long.uppercase()} · ${current.stage.label()}",
                                Crystal.InkDim,
                            )
                            current.detail?.let { StatusLine(it, Crystal.InkDim) }
                            current.progress?.let { p ->
                                StatusTile(
                                    label = "PROGRESS",
                                    value = "${(p * 100).toInt()}%",
                                    barFraction = p,
                                )
                            }
                        }
                    }
                }
            } else {
                panel {
                    StatusStrip("WRAPPING UP…")
                }
            }
            // u77: compact queue summary — the full per-row list and
            // the technical log tail pushed the actual progress below
            // the fold on the Nova. What matters: how many done,
            // what's active, what's left, what failed.
            val done = state.rows.count { it.state == RowState.DONE }
            val failed = state.rows.count { it.state == RowState.FAILED }
            val skipped = state.rows.count { it.state == RowState.SKIPPED }
            val pending = state.rows.count { it.state == RowState.PENDING }
            panel {
                ZoneHeader("QUEUE")
            }
            statTile(label = "DONE", value = "$done")
            statTile(label = "LEFT", value = "$pending")
            statTile(label = "FAILED", value = "$failed")
            statTile(label = "SKIPPED", value = "$skipped")
            val failedTitles = state.rows
                .filter { it.state == RowState.FAILED }
                .take(3)
            if (failedTitles.isNotEmpty()) {
                panel {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (row in failedTitles) {
                                StatusLine("✗ ${row.title}", Crystal.Bad)
                            }
                        }
                    }
                }
            }
            if (state.cancelRequested) {
                panel {
                    StatusStrip(
                        "CANCELLING AFTER THE CURRENT GAME…",
                        attention = true,
                    )
                }
            } else {
                commandTile(
                    key = "cancel-after-current",
                    label = "CANCEL AFTER CURRENT",
                    subLabel = "FINISHES THIS GAME, THEN STOPS",
                    onClick = { engine.cancelAfterCurrent() },
                    danger = true,
                )
            }
        }
    }
}
