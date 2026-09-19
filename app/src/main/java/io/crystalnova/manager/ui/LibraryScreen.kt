package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.scraper.ScraperUiState
import io.crystalnova.manager.storage.LocationState

/**
 * ROMS: the thin on-demand ROM inventory (u44 pivot). Status header
 * first (ROM and MEDIA friendly paths, never raw content:// URIs),
 * then one honest line per discovered system — name and game count.
 * No scraping, no metadata ownership, no per-system drill-down: iiSU
 * is the authoritative library and frontend. When no ROM library is
 * configured (or its grant was lost), the list is replaced by a
 * prominent SELECT ROM LIBRARY action.
 */
@Composable
fun LibraryScreen(
    state: ScraperUiState,
    onPickRomLibrary: () -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val romReady = state.romLocation is LocationState.Ready
    ScreenScaffold(
        routeKey = "library",
        title = "ROMS",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = if (romReady) "roms-back" else "library-pick-rom",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                StatusLine("ROM LIBRARY: ${friendlyLocation(state.romLocation)}")
                StatusLine("MEDIA LIBRARY: ${friendlyLocation(state.mediaLocation)}")
            }
            if (!romReady) {
                section {
                    CrystalPanel {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusLine("SELECT YOUR ROMS FOLDER", Crystal.Cream)
                            DimLine(
                                "EACH SUBFOLDER IS TREATED AS ONE SYSTEM. " +
                                    "YOUR ROMS ARE NEVER MODIFIED.",
                            )
                            if (state.romLocation is LocationState.AccessLost) {
                                StatusLine("ACCESS LOST — PLEASE RESELECT", Crystal.Bad)
                            }
                        }
                    }
                }
                control(
                    key = "library-pick-rom",
                    testTag = "library-pick-rom",
                    label = "SELECT ROM LIBRARY",
                    onClick = onPickRomLibrary,
                )
            } else {
                section {
                    StatusLine(
                        "${state.stats.totalGames} GAMES · ${state.systems.size} SYSTEMS",
                        Crystal.Cream,
                    )
                }
                // One honest line per system — display only. The
                // per-system scrape drill-down is archived (u44): iiSU
                // owns the library now.
                state.systems.sortedByDescending { it.gameCount }.forEach { sys ->
                    section {
                        StatusLine(
                            "${sys.label.uppercase()} — ${sys.gameCount} GAMES",
                            Crystal.Ink,
                        )
                    }
                }
                // Truthful scan feedback: current folder, systems x/y,
                // games found so far. Never a percentage. The library
                // discovers itself automatically (startup, ROM-folder
                // pick); the manual rebuild lives under
                // SYSTEM → DIAGNOSTICS → RECOVERY.
                if (state.scanning) {
                    section {
                        StatusLine(
                            state.scanProgress?.displayLine() ?: "SCANNING…",
                            Crystal.Divider,
                        )
                    }
                }
            }
            state.notice?.let {
                section { notice(it, onDismissNotice) }
            }
            control(
                key = "roms-back",
                testTag = "roms-back",
                label = "BACK",
                onClick = onBack,
            )
        }
    }
}
