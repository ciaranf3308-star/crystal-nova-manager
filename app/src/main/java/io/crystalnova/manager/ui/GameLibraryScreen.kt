package io.crystalnova.manager.ui

import androidx.compose.foundation.lazy.grid.GridCells
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
import io.crystalnova.manager.importer.chunkLibraryFolder
import io.crystalnova.manager.importer.scanGameLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * GAME LIBRARY (u89 command center): read-only export of the ROM
 * collection as JSON that mirrors the real folder structure under
 * the ROM root. COPY ALL puts the full JSON on the clipboard for
 * pasting into ChatGPT, each folder gets its own COPY control that
 * copies ONLY that folder's entries, and each folder is ALSO split
 * into consecutive 50-file chunks with their own COPY controls — one
 * chunk at a time stays under both the clipboard character limit
 * and a chat input window, so the user can paste chunk after chunk
 * into an LLM until the whole library is across. No database, no
 * metadata, no management UI: scan -> JSON -> copy.
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
 * u89 layout (behavior unchanged from u84): the screen is zoned
 * tiles, not a full-width stacked list. SUMMARY is two statTiles
 * (total files, folder count) sharing a grid row; CONTROLS is a
 * commandTile grid (REFRESH, COPY ALL); each folder is its own
 * zone — a ZoneHeader plus a statTile (name + file count) with
 * that folder's COPY and chunk COPY commandTiles grouped right
 * under it. Empty folders get a tile and no copy control, as
 * before. Every key, label, sublabel, enabled rule, onClick, and
 * copied-feedback line is exactly as in u84; only the grouping
 * and the tile presentation changed, so D-pad focus order now
 * walks folder by folder instead of all folder copies first and
 * all chunk copies second. COPY ALL keeps initial focus via the
 * scaffold's fallbackFocusKey.
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
            columns = GridCells.Fixed(gridColumnsForWidth(LocalContentWidth.current.value.toInt(), 2)),
            initialFocus = ::isInitialFocus,
        ) {
            val result = scan
            if (scanning && result == null) {
                panel {
                    StatusStrip("SCANNING ROM FOLDERS…", attention = true)
                }
            } else if (result == null) {
                panel {
                    StatusStrip("ROM ROOT NOT GRANTED", attention = true)
                    DimLine("GRANT THE ROM ROOT IN THE GAME IMPORTER FIRST.")
                }
            } else {
                val total = result.totalFiles
                val folders = result.folderCount
                panel {
                    ZoneHeader("SUMMARY")
                }
                statTile(
                    key = "summary-files",
                    label = "TOTAL FILES",
                    value = "$total",
                )
                statTile(
                    key = "summary-folders",
                    label = "FOLDERS",
                    value = "$folders",
                )
                if (scanning) {
                    panel {
                        StatusStrip("SCANNING ROM FOLDERS…", attention = true)
                    }
                }
                panel {
                    ZoneHeader("CONTROLS")
                }
                commandTile(
                    key = "refresh",
                    label = "REFRESH",
                    onClick = { refreshTick++ },
                )
                commandTile(
                    key = "copy-all",
                    label = "COPY ALL",
                    subLabel = "COPY THE REAL FOLDER STRUCTURE AS JSON",
                    onClick = {
                        clipboard.setText(AnnotatedString(result.exportText))
                        copiedLabel = "COPIED TO CLIPBOARD"
                    },
                    enabled = total > 0,
                )
                if (copiedLabel != null) {
                    panel {
                        StatusStrip(copiedLabel!!)
                    }
                }
                if (total == 0) {
                    panel {
                        DimLine("NO FILES FOUND IN THE ROM FOLDER.")
                    }
                } else {
                    // Per-folder zones: each folder is a tile (name +
                    // file count) with its own COPY controls grouped
                    // right under it — the whole-folder COPY (u83)
                    // first, then its 50-file chunk COPY controls
                    // (u84), instead of the old two separate passes
                    // over all folders. Same render path as COPY ALL,
                    // restricted to a single folder or a single chunk
                    // — no new helper, no changed copy semantics.
                    // Empty folders get a tile and no copy control.
                    for (folder in result.folders) {
                        panel {
                            ZoneHeader(folder.name.uppercase())
                        }
                        statTile(
                            key = "stat-folder-${folder.name}",
                            label = folder.name.uppercase(),
                            value = "${folder.files.size} FILES",
                        )
                        if (folder.files.isEmpty()) continue
                        commandTile(
                            key = "copy-folder-${folder.name}",
                            label = "COPY ${folder.name.uppercase()} — ${folder.files.size} FILES",
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(LibraryScan(listOf(folder)).exportText),
                                )
                                copiedLabel = "COPIED ${folder.name.uppercase()}"
                            },
                        )
                        // Chunked COPY (u84): even one folder can
                        // exceed the clipboard limit when it holds
                        // many games, so each folder is split into
                        // consecutive 50-file chunks (see
                        // chunkLibraryFolder) with their own
                        // controls. Each chunk copies through the
                        // exact same single-folder JSON path — only
                        // that chunk's files — so every copy is tiny
                        // enough for the clipboard AND a chat input
                        // window. Empty folders yield zero chunks,
                        // so they get no controls.
                        val chunks = chunkLibraryFolder(folder)
                        for ((index, chunk) in chunks.withIndex()) {
                            val chunkLabel = "${index + 1}/${chunks.size}"
                            commandTile(
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
