package io.crystalnova.manager.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.crystalnova.manager.importer.ImportUiState
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.ImportStage
import io.crystalnova.manager.importer.labels
import io.crystalnova.manager.importer.shortUnreadableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GAME IMPORTER hub: grant status, queue status, scan/resume, recent
 * history, settings entry. The engine's [ImportUiState] drives the
 * status lines; navigation side effects live in MainActivity.
 *
 * u70: single-column [ControllerList], not a grid. The old 2-column
 * grid squeezed the long-labeled action buttons (DISMISS n MISSING,
 * RESUME QUEUE, SCAN DOWNLOADS) into half-width cells and the Nova
 * rendered them clipped/overlapping with cut-off text (photo-proven).
 * Every action is now full-width with breathing room; status blocks
 * get CrystalPanel chrome so the screen reads top-down: what am I
 * looking at → what's the primary action.
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

    val waiting = queueItems.count {
        it.stage != ImportStage.COMPLETE && it.stage != ImportStage.SKIPPED && !it.isClaimed
    }
    val needsReview = queueItems.count { it.needsReview }
    val failed = queueItems.count { it.stage == ImportStage.FAILED }
    val missing = queueItems.count {
        it.sourceMissing && it.stage != ImportStage.COMPLETE && it.stage != ImportStage.SKIPPED
    }

    // u70: an error notice owns initial focus so the feedback is in
    // the viewport immediately — otherwise the primary scan action.
    val fallbackFocusKey: Any? =
        if (uiState is ImportUiState.Error) "dismiss-notice" else "scan"

    ScreenScaffold(
        routeKey = "import-hub",
        title = "GAME IMPORTER",
        onBack = onBack,
        fallbackFocusKey = fallbackFocusKey,
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            if (uiState is ImportUiState.Error) {
                val err = uiState as ImportUiState.Error
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        StatusLine(err.message, Crystal.Bad)
                    }
                }
                control(
                    key = "dismiss-notice",
                    testTag = "hub-dismiss-notice",
                    label = "DISMISS",
                    onClick = { engine.backToIdle() },
                )
            }
            if (uiState is ImportUiState.Scanning) {
                val s = uiState as ImportUiState.Scanning
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        StatusLine("SCANNING DOWNLOADS… ${s.done}/${s.total}", Crystal.Joystick)
                    }
                }
            }
            if (uiState is ImportUiState.Classifying) {
                val s = uiState as ImportUiState.Classifying
                if (s.actionable == 0) {
                    section {
                        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        "${s.unreadable.size} UNREADABLE FILES",
                                        Crystal.Bad,
                                    )
                                    // u72: name the files and why — a bare
                                    // count left the user guessing which of
                                    // their downloads was at fault. Filename
                                    // stays intact and wraps; the reason is
                                    // the terse human form (never the raw
                                    // engine dump). Rows are plain text, so
                                    // D-pad focus order is unchanged.
                                    for (item in s.unreadable) {
                                        StatusLine("✗ ${item.archiveName}", Crystal.Bad)
                                        DimLine(shortUnreadableReason(item.failureDetail))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // ---- grants: one panel, three groups ----
            section {
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusLine("SOURCE — DOWNLOADS")
                        val (downloadsText, downloadsColor) = when {
                            downloadsOk && allFilesGranted -> "GRANTED — DIRECT SCAN" to Crystal.Good
                            downloadsOk -> "GRANTED" to Crystal.Good
                            else -> "NOT GRANTED — PICK THE FOLDER" to Crystal.Bad
                        }
                        StatusLine(downloadsText, downloadsColor)
                        if (allFilesApi) {
                            CrystalDivider()
                            StatusLine("ALL FILES ACCESS")
                            StatusLine(
                                if (allFilesGranted) "GRANTED" else "NOT GRANTED",
                                if (allFilesGranted) Crystal.Good else Crystal.Bad,
                            )
                        }
                        CrystalDivider()
                        StatusLine("DESTINATION — ROM ROOT")
                        StatusLine(
                            if (romsOk) "GRANTED" else "NOT GRANTED — PICK THE FOLDER",
                            if (romsOk) Crystal.Good else Crystal.Bad,
                        )
                    }
                }
            }
            // SAF subfolder grant is the fallback: only offered when the
            // direct all-files scan is not granted.
            if (probe != null && !downloadsOk && !allFilesGranted) {
                control(
                    key = "grant-downloads",
                    testTag = "hub-grant-downloads",
                    label = "GRANT DOWNLOADS FOLDER",
                    subLabel = "SAF FALLBACK — PICK A SUBFOLDER INSIDE DOWNLOADS",
                    onClick = onGrantDownloads,
                )
            }
            if (allFilesApi && !allFilesGranted) {
                control(
                    key = "all-files-settings",
                    testTag = "hub-all-files-settings",
                    label = "OPEN SETTINGS",
                    subLabel = "ALLOW ALL FILES ACCESS FOR DIRECT SCAN",
                    onClick = onOpenAllFilesSettings,
                )
            }
            if (probe != null && !romsOk) {
                control(
                    key = "grant-roms",
                    testTag = "hub-grant-roms",
                    label = "GRANT ROM ROOT FOLDER",
                    onClick = onGrantRoms,
                )
            }
            // ---- primary action: full-width, nothing squeezed ----
            control(
                key = "scan",
                testTag = "hub-scan",
                label = "SCAN DOWNLOADS",
                subLabel = "ZIP + 7Z + LOOSE GAME FILES — RAR REPORTED, NOT SUPPORTED",
                enabled = downloadsOk && uiState !is ImportUiState.Scanning,
                onClick = { engine.scan() },
            )
            // ---- queue ----
            if (waiting > 0) {
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusLine("QUEUE — $waiting WAITING")
                            if (needsReview > 0) {
                                StatusLine(
                                    "$needsReview NEED${if (needsReview == 1) "S" else ""} CLASSIFICATION",
                                    Crystal.Joystick,
                                )
                            }
                            if (failed > 0) {
                                StatusLine("$failed FAILED — RETRY OR SKIP", Crystal.Bad)
                            }
                            if (missing > 0) {
                                StatusLine(
                                    "$missing SOURCE MISSING — FILE IS GONE",
                                    Crystal.Bad,
                                )
                            }
                        }
                    }
                }
                control(
                    key = "resume",
                    testTag = "hub-resume",
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
                if (missing > 0) {
                    control(
                        key = "dismiss-missing",
                        testTag = "hub-dismiss-missing",
                        label = "DISMISS $missing MISSING",
                        subLabel = "DROPS THEM FROM THE QUEUE — NOTHING IS DELETED",
                        onClick = { engine.dismissMissingItems() },
                        danger = true,
                    )
                }
            }
            if (uiState is ImportUiState.Importing) {
                val importing = uiState as ImportUiState.Importing
                control(
                    key = "view-import",
                    testTag = "hub-view-import",
                    label = "VIEW IMPORT — ${importing.index + 1}/${importing.total}",
                    subLabel = "THE RUN CONTINUES IN THE BACKGROUND",
                    onClick = onViewImport,
                )
            }
            if (history.isNotEmpty()) {
                section {
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }
            section {
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusLine("ONE FILE AT A TIME. SOURCE DELETED ONLY")
                        StatusLine("AFTER THE DESTINATION VERIFIES.")
                        StatusLine("CANCEL MEANS “AFTER THE CURRENT GAME”.", Crystal.InkDim)
                    }
                }
            }
            control(
                key = "settings",
                testTag = "hub-settings",
                label = "IMPORTER SETTINGS",
                subLabel = "GRANTS · PLATFORM FOLDERS · DEFAULTS",
                onClick = onOpenSettings,
            )
        }
    }
}
