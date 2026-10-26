package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 *
 * u89 command-center layout (layout only): result counts as statTiles,
 * each failed row as a full-width detail panel (title, platform, and
 * the failure reason each on their own line, wrapping freely, never
 * clipped) with its four actions as commandTiles. The old 3-column
 * layout left the fourth button stranded on its own row; the u70
 * 2-column grid keeps the 2×2 action grouping.
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
            columns = GridCells.Fixed(gridColumnsForWidth(LocalContentWidth.current.value.toInt(), 2)),
            initialFocus = ::isInitialFocus,
        ) {
            if (state == null) {
                panel { StatusStrip("…") }
                return@ControllerGrid
            }
            panel {
                ZoneHeader(if (state.cancelled) "RUN CANCELLED" else "RUN FINISHED")
                if (state.deletedSources > 0 || state.failed.isNotEmpty()) {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    }
                }
            }
            statTile(label = "IMPORTED", value = "${state.succeeded}")
            statTile(label = "FAILED", value = "${state.failed.size}")
            statTile(label = "SKIPPED", value = "${state.skipped}")
            for (row in state.failed) {
                FailedSection(row, platforms, platformOverride, graph)
            }
            commandTile(
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
    // Full-width failure card: every line wraps freely — the reason
    // is never clipped, however long it gets.
    panel {
        ZoneHeader("✗ ${row.title}")
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val platformName = row.platform?.labels()?.long?.uppercase() ?: "UNKNOWN PLATFORM"
                StatusLine("PLATFORM: $platformName", Crystal.Ink)
                StatusLine("REASON: ${row.reason.label()}", Crystal.InkDim)
                row.detail?.let { StatusLine(it, Crystal.InkDim) }
                if (chosen != null && chosen != row.platform) {
                    StatusLine(
                        "WILL RETRY AS ${chosen.labels().long.uppercase()}",
                        Crystal.Joystick,
                    )
                }
            }
        }
    }
    // One action per tile: controller-simple, no nested focus grid.
    commandTile(
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
    commandTile(
        key = "platform-${row.itemId}",
        label = "PLATFORM: ${(chosen ?: row.platform)?.labels()?.short ?: "?"}",
        subLabel = "TAP TO CYCLE — THEN RETRY",
        onClick = {
            val current = chosen ?: row.platform ?: platforms.first()
            val next = platforms[(platforms.indexOf(current) + 1) % platforms.size]
            platformOverride[row.itemId] = next
        },
    )
    commandTile(
        key = "skip-${row.itemId}",
        label = "SKIP",
        subLabel = "LEAVES THE SOURCE IN DOWNLOADS",
        onClick = { engine.skipFailedItem(row.itemId) },
    )
    commandTile(
        key = "remove-${row.itemId}",
        label = "REMOVE",
        subLabel = "DROPS IT FROM THE QUEUE — NOTHING IS DELETED",
        onClick = { engine.removeFailedItem(row.itemId) },
        danger = true,
    )
}
