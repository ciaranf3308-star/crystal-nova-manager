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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.LibraryScan
import io.crystalnova.manager.importer.chunkLibraryFolder
import io.crystalnova.manager.importer.scanGameLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * GAME LIBRARY (u84): read-only export of the ROM collection as JSON
 * that mirrors the real folder structure under the ROM root. COPY ALL
 * puts the full JSON on the clipboard for pasting into ChatGPT, each
 * folder gets its own COPY control that copies ONLY that folder's
 * entries, and each folder is ALSO split into consecutive 50-file
 * chunks with their own COPY controls — one chunk at a time stays
 * under both the clipboard character limit and a chat input window,
 * so the user can paste chunk after chunk into an LLM until the
 * whole library is across. No database, no metadata, no management
 * UI: scan -> JSON -> copy.
 *
 * The export walks the ACTUAL children of the ROM root — no
 * platform→folder mapping, no guessing, no filtering, no collapsing:
 * every real folder appears under its real name, files keep their
 * full names and extensions, and empty folders are included. What
 * you see in your file manager is what you get.
 *
 * Pure function of the graph: the scan runs on IO and is strictly
 * read-only (see [scanGameLibrary]). A null scan means the ROM root
 * is not granted — the screen says so and points at the importer.
 *
 * 4:3 redesign: COPY ALL stays the big primary tile through its
 * two-line sublabel (content-sized, no fixed height). The folder
 * chips are a deterministic vertical stack of full-width rows —
 * folder name + file count, ellipsis if long — instead of a FlowRow
 * whose wrapping is unpredictable on the handheld. The per-folder
 * and per-chunk COPY controls are real grid controls below the
 * summary, so D-pad focus moves between them like any other control.
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
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTick) {
        scanning = true
        scan = withContext(Dispatchers.IO) {
            scanGameLibrary(graph.env.romsFs())
        }
        scanning = false
    }
    if (copiedLabel != null) {
        LaunchedEffect(Unit) {
            delay(2500)
            copiedLabel = null
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
            columns = GridCells.Fixed(gridColumnsForWidth(LocalConfiguration.current.screenWidthDp, 2)),
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
                val total = result.totalFiles
                val folders = result.folderCount
                panel {
                    StatusLine("$total FILES IN $folders FOLDERS")
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
                    subLabel = "COPY THE REAL FOLDER STRUCTURE AS JSON",
                    enabled = total > 0,
                    onClick = {
                        clipboard.setText(AnnotatedString(result.exportText))
                        copiedLabel = "COPIED TO CLIPBOARD"
                    },
                )
                if (copiedLabel != null) {
                    panel {
                        StatusLine(copiedLabel!!, Crystal.Good)
                    }
                }
                if (total == 0) {
                    panel {
                        DimLine("NO FILES FOUND IN THE ROM FOLDER.")
                    }
                } else {
                    panel {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            for (folder in result.folders) {
                                SystemRow("${folder.name} — ${folder.files.size} FILES")
                            }
                        }
                    }
                    // Per-folder COPY (u83): one folder's JSON is far
                    // smaller than the whole library, so each copy
                    // stays under the clipboard character limit that
                    // COPY ALL exceeds. Same render path as COPY ALL,
                    // restricted to a single folder — no new helper.
                    // Empty folders get no copy control.
                    for (folder in result.folders) {
                        if (folder.files.isEmpty()) continue
                        control(
                            key = "copy-folder-${folder.name}",
                            label = "COPY ${folder.name.uppercase()} — ${folder.files.size} FILES",
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(LibraryScan(listOf(folder)).exportText),
                                )
                                copiedLabel = "COPIED ${folder.name.uppercase()}"
                            },
                        )
                    }
                    // Chunked COPY (u84): even one folder can exceed
                    // the clipboard limit when it holds many games, so
                    // each folder is split into consecutive 50-file
                    // chunks (see chunkLibraryFolder) with their own
                    // controls. Each chunk copies through the exact
                    // same single-folder JSON path — only that chunk's
                    // files — so every copy is tiny enough for the
                    // clipboard AND a chat input window. Empty folders
                    // yield zero chunks, so they get no controls.
                    for (folder in result.folders) {
                        val chunks = chunkLibraryFolder(folder)
                        for ((index, chunk) in chunks.withIndex()) {
                            val chunkLabel = "${index + 1}/${chunks.size}"
                            control(
                                key = "copy-chunk-${folder.name}-${index + 1}",
                                label = "COPY ${folder.name.uppercase()} $chunkLabel",
                                subLabel = "${chunk.files.size} FILES",
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString(LibraryScan(listOf(chunk)).exportText),
                                    )
                                    copiedLabel = "COPIED ${folder.name.uppercase()} $chunkLabel"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Display-only folder rows: a deterministic vertical stack of
 * full-width rows inside the panel. Not focusable: the per-folder
 * and per-chunk COPY controls below the summary are the focusable
 * folder actions, so D-pad focus moves between REFRESH, COPY ALL,
 * and the folder/chunk COPY controls.
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
