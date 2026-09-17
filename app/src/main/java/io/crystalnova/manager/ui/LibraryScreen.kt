package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.scraper.ScraperUiState
import io.crystalnova.manager.scraper.scan.libraryCardKey
import io.crystalnova.manager.storage.LocationState

/**
 * LIBRARY: the scraper's front door. Status header first (ROM and
 * MEDIA friendly paths, never raw content:// URIs), then the systems
 * as a compact 4-column card grid — system name dominant, game count
 * secondary — never a giant stacked list. When no ROM library is
 * configured (or its grant was lost), the grid is replaced by a
 * prominent SELECT ROM LIBRARY action.
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
    val romReady = state.romLocation is LocationState.Ready
    ScreenScaffold(
        routeKey = "library",
        title = "LIBRARY",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = if (romReady) "library-card-all" else "library-pick-rom",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusLine("ROM LIBRARY: ${friendlyLocation(state.romLocation)}")
            StatusLine("MEDIA LIBRARY: ${friendlyLocation(state.mediaLocation)}")
            if (!romReady) {
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    key = "library-pick-rom",
                    testTag = "library-pick-rom",
                    label = "SELECT ROM LIBRARY",
                    onClick = onPickRomLibrary,
                    dispatcher = dispatcher,
                    requestInitialFocus = isInitialFocus("library-pick-rom"),
                )
            } else {
                // The grid is this screen's single scroll container:
                // D-pad focus on a below-the-fold card scrolls it into
                // view via the controller grid state. Four compact
                // columns fit many more systems per screen; the name is
                // the card's visual anchor, the count a small second line.
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    ControllerGrid(
                        state = gridState,
                        dispatcher = dispatcher,
                        columns = GridCells.Fixed(4),
                        initialFocus = ::isInitialFocus,
                    ) {
                        control(
                            key = "library-card-all",
                            testTag = "library-card-all",
                            label = "ALL SYSTEMS",
                            subLabel = "${state.stats.totalGames} GAMES",
                            onClick = { onSelectSystem("", "ALL SYSTEMS") },
                        )
                        state.systems.forEach { sys ->
                            control(
                                key = libraryCardKey(sys),
                                testTag = libraryCardKey(sys),
                                label = sys.label.uppercase(),
                                subLabel = "${sys.gameCount} GAMES",
                                onClick = { onSelectSystem(sys.platformSlug, sys.label) },
                            )
                        }
                    }
                }
                // Truthful scan feedback: current folder, systems x/y,
                // games found so far. Never a percentage.
                if (state.scanning) {
                    StatusLine(
                        state.scanProgress?.displayLine() ?: "SCANNING…",
                        Crystal.Divider,
                    )
                }
                CrystalButton(
                    key = "library-rescan",
                    testTag = "library-rescan",
                    label = if (state.scanning) "SCANNING…" else "RESCAN LIBRARY",
                    onClick = onRescan,
                    dispatcher = dispatcher,
                    enabled = !state.scanning && !state.scraping,
                    requestInitialFocus = isInitialFocus("library-rescan"),
                )
            }
            state.notice?.let { NoticeBlock(it, onDismissNotice, dispatcher) }
        }
    }
}
