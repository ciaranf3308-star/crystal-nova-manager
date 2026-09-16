package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.updater.ManagerEvent
import io.crystalnova.manager.updater.ManagerState
import io.crystalnova.manager.updater.Stage
import io.crystalnova.manager.updater.VersionDisplay

/**
 * THEME: the Pegasus theme updater, and nothing else. Same updater
 * behavior and transaction logic as before — only the presentation is
 * reorganized.
 *
 * The MANAGER APP self-updater is NOT here: it lives on HOME (banner)
 * and at the top of SETTINGS. The themes-folder config and the
 * DIAGNOSTICS entry moved to SETTINGS as well.
 *
 * Controller B pops one level via the activity's navigator; it never
 * dismisses an in-flight updater transaction (the UpdateManager owns
 * the transaction and survives screen changes).
 *
 * Pure function of [state]: no Android APIs, so it renders on the JVM
 * for screenshot tests.
 */
@Composable
fun ThemeScreen(
    state: ManagerState,
    onEvent: (ManagerEvent) -> Unit,
    pegasusLaunchable: Boolean,
    onPickFolder: () -> Unit,
    /** Pops one navigation level (B). */
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Initial focus mirrors the old per-state requestInitialFocus logic:
    // the primary action for the current updater state, so D-pad starts
    // on the control the user most likely wants.
    val fallbackFocusKey = when {
        state is ManagerState.Ready && state.updateAvailable -> "theme-update"
        state is ManagerState.Ready && state.notice != null -> "theme-retry"
        state is ManagerState.Ready && state.backup != null -> "theme-rollback"
        state is ManagerState.NeedsFolder -> "theme-pick"
        state is ManagerState.UpdateFailed -> "theme-back"
        state is ManagerState.UpdateDone ->
            if (pegasusLaunchable) "theme-open-pegasus" else "theme-done-back"
        state is ManagerState.RollbackDone -> "theme-rb-back"
        state is ManagerState.RollbackFailed -> "theme-rbf-back"
        else -> "theme-change-folder"
    }
    ScreenScaffold(
        routeKey = "theme",
        title = "THEME",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = fallbackFocusKey,
    ) {
        // This screen's single scroll container: D-pad focus on any
        // control scrolls it to a comfortable viewport position, so
        // below-the-fold actions (e.g. CHANGE THEMES FOLDER) are reachable.
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            when (val s = state) {
                is ManagerState.NeedsFolder -> {
                    section { NeedsFolderPanel(s.message) }
                    control(
                        key = "theme-pick",
                        testTag = "theme-pick",
                        label = "SELECT PEGASUS THEMES FOLDER",
                        onClick = onPickFolder,
                    )
                }
                is ManagerState.Ready -> {
                    section { ReadyPanel(s) }
                    if (s.updateAvailable && !s.checking) {
                        control(
                            key = "theme-update",
                            testTag = "theme-update",
                            label = "UPDATE THEME",
                            onClick = { onEvent(ManagerEvent.StartUpdate) },
                        )
                    }
                    if (s.backup != null) {
                        control(
                            key = "theme-rollback",
                            testTag = "theme-rollback",
                            label = if (s.backup.isLegacy) "ROLLBACK TO PREVIOUS"
                            else "ROLLBACK TO v${s.backup.version}",
                            onClick = { onEvent(ManagerEvent.StartRollback) },
                            danger = true,
                        )
                    }
                    if (s.notice != null) {
                        control(
                            key = "theme-retry",
                            testTag = "theme-retry",
                            label = "RETRY",
                            onClick = { onEvent(ManagerEvent.CheckNow) },
                        )
                    }
                    if (pegasusLaunchable) {
                        control(
                            key = "theme-open-pegasus",
                            testTag = "theme-open-pegasus",
                            label = "OPEN PEGASUS",
                            onClick = { onEvent(ManagerEvent.OpenPegasus) },
                        )
                    }
                }
                is ManagerState.Updating -> section { UpdatingPanel(s) }
                is ManagerState.UpdateFailed -> {
                    section { FailedPanel(s) }
                    control(
                        key = "theme-back",
                        testTag = "theme-back",
                        label = "BACK",
                        onClick = { onEvent(ManagerEvent.Dismiss) },
                    )
                }
                is ManagerState.UpdateDone -> {
                    section { DonePanel(s) }
                    if (pegasusLaunchable) {
                        control(
                            key = "theme-open-pegasus",
                            testTag = "theme-open-pegasus",
                            label = "OPEN PEGASUS",
                            onClick = { onEvent(ManagerEvent.OpenPegasus) },
                        )
                    }
                    control(
                        key = "theme-done-back",
                        testTag = "theme-done-back",
                        label = "BACK",
                        onClick = { onEvent(ManagerEvent.Dismiss) },
                    )
                }
                is ManagerState.RollingBack -> section { RollingBackPanel(s) }
                is ManagerState.RollbackDone -> {
                    section { RollbackDonePanel(s) }
                    control(
                        key = "theme-rb-back",
                        testTag = "theme-rb-back",
                        label = "BACK",
                        onClick = { onEvent(ManagerEvent.Dismiss) },
                    )
                }
                is ManagerState.RollbackFailed -> {
                    section { RollbackFailedPanel(s) }
                    control(
                        key = "theme-rbf-back",
                        testTag = "theme-rbf-back",
                        label = "BACK",
                        onClick = { onEvent(ManagerEvent.Dismiss) },
                    )
                }
            }
            // Theme storage root stays here — it belongs to the theme
            // updater. Everything manager-app related moved to HOME /
            // SETTINGS.
            section {
                val s = this
                s.control(
                    key = "theme-change-folder",
                    testTag = "theme-change-folder",
                    label = "CHANGE THEMES FOLDER",
                    onClick = onPickFolder,
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Primary block: theme transaction rendering (logic unchanged).
// ------------------------------------------------------------------

@Composable
private fun VersionColumns(installed: VersionDisplay?, latest: VersionDisplay?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SectionLabel("INSTALLED")
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = when {
                    installed == null -> "—"
                    installed.isLegacy -> "PRE-2.0"
                    else -> "v${installed.version}"
                },
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontWeight = FontWeight.Bold,
                    fontSize = Crystal.BodySize, color = Crystal.Ink,
                ),
            )
            BasicText(
                text = when {
                    installed == null -> "—"
                    installed.isLegacy -> "NO VERSION MARKER"
                    else -> installed.shortCommit
                },
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontSize = Crystal.SmallSize,
                    color = Crystal.InkDim,
                ),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            SectionLabel("LATEST")
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = latest?.let { "v${it.version}" } ?: "—",
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontWeight = FontWeight.Bold,
                    fontSize = Crystal.BodySize, color = Crystal.Ink,
                ),
            )
            BasicText(
                text = latest?.shortCommit ?: "—",
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontSize = Crystal.SmallSize,
                    color = Crystal.InkDim,
                ),
            )
        }
    }
}

@Composable
private fun ReadyPanel(state: ManagerState.Ready) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            VersionColumns(state.installed, state.latest)
            Spacer(Modifier.height(4.dp))
            when {
                state.checking -> StatusLine("CHECKING…", Crystal.Divider)
                state.notice != null -> StatusLine(state.notice, Crystal.Bad)
                state.updateAvailable -> StatusLine("UPDATE AVAILABLE", Crystal.Cream)
                else -> StatusLine("✓ CRYSTAL IS UP TO DATE", Crystal.Good)
            }
            if (state.updateAvailable && state.destination != null) {
                BasicText(
                    text = "INSTALLS TO: ${state.destination}",
                    style = TextStyle(
                        fontFamily = Crystal.Mono, fontSize = Crystal.SmallSize,
                        color = Crystal.InkDim,
                    ),
                )
            }
            state.backup?.let {
                BasicText(
                    text = if (it.isLegacy) "BACKUP READY — PREVIOUS INSTALL"
                    else "BACKUP v${it.version} READY",
                    style = TextStyle(
                        fontFamily = Crystal.Mono, fontSize = Crystal.SmallSize,
                        color = Crystal.InkDim,
                    ),
                )
            }
        }
    }
}

@Composable
private fun NeedsFolderPanel(message: String?) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(
                text = "SELECT PEGASUS THEMES FOLDER",
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontWeight = FontWeight.Bold,
                    fontSize = Crystal.BodySize, color = Crystal.Ink,
                ),
            )
            BasicText(
                text = "CHOOSE THE THEMES FOLDER THAT CONTAINS YOUR THEMES " +
                    "(NOT THE THEME FOLDER ITSELF).",
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontSize = Crystal.SmallSize,
                    color = Crystal.InkDim,
                ),
            )
            if (message != null) {
                BasicText(
                    text = message,
                    style = TextStyle(
                        fontFamily = Crystal.Mono, fontWeight = FontWeight.Bold,
                        fontSize = Crystal.SmallSize, color = Crystal.Bad,
                    ),
                )
            }
        }
    }
}

@Composable
private fun UpdatingPanel(state: ManagerState.Updating) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Stage.entries.forEach { stage ->
                val active = stage == state.stage
                val done = stage.ordinal < state.stage.ordinal
                val label = when (stage) {
                    Stage.DOWNLOADING -> if (state.progress != null && active) {
                        "DOWNLOADING ${ (state.progress * 100).toInt() }%"
                    } else "DOWNLOADING"
                    else -> stage.name
                }
                BasicText(
                    text = (if (done) "✓ " else if (active) "▶ " else "  ") + label,
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = Crystal.BodySize,
                        color = when {
                            active -> Crystal.Cream
                            done -> Crystal.Good
                            else -> Crystal.FrameDim
                        },
                    ),
                )
            }
        }
    }
    BasicText(
        text = "DO NOT CLOSE THE APP",
        style = TextStyle(
            fontFamily = Crystal.Mono, fontSize = Crystal.SmallSize,
            color = Crystal.InkDim, textAlign = TextAlign.Center,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FailedPanel(state: ManagerState.UpdateFailed) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLine(state.message, Crystal.Bad)
            if (state.restored) {
                BasicText(
                    text = "The previous working theme is still in place.",
                    style = TextStyle(
                        fontFamily = Crystal.Mono, fontSize = Crystal.SmallSize,
                        color = Crystal.InkDim,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DonePanel(state: ManagerState.UpdateDone) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLine("✓ UPDATE INSTALLED — v${state.version.version}", Crystal.Good)
            BasicText(
                text = "RESTART PEGASUS TO APPLY",
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontWeight = FontWeight.Bold,
                    fontSize = Crystal.BodySize, color = Crystal.Cream,
                ),
            )
        }
    }
}

@Composable
private fun RollingBackPanel(state: ManagerState.RollingBack) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        StatusLine("ROLLING BACK — ${state.stage.name}", Crystal.Cream)
    }
}

@Composable
private fun RollbackDonePanel(state: ManagerState.RollbackDone) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLine(
                "✓ ROLLBACK COMPLETE" +
                    (state.version?.let { " — v${it.version}" } ?: ""),
                Crystal.Good,
            )
            BasicText(
                text = "RESTART PEGASUS TO APPLY",
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontWeight = FontWeight.Bold,
                    fontSize = Crystal.BodySize, color = Crystal.Cream,
                ),
            )
        }
    }
}

@Composable
private fun RollbackFailedPanel(state: ManagerState.RollbackFailed) {
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        StatusLine(state.message, Crystal.Bad)
    }
}
