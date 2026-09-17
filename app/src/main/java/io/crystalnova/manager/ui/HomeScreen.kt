package io.crystalnova.manager.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
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
import io.crystalnova.manager.storage.LocationState
import io.crystalnova.manager.updater.AppUpdateState

/**
 * HOME: a fixed single-screen dashboard — NO scrolling. Everything
 * visible at once on the 1280×960 Nova viewport:
 *
 * - header: CRYSTAL NOVA + version (5-tap opens DIAGNOSTICS)
 * - 2×2 grid: LIBRARY / PEGASUS / THEME / SETTINGS
 * - manager-app update banner when an update is available (never buried)
 * - one-line status strip: ROM ✓ MEDIA ✓ PEGASUS ✓
 * - pinned footer: A SELECT · B EXIT
 *
 * The grid is this screen's single (non-scrolling) controller
 * container; the banner button is a plain focusable below it.
 */
@Composable
fun HomeScreen(
    scraperState: ScraperUiState,
    appVersion: String,
    appUpdate: AppUpdateState,
    pegasusReady: Boolean,
    onUpdateApp: () -> Unit,
    onLibrary: () -> Unit,
    onTheme: () -> Unit,
    onPegasusSetup: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "home",
        title = "HOME",
        onBack = onExit,
        modifier = modifier,
        isHome = true,
        showMasthead = false,
        fallbackFocusKey = if (appUpdate is AppUpdateState.Available) "home-update-app" else "home-library",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = "CRYSTAL NOVA",
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = Crystal.TitleSize,
                        color = Crystal.Ink,
                    ),
                )
                VersionTapLabel(appVersion = appVersion, onDiagnostics = onDiagnostics)
            }
            // The 2×2 destination grid owns the middle of the screen.
            // Tiles are fixed-height so all four destinations are always
            // visible simultaneously; the grid never scrolls here.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                ControllerGrid(
                    state = gridState,
                    dispatcher = dispatcher,
                    columns = GridCells.Fixed(2),
                    initialFocus = ::isInitialFocus,
                    // Rows center in the leftover space so the 2×2 block
                    // sits mid-screen instead of clinging to the top.
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    control(
                        key = "home-library",
                        testTag = "home-library",
                        label = "LIBRARY",
                        onClick = onLibrary,
                        modifier = Modifier.height(280.dp),
                    )
                    control(
                        key = "home-pegasus",
                        testTag = "home-pegasus",
                        label = "PEGASUS",
                        onClick = onPegasusSetup,
                        modifier = Modifier.height(280.dp),
                    )
                    control(
                        key = "home-theme",
                        testTag = "home-theme",
                        label = "THEME",
                        onClick = onTheme,
                        modifier = Modifier.height(280.dp),
                    )
                    control(
                        key = "home-settings",
                        testTag = "home-settings",
                        label = "SETTINGS",
                        onClick = onSettings,
                        modifier = Modifier.height(280.dp),
                    )
                }
            }
            // Manager-app self-update: surfaced HERE, never buried.
            // (The theme updater lives on the THEME screen only.)
            when (val u = appUpdate) {
                is AppUpdateState.Available -> UpdateBanner(
                    text = "MANAGER v${u.info.version} AVAILABLE",
                    buttonLabel = "UPDATE APP",
                    onClick = onUpdateApp,
                    dispatcher = dispatcher,
                    isInitialFocus = isInitialFocus("home-update-app"),
                )
                is AppUpdateState.Downloaded -> UpdateBanner(
                    text = "APP UPDATE READY",
                    buttonLabel = "INSTALL APP UPDATE",
                    onClick = onUpdateApp,
                    dispatcher = dispatcher,
                    isInitialFocus = isInitialFocus("home-update-app"),
                )
                is AppUpdateState.Downloading -> {
                    val pct = u.progress?.let { " — ${(it * 100).toInt()}%" } ?: ""
                    StatusLine("DOWNLOADING APP UPDATE$pct", Crystal.Divider)
                }
                is AppUpdateState.Checking ->
                    StatusLine("CHECKING FOR APP UPDATES…", Crystal.Divider)
                is AppUpdateState.Failed -> UpdateBanner(
                    text = "APP UPDATE FAILED — ${u.message}",
                    buttonLabel = "RETRY",
                    onClick = onUpdateApp,
                    dispatcher = dispatcher,
                    isInitialFocus = isInitialFocus("home-update-app"),
                )
                is AppUpdateState.Idle ->
                    if (u.lastCheckFailed) {
                        UpdateBanner(
                            text = "APP UPDATE CHECK FAILED",
                            buttonLabel = "RETRY",
                            onClick = onUpdateApp,
                            dispatcher = dispatcher,
                            isInitialFocus = isInitialFocus("home-update-app"),
                        )
                    }
                is AppUpdateState.Installing ->
                    StatusLine("INSTALLING — FOLLOW THE SYSTEM PROMPT", Crystal.Divider)
            }
            StatusStrip(
                romReady = scraperState.romLocation is LocationState.Ready,
                mediaReady = scraperState.mediaLocation is LocationState.Ready,
                pegasusReady = pegasusReady,
            )
        }
    }
}

/** Compact banner row: status text + one action button. */
@Composable
private fun UpdateBanner(
    text: String,
    buttonLabel: String,
    onClick: () -> Unit,
    dispatcher: FocusDispatcher,
    isInitialFocus: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            StatusLine(text, Crystal.Joystick)
        }
        Box(modifier = Modifier.weight(1f)) {
            CrystalButton(
                key = "home-update-app",
                testTag = "home-update-app",
                label = buttonLabel,
                onClick = onClick,
                dispatcher = dispatcher,
                requestInitialFocus = isInitialFocus,
            )
        }
    }
}

/** One-line readiness strip: ROM ✓ MEDIA ✓ PEGASUS ✓. */
@Composable
private fun StatusStrip(romReady: Boolean, mediaReady: Boolean, pegasusReady: Boolean) {
    fun tick(ok: Boolean) = if (ok) "✓" else "—"
    val allOk = romReady && mediaReady && pegasusReady
    StatusLine(
        "ROM ${tick(romReady)}   MEDIA ${tick(mediaReady)}   PEGASUS ${tick(pegasusReady)}",
        if (allOk) Crystal.Good else Crystal.Ink,
    )
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
