package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 *
 * 4:3 redesign: 2-column grid. Each platform group is a full-width
 * panel with a clear header (platform → destination folder) and a
 * deterministic vertical stack of readable game rows (filename +
 * size, ellipsis if long) — no FlowRow chip soup. IMPORT ALL is the
 * first, biggest control after the storage summary, carrying the
 * storage numbers in its own sublabel so the go/no-go is visible on
 * the button itself. Conflict choices get an unmistakable "▸ "
 * marker plus SELECTED on the active option.
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
            columns = GridCells.Fixed(2),
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
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                for (item in group.items) {
                    GameRow("· ${item.displayTitle} (${formatBytes(item.archiveBytes)})")
                }
            }
        }
    }
    panel {
        StatusLine("SEQUENTIAL — ONE ARCHIVE AT A TIME", Crystal.InkDim)
        StatusLine("SOURCE DELETED ONLY AFTER VERIFY", Crystal.InkDim)
    }
    panel {
        StatusLine("▶ READY TO IMPORT", Crystal.Joystick)
    }
    // The big primary action: content-sized (two-line sublabel carries
    // the storage verdict, so the tile is naturally tall — no fixed
    // height), first focusable in the zone, storage summary in the
    // panel directly above it.
    control(
        key = "import-all",
        label = "IMPORT ALL (${s.totalGames})",
        subLabel = "NEED ${formatBytes(s.estimatedBytes)} · FREE ${formatBytes(s.freeBytes)}\n" +
            if (s.storageOk) "RUNS IN A FOREGROUND SERVICE"
            else "BLOCKED — FREE UP SPACE FIRST",
        enabled = s.storageOk,
        onClick = onStartImport,
    )
    control(
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
        StatusLine("⚠ ${conflict.title}", Crystal.Bad)
        StatusLine(
            "EXISTS AS ${conflict.existingName}",
            Crystal.InkDim,
        )
        StatusLine("CHOICE: ${current.label()}", Crystal.Joystick)
    }
    // One choice per control: controller-simple, no nested focus grid.
    // The active choice is unmistakable — "▸ " prefix on the label
    // plus SELECTED, visible with or without focus.
    control(
        key = "conflict-skip-${conflict.itemId}",
        label = if (current == DuplicatePolicy.SKIP) "▸ SKIP" else "SKIP",
        subLabel = if (current == DuplicatePolicy.SKIP) "SELECTED" else "LEAVE THE LIBRARY COPY ALONE",
        onClick = { choices[conflict.itemId] = DuplicatePolicy.SKIP },
    )
    control(
        key = "conflict-replace-${conflict.itemId}",
        label = if (current == DuplicatePolicy.REPLACE) "▸ REPLACE" else "REPLACE",
        subLabel = if (current == DuplicatePolicy.REPLACE) "SELECTED" else "OVERWRITE THE LIBRARY COPY",
        onClick = { choices[conflict.itemId] = DuplicatePolicy.REPLACE },
    )
    control(
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

/**
 * Display-only game row for the review groups: a deterministic
 * vertical stack of full-width rows inside the platform panel.
 * Filename + size, ellipsis if long — no wrapping chip soup. Not
 * focusable: there is no per-game action on this screen (IMPORT ALL /
 * conflict choices are the actions), so D-pad focus skips straight
 * past them.
 */
@Composable
private fun GameRow(text: String) {
    BasicText(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(Crystal.TileDeep)
            .border(2.dp, Crystal.FrameDim, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = TextStyle(
            fontFamily = Crystal.Mono,
            fontSize = Crystal.SmallSize,
            color = Crystal.Ink,
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
