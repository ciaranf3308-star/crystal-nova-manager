package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import io.crystalnova.manager.importer.DuplicatePolicy
import io.crystalnova.manager.importer.ImportUiState
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.ItemConflict
import io.crystalnova.manager.importer.formatBytes
import io.crystalnova.manager.importer.labels

/**
 * Review before the run: games grouped by platform with their target
 * folders, the storage preflight, and IMPORT ALL. Duplicate conflicts
 * are resolved here first — per-item SKIP / REPLACE / KEEP BOTH —
 * with the persisted default pre-selected.
 *
 * IMPORT ALL starts the foreground service and the engine. Execution
 * is strictly sequential and always on; there is no parallelism
 * toggle to show.
 *
 * u89 command-center layout (layout only): storage + counts as
 * statTiles, each platform group under a ZoneHeader with its games
 * as reclassify commandTiles, each conflict as a detail panel with
 * its three choice tiles. Approve/reject/skip semantics are exactly
 * as before — the choice tiles keep the "▸ " marker plus SELECTED on
 * the active option.
 */
@Composable
fun ImporterReviewScreen(
    graph: ImporterGraph,
    onBack: () -> Unit,
    /** Starts the foreground service AND the engine run. */
    onStartImport: () -> Unit,
) {
    val engine = graph.engine
    val uiState by engine.uiState.collectAsState()
    // Per-conflict choice, pre-seeded with the persisted default.
    // Hoisted here (not in the ControllerGrid content lambda) because
    // that lambda is not a @Composable context.
    val conflicts = (uiState as? ImportUiState.ConflictReview)?.conflicts
    val choices = remember(conflicts) {
        mutableStateMapOf<String, DuplicatePolicy>().apply {
            conflicts?.forEach { put(it.itemId, it.resolution) }
        }
    }

    ScreenScaffold(
        routeKey = "import-review",
        title = "REVIEW IMPORT",
        onBack = onBack,
        fallbackFocusKey = "import-all",
    ) {
        ControllerGrid(
            state = gridState,
            dispatcher = dispatcher,
            columns = GridCells.Fixed(gridColumnsForWidth(LocalContentWidth.current.value.toInt(), 2)),
            initialFocus = ::isInitialFocus,
        ) {
            when (val s = uiState) {
                is ImportUiState.ConflictReview -> ConflictSection(s, graph, choices)
                is ImportUiState.Ready -> ReadySection(s, graph, onStartImport)
                is ImportUiState.Error -> {
                    panel {
                        StatusStrip(s.message, attention = true)
                    }
                    commandTile(
                        key = "dismiss-notice",
                        label = "DISMISS",
                        onClick = { engine.backToIdle() },
                    )
                }
                else -> panel {
                    StatusStrip("PREPARING…")
                }
            }
        }
    }
}

private fun ControllerGridContent.ReadySection(
    s: ImportUiState.Ready,
    graph: ImporterGraph,
    onStartImport: () -> Unit,
) {
    val engine = graph.engine
    panel {
        ZoneHeader("STORAGE")
    }
    statTile(
        label = "GAMES",
        value = "${s.totalGames}",
        subline = "NEED ${formatBytes(s.estimatedBytes)}",
    )
    statTile(
        label = "FREE",
        value = formatBytes(s.freeBytes),
        subline = if (s.storageOk) null else "NOT ENOUGH HEADROOM",
    )
    for (group in s.groups) {
        panel {
            ZoneHeader(
                "${group.platform.labels().long.uppercase()} → /${graph.mapping.folderFor(group.platform)}",
            )
        }
        // One tile per game: a wrong console pick goes back to the
        // classify screen as the first item with a single tap.
        for (item in group.items) {
            commandTile(
                key = "reclassify-${item.id}",
                label = "· ${item.displayTitle} (${formatBytes(item.archiveBytes)})",
                subLabel = "WRONG CONSOLE? TAP TO RE-CLASSIFY",
                onClick = { engine.reclassifyItem(item.id); engine.backToClassifying() },
            )
        }
    }
    panel {
        ZoneHeader("IMPORT")
        StatusStrip("SEQUENTIAL — ONE ITEM AT A TIME")
        StatusStrip("SOURCE DELETED ONLY AFTER VERIFY")
        StatusStrip("▶ READY TO IMPORT")
    }
    // The big primary action: the storage verdict rides in its
    // sublabel, so the tile carries the go/no-go itself.
    commandTile(
        key = "import-all",
        label = "IMPORT ALL (${s.totalGames})",
        subLabel = "NEED ${formatBytes(s.estimatedBytes)} · FREE ${formatBytes(s.freeBytes)}\n" +
            if (s.storageOk) "RUNS IN A FOREGROUND SERVICE"
            else "BLOCKED — FREE UP SPACE FIRST",
        enabled = s.storageOk,
        onClick = onStartImport,
    )
    commandTile(
        key = "back-classify",
        label = "BACK TO CLASSIFICATION",
        subLabel = "CHANGE A PLATFORM PICK",
        onClick = { engine.backToClassifying() },
    )
}

private fun ControllerGridContent.ConflictSection(
    s: ImportUiState.ConflictReview,
    graph: ImporterGraph,
    choices: MutableMap<String, DuplicatePolicy>,
) {
    val engine = graph.engine
    panel {
        ZoneHeader("ALREADY IN THE LIBRARY")
    }
    statTile(label = "CONFLICTS", value = "${s.conflicts.size}")
    statTile(label = "DEFAULT", value = s.default.label())
    panel {
        StatusStrip("PICK PER GAME, THEN CONFIRM.")
    }
    for (conflict in s.conflicts) {
        ConflictRow(conflict, choices)
    }
    commandTile(
        key = "confirm-conflicts",
        label = "CONFIRM + CONTINUE",
        onClick = {
            engine.confirmConflicts(
                s.conflicts.map {
                    it.copy(resolution = choices[it.itemId] ?: it.resolution)
                },
            )
        },
    )
    commandTile(
        key = "back-classify-2",
        label = "BACK TO CLASSIFICATION",
        onClick = { engine.backToClassifying() },
    )
}

private fun ControllerGridContent.ConflictRow(
    conflict: ItemConflict,
    choices: MutableMap<String, DuplicatePolicy>,
) {
    val current = choices[conflict.itemId] ?: conflict.resolution
    panel {
        ZoneHeader("⚠ ${conflict.title}")
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusLine(
                    "EXISTS AS ${conflict.existingName}",
                    Crystal.InkDim,
                )
                StatusLine("CHOICE: ${current.label()}", Crystal.Joystick)
            }
        }
    }
    // One choice per tile: controller-simple, no nested focus grid.
    // The active choice is unmistakable — "▸ " prefix on the label
    // plus SELECTED, visible with or without focus.
    commandTile(
        key = "conflict-skip-${conflict.itemId}",
        label = if (current == DuplicatePolicy.SKIP) "▸ SKIP" else "SKIP",
        subLabel = if (current == DuplicatePolicy.SKIP) "SELECTED" else "LEAVE THE LIBRARY COPY ALONE",
        onClick = { choices[conflict.itemId] = DuplicatePolicy.SKIP },
    )
    commandTile(
        key = "conflict-replace-${conflict.itemId}",
        label = if (current == DuplicatePolicy.REPLACE) "▸ REPLACE" else "REPLACE",
        subLabel = if (current == DuplicatePolicy.REPLACE) "SELECTED" else "OVERWRITE THE LIBRARY COPY",
        onClick = { choices[conflict.itemId] = DuplicatePolicy.REPLACE },
    )
    commandTile(
        key = "conflict-keepboth-${conflict.itemId}",
        label = if (current == DuplicatePolicy.KEEP_BOTH) "▸ KEEP BOTH" else "KEEP BOTH",
        subLabel = if (current == DuplicatePolicy.KEEP_BOTH) "SELECTED" else "IMPORT ALONGSIDE THE EXISTING COPY",
        onClick = { choices[conflict.itemId] = DuplicatePolicy.KEEP_BOTH },
    )
}

private fun DuplicatePolicy.label(): String = when (this) {
    DuplicatePolicy.SKIP -> "SKIP"
    DuplicatePolicy.REPLACE -> "REPLACE"
    DuplicatePolicy.KEEP_BOTH -> "KEEP BOTH"
}
