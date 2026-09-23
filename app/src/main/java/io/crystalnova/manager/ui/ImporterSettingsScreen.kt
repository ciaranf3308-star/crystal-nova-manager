package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.os.Build
import io.crystalnova.manager.importer.AllFilesAccess
import io.crystalnova.manager.importer.DuplicatePolicy
import io.crystalnova.manager.importer.GrantProbe
import io.crystalnova.manager.importer.ImporterGraph
import io.crystalnova.manager.importer.PlatformId
import io.crystalnova.manager.importer.PlatformMapping
import io.crystalnova.manager.importer.labels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Importer settings: the two SAF grants, the per-platform ROM folder
 * mapping (validated single path segments, ES-DE-conventional
 * defaults), archive support, and import defaults.
 *
 * The folder editor is an inline text field: A opens the IME while it
 * is focused (the scaffold passes A through), DONE saves.
 */
@Composable
fun ImporterSettingsScreen(
    graph: ImporterGraph,
    /** Bumped by MainActivity whenever a grant picker returns. */
    grantRev: Int,
    onBack: () -> Unit,
    onGrantDownloads: () -> Unit,
    onGrantRoms: () -> Unit,
    /** u54: opens the system "All files access" Settings page. */
    onOpenAllFilesSettings: () -> Unit,
) {
    val engine = graph.engine
    val settings = graph.settings
    val mapping = graph.mapping
    var editingFolder by remember { mutableStateOf<PlatformId?>(null) }
    var editorFocused by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    // SAF state lives outside Compose: probe it on IO when the grant
    // changes — a resolver query on Main can block.
    var probe by remember { mutableStateOf<GrantProbe?>(null) }
    LaunchedEffect(grantRev) {
        probe = withContext(Dispatchers.IO) { engine.probeGrants() }
    }
    val downloadsOk = probe?.downloadsOk == true
    val romsOk = probe?.romsOk == true
    // u54: "All files access" is granted in system Settings, outside
    // the app — re-check on every resume, not just on grantRev bumps.
    val allFilesApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    var settingsTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) settingsTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val allFilesGranted = remember(settingsTick) {
        allFilesApi && AllFilesAccess.hasAccess()
    }
    // Write-through UI state, seeded from the persisted settings.
    var deleteAfterSuccess by remember { mutableStateOf(settings.deleteAfterSuccess) }
    var autoIdentify by remember { mutableStateOf(settings.autoIdentify) }
    var confirmBeforeImport by remember { mutableStateOf(settings.confirmBeforeImport) }
    var duplicateDefault by remember { mutableStateOf(settings.duplicateDefault) }

    ScreenScaffold(
        routeKey = "import-settings",
        title = "IMPORTER SETTINGS",
        onBack = onBack,
        passThroughAWhen = { editorFocused },
        fallbackFocusKey = "grant-downloads",
    ) {
        ControllerGrid(
            state = gridState,
            dispatcher = dispatcher,
            columns = GridCells.Fixed(2),
            initialFocus = ::isInitialFocus,
        ) {
            panel {
                StatusLine("FOLDERS")
            }
            panel {
                StatusLine("DOWNLOADS (SOURCE)")
                StatusLine(
                    if (downloadsOk) "GRANTED" else "NOT GRANTED",
                    if (downloadsOk) Crystal.Good else Crystal.Bad,
                )
            }
            control(
                key = "grant-downloads",
                label = if (downloadsOk) "CHANGE DOWNLOADS FOLDER" else "GRANT DOWNLOADS FOLDER",
                onClick = onGrantDownloads,
            )
            if (allFilesApi) {
                panel {
                    StatusLine("ALL FILES ACCESS")
                    StatusLine(
                        if (allFilesGranted) "GRANTED — DIRECT SCAN" else "NOT GRANTED",
                        if (allFilesGranted) Crystal.Good else Crystal.Bad,
                    )
                }
                if (!allFilesGranted) {
                    control(
                        key = "all-files-settings",
                        label = "OPEN SETTINGS",
                        subLabel = "ALLOW ALL FILES ACCESS FOR DIRECT SCAN",
                        onClick = onOpenAllFilesSettings,
                    )
                }
            }
            panel {
                StatusLine("ROM ROOT (DESTINATION)")
                StatusLine(
                    if (romsOk) "GRANTED" else "NOT GRANTED",
                    if (romsOk) Crystal.Good else Crystal.Bad,
                )
            }
            control(
                key = "grant-roms",
                label = if (romsOk) "CHANGE ROM ROOT FOLDER" else "GRANT ROM ROOT FOLDER",
                onClick = onGrantRoms,
            )
            panel {
                StatusLine("PLATFORM FOLDERS")
                StatusLine("NAMES ONLY — NO / OR ..", Crystal.InkDim)
            }
            for (platform in PlatformMapping.ORDERED) {
                if (editingFolder == platform) {
                    panel {
                        FolderEditor(
                            platform = platform,
                            initial = mapping.folderFor(platform),
                            scrollModifier = Modifier,
                            onFocusChange = { editorFocused = it },
                            onDone = { draft ->
                                if (mapping.setFolder(platform, draft)) {
                                    editingFolder = null
                                    editorFocused = false
                                    folderError = null
                                    true
                                } else {
                                    folderError = "BAD FOLDER NAME — SINGLE SEGMENT ONLY"
                                    false
                                }
                            },
                        )
                        folderError?.let { StatusLine(it, Crystal.Bad) }
                    }
                } else {
                    control(
                        key = "folder-${platform.name}",
                        label = platform.labels().long.uppercase(),
                        subLabel = "/${mapping.folderFor(platform)} — TAP TO EDIT",
                        onClick = {
                            folderError = null
                            editingFolder = platform
                        },
                    )
                }
            }
            panel {
                StatusLine("ARCHIVE SUPPORT")
                StatusLine("ZIP · 7Z", Crystal.Good)
                StatusLine("RAR — DETECTED BUT NOT SUPPORTED", Crystal.Bad)
                StatusLine("UNRELATED FILES ARE IGNORED", Crystal.InkDim)
            }
            panel {
                StatusLine("DEFAULTS")
            }
            control(
                key = "default-duplicate",
                label = "DUPLICATE DEFAULT: ${duplicateDefault.name}",
                subLabel = "TAP TO CYCLE — APPLIES TO NEW CONFLICTS",
                onClick = {
                    duplicateDefault = when (duplicateDefault) {
                        DuplicatePolicy.SKIP -> DuplicatePolicy.REPLACE
                        DuplicatePolicy.REPLACE -> DuplicatePolicy.KEEP_BOTH
                        DuplicatePolicy.KEEP_BOTH -> DuplicatePolicy.SKIP
                    }
                    settings.duplicateDefault = duplicateDefault
                },
            )
            control(
                key = "default-delete",
                label = "DELETE SOURCE AFTER VERIFY: ${if (deleteAfterSuccess) "ON" else "OFF"}",
                subLabel = "SOURCE IS ALWAYS KEPT ON ANY FAILURE",
                onClick = {
                    deleteAfterSuccess = !deleteAfterSuccess
                    settings.deleteAfterSuccess = deleteAfterSuccess
                },
            )
            control(
                key = "default-autoidentify",
                label = "AUTO-IDENTIFY CERTAIN GAMES: ${if (autoIdentify) "ON" else "OFF"}",
                subLabel = "OFF SENDS EVERYTHING TO CLASSIFICATION",
                onClick = {
                    autoIdentify = !autoIdentify
                    settings.autoIdentify = autoIdentify
                },
            )
            control(
                key = "default-confirm",
                label = "CONFIRM BEFORE IMPORT: ${if (confirmBeforeImport) "ON" else "OFF"}",
                subLabel = "REVIEW SCREEN ALWAYS SHOWS FIRST",
                onClick = {
                    confirmBeforeImport = !confirmBeforeImport
                    settings.confirmBeforeImport = confirmBeforeImport
                },
            )
            panel {
                StatusLine("IMPORTS RUN SEQUENTIALLY — ONE ARCHIVE", Crystal.InkDim)
                StatusLine("AT A TIME. ALWAYS. NO TOGGLE.", Crystal.InkDim)
            }
        }
    }
}

/**
 * Inline folder-name editor. A (passed through by the scaffold while
 * this is focused) opens the IME; the DONE action saves.
 */
@Composable
private fun FolderEditor(
    platform: PlatformId,
    initial: String,
    scrollModifier: Modifier,
    onFocusChange: (Boolean) -> Unit,
    /** Returns true when the draft saved (field may drop focus). */
    onDone: (String) -> Boolean,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var draft by remember(platform) { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    StatusLine("${platform.labels().long.uppercase()} →")
    Box(
        modifier = scrollModifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .focusable()
            .background(Crystal.TileDeep)
            .padding(10.dp),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                fontFamily = Crystal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = Crystal.BodySize,
                color = if (focused) Crystal.Joystick else Crystal.Ink,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (onDone(draft)) focusManager.clearFocus()
                },
            ),
            singleLine = true,
        )
    }
    // Request focus when the editor appears so A opens the IME.
    LaunchedEffect(platform) {
        runCatching { focusRequester.requestFocus() }
    }
    StatusLine("IME DONE = SAVE · B = BACK (DISCARDS)", Crystal.InkDim)
}
