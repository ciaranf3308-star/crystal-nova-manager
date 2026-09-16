package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.updater.ManagerEvent
import io.crystalnova.manager.updater.ManagerState
import io.crystalnova.manager.updater.Stage
import io.crystalnova.manager.updater.VersionDisplay

/**
 * Phase U1 implements the THEME section only. The home screen is
 * architected for THEME / SCRAPER / SETTINGS later — this composable
 * is the THEME section.
 *
 * Pure function of [state]: no Android APIs, so it renders on the JVM
 * for screenshot tests. Controller input (D-pad/A/B) is handled via
 * [FocusDispatcher] + [onPreviewKeyEvent]; touch via clickable.
 */
@Composable
fun ThemeUpdateScreen(
    state: ManagerState,
    onEvent: (ManagerEvent) -> Unit,
    pegasusLaunchable: Boolean,
    onPickFolder: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dispatcher = remember { FocusDispatcher() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Crystal.Background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    // Gamepad A / D-pad center / Enter confirms the focused action.
                    Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        dispatcher.activateFocused()
                        true
                    }
                    // Gamepad B goes back.
                    Key.ButtonB -> {
                        onBack(state, onEvent, onExit)
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            CrystalDivider()
            when (state) {
                is ManagerState.NeedsFolder -> NeedsFolderBody(dispatcher, onPickFolder)
                is ManagerState.Ready -> ReadyBody(state, dispatcher, onEvent, pegasusLaunchable)
                is ManagerState.Updating -> UpdatingBody(state)
                is ManagerState.UpdateFailed -> FailedBody(state, dispatcher, onEvent)
                is ManagerState.UpdateDone -> DoneBody(state, dispatcher, onEvent, pegasusLaunchable)
                is ManagerState.RollingBack -> RollingBackBody(state)
                is ManagerState.RollbackDone -> RollbackDoneBody(state, dispatcher, onEvent)
                is ManagerState.RollbackFailed -> RollbackFailedBody(state, dispatcher, onEvent)
            }
            Spacer(Modifier.weight(1f))
            CrystalDivider()
            Keycap(key = "B", label = "BACK / EXIT")
        }
    }
}

private fun onBack(
    state: ManagerState,
    onEvent: (ManagerEvent) -> Unit,
    onExit: () -> Unit,
) {
    when (state) {
        is ManagerState.Ready -> onExit()
        is ManagerState.NeedsFolder -> onExit()
        else -> onEvent(ManagerEvent.Dismiss)
    }
}

@Composable
private fun Header() {
    Column {
        BasicText(
            text = "CRYSTAL NOVA",
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = Crystal.TitleSize,
                color = Crystal.Ink,
            ),
        )
        BasicText(
            text = "MANAGER",
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.TitleSize,
                color = Crystal.Divider,
            ),
        )
    }
}

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
private fun ReadyBody(
    state: ManagerState.Ready,
    dispatcher: FocusDispatcher,
    onEvent: (ManagerEvent) -> Unit,
    pegasusLaunchable: Boolean,
) {
    SectionLabel("THEME")
    Spacer(Modifier.height(4.dp))
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
    if (state.updateAvailable && !state.checking) {
        CrystalButton(
            key = "update",
            label = "UPDATE CRYSTAL",
            onClick = { onEvent(ManagerEvent.StartUpdate) },
            dispatcher = dispatcher,
            requestInitialFocus = true,
        )
    }
    if (state.backup != null) {
        CrystalButton(
            key = "rollback",
            label = if (state.backup.isLegacy) "ROLLBACK TO PREVIOUS"
            else "ROLLBACK TO v${state.backup.version}",
            onClick = { onEvent(ManagerEvent.StartRollback) },
            dispatcher = dispatcher,
            danger = true,
            requestInitialFocus = !state.updateAvailable && !state.checking,
        )
    }
    if (state.notice != null) {
        CrystalButton(
            key = "retry",
            label = "RETRY",
            onClick = { onEvent(ManagerEvent.CheckNow) },
            dispatcher = dispatcher,
            requestInitialFocus = !state.updateAvailable,
        )
    }
    if (pegasusLaunchable) {
        CrystalButton(
            key = "pegasus",
            label = "OPEN PEGASUS",
            onClick = { onEvent(ManagerEvent.OpenPegasus) },
            dispatcher = dispatcher,
        )
    }
}

@Composable
private fun NeedsFolderBody(dispatcher: FocusDispatcher, onPick: () -> Unit) {
    SectionLabel("THEME")
    Spacer(Modifier.height(4.dp))
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(
                text = "CHOOSE THE PEGASUS THEMES FOLDER",
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontWeight = FontWeight.Bold,
                    fontSize = Crystal.BodySize, color = Crystal.Ink,
                ),
            )
            BasicText(
                text = "Select pegasus-frontend/themes/ once. " +
                    "Crystal Nova Manager will remember it and never " +
                    "ask again.",
                style = TextStyle(
                    fontFamily = Crystal.Mono, fontSize = Crystal.SmallSize,
                    color = Crystal.InkDim,
                ),
            )
        }
    }
    CrystalButton(
        key = "pick",
        label = "CHOOSE CRYSTAL THEME FOLDER",
        onClick = onPick,
        dispatcher = dispatcher,
        requestInitialFocus = true,
    )
}

@Composable
private fun UpdatingBody(state: ManagerState.Updating) {
    SectionLabel("THEME")
    Spacer(Modifier.height(4.dp))
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
private fun FailedBody(
    state: ManagerState.UpdateFailed,
    dispatcher: FocusDispatcher,
    onEvent: (ManagerEvent) -> Unit,
) {
    SectionLabel("THEME")
    Spacer(Modifier.height(4.dp))
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
    CrystalButton(
        key = "back",
        label = "BACK",
        onClick = { onEvent(ManagerEvent.Dismiss) },
        dispatcher = dispatcher,
        requestInitialFocus = true,
    )
}

@Composable
private fun DoneBody(
    state: ManagerState.UpdateDone,
    dispatcher: FocusDispatcher,
    onEvent: (ManagerEvent) -> Unit,
    pegasusLaunchable: Boolean,
) {
    SectionLabel("THEME")
    Spacer(Modifier.height(4.dp))
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
    if (pegasusLaunchable) {
        CrystalButton(
            key = "open-pegasus",
            label = "OPEN PEGASUS",
            onClick = { onEvent(ManagerEvent.OpenPegasus) },
            dispatcher = dispatcher,
            requestInitialFocus = true,
        )
    }
    CrystalButton(
        key = "done-back",
        label = "BACK",
        onClick = { onEvent(ManagerEvent.Dismiss) },
        dispatcher = dispatcher,
        requestInitialFocus = !pegasusLaunchable,
    )
}

@Composable
private fun RollingBackBody(state: ManagerState.RollingBack) {
    SectionLabel("THEME")
    Spacer(Modifier.height(4.dp))
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        StatusLine("ROLLING BACK — ${state.stage.name}", Crystal.Cream)
    }
}

@Composable
private fun RollbackDoneBody(
    state: ManagerState.RollbackDone,
    dispatcher: FocusDispatcher,
    onEvent: (ManagerEvent) -> Unit,
) {
    SectionLabel("THEME")
    Spacer(Modifier.height(4.dp))
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
    CrystalButton(
        key = "rb-back",
        label = "BACK",
        onClick = { onEvent(ManagerEvent.Dismiss) },
        dispatcher = dispatcher,
        requestInitialFocus = true,
    )
}

@Composable
private fun RollbackFailedBody(
    state: ManagerState.RollbackFailed,
    dispatcher: FocusDispatcher,
    onEvent: (ManagerEvent) -> Unit,
) {
    SectionLabel("THEME")
    Spacer(Modifier.height(4.dp))
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        StatusLine(state.message, Crystal.Bad)
    }
    CrystalButton(
        key = "rbf-back",
        label = "BACK",
        onClick = { onEvent(ManagerEvent.Dismiss) },
        dispatcher = dispatcher,
        requestInitialFocus = true,
    )
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    BasicText(
        text = text,
        style = TextStyle(
            fontFamily = Crystal.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = Crystal.BodySize,
            color = color,
        ),
    )
}
