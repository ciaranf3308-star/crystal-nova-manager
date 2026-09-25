package io.crystalnova.manager.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.crystalnova.manager.importer.Confidence
import io.crystalnova.manager.importer.ArchiveKind
import io.crystalnova.manager.importer.ImportUiState
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.PlatformId
import io.crystalnova.manager.importer.formatBytes
import io.crystalnova.manager.importer.labels

/** Human-readable container label for the classify identity line. */
private fun kindLabel(kind: ArchiveKind): String = when (kind) {
    ArchiveKind.ZIP -> "ZIP"
    ArchiveKind.SEVEN_Z -> "7Z"
    ArchiveKind.LOOSE_FILE -> "LOOSE FILE"
    ArchiveKind.RAR_UNSUPPORTED -> "RAR (UNSUPPORTED)"
    ArchiveKind.UNKNOWN -> "FILE"
}

/**
 * One-at-a-time platform classification. Only items the detector
 * could not place arrive here — everything certain skipped this
 * screen entirely.
 *
 * A platform tap saves immediately and advances (no save button).
 * When nothing is left to review, DONE moves on: REVIEW when games
 * are actionable, otherwise back to the hub.
 *
 * 4:3 redesign: a 2-column grid with a clear three-zone hierarchy —
 * the item's identity in a prominent full-width panel at top
 * (title, filename, size, friendly detector clues), the 18 platform
 * choices as generous content-sized tiles, and SKIP / NOT A GAME as
 * a distinct action row at the bottom. Tiles size to their content;
 * long text ellipsizes — no fixed heights anywhere.
 */
@Composable
fun ImporterClassifyScreen(
    graph: ImporterGraph,
    onBack: () -> Unit,
    onOpenHub: () -> Unit,
) {
    val engine = graph.engine
    val uiState by engine.uiState.collectAsState()
    val state = uiState as? ImportUiState.Classifying

    ScreenScaffold(
        routeKey = "import-classify",
        title = "CLASSIFY GAME",
        onBack = onBack,
        fallbackFocusKey = "done",
    ) {
        ControllerGrid(
            state = gridState,
            dispatcher = dispatcher,
            columns = GridCells.Fixed(2),
            initialFocus = ::isInitialFocus,
        ) {
            if (state == null || state.needsReview.isEmpty()) {
                panel {
                    StatusLine("NOTHING LEFT TO CLASSIFY.", Crystal.Good)
                    val actionable = state?.actionable ?: 0
                    if (actionable > 0) {
                        StatusLine("$actionable GAME${if (actionable == 1) "" else "S"} READY FOR REVIEW", Crystal.Joystick)
                    }
                }
                if ((state?.actionable ?: 0) > 0) {
                    control(
                        key = "done",
                        label = "DONE — REVIEW GAMES",
                        onClick = { engine.prepareImport() },
                    )
                } else {
                    control(
                        key = "done",
                        label = "DONE — BACK TO HUB",
                        onClick = { onOpenHub() },
                    )
                }
                return@ControllerGrid
            }

            val item = state.needsReview.first()
            val remaining = state.needsReview.size - 1
            // ---- Zone 1: item identity — the whole point of this
            // screen is "what is THIS file", so it gets the biggest
            // panel and the biggest type. ----
            panel {
                StatusLine(
                    if (remaining == 0) "LAST ONE" else "$remaining MORE AFTER THIS",
                    Crystal.Joystick,
                )
                BasicText(
                    text = item.displayTitle,
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        color = Crystal.Ink,
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                DimLine(
                    "${item.archiveName} · ${formatBytes(item.archiveBytes)} · ${kindLabel(item.archiveKind)}",
                )
                if (item.relativePath.isNotEmpty()) {
                    DimLine("IN DOWNLOADS/${item.relativePath}")
                }
                // Detector clues, translated from debug evidence into
                // plain-language hints.
                val clues = item.detection.signals
                    .take(3)
                    .map { friendlyClue(it, item.detection.platform) }
                if (clues.isNotEmpty()) {
                    for (clue in clues) {
                        DimLine("• $clue")
                    }
                } else if (item.detection.confidence == Confidence.UNKNOWN) {
                    DimLine("THE DETECTOR FOUND NO USABLE CLUES — YOUR CALL.")
                }
                if (item.detection.confidence == Confidence.LIKELY &&
                    item.detection.platform != null
                ) {
                    StatusLine(
                        "DETECTOR'S GUESS: ${item.detection.platform.labels().long.uppercase()} — " +
                            "TAP IT IF IT LOOKS RIGHT",
                        Crystal.Joystick,
                    )
                }
            }
            // ---- Zone 2: the platform choice. Two columns of generous
            // tiles — 18 long labels stay readable, nothing clips. ----
            // The tap confirmation lives here too, right where focus
            // is: on the 4:3 Nova display the Zone 1 identity panel is
            // off-screen while tapping tiles, so without this the user
            // had to scroll up to verify a tap registered. ----
            panel {
                StatusLine("PICK THE PLATFORM — TAP SAVES + ADVANCES")
                val pick = state.lastPick
                if (pick != null) {
                    StatusLine(
                        "✓ ${pick.title} → ${pick.platformLabel} SAVED",
                        Crystal.Good,
                    )
                }
            }
            for (platform in PlatformId.entries) {
                val hint = if (platform == item.detection.platform &&
                    item.detection.confidence == Confidence.LIKELY
                ) " ← DETECTOR GUESS" else null
                control(
                    key = "platform-${platform.name}",
                    label = platform.labels().long.uppercase(),
                    subLabel = hint,
                    onClick = { engine.setPlatform(item.id, platform) },
                )
            }
            // ---- Zone 3: the escape hatches, one unmistakable row. ----
            panel {
                StatusLine("NOT SURE? SKIP KEEPS IT FOR LATER.")
            }
            control(
                key = "skip",
                label = "SKIP FOR NOW",
                onClick = { engine.skipItem(item.id) },
            )
            control(
                key = "not-a-game",
                label = "NOT A GAME",
                subLabel = "DROPS IT — NOTHING IS DELETED",
                onClick = { engine.ignoreNotAGame(item.id) },
                danger = true,
            )
            // Undo the last platform tap: re-asks that exact console
            // choice as the next item. Disabled (D-pad skips it) until
            // a pick exists — same pattern as the storage-blocked
            // IMPORT ALL control.
            control(
                key = "undo-last-pick",
                label = "↩ UNDO LAST PICK",
                subLabel = "RE-ASK THE LAST CONSOLE CHOICE",
                enabled = state.lastPick != null,
                onClick = { engine.undoLastPick() },
            )
        }
    }
}

private val serialPattern = Regex("^[A-Z]{3,4}-?\\d{3,5}$", RegexOption.IGNORE_CASE)

/**
 * Translates raw detector evidence ("SLUS-20554", "game.iso (disc
 * header)", "name: (?i)\bgba\b…") into a plain-language clue.
 */
private fun friendlyClue(signal: String, platform: PlatformId?): String {
    val s = signal.trim()
    val lower = s.lowercase()
    val platformLong = platform?.labels()?.long ?: "a console"
    val platformShort = platform?.labels()?.short ?: "?"
    return when {
        s.startsWith("name: ") ->
            "the filename hints at $platformLong"
        lower.endsWith(" (disc header)") ->
            "disc image header matches $platformLong"
        serialPattern.matches(s) ->
            "$platformShort disc serial $s found"
        lower.contains("system.cnf") ->
            "looks like a PS1 disc image: found SYSTEM.CNF"
        lower.contains("eboot.pbp") ->
            "looks like a PSP game: found EBOOT.PBP"
        lower.contains("umd_data.bin") ->
            "looks like a PSP disc image: found UMD_DATA.BIN"
        lower.contains("psp_game") ->
            "looks like a PSP disc layout: found PSP_GAME"
        lower.contains("1st_read.bin") ->
            "looks like a Dreamcast disc image: found 1ST_READ.BIN"
        lower == "ip.bin" || lower.endsWith("/ip.bin") || lower.endsWith("\\ip.bin") ->
            "looks like a Dreamcast disc image: found IP.BIN"
        else ->
            "contains $s"
    }
}
