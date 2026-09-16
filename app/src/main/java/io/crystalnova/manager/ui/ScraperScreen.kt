package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.scraper.ScraperUiState
import io.crystalnova.manager.scraper.work.SlotState

/**
 * SCRAPER section: library dashboard, scrape options, live progress.
 * Controller-first like the THEME section; pure function of [state].
 */
@Composable
fun ScraperScreen(
    state: ScraperUiState,
    onPickGamesFolder: () -> Unit,
    onScan: () -> Unit,
    onSelectPlatform: (String?) -> Unit,
    onStartScrape: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismissNotice: () -> Unit,
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
                    Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        dispatcher.activateFocused()
                        true
                    }
                    // Gamepad B exits, mirroring the THEME section.
                    Key.ButtonB -> {
                        onExit()
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
            SectionLabel("SCRAPER")
            if (state.needsGamesFolder) {
                NeedsGamesFolderBody(dispatcher, onPickGamesFolder)
            } else {
                DashboardBody(state, dispatcher)
                CrystalDivider()
                if (state.scraping) {
                    ProgressBody(state, dispatcher, onCancel)
                } else {
                    OptionsBody(state, dispatcher, onScan, onSelectPlatform, onStartScrape, onRetry, onPickGamesFolder)
                }
            }
            state.notice?.let {
                StatusLineLocal(it, Crystal.Bad)
                CrystalButton(
                    key = "dismiss-notice",
                    label = "DISMISS",
                    onClick = onDismissNotice,
                    dispatcher = dispatcher,
                )
            }
        }
    }
}

@Composable
private fun NeedsGamesFolderBody(dispatcher: FocusDispatcher, onPickGamesFolder: () -> Unit) {
    StatusLineLocal("SELECT YOUR GAMES / ROMS FOLDER", Crystal.Cream)
    StatusLineLocal(
        "EACH SUBFOLDER IS TREATED AS ONE SYSTEM. ARTWORK IS STORED " +
            "IN crystal-nova-data/ NEXT TO THE THEME — YOUR ROMS ARE NEVER MODIFIED.",
        Crystal.Divider,
    )
    CrystalButton(
        key = "pick-games-folder",
        label = "SELECT GAMES FOLDER",
        onClick = onPickGamesFolder,
        dispatcher = dispatcher,
        requestInitialFocus = true,
    )
}

@Composable
private fun DashboardBody(state: ScraperUiState, dispatcher: FocusDispatcher) {
    val stats = state.stats
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DashboardRow("SYSTEMS", stats.systems.size.toString())
            DashboardRow("TOTAL GAMES", stats.totalGames.toString())
            DashboardRow("COMPLETE", stats.complete.toString(), Crystal.Good)
            DashboardRow("PARTIAL", stats.partial.toString(), Crystal.Cream)
            DashboardRow("UNMATCHED", stats.unmatched.toString(), Crystal.Bad)
            DashboardRow("REAL ASSETS", stats.realAssets.toString())
            DashboardRow("GENERATED ASSETS", stats.generatedAssets.toString())
        }
    }
}

@Composable
private fun DashboardRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = Crystal.Ink) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatusLineLocal(label, Crystal.Divider)
        StatusLineLocal(value, color)
    }
}

@Composable
private fun OptionsBody(
    state: ScraperUiState,
    dispatcher: FocusDispatcher,
    onScan: () -> Unit,
    onSelectPlatform: (String?) -> Unit,
    onStartScrape: () -> Unit,
    onRetry: () -> Unit,
    onPickGamesFolder: () -> Unit,
) {
    SectionLabel("SYSTEM")
    // Platform picker: ALL + one row per discovered system.
    CrystalButton(
        key = "platform-all",
        label = if (state.selectedPlatform == null) "> ALL SYSTEMS" else "  ALL SYSTEMS",
        onClick = { onSelectPlatform(null) },
        dispatcher = dispatcher,
    )
    for (system in state.systems) {
        val selected = state.selectedPlatform == system.platformSlug
        CrystalButton(
            key = "platform-${system.platformSlug}",
            label = (if (selected) "> " else "  ") +
                "${system.label.uppercase()} (${system.gameCount})",
            onClick = { onSelectPlatform(system.platformSlug) },
            dispatcher = dispatcher,
        )
    }
    CrystalDivider()
    CrystalButton(
        key = "rescan",
        label = if (state.scanning) "SCANNING…" else "RESCAN LIBRARY",
        onClick = onScan,
        dispatcher = dispatcher,
        enabled = !state.scanning,
    )
    CrystalButton(
        key = "start-scrape",
        label = "START SCRAPE",
        onClick = onStartScrape,
        dispatcher = dispatcher,
        requestInitialFocus = state.systems.isNotEmpty(),
    )
    CrystalButton(
        key = "retry-incomplete",
        label = "RETRY INCOMPLETE",
        onClick = onRetry,
        dispatcher = dispatcher,
    )
    CrystalDivider()
    CrystalButton(
        key = "change-games-folder",
        label = "CHANGE GAMES FOLDER",
        onClick = onPickGamesFolder,
        dispatcher = dispatcher,
    )
}

@Composable
private fun ProgressBody(
    state: ScraperUiState,
    dispatcher: FocusDispatcher,
    onCancel: () -> Unit,
) {
    val p = state.progress ?: return
    StatusLineLocal(
        "SCRAPING ${p.done}/${p.total} — OK ${p.succeeded} · PARTIAL ${p.partial} · " +
            "FAILED ${p.failed} · UNMATCHED ${p.unmatched}" +
            if (p.cancelled) " — CANCELLED" else "",
        if (p.cancelled) Crystal.Bad else Crystal.Cream,
    )
    p.current?.let { cur ->
        CrystalPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusLineLocal(cur.system.uppercase(), Crystal.Divider)
                StatusLineLocal(cur.title.take(48), Crystal.Ink)
                StatusLineLocal("STAGE: ${cur.stage}", Crystal.Divider)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SlotChip("FRONT", cur.front)
                    SlotChip("SPINE", cur.spine)
                    SlotChip("BACK", cur.back)
                    SlotChip("MEDIA", cur.media)
                }
            }
        }
    }
    CrystalButton(
        key = "cancel-scrape",
        label = "CANCEL",
        onClick = onCancel,
        dispatcher = dispatcher,
        danger = true,
        requestInitialFocus = true,
    )
}

@Composable
private fun SlotChip(label: String, slotState: SlotState) {
    val (text, color) = when (slotState) {
        SlotState.PENDING -> "○" to Crystal.Divider
        SlotState.WORKING -> "…" to Crystal.Cream
        SlotState.REAL -> "R" to Crystal.Good
        SlotState.GENERATED -> "G" to Crystal.Frame
        SlotState.FAILED -> "!" to Crystal.Bad
        SlotState.SKIPPED -> "–" to Crystal.Divider
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLineLocal("$label ", Crystal.Divider)
        StatusLineLocal(text, color)
    }
}

@Composable
private fun StatusLineLocal(text: String, color: androidx.compose.ui.graphics.Color) {
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
