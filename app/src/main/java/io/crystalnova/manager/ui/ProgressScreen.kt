package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.scraper.ScraperUiState
import io.crystalnova.manager.scraper.work.SlotState

/**
 * PROGRESS: the focused scrape run. Current game title/stage, live
 * counts, and exactly one primary control: CANCEL while the run is
 * active, DONE once it has finished (or was cancelled).
 *
 * Controller B (and the system back button) only pop back to the
 * SYSTEM screen — the scrape job is owned by the activity-scoped
 * ScraperManager and keeps running in the background. To watch it
 * again, return via LIBRARY → system card → SCRAPE: re-tapping SCRAPE
 * while a run is active is a safe no-op that re-opens this screen.
 */
@Composable
fun ProgressScreen(
    state: ScraperUiState,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dispatcher = remember { FocusDispatcher() }
    val p = state.progress
    ScreenRoot(onBack = onBack, dispatcher = dispatcher, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("SCRAPING")
            when {
                state.scraping -> StatusLine("SCRAPING…", Crystal.Cream)
                p?.cancelled == true -> StatusLine("CANCELLED", Crystal.Bad)
                p != null -> StatusLine("FINISHED", Crystal.Good)
                else -> StatusLine("NO ACTIVE SCRAPE", Crystal.Divider)
            }
            p?.let { prog ->
                StatusLine(
                    "${prog.done}/${prog.total} — OK ${prog.succeeded} · " +
                        "PARTIAL ${prog.partial} · FAILED ${prog.failed} · " +
                        "UNMATCHED ${prog.unmatched}",
                )
                prog.current?.let { cur ->
                    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusLine(cur.system.uppercase(), Crystal.Divider)
                            StatusLine(cur.title.take(48))
                            DimLine("STAGE: ${cur.stage}")
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
            }
            state.notice?.let { NoticeBlock(it, onDismissNotice, dispatcher) }
            if (state.scraping) {
                CrystalButton(
                    key = "cancel-scrape",
                    label = "CANCEL",
                    onClick = onCancel,
                    dispatcher = dispatcher,
                    danger = true,
                    requestInitialFocus = true,
                )
            } else {
                CrystalButton(
                    key = "scrape-done",
                    label = "DONE",
                    onClick = onDone,
                    dispatcher = dispatcher,
                    requestInitialFocus = true,
                )
            }
            BackFooter(label = "BACK (SCRAPE KEEPS RUNNING)")
        }
    }
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
        DimLine("$label ")
        StatusLine(text, color)
    }
}
