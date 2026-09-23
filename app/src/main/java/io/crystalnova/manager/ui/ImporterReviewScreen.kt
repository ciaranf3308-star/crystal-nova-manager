package io.crystalnova.manager.ui

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

    ScreenScaffold(
        routeKey = "import-review",
        title = "REVIEW IMPORT",
        onBack = onBack,
        fallbackFocusKey = "import-all",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            when (val s = uiState) {
                is ImportUiState.ConflictReview -> ConflictSection(s, graph)
                is ImportUiState.Ready -> ReadySection(s, graph)
                is ImportUiState.Error -> section {
                    notice(s.message) { engine.backToIdle() }
                }
                else -> section {
                    StatusLine("PREPARING…", Crystal.InkDim)
                }
            }
        }
    }
}

private fun ControllerListContent.ReadySection(
    s: ImportUiState.Ready,
    graph: ImporterGraph,
) {
    val engine = graph.engine
    section {
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
        section {
            val folder = graph.mapping.folderFor(group.platform)
            StatusLine(
                "${group.platform.labels().long.uppercase()} → /$folder",
                Crystal.Cream,
            )
            for (item in group.items) {
                StatusLine("· ${item.displayTitle} (${formatBytes(item.archiveBytes)})")
            }
        }
    }
    section {
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
    )
    control(
        key = "back-classify",
        label = "BACK TO CLASSIFICATION",
        onClick = { engine.backToClassifying() },
    )
}

private fun ControllerListContent.ConflictSection(
    s: ImportUiState.ConflictReview,
    graph: ImporterGraph,
) {
    val engine = graph.engine
    // Per-conflict choice, pre-seeded with the persisted default.
    val choices = remember(s.conflicts) {
        mutableStateMapOf<String, DuplicatePolicy>().apply {
            s.conflicts.forEach { put(it.itemId, it.resolution) }
        }
    }
    section {
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

private fun ControllerListContent.ConflictRow(
    conflict: ItemConflict,
    choices: MutableMap<String, DuplicatePolicy>,
) {
    val current = choices[conflict.itemId] ?: conflict.resolution
    section {
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
