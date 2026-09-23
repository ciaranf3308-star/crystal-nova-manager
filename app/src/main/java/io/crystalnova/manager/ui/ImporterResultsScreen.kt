package io.crystalnova.manager.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import io.crystalnova.manager.importer.FailedRow
import io.crystalnova.manager.importer.ImportUiState
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.PlatformId
import io.crystalnova.manager.importer.label
import io.crystalnova.manager.importer.labels

/**
 * Run summary. Failures keep their source archive (the engine never
 * deletes on failure), so each failed row offers RETRY, a platform
 * override that retries under the new platform, and SKIP. DONE clears
 * the finished items and returns to the hub — or back to
 * classification when work remains.
 */
@Composable
fun ImporterResultsScreen(
    graph: ImporterGraph,
    onBack: () -> Unit,
) {
    val engine = graph.engine
    val uiState by engine.uiState.collectAsState()
    val state = uiState as? ImportUiState.Results
    val platforms = remember { PlatformId.entries.toList() }
    val platformOverride = remember(state) { mutableStateMapOf<String, PlatformId>() }

    ScreenScaffold(
        routeKey = "import-results",
        title = "IMPORT RESULTS",
        onBack = onBack,
        fallbackFocusKey = "done",
    ) {
        ControllerGrid(
            state = gridState,
            dispatcher = dispatcher,
            columns = GridCells.Fixed(3),
            initialFocus = ::isInitialFocus,
        ) {
            if (state == null) {
                panel { StatusLine("…", Crystal.InkDim) }
                return@ControllerGrid
            }
            panel {
                val headline = if (state.cancelled) "RUN CANCELLED" else "RUN FINISHED"
                StatusLine(headline, Crystal.Joystick)
                StatusLine(
                    "${state.succeeded} IMPORTED · ${state.failed.size} FAILED · " +
                        "${state.skipped} SKIPPED",
                    if (state.failed.isEmpty()) Crystal.Good else Crystal.Ink,
                )
                if (state.deletedSources > 0) {
                    StatusLine(
                        "${state.deletedSources} SOURCE ARCHIVE${if (state.deletedSources == 1) "" else "S"} " +
                            "DELETED AFTER VERIFY",
                        Crystal.InkDim,
                    )
                }
                if (state.failed.isNotEmpty()) {
                    StatusLine(
                        "FAILED SOURCES WERE KEPT — RETRY OR SKIP BELOW",
                        Crystal.Bad,
                    )
                }
            }
            for (row in state.failed) {
                FailedSection(row, platforms, platformOverride, graph)
            }
            control(
                key = "done",
                label = "DONE",
                onClick = { engine.dismissResults() },
            )
        }
    }
}

private fun ControllerGridContent.FailedSection(
    row: FailedRow,
    platforms: List<PlatformId>,
    platformOverride: MutableMap<String, PlatformId>,
    graph: ImporterGraph,
) {
    val engine = graph.engine
    val chosen = platformOverride[row.itemId] ?: row.platform
    panel {
        StatusLine("✗ ${row.title}", Crystal.Bad)
        val platformName = row.platform?.labels()?.long?.uppercase() ?: "UNKNOWN PLATFORM"
        StatusLine("$platformName · ${row.reason.label()}", Crystal.InkDim)
        if (chosen != null && chosen != row.platform) {
            StatusLine(
                "WILL RETRY AS ${chosen.labels().long.uppercase()}",
                Crystal.Joystick,
            )
        }
    }
    // One action per row: controller-simple, no nested focus grid.
    control(
        key = "retry-${row.itemId}",
        label = "RETRY",
        subLabel = if (chosen != null) "RETRIES THIS GAME AS ${chosen.labels().short}"
        else "RETRIES ALL FAILED",
        onClick = {
            if (chosen != null) {
                engine.changePlatformAndRetry(row.itemId, chosen)
            } else {
                engine.retryFailed()
            }
        },
    )
    control(
        key = "platform-${row.itemId}",
        label = "PLATFORM: ${(chosen ?: row.platform)?.labels()?.short ?: "?"}",
        subLabel = "TAP TO CYCLE — THEN RETRY",
        onClick = {
            val current = chosen ?: row.platform ?: platforms.first()
            val next = platforms[(platforms.indexOf(current) + 1) % platforms.size]
            platformOverride[row.itemId] = next
        },
    )
    control(
        key = "skip-${row.itemId}",
        label = "SKIP",
        subLabel = "LEAVES THE SOURCE IN DOWNLOADS",
        onClick = { engine.skipFailedItem(row.itemId) },
    )
}
