package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.scraper.ScraperUiState
import io.crystalnova.manager.storage.LocationState

/**
 * LIBRARY: the scraper's front door. Status header first (ROM and
 * MEDIA friendly paths, never raw content:// URIs), then the systems
 * as a compact 3-column card grid — never a giant stacked list. When
 * no ROM library is configured (or its grant was lost), the grid is
 * replaced by a prominent SELECT ROM LIBRARY action.
 */
@Composable
fun LibraryScreen(
    state: ScraperUiState,
    onPickRomLibrary: () -> Unit,
    onSelectSystem: (slug: String, label: String) -> Unit,
    onRescan: () -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dispatcher = remember { FocusDispatcher() }
    ScreenRoot(onBack = onBack, dispatcher = dispatcher, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("LIBRARY")
            StatusLine("ROM LIBRARY: ${friendlyLocation(state.romLocation)}")
            StatusLine("MEDIA LIBRARY: ${friendlyLocation(state.mediaLocation)}")
            val romReady = state.romLocation is LocationState.Ready
            if (!romReady) {
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusLine("SELECT YOUR ROMS FOLDER", Crystal.Cream)
                        DimLine(
                            "EACH SUBFOLDER IS TREATED AS ONE SYSTEM. " +
                                "ARTWORK AND THE INDEX LIVE IN YOUR MEDIA LIBRARY — " +
                                "YOUR ROMS ARE NEVER MODIFIED.",
                        )
                        if (state.romLocation is LocationState.AccessLost) {
                            StatusLine("ACCESS LOST — PLEASE RESELECT", Crystal.Bad)
                        }
                    }
                }
                CrystalButton(
                    key = "pick-rom",
                    label = "SELECT ROM LIBRARY",
                    onClick = onPickRomLibrary,
                    dispatcher = dispatcher,
                    requestInitialFocus = true,
                )
            } else {
                SectionLabel("SYSTEMS")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "sys-all") {
                        CrystalButton(
                            key = "card-all",
                            label = "ALL SYSTEMS\n${state.stats.totalGames} GAMES",
                            onClick = { onSelectSystem("", "ALL SYSTEMS") },
                            dispatcher = dispatcher,
                            requestInitialFocus = true,
                        )
                    }
                    items(
                        items = state.systems,
                        key = { sys -> "sys-${sys.platformSlug}" },
                    ) { sys ->
                        CrystalButton(
                            key = "card-${sys.platformSlug}",
                            label = "${sys.label.uppercase()}\n${sys.gameCount} GAMES",
                            onClick = { onSelectSystem(sys.platformSlug, sys.label) },
                            dispatcher = dispatcher,
                        )
                    }
                }
                CrystalButton(
                    key = "rescan",
                    label = if (state.scanning) "SCANNING…" else "RESCAN LIBRARY",
                    onClick = onRescan,
                    dispatcher = dispatcher,
                    enabled = !state.scanning && !state.scraping,
                )
            }
            state.notice?.let { NoticeBlock(it, onDismissNotice, dispatcher) }
            BackFooter()
        }
    }
}
