package io.crystalnova.manager.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.scraper.ScraperUiState
import io.crystalnova.manager.updater.ManagerState

/**
 * HOME: the front door. Four large controller-friendly cards —
 * LIBRARY, THEME, PEGASUS SETUP, SETTINGS — plus a quiet 4-line status
 * block. Not a stats wall: theme status, library size, media path,
 * incomplete count. The version label keeps the hidden 5-tap
 * diagnostics shortcut.
 */
@Composable
fun HomeScreen(
    themeState: ManagerState,
    scraperState: ScraperUiState,
    appVersion: String,
    onLibrary: () -> Unit,
    onTheme: () -> Unit,
    onPegasusSetup: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dispatcher = remember { FocusDispatcher() }
    ScreenRoot(onBack = onExit, dispatcher = dispatcher, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CrystalHeader()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("HOME")
                VersionTapLabel(appVersion = appVersion, onDiagnostics = onDiagnostics)
            }
            CrystalButton(
                key = "home-library",
                label = "LIBRARY",
                onClick = onLibrary,
                dispatcher = dispatcher,
                requestInitialFocus = true,
            )
            CrystalButton(
                key = "home-theme",
                label = "THEME",
                onClick = onTheme,
                dispatcher = dispatcher,
            )
            CrystalButton(
                key = "home-pegasus",
                label = "PEGASUS SETUP",
                onClick = onPegasusSetup,
                dispatcher = dispatcher,
            )
            CrystalButton(
                key = "home-settings",
                label = "SETTINGS",
                onClick = onSettings,
                dispatcher = dispatcher,
            )
            CrystalDivider()
            SectionLabel("STATUS")
            val stats = scraperState.stats
            val (themeLine, themeColor) = themeStatus(themeState)
            StatusLine(themeLine, themeColor)
            StatusLine("${stats.systems.size} SYSTEMS · ${stats.totalGames} GAMES")
            StatusLine("MEDIA: ${friendlyLocation(scraperState.mediaLocation)}")
            StatusLine("INCOMPLETE: ${stats.partial + stats.unmatched}")
            Spacer(Modifier.weight(1f))
            Keycap(key = "B", label = "EXIT")
        }
    }
}

/** One-line theme status derived from the updater state machine. */
private fun themeStatus(state: ManagerState): Pair<String, androidx.compose.ui.graphics.Color> =
    when (state) {
        is ManagerState.NeedsFolder -> "THEME: FOLDER NOT SELECTED" to Crystal.Divider
        is ManagerState.Ready -> when {
            state.checking -> "THEME: CHECKING…" to Crystal.Divider
            state.updateAvailable -> {
                val from = state.installed?.let { if (it.isLegacy) "PRE-2.0" else "v${it.version}" } ?: "?"
                val to = state.latest?.let { "v${it.version}" } ?: "?"
                "THEME: UPDATE AVAILABLE ($from → $to)" to Crystal.Cream
            }
            state.notice != null -> "THEME: ${state.notice}" to Crystal.Bad
            else -> {
                val v = state.installed?.let { if (it.isLegacy) "PRE-2.0" else "v${it.version}" } ?: "?"
                "THEME: UP TO DATE · $v" to Crystal.Good
            }
        }
        is ManagerState.Updating -> "THEME: UPDATING…" to Crystal.Cream
        is ManagerState.UpdateFailed -> "THEME: UPDATE FAILED" to Crystal.Bad
        is ManagerState.UpdateDone -> "THEME: UPDATED — v${state.version.version}" to Crystal.Good
        is ManagerState.RollingBack -> "THEME: ROLLING BACK…" to Crystal.Cream
        is ManagerState.RollbackDone -> "THEME: ROLLBACK COMPLETE" to Crystal.Good
        is ManagerState.RollbackFailed -> "THEME: ROLLBACK FAILED" to Crystal.Bad
    }

/**
 * Hidden diagnostics entry: 5 taps on the version label within 3
 * seconds. No visual affordance — this is a dev/support screen.
 */
@Composable
private fun VersionTapLabel(
    appVersion: String,
    onDiagnostics: () -> Unit,
) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    BasicText(
        text = "v$appVersion",
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
        ) {
            val now = SystemClock.uptimeMillis()
            taps = if (now - lastTapMs > 3000) 1 else taps + 1
            lastTapMs = now
            if (taps >= 5) {
                taps = 0
                onDiagnostics()
            }
        },
        style = TextStyle(
            fontFamily = Crystal.Mono,
            fontSize = Crystal.SmallSize,
            color = Crystal.InkDim,
        ),
    )
}
