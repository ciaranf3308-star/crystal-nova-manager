package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.crystalnova.manager.importer.Confidence
import io.crystalnova.manager.importer.ImportUiState
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.PlatformId
import io.crystalnova.manager.importer.formatBytes
import io.crystalnova.manager.importer.labels

/**
 * One-at-a-time platform classification. Only archives the detector
 * could not place arrive here — everything certain skipped this
 * screen entirely.
 *
 * A platform tap saves immediately and advances (no save button).
 * When nothing is left to review, DONE moves on: REVIEW when games
 * are actionable, otherwise back to the hub.
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
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            if (state == null || state.needsReview.isEmpty()) {
                section {
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
                return@ControllerList
            }

            val item = state.needsReview.first()
            val remaining = state.needsReview.size - 1
            section {
                StatusLine(
                    if (remaining == 0) "LAST ONE" else "$remaining MORE AFTER THIS",
                    Crystal.Joystick,
                )
                StatusLine(item.displayTitle)
                StatusLine(
                    "${item.archiveName} · ${formatBytes(item.archiveBytes)} · ${item.archiveKind}",
                    Crystal.InkDim,
                )
                if (item.relativePath.isNotEmpty()) {
                    StatusLine("IN DOWNLOADS/${item.relativePath}", Crystal.InkDim)
                }
                if (item.detection.signals.isNotEmpty()) {
                    StatusLine(
                        "CLUES: ${item.detection.signals.take(3).joinToString(" · ")}",
                        Crystal.InkDim,
                    )
                }
                if (item.detection.confidence == Confidence.LIKELY && item.detection.platform != null) {
                    StatusLine(
                        "DETECTOR GUESSES: ${item.detection.platform.labels().long}",
                        Crystal.InkDim,
                    )
                }
            }
            section {
                StatusLine("PICK THE PLATFORM — TAP SAVES + ADVANCES")
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
            section {
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
        }
    }
}
