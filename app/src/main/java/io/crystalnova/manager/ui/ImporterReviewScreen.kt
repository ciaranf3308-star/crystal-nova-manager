package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    // Hoisted here (not in the ControllerList content lambda) because
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
            columns = GridCells.Fixed(3),
            initialFocus = ::isInitialFocus,
        ) {
            when (val s = uiState) {
                is ImportUiState.ConflictReview -> ConflictSection(s, graph, choices)
                is ImportUiState.Ready -> ReadySection(s, graph, onStartImport)
                is ImportUiState.Error -> {
                    panel {
                        StatusLine(s.message, Crystal.Bad)
                    }
                    control(
                        key = "dismiss-notice",
                        label = "DISMISS",
                        onClick = { engine.backToIdle() },
                    )
                }
                else -> panel {
                    StatusLine("PREPARING…", Crystal.InkDim)
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
        StatusLine(
            "${s.totalGames} GAME${if (s.totalGames == 1) "" else "S"} · " +
                "NEED ${formatBytes(s.estimatedBytes)}",
            Crystal.Joystick,
        )
        val freeLine = "FREE ${formatBytes(s.freeBytes)}"
        StatusLine(
            if (s.storageOk) freeLine else "$freeLine — NOT ENOUGH HEADROOM",
            if (s.storageOk) Crystal.Good else Crystal.Bad,
        )
    }
    for (group in s.groups) {
        panel {
            val folder = graph.mapping.folderFor(group.platform)
            StatusLine(
                "${group.platform.labels().long.uppercase()} → /$folder",
                Crystal.Cream,
            )
            GameChipRow(
                games = group.items.map {
                    "· ${it.displayTitle} (${formatBytes(it.archiveBytes)})"
                },
            )
        }
    }
    panel {
        StatusLine("SEQUENTIAL — ONE ARCHIVE AT A TIME", Crystal.InkDim)
        StatusLine("SOURCE DELETED ONLY AFTER VERIFY", Crystal.InkDim)
    }
    control(
        key = "import-all",
        label = "IMPORT ALL (${s.totalGames})",
        subLabel = if (s.storageOk) "RUNS IN A FOREGROUND SERVICE"
        else "BLOCKED — FREE UP SPACE FIRST",
        enabled = s.storageOk,
        onClick = onStartImport,
        modifier = Modifier.height(88.dp),
    )
    control(
        key = "back-classify",
        label = "BACK TO CLASSIFICATION",
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
        StatusLine(
            "${s.conflicts.size} ALREADY IN THE LIBRARY",
            Crystal.Joystick,
        )
        StatusLine("DEFAULT: ${s.default.label()}", Crystal.InkDim)
        StatusLine("PICK PER GAME, THEN CONFIRM.", Crystal.InkDim)
    }
    for (conflict in s.conflicts) {
        ConflictRow(conflict, choices)
    }
    control(
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
    control(
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
        StatusLine(conflict.title)
        StatusLine(
            "EXISTS AS ${conflict.existingName}",
            Crystal.InkDim,
        )
        StatusLine("CHOICE: ${current.label()}", Crystal.Joystick)
    }
    // One choice per row: controller-simple, no nested focus grid.
    control(
        key = "conflict-skip-${conflict.itemId}",
        label = "SKIP THIS GAME",
        subLabel = if (current == DuplicatePolicy.SKIP) "SELECTED" else null,
        onClick = { choices[conflict.itemId] = DuplicatePolicy.SKIP },
    )
    control(
        key = "conflict-replace-${conflict.itemId}",
        label = "REPLACE EXISTING",
        subLabel = if (current == DuplicatePolicy.REPLACE) "SELECTED" else null,
        onClick = { choices[conflict.itemId] = DuplicatePolicy.REPLACE },
    )
    control(
        key = "conflict-keepboth-${conflict.itemId}",
        label = "KEEP BOTH",
        subLabel = if (current == DuplicatePolicy.KEEP_BOTH) "SELECTED" else null,
        onClick = { choices[conflict.itemId] = DuplicatePolicy.KEEP_BOTH },
    )
}

private fun DuplicatePolicy.label(): String = when (this) {
    DuplicatePolicy.SKIP -> "SKIP"
    DuplicatePolicy.REPLACE -> "REPLACE"
    DuplicatePolicy.KEEP_BOTH -> "KEEP BOTH"
}

/**
 * Display-only game chips for the review groups: a flowing row of
 * small tiles inside the platform panel. Not focusable — there is no
 * per-game action on this screen (IMPORT ALL / conflict choices are
 * the actions), so D-pad focus skips straight past them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameChipRow(games: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (game in games) {
            GameChip(game)
        }
    }
}

@Composable
private fun GameChip(text: String) {
    Box(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Crystal.TileDeep)
            .border(2.dp, Crystal.FrameDim, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.SmallSize,
                color = Crystal.Ink,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
