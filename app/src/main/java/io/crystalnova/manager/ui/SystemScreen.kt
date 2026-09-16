package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.scraper.ScraperUiState

/**
 * SYSTEM: one library platform. Title, a compact stats row (zeros when
 * the system is unknown to the index stats), and three actions:
 * SCRAPE (full run), RETRY INCOMPLETE (incomplete only), BACK. Both
 * scrape actions hand to the PROGRESS destination; the ScraperManager
 * ignores duplicate starts while a run is already active.
 *
 * The ALL SYSTEMS pseudo-card passes slug "" and reads the aggregate
 * index stats instead of a per-platform row.
 */
@Composable
fun SystemScreen(
    slug: String,
    label: String,
    state: ScraperUiState,
    onScrape: () -> Unit,
    onRetryIncomplete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "system-$slug",
        title = "SYSTEM",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "scrape",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                text = label.uppercase(),
                style = TextStyle(
                    fontFamily = Crystal.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = Crystal.TitleSize,
                    color = Crystal.Ink,
                ),
            )
            StatsRow(slug = slug, state = state)
            CrystalButton(
                key = "scrape",
                label = "SCRAPE",
                onClick = onScrape,
                dispatcher = dispatcher,
                requestInitialFocus = isInitialFocus("scrape"),
            )
            CrystalButton(
                key = "retry-incomplete",
                label = "RETRY INCOMPLETE",
                onClick = onRetryIncomplete,
                dispatcher = dispatcher,
                requestInitialFocus = isInitialFocus("retry-incomplete"),
            )
            CrystalButton(
                key = "system-back",
                label = "BACK",
                onClick = onBack,
                dispatcher = dispatcher,
                requestInitialFocus = isInitialFocus("system-back"),
            )
        }
    }
}

/**
 * GAMES / COMPLETE / PARTIAL / UNMATCHED from the per-platform stats;
 * the ALL SYSTEMS card (empty slug) uses the aggregate index stats; an
 * unknown platform shows zeros gracefully.
 */
@Composable
private fun StatsRow(slug: String, state: ScraperUiState) {
    val row = state.systemStats[slug]
    val (games, complete, partial, unmatched) = when {
        row != null -> Quad(row.games, row.complete, row.partial, row.unmatched)
        slug.isEmpty() -> {
            val s = state.stats
            Quad(s.totalGames, s.complete, s.partial, s.unmatched)
        }
        else -> Quad(0, 0, 0, 0)
    }
    CrystalPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatCell("GAMES", games.toString(), Crystal.Ink)
            StatCell("COMPLETE", complete.toString(), Crystal.Good)
            StatCell("PARTIAL", partial.toString(), Crystal.Cream)
            StatCell("UNMATCHED", unmatched.toString(), Crystal.Bad)
        }
    }
}

private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

@Composable
private fun StatCell(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusLine(value, color)
        DimLine(label)
    }
}
