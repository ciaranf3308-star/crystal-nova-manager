package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.LibraryScan
import io.crystalnova.manager.importer.labels
import io.crystalnova.manager.importer.scanGameLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * GAME LIBRARY (u56): read-only export of the ROM collection as a
 * plain-text list grouped by console. COPY ALL puts the whole list on
 * the clipboard for pasting into ChatGPT. No database, no metadata,
 * no management UI: scan -> text -> copy.
 *
 * Pure function of the graph: the scan runs on IO and is strictly
 * read-only (see [scanGameLibrary]). A null scan means the ROM root
 * is not granted — the screen says so and points at the importer.
 */
@Composable
fun GameLibraryScreen(
    graph: ImporterGraph,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var refreshTick by remember { mutableStateOf(0) }
    var scan by remember { mutableStateOf<LibraryScan?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var justCopied by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        scanning = true
        scan = withContext(Dispatchers.IO) {
            scanGameLibrary(graph.mapping, graph.env.romsFs())
        }
        scanning = false
    }
    if (justCopied) {
        LaunchedEffect(Unit) {
            delay(2500)
            justCopied = false
        }
    }

    ScreenScaffold(
        routeKey = "game-library",
        title = "GAME LIBRARY",
        onBack = onBack,
        fallbackFocusKey = "copy-all",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            val result = scan
            if (scanning && result == null) {
                section {
                    StatusLine("SCANNING ROM FOLDERS…", Crystal.Joystick)
                }
            } else if (result == null) {
                section {
                    StatusLine("ROM ROOT NOT GRANTED", Crystal.Bad)
                    DimLine("GRANT THE ROM ROOT IN THE GAME IMPORTER FIRST.")
                }
            } else {
                val total = result.totalGames
                val systems = result.systemCount
                section {
                    StatusLine("$total ${if (total == 1) "game" else "games"} found")
                    StatusLine("$systems ${if (systems == 1) "system" else "systems"}")
                }
                if (scanning) {
                    section {
                        StatusLine("SCANNING ROM FOLDERS…", Crystal.Joystick)
                    }
                }
                control(
                    key = "refresh",
                    label = "REFRESH",
                    onClick = { refreshTick++ },
                )
                control(
                    key = "copy-all",
                    label = "COPY ALL",
                    subLabel = "COPY THE FULL LIST TO THE CLIPBOARD",
                    enabled = total > 0,
                    onClick = {
                        clipboard.setText(AnnotatedString(result.exportText))
                        justCopied = true
                    },
                )
                if (justCopied) {
                    section {
                        StatusLine("COPIED TO CLIPBOARD", Crystal.Good)
                    }
                }
                if (total == 0) {
                    section {
                        DimLine("NO GAMES FOUND IN THE MAPPED ROM FOLDERS.")
                    }
                } else {
                    section {
                        for (system in result.systems) {
                            val label = system.platform.labels()
                            StatusLine("${label.short} — ${system.games.size}")
                        }
                    }
                }
            }
        }
    }
}
