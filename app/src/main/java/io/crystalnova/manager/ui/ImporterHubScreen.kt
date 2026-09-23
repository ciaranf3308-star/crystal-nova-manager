package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.crystalnova.manager.importer.GrantProbe
import io.crystalnova.manager.importer.ImportOutcome
import io.crystalnova.manager.importer.ImportUiState
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.ImportStage
import io.crystalnova.manager.importer.labels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GAME IMPORTER hub: grant status, queue status, scan/resume, recent
 * history, settings entry. The engine's [ImportUiState] drives the
 * status lines; navigation side effects live in MainActivity.
 *
 * Pure function of the graph + callbacks: every side effect (SAF
 * pickers, service start, engine calls) arrives as a lambda.
 */
@Composable
fun ImporterHubScreen(
    graph: ImporterGraph,
    /** Bumped by MainActivity whenever a grant picker returns. */
    grantRev: Int,
    onBack: () -> Unit,
    onGrantDownloads: () -> Unit,
    onGrantRoms: () -> Unit,
    onViewImport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val engine = graph.engine
    val uiState by engine.uiState.collectAsState()
    val queueItems by graph.queue.items.collectAsState()
    val history by graph.history.entries.collectAsState()
    // SAF state lives outside Compose: probe it on IO when the grant
    // changes — a resolver query on Main can block.
    var probe by remember { mutableStateOf<GrantProbe?>(null) }
    LaunchedEffect(grantRev) {
        probe = withContext(Dispatchers.IO) { engine.probeGrants() }
    }
    val downloadsOk = probe?.downloadsOk == true
    val romsOk = probe?.romsOk == true

    val waiting = queueItems.count {
        it.stage != ImportStage.COMPLETE && it.stage != ImportStage.SKIPPED
    }
    val needsReview = queueItems.count { it.needsReview }
    val failed = queueItems.count { it.stage == ImportStage.FAILED }

    ScreenScaffold(
        routeKey = "import-hub",
        title = "GAME IMPORTER",
        onBack = onBack,
        fallbackFocusKey = "scan",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            if (uiState is ImportUiState.Error) {
                val err = uiState as ImportUiState.Error
                section {
                    notice(err.message) { engine.backToIdle() }
                }
            }
            if (uiState is ImportUiState.Scanning) {
                val s = uiState as ImportUiState.Scanning
                section {
                    StatusLine("SCANNING DOWNLOADS… ${s.done}/${s.total}", Crystal.Joystick)
                }
            }
            if (uiState is ImportUiState.Classifying) {
                val s = uiState as ImportUiState.Classifying
                if (s.actionable == 0) {
                    section {
                        StatusLine("SCAN FOUND NOTHING TO IMPORT", Crystal.InkDim)
                        if (s.unsupportedCount > 0) {
                            StatusLine(
                                "${s.unsupportedCount} RAR — NOT SUPPORTED",
                                Crystal.Bad,
                            )
                        }
                        if (s.notRecognized.isNotEmpty()) {
                            StatusLine(
                                "${s.notRecognized.size} NOT RECOGNIZED AS GAMES",
                                Crystal.InkDim,
                            )
                        }
                        if (s.unreadable.isNotEmpty()) {
                            StatusLine(
                                "${s.unreadable.size} UNREADABLE ARCHIVES",
                                Crystal.Bad,
                            )
                        }
                    }
                }
            }
            section {
                StatusLine("SOURCE — DOWNLOADS")
                StatusLine(
                    if (downloadsOk) "GRANTED" else "NOT GRANTED — PICK THE FOLDER",
                    if (downloadsOk) Crystal.Good else Crystal.Bad,
                )
            }
            section {
                StatusLine("DESTINATION — ROM ROOT")
                StatusLine(
                    if (romsOk) "GRANTED" else "NOT GRANTED — PICK THE FOLDER",
                    if (romsOk) Crystal.Good else Crystal.Bad,
                )
            }
            if (probe != null && !downloadsOk) {
                control(
                    key = "grant-downloads",
                    label = "GRANT DOWNLOADS FOLDER",
                    onClick = onGrantDownloads,
                )
            }
            if (probe != null && !romsOk) {
                control(
                    key = "grant-roms",
                    label = "GRANT ROM ROOT FOLDER",
                    onClick = onGrantRoms,
                )
            }
            if (waiting > 0) {
                section {
                    StatusLine("QUEUE — $waiting WAITING")
                    if (needsReview > 0) {
                        StatusLine("$needsReview NEED${if (needsReview == 1) "S" else ""} CLASSIFICATION", Crystal.Joystick)
                    }
                    if (failed > 0) {
                        StatusLine("$failed FAILED — RETRY OR SKIP", Crystal.Bad)
                    }
                }
                control(
                    key = "resume",
                    label = "RESUME QUEUE ($waiting)",
                    subLabel = "CLASSIFICATION AND PLANS SURVIVED THE RESTART",
                    onClick = {
                        when {
                            needsReview > 0 -> engine.backToClassifying()
                            failed > 0 -> engine.reviewFailures()
                            else -> engine.prepareImport()
                        }
                    },
                )
            }
            control(
                key = "scan",
                label = "SCAN DOWNLOADS",
                subLabel = "ZIP + 7Z — RAR REPORTED, NOT SUPPORTED",
                enabled = downloadsOk && uiState !is ImportUiState.Scanning,
                onClick = { engine.scan() },
            )
            if (uiState is ImportUiState.Importing) {
                val importing = uiState as ImportUiState.Importing
                control(
                    key = "view-import",
                    label = "VIEW IMPORT — ${importing.index + 1}/${importing.total}",
                    subLabel = "THE RUN CONTINUES IN THE BACKGROUND",
                    onClick = onViewImport,
                )
            }
            if (history.isNotEmpty()) {
                section {
                    StatusLine("RECENT IMPORTS")
                    for (entry in history.take(8)) {
                        val ok = entry.outcome == ImportOutcome.SUCCESS
                        val mark = if (ok) "✓" else "✗"
                        val color = if (ok) Crystal.Good else Crystal.Bad
                        val platform = entry.platform?.labels()?.short ?: "—"
                        StatusLine("$mark $platform · ${entry.title}", color)
                    }
                }
            }
            section {
                StatusLine("ONE ARCHIVE AT A TIME. SOURCE DELETED ONLY")
                StatusLine("AFTER THE DESTINATION VERIFIES.")
                StatusLine("CANCEL MEANS “AFTER THE CURRENT GAME”.", Crystal.InkDim)
            }
            control(
                key = "settings",
                label = "IMPORTER SETTINGS",
                subLabel = "GRANTS · PLATFORM FOLDERS · DEFAULTS",
                onClick = onOpenSettings,
            )
        }
    }
}
