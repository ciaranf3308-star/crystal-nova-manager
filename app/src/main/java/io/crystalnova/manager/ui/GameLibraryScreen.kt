package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.importer.LibrarySystem
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
 *
 * 4:3 redesign: COPY ALL stays the big primary tile through its
 * two-line sublabel (content-sized, no fixed height). The system
 * chips are a deterministic vertical stack of full-width rows —
 * short name + game count, ellipsis if long — instead of a FlowRow
 * whose wrapping is unpredictable on the handheld.
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
        ControllerGrid(
            state = gridState,
            dispatcher = dispatcher,
            columns = GridCells.Fixed(2),
            initialFocus = ::isInitialFocus,
        ) {
            val result = scan
            if (scanning && result == null) {
                panel {
                    StatusLine("SCANNING ROM FOLDERS…", Crystal.Joystick)
                }
            } else if (result == null) {
                panel {
                    StatusLine("ROM ROOT NOT GRANTED", Crystal.Bad)
                    DimLine("GRANT THE ROM ROOT IN THE GAME IMPORTER FIRST.")
                }
            } else {
                val total = result.totalGames
                val systems = result.systemCount
                panel {
                    StatusLine("$total ${if (total == 1) "game" else "games"} found")
                    StatusLine("$systems ${if (systems == 1) "system" else "systems"}")
                }
                if (scanning) {
                    panel {
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
                    panel {
                        StatusLine("COPIED TO CLIPBOARD", Crystal.Good)
                    }
                }
                if (total == 0) {
                    panel {
                        DimLine("NO GAMES FOUND IN THE MAPPED ROM FOLDERS.")
                    }
                } else {
                    panel {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            for (system in result.systems) {
                                val label = system.platform.labels()
                                SystemRow("${label.short} — ${system.games.size} GAME${if (system.games.size == 1) "" else "S"}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Display-only system rows: a deterministic vertical stack of
 * full-width rows inside the panel. Not focusable: tapping a system
 * to see its filenames is explicitly out of scope for this build, so
 * D-pad focus moves straight between REFRESH and COPY ALL.
 */
@Composable
private fun SystemRow(text: String) {
    BasicText(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Crystal.TileDeep)
            .border(2.dp, Crystal.FrameDim, RoundedCornerShape(2.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(
            fontFamily = Crystal.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = Crystal.BodySize,
            color = Crystal.Ink,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
