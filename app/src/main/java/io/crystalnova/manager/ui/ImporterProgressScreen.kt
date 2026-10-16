package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
 * queue rows, and the technical log tail. CANCEL AFTER CURRENT is the
 * only stop control — backing out of this screen leaves the run going
 * in the foreground service; the hub offers a way back in.
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
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            if (state == null) {
                section {
                    StatusLine("STARTING…", Crystal.InkDim)
                }
                return@ControllerList
            }
            val current = state.current
            if (current != null) {
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusLine(
                                "GAME ${(state.index + 1).coerceAtMost(state.total)} OF ${state.total}",
                                Crystal.Joystick,
                            )
                            StatusLine(current.title)
                            StatusLine(
                                "${current.platform.labels().long.uppercase()} · ${current.stage.label()}",
                                Crystal.InkDim,
                            )
                            current.detail?.let { StatusLine(it, Crystal.InkDim) }
                            current.progress?.let { p ->
                                CrystalProgressBar(p)
                                StatusLine("${(p * 100).toInt()}%", Crystal.InkDim)
                            }
                        }
                    }
                }
            } else {
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        StatusLine("WRAPPING UP…", Crystal.InkDim)
                    }
                }
            }
            section {
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // u77: compact queue summary — the full per-row
                        // list and the technical log tail pushed the
                        // actual progress below the fold on the Nova.
                        // What matters: how many done, what's active,
                        // what's left, what failed.
                        val done = state.rows.count { it.state == RowState.DONE }
                        val failed = state.rows.count { it.state == RowState.FAILED }
                        val skipped =
                            state.rows.count { it.state == RowState.SKIPPED }
                        val pending =
                            state.rows.count { it.state == RowState.PENDING }
                        StatusLine(
                            "✓ $done DONE · · $pending LEFT" +
                                if (failed > 0) " · ✗ $failed FAILED" else "" +
                                if (skipped > 0) " · – $skipped SKIPPED" else "",
                            Crystal.Ink,
                        )
                        val failedTitles = state.rows
                            .filter { it.state == RowState.FAILED }
                            .take(3)
                        for (row in failedTitles) {
                            StatusLine("✗ ${row.title}", Crystal.Bad)
                        }
                    }
                }
            }
            if (state.cancelRequested) {
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        StatusLine(
                            "CANCELLING AFTER THE CURRENT GAME…",
                            Crystal.Joystick,
                        )
                    }
                }
            } else {
                control(
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
