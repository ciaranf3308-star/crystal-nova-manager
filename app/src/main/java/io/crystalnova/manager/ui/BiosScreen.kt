package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.bios.BiosRootState
import io.crystalnova.manager.bios.BiosStatus

/** One row of the BIOS STATUS list. */
data class BiosStatusRow(
    val label: String,
    val status: BiosStatus,
)

/** UI state for the BIOS screen, built by MainActivity from [BiosInventory]. */
data class BiosScreenState(
    val rootState: BiosRootState,
    val rows: List<BiosStatusRow>,
    val ps2Status: BiosStatus,
    /** Display path of the detected PS2 BIOS, e.g. `SD CARD /bios/ps2/scph39001.bin`. */
    val ps2BiosDisplayPath: String?,
    val ps2GameCount: Int,
    val hasLauncherIssues: Boolean,
    val netherSX2Installed: Boolean,
    val notice: String? = null,
    /** True while the recursive BIOS scan is running on Dispatchers.IO. */
    val scanning: Boolean = false,
)

private fun biosStatusText(s: BiosStatus): String = when (s) {
    BiosStatus.READY -> "READY"
    BiosStatus.NOT_REQUIRED -> "NOT REQUIRED"
    BiosStatus.REQUIRED_MISSING -> "MISSING"
    BiosStatus.IMPORT_REQUIRED -> "FOUND · IMPORT REQUIRED"
    BiosStatus.FOUND_UNVERIFIED -> "FOUND · UNVERIFIED"
}

private fun biosStatusColor(s: BiosStatus): Color = when (s) {
    BiosStatus.READY -> Crystal.Good
    BiosStatus.NOT_REQUIRED -> Crystal.InkDim
    else -> Crystal.Joystick
}

/**
 * BIOS: the v24 firmware screen. Small and honest —
 *
 * - the BIOS folder (preferred: the `bios/` sibling of the ROM root;
 *   SAF re-pick only when there is no grant),
 * - a BIOS STATUS list — v24 is PS2-first, so this is the PS2 row
 *   with its real scanned status. Other platforms' firmware is out
 *   of scope: omitted, never overclaimed, never gating.
 * - the scan runs on Dispatchers.IO and the screen reads the cached
 *   result (a SCANNING line shows while it runs) — never blocking
 *   the UI thread,
 * - the PS2 setup flow: NetherSX2 keeps its BIOS in app-private
 *   storage, which Crystal cannot write on Android 11+, so the one
 *   supported path is the in-app import: [ OPEN BIOS SETUP ] launches
 *   NetherSX2 and the screen states the exact taps. [ MARK IMPORTED ]
 *   records the user's attestation — completion cannot be verified
 *   automatically, and the screen never claims otherwise.
 *
 * User-owned files only: nothing here distributes, downloads, or
 * links firmware.
 */
@Composable
fun BiosScreen(
    state: BiosScreenState,
    onSelectBiosFolder: () -> Unit,
    onOpenNetherSX2: () -> Unit,
    onMarkImported: () -> Unit,
    onReviewLaunchers: () -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "bios",
        title = "BIOS",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "bios-primary",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.notice?.let {
                StatusLine(it, Crystal.Joystick)
            }
            // BIOS folder row.
            when (val root = state.rootState) {
                is BiosRootState.Granted -> StatusLine(
                    "BIOS FOLDER · ${root.displayPath}", Crystal.Ink
                )
                is BiosRootState.NotGranted -> {
                    StatusLine("BIOS FOLDER NOT FOUND", Crystal.Joystick)
                    root.displayPath?.let {
                        StatusLine("EXPECTED AT $it", Crystal.InkDim)
                    }
                }
                is BiosRootState.NoRomRoot ->
                    StatusLine("PICK YOUR ROMS FOLDER FIRST", Crystal.Joystick)
            }
            if (state.rootState !is BiosRootState.Granted) {
                CrystalButton(
                    key = "bios-pick-folder",
                    testTag = "bios-pick-folder",
                    label = "SELECT BIOS FOLDER",
                    subLabel = "OPENS AT YOUR SD CARD",
                    onClick = onSelectBiosFolder,
                    dispatcher = dispatcher,
                    requestInitialFocus = isInitialFocus("bios-primary"),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.scanning) {
                StatusLine("SCANNING BIOS FOLDER…", Crystal.InkDim)
            }

            BasicText(
                text = "BIOS STATUS",
                style = TextStyle(
                    fontFamily = Crystal.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = Crystal.SectionSize,
                    color = Crystal.InkDim,
                ),
            )
            for (row in state.rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = row.label,
                        style = TextStyle(
                            fontFamily = Crystal.Mono,
                            fontSize = Crystal.BodySize,
                            color = Crystal.Ink,
                        ),
                    )
                    BasicText(
                        text = biosStatusText(row.status),
                        style = TextStyle(
                            fontFamily = Crystal.Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = Crystal.BodySize,
                            color = biosStatusColor(row.status),
                        ),
                    )
                }
            }

            // PS2 detail: the only firmware flow that can block READY.
            if (state.ps2GameCount > 0) {
                when (state.ps2Status) {
                    BiosStatus.REQUIRED_MISSING -> {
                        StatusLine("NO PS2 BIOS ON THE SD CARD", Crystal.Joystick)
                        val expected = (state.rootState as? BiosRootState.NotGranted)?.displayPath
                        StatusLine(
                            "COPY YOUR EMUDECK BIOS FOLDER TO ${expected ?: "SD CARD /bios"}",
                            Crystal.InkDim,
                        )
                        CrystalButton(
                            key = "bios-ps2-pick",
                            testTag = "bios-ps2-pick",
                            label = "SELECT BIOS FOLDER",
                            onClick = onSelectBiosFolder,
                            dispatcher = dispatcher,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    BiosStatus.IMPORT_REQUIRED -> {
                        StatusLine("PS2 · BIOS FOUND", Crystal.Good)
                        state.ps2BiosDisplayPath?.let {
                            StatusLine(it, Crystal.Ink)
                        }
                        StatusLine("NETHERSX2 IMPORT REQUIRED", Crystal.Joystick)
                        StatusLine(
                            "IN NETHERSX2: APP SETTINGS → BIOS → IMPORT BIOS",
                            Crystal.InkDim,
                        )
                        StatusLine("THEN PICK THE FILE ABOVE", Crystal.InkDim)
                        CrystalButton(
                            key = "bios-primary",
                            testTag = "bios-primary",
                            label = "OPEN BIOS SETUP",
                            subLabel = if (state.netherSX2Installed) "OPENS NETHERSX2"
                            else "NETHERSX2 NOT INSTALLED",
                            onClick = onOpenNetherSX2,
                            dispatcher = dispatcher,
                            enabled = state.netherSX2Installed,
                            requestInitialFocus = isInitialFocus("bios-primary"),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CrystalButton(
                            key = "bios-mark-imported",
                            testTag = "bios-mark-imported",
                            label = "I'VE IMPORTED IT",
                            onClick = onMarkImported,
                            dispatcher = dispatcher,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    BiosStatus.FOUND_UNVERIFIED -> {
                        StatusLine("PS2 BIOS FILE LOOKS WRONG", Crystal.Joystick)
                        state.ps2BiosDisplayPath?.let {
                            StatusLine(it, Crystal.Ink)
                        }
                        StatusLine(
                            "SIZE DOES NOT MATCH A RETAIL PS2 BIOS (4 MIB)",
                            Crystal.InkDim,
                        )
                    }
                    BiosStatus.READY ->
                        StatusLine("PS2 BIOS READY", Crystal.Good)
                    BiosStatus.NOT_REQUIRED -> Unit
                }
            }

            if (state.hasLauncherIssues) {
                CrystalButton(
                    key = "bios-review-launchers",
                    testTag = "bios-review-launchers",
                    label = "REVIEW LAUNCHER ISSUES",
                    onClick = onReviewLaunchers,
                    dispatcher = dispatcher,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
