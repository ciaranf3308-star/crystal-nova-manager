package io.crystalnova.manager.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.crystalnova.manager.importer.AllFilesAccess
import io.crystalnova.manager.importer.GrantProbe
import io.crystalnova.manager.importer.ImportOutcome
import io.crystalnova.manager.importer.ImportStage
import io.crystalnova.manager.importer.ImportUiState
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.headline
import io.crystalnova.manager.importer.hubQueueDecision
import io.crystalnova.manager.importer.hubSummary
import io.crystalnova.manager.importer.label
import io.crystalnova.manager.importer.labels
import io.crystalnova.manager.importer.shortUnreadableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GAME IMPORTER hub — u89 command-center layout (layout only; the
 * u77 information architecture, labels, keys, and callbacks are
 * unchanged).
 *
 * Zones, top to bottom:
 *   1. STATUS — queue state as a StatusStrip + hint + 7z line.
 *   2. QUEUE COUNTS — statTiles for what is waiting.
 *   3. PROGRESS — when scanning/importing: big bar, counts, current file.
 *   4. PRIMARY ACTIONS — SCAN always; RESUME only when actionable,
 *      with an honest label (CLASSIFY / REVIEW FAILURES / IMPORT).
 *   5. ACTIONABLE QUEUE — needs-review tiles (tap to classify),
 *      failed zone (retry all / review each).
 *   6. PROBLEM ZONES — unreadable / not-recognized as collapsed
 *      counted toggles that expand on demand; each item keeps its
 *      detail panel grouped with its own action tiles.
 *   7. GRANTS — one quiet strip when all OK; tiles only on problems.
 *   8. HISTORY — collapsed. SETTINGS.
 *
 * Terminal/console style, ALL-CAPS, blue/cyan + small yellow accents,
 * no purple. D-pad first via the grid focus system; the grid collapses
 * to one column on narrow (portrait) viewports via gridColumnsForWidth.
 */
@Composable
fun ImporterHubScreen(
    graph: ImporterGraph,
    /** Bumped by MainActivity whenever a grant picker returns. */
    grantRev: Int,
    onBack: () -> Unit,
    onGrantDownloads: () -> Unit,
    onGrantRoms: () -> Unit,
    /** u54: opens the system "All files access" Settings page. */
    onOpenAllFilesSettings: () -> Unit,
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

    // u54: "All files access" is granted in system Settings, outside
    // the app — re-check on every resume, not just on grantRev bumps.
    // API 30+ only; older handhelds keep the SAF subfolder fallback.
    val allFilesApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    var settingsTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) settingsTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val allFilesGranted = remember(settingsTick) {
        allFilesApi && AllFilesAccess.hasAccess()
    }

    // ---- u77: pure status model drives the whole screen ----
    val summary = hubSummary(queueItems)
    val queueDecision = hubQueueDecision(queueItems)
    val liveItems = queueItems.filter {
        it.stage != ImportStage.COMPLETE &&
            it.stage != ImportStage.SKIPPED &&
            !it.isClaimed
    }
    val reviewItems = liveItems.filter { it.needsReview }
    val failedItems = liveItems.filter { it.stage == ImportStage.FAILED }
    val notRecognizedItems = liveItems.filter {
        !it.inspectFailed && !it.sourceMissing && !it.detection.recognizedAsGame
    }
    val missingCount = liveItems.count { it.sourceMissing }

    // Collapsed problem lists — remember at screen level (the grid
    // content lambda is not a @Composable context).
    var unreadableExpanded by remember { mutableStateOf(false) }
    var notRecognizedExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }

    val attention = summary.failed > 0 || summary.unreadable > 0
    val hint = when {
        uiState is ImportUiState.Scanning -> "WORKING THROUGH YOUR DOWNLOADS…"
        uiState is ImportUiState.Importing -> "IMPORT RUNNING — SEE BELOW"
        summary.waiting == 0 -> "SCAN DOWNLOADS TO ADD GAMES"
        summary.toReview > 0 -> "TAP A GAME TO CLASSIFY IT"
        summary.failed > 0 -> "RETRY THEM ALL OR REVIEW EACH ONE"
        summary.unreadable > 0 && !queueDecision.canResume ->
            "NOTHING HERE CAN BE IMPORTED — CLEAR THEM OUT"
        summary.importable > 0 -> "READY — START THE IMPORT"
        summary.notRecognized > 0 -> "SCAN RETRIES THESE — OR DROP THEM"
        summary.missing > 0 -> "THOSE FILES ARE GONE — DISMISS THEM"
        else -> ""
    }
    // Honest resume label: name the actual next step.
    val resumeLabel: String? = when {
        !queueDecision.canResume -> null
        summary.toReview > 0 -> "CLASSIFY GAMES (${summary.toReview})"
        summary.failed > 0 -> "REVIEW FAILURES (${summary.failed})"
        else -> "IMPORT (${summary.importable})"
    }

    val fallbackFocusKey: Any? =
        if (uiState is ImportUiState.Error) "dismiss-notice" else "scan"

    ScreenScaffold(
        routeKey = "import-hub",
        title = "GAME IMPORTER",
        onBack = onBack,
        fallbackFocusKey = fallbackFocusKey,
    ) {
        ControllerGrid(
            state = gridState,
            dispatcher = dispatcher,
            columns = GridCells.Fixed(gridColumnsForWidth(LocalContentWidth.current.value.toInt(), 2)),
            initialFocus = ::isInitialFocus,
        ) {
            // ---- 0. error notice ----
            if (uiState is ImportUiState.Error) {
                val err = uiState as ImportUiState.Error
                panel {
                    StatusStrip(err.message, attention = true)
                }
                commandTile(
                    key = "dismiss-notice",
                    testTag = "hub-dismiss-notice",
                    label = "DISMISS",
                    onClick = { engine.backToIdle() },
                )
            }
            // ---- 1. status: what needs Ciaran, at a glance ----
            panel {
                ZoneHeader("STATUS")
                StatusStrip(summary.headline(), attention = attention)
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hint.isNotEmpty()) {
                            DimLine(hint)
                        }
                        // u78: 7zz backend state — one photo diagnoses it.
                        DimLine(graph.sevenZStatus)
                    }
                }
            }
            // ---- 1b. queue counts as tiles ----
            statTile(label = "WAITING", value = "${summary.waiting}")
            statTile(label = "TO REVIEW", value = "${summary.toReview}")
            statTile(label = "READY", value = "${summary.importable}")
            statTile(label = "FAILED", value = "${summary.failed}")
            statTile(label = "UNREADABLE", value = "${summary.unreadable}")
            statTile(label = "NOT RECOGNIZED", value = "${summary.notRecognized}")
            statTile(label = "MISSING", value = "${summary.missing}")
            // ---- 2. progress: unmissable, in the viewport ----
            if (uiState is ImportUiState.Scanning) {
                val s = uiState as ImportUiState.Scanning
                panel {
                    ZoneHeader("SCANNING DOWNLOADS")
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CrystalProgressBar(
                                if (s.total > 0) s.done.toFloat() / s.total else 0f,
                            )
                            StatusLine(
                                "${s.done} / ${s.total} FILES",
                                Crystal.InkDim,
                            )
                        }
                    }
                }
            }
            if (uiState is ImportUiState.Importing) {
                val imp = uiState as ImportUiState.Importing
                panel {
                    ZoneHeader(
                        "IMPORTING — GAME ${(imp.index + 1).coerceAtMost(imp.total)} OF ${imp.total}",
                    )
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            imp.current?.let { current ->
                                StatusLine(current.title)
                                current.progress?.let { p ->
                                    CrystalProgressBar(p)
                                    StatusLine(
                                        "${(p * 100).toInt()}% · ${current.stage.label()}",
                                        Crystal.InkDim,
                                    )
                                } ?: StatusLine(
                                    current.stage.label(),
                                    Crystal.InkDim,
                                )
                            }
                        }
                    }
                }
                commandTile(
                    key = "view-import",
                    testTag = "hub-view-import",
                    label = "VIEW IMPORT",
                    subLabel = "THE RUN CONTINUES IN THE BACKGROUND",
                    onClick = onViewImport,
                )
            }
            // ---- 3. primary actions ----
            panel {
                ZoneHeader("ACTIONS")
            }
            commandTile(
                key = "scan",
                testTag = "hub-scan",
                label = "SCAN DOWNLOADS",
                subLabel = "ZIP + 7Z + LOOSE GAME FILES — RAR REPORTED, NOT SUPPORTED",
                enabled = downloadsOk && uiState !is ImportUiState.Scanning,
                onClick = { engine.scan() },
            )
            if (resumeLabel != null) {
                commandTile(
                    key = "resume",
                    testTag = "hub-resume",
                    label = resumeLabel,
                    subLabel = when {
                        summary.toReview > 0 -> "PICK A PLATFORM FOR EACH GAME"
                        summary.failed > 0 -> "DECIDE WHAT HAPPENS TO EACH FAILURE"
                        else -> "REVIEW + START THE IMPORT RUN"
                    },
                    onClick = {
                        when {
                            summary.toReview > 0 -> engine.backToClassifying()
                            summary.failed > 0 -> engine.reviewFailures()
                            else -> engine.prepareImport()
                        }
                    },
                )
            }
            // ---- 4a. needs review: tap a tile to classify it ----
            if (reviewItems.isNotEmpty()) {
                panel {
                    ZoneHeader("NEEDS REVIEW (${reviewItems.size}) — TAP TO CLASSIFY")
                }
                for (item in reviewItems) {
                    val guess = item.detection.platform?.labels()?.short
                    commandTile(
                        key = "classify-${item.id}",
                        label = "▸ ${item.displayTitle}",
                        subLabel = if (guess != null) "DETECTOR GUESS: $guess — TAP TO CONFIRM"
                        else "TAP TO PICK THE PLATFORM",
                        onClick = {
                            engine.reclassifyItem(item.id)
                            engine.backToClassifying()
                        },
                    )
                }
            }
            // ---- 4b. failed: retry all inline, or review each ----
            if (failedItems.isNotEmpty()) {
                panel {
                    ZoneHeader("FAILED (${failedItems.size})")
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (item in failedItems) {
                                DimLine("✗ ${item.displayTitle}")
                            }
                        }
                    }
                }
                commandTile(
                    key = "retry-all-failed",
                    label = "RETRY ALL FAILED (${failedItems.size})",
                    subLabel = "RESETS THEM AND STARTS THE IMPORT",
                    onClick = { engine.retryFailed() },
                )
                commandTile(
                    key = "review-failures",
                    label = "REVIEW EACH FAILURE",
                    subLabel = "RETRY · CHANGE PLATFORM · SKIP PER GAME",
                    onClick = { engine.reviewFailures() },
                )
            }
            // ---- 5a. unreadable: collapsed, expandable, removable ----
            if (queueDecision.unreadable.isNotEmpty()) {
                panel {
                    ZoneHeader("PROBLEM FILES")
                }
                commandTile(
                    key = "toggle-unreadable",
                    label = "UNREADABLE (${queueDecision.unreadable.size}) " +
                        if (unreadableExpanded) "▾" else "▸",
                    subLabel = "THE SCAN COULDN'T READ THESE FILES",
                    onClick = { unreadableExpanded = !unreadableExpanded },
                )
                if (unreadableExpanded) {
                    for (item in queueDecision.unreadable) {
                        panel {
                            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    StatusLine("✗ ${item.archiveName}", Crystal.Bad)
                                    DimLine(shortUnreadableReason(item.failureDetail))
                                }
                            }
                        }
                        commandTile(
                            key = "remove-unreadable-${item.id}",
                            testTag = "hub-remove-unreadable",
                            label = "REMOVE THIS FILE",
                            subLabel = "DROPS IT FROM THE QUEUE — NOTHING IS DELETED",
                            danger = true,
                            onClick = { engine.removeItem(item.id) },
                        )
                    }
                }
            }
            // ---- 5b. not recognized: collapsed, expandable, actionable ----
            if (notRecognizedItems.isNotEmpty()) {
                commandTile(
                    key = "toggle-notrecognized",
                    label = "NOT RECOGNIZED (${notRecognizedItems.size}) " +
                        if (notRecognizedExpanded) "▾" else "▸",
                    subLabel = "SCAN RETRIES THESE — OR DROP THEM",
                    onClick = { notRecognizedExpanded = !notRecognizedExpanded },
                )
                if (notRecognizedExpanded) {
                    for (item in notRecognizedItems) {
                        panel {
                            CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    StatusLine("· ${item.archiveName}", Crystal.Ink)
                                    DimLine("THE SCAN COULDN'T IDENTIFY THIS FILE")
                                    // u78: what the detector actually saw.
                                    item.scanDiagnostic?.let { DimLine(it) }
                                }
                            }
                        }
                        commandTile(
                            key = "notgame-${item.id}",
                            label = "NOT A GAME",
                            subLabel = "DROPS IT — NOTHING IS DELETED",
                            onClick = { engine.ignoreNotAGame(item.id) },
                        )
                        commandTile(
                            key = "remove-notrecognized-${item.id}",
                            label = "REMOVE",
                            subLabel = "DROPS IT FROM THE QUEUE — NOTHING IS DELETED",
                            danger = true,
                            onClick = { engine.removeItem(item.id) },
                        )
                    }
                }
            }
            // ---- 5c. missing sources ----
            if (missingCount > 0) {
                commandTile(
                    key = "dismiss-missing",
                    testTag = "hub-dismiss-missing",
                    label = "DISMISS $missingCount MISSING",
                    subLabel = "DROPS THEM FROM THE QUEUE — NOTHING IS DELETED",
                    onClick = { engine.dismissMissingItems() },
                    danger = true,
                )
            }
            // ---- 6. grants: one quiet strip when all OK ----
            val grantsOk = downloadsOk && romsOk && (!allFilesApi || allFilesGranted)
            if (grantsOk) {
                panel {
                    StatusStrip("GRANTS OK — DOWNLOADS + ROMS READY")
                }
            } else {
                panel {
                    ZoneHeader("GRANTS NEED ATTENTION")
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!downloadsOk && !allFilesGranted) {
                                DimLine("DOWNLOADS: NOT GRANTED")
                            }
                            if (allFilesApi && !allFilesGranted) {
                                DimLine("ALL FILES ACCESS: NOT GRANTED")
                            }
                            if (!romsOk) {
                                DimLine("ROM ROOT: NOT GRANTED")
                            }
                        }
                    }
                }
                if (probe != null && !downloadsOk && !allFilesGranted) {
                    commandTile(
                        key = "grant-downloads",
                        testTag = "hub-grant-downloads",
                        label = "GRANT DOWNLOADS FOLDER",
                        subLabel = "SAF FALLBACK — PICK A SUBFOLDER INSIDE DOWNLOADS",
                        onClick = onGrantDownloads,
                    )
                }
                if (allFilesApi && !allFilesGranted) {
                    commandTile(
                        key = "all-files-settings",
                        testTag = "hub-all-files-settings",
                        label = "OPEN SETTINGS",
                        subLabel = "ALLOW ALL FILES ACCESS FOR DIRECT SCAN",
                        onClick = onOpenAllFilesSettings,
                    )
                }
                if (probe != null && !romsOk) {
                    commandTile(
                        key = "grant-roms",
                        testTag = "hub-grant-roms",
                        label = "GRANT ROM ROOT FOLDER",
                        onClick = onGrantRoms,
                    )
                }
            }
            // ---- 7. history: collapsed ----
            if (history.isNotEmpty()) {
                commandTile(
                    key = "toggle-history",
                    label = "RECENT IMPORTS (${history.size}) " +
                        if (historyExpanded) "▾" else "▸",
                    onClick = { historyExpanded = !historyExpanded },
                )
                if (historyExpanded) {
                    panel {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (entry in history.take(8)) {
                                    val ok = entry.outcome == ImportOutcome.SUCCESS
                                    val mark = if (ok) "✓" else "✗"
                                    val color = if (ok) Crystal.Good else Crystal.Bad
                                    val platform =
                                        entry.platform?.labels()?.short ?: "—"
                                    StatusLine("$mark $platform · ${entry.title}", color)
                                }
                            }
                        }
                    }
                }
            }
            commandTile(
                key = "settings",
                testTag = "hub-settings",
                label = "IMPORTER SETTINGS",
                subLabel = "GRANTS · PLATFORM FOLDERS · DEFAULTS",
                onClick = onOpenSettings,
            )
        }
    }
}
