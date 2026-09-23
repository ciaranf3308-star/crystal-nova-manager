package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
                        ProgressBar(p)
                        StatusLine("${(p * 100).toInt()}%", Crystal.InkDim)
                    }
                }
            } else {
                section {
                    StatusLine("WRAPPING UP…", Crystal.InkDim)
                }
            }
            section {
                StatusLine("QUEUE")
                for (row in state.rows) {
                    val (mark, color) = when (row.state) {
                        RowState.DONE -> "✓" to Crystal.Good
                        RowState.FAILED -> "✗" to Crystal.Bad
                        RowState.ACTIVE -> "▶" to Crystal.Joystick
                        RowState.SKIPPED -> "–" to Crystal.InkDim
                        RowState.PENDING -> "·" to Crystal.Ink
                    }
                    StatusLine("$mark ${row.title}", color)
                }
            }
            if (state.log.isNotEmpty()) {
                section {
                    StatusLine("LOG")
                    for (line in state.log.takeLast(10)) {
                        StatusLine(line, Crystal.InkDim)
                    }
                }
            }
            if (state.cancelRequested) {
                section {
                    StatusLine(
                        "CANCELLING AFTER THE CURRENT GAME…",
                        Crystal.Joystick,
                    )
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

@Composable
private fun ProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(Crystal.TileDeep),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(10.dp)
                .background(Crystal.Joystick),
        )
    }
}
