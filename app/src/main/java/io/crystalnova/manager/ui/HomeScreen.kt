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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.crystalnova.manager.updater.AppUpdateState

/**
 * Appliance-ready HOME state (v23), derived from real persisted
 * production state only: the scanned library, the stored per-system
 * launcher profiles, and the Pegasus install check.
 *
 * READY is never invented: it requires a ROM folder that reads, games
 * scanned, Pegasus installed, and every launcher configured with its
 * emulator present. Artwork completeness never blocks READY — a game
 * launches with or without its box art.
 */
data class HomeReadiness(
    val systemCount: Int,
    val totalGames: Int,
    val configuredCount: Int,
    val issueCount: Int,
    val pegasusInstalled: Boolean,
    val romReady: Boolean,
) {
    val ready: Boolean
        get() = romReady && pegasusInstalled && systemCount > 0 && issueCount == 0
}

/**
 * Builds HOME's readiness from the authoritative discovered ROM library
 * ([PegasusSystemRow.gameCount] sums), the Pegasus install check, and the
 * ROM-location state.
 *
 * The scraper/artwork index is deliberately NOT an input: artwork
 * statistics must never gate or distort HOME's library count (e.g. an
 * unscraped library of 147 ROMs must read "147 GAMES", never "0 GAMES").
 * Artwork statistics live on the LIBRARY / ARTWORK screens only.
 */
fun buildHomeReadiness(
    rows: List<PegasusSystemRow>,
    pegasusInstalled: Boolean,
    romReady: Boolean,
): HomeReadiness {
    val withGames = rows.filter { it.gameCount > 0 }
    val issues = withGames.count {
        it.launcherStatus == "NOT CONFIGURED" || !it.launcherInstalled
    }
    return HomeReadiness(
        systemCount = withGames.size,
        totalGames = withGames.sumOf { it.gameCount },
        configuredCount = withGames.size - issues,
        issueCount = issues,
        pegasusInstalled = pegasusInstalled,
        romReady = romReady,
    )
}

/**
 * The exact HOME hero strings, as rendered by HomeScreen. Extracted so the
 * wiring tests pin what the user actually reads — the composition below
 * must call these rather than re-templating.
 */
fun homeStatsLine(r: HomeReadiness) = "${r.systemCount} SYSTEMS · ${r.totalGames} GAMES"

fun homeLauncherLine(r: HomeReadiness): String = if (r.issueCount == 0) {
    "${r.configuredCount} LAUNCHERS CONFIGURED"
} else {
    val needs = if (r.issueCount == 1) "NEEDS" else "NEED"
    "${r.configuredCount} CONFIGURED · ${r.issueCount} $needs ATTENTION"
}

fun homeLibraryLine(r: HomeReadiness): String = when {
    !r.romReady -> "NO LIBRARY — PICK YOUR ROMS FOLDER"
    !r.pegasusInstalled -> "PEGASUS NOT INSTALLED"
    r.systemCount > 0 -> "LIBRARY BUILT"
    else -> "LIBRARY EMPTY — SCAN YOUR ROMS"
}

/**
 * HOME: a fixed single-screen appliance dashboard — NO scrolling.
 * Everything visible at once on the 1280×960 Nova viewport:
 *
 * - header: CRYSTAL NOVA + version (5-tap opens DIAGNOSTICS)
 * - hero: PEGASUS / READY TO PLAY (or FINISH SETUP) with honest
 *   system/launcher/library lines and the happy-path action:
 *   OPEN PEGASUS when ready, MAKE READY (+ REVIEW ISSUES) otherwise
 * - manager-app update banner when an update is available (never buried)
 * - secondary: LIBRARY / ARTWORK
 * - Advanced > : THEME and SETTINGS live one tap away; every manual
 *   control is preserved, nothing removed
 * - pinned footer: A SELECT · B EXIT (from the scaffold)
 *
 * Everyday use never needs Diagnostics (still 5-tap hidden) and never
 * needs the setup screens when everything is READY.
 */
@Composable
fun HomeScreen(
    readiness: HomeReadiness,
    appVersion: String,
    appUpdate: AppUpdateState,
    themeSubtitle: String,
    settingsSubtitle: String,
    onUpdateApp: () -> Unit,
    onOpenPegasus: () -> Unit,
    onMakeReady: () -> Unit,
    onReviewIssues: () -> Unit,
    onLibrary: () -> Unit,
    onTheme: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    ScreenScaffold(
        routeKey = "home",
        title = "HOME",
        onBack = onExit,
        modifier = modifier,
        isHome = true,
        showMasthead = false,
        fallbackFocusKey = if (appUpdate is AppUpdateState.Available) "home-update-app" else "home-primary",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
            // The hero owns the middle of the screen: the one state that
            // matters (READY TO PLAY vs FINISH SETUP) and the happy-path
            // action. It centers in the leftover space.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Hero(
                    readiness = readiness,
                    onOpenPegasus = onOpenPegasus,
                    onMakeReady = onMakeReady,
                    onReviewIssues = onReviewIssues,
                    dispatcher = dispatcher,
                    isPrimaryInitialFocus = isInitialFocus("home-primary"),
                )
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
            // Secondary: the library (browse, rescan, scrape artwork).
            CrystalButton(
                key = "home-library",
                testTag = "home-library",
                label = "LIBRARY / ARTWORK",
                subLabel = homeStatsLine(readiness),
                onClick = onLibrary,
                dispatcher = dispatcher,
                requestInitialFocus = isInitialFocus("home-library"),
                modifier = Modifier.fillMaxWidth(),
            )
            // Advanced: every manual control, one tap away, none removed.
            CrystalButton(
                key = "home-advanced",
                testTag = "home-advanced",
                label = if (advancedExpanded) "ADVANCED  ∧" else "ADVANCED  ∨",
                onClick = { advancedExpanded = !advancedExpanded },
                dispatcher = dispatcher,
                requestInitialFocus = isInitialFocus("home-advanced"),
                modifier = Modifier.fillMaxWidth(),
            )
            if (advancedExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CrystalButton(
                            key = "home-theme",
                            testTag = "home-theme",
                            label = "THEME",
                            subLabel = themeSubtitle,
                            onClick = onTheme,
                            dispatcher = dispatcher,
                            requestInitialFocus = isInitialFocus("home-theme"),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        CrystalButton(
                            key = "home-settings",
                            testTag = "home-settings",
                            label = "SETTINGS",
                            subLabel = settingsSubtitle,
                            onClick = onSettings,
                            dispatcher = dispatcher,
                            requestInitialFocus = isInitialFocus("home-settings"),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** The hero: state headline, honest status lines, happy-path action. */
@Composable
private fun Hero(
    readiness: HomeReadiness,
    onOpenPegasus: () -> Unit,
    onMakeReady: () -> Unit,
    onReviewIssues: () -> Unit,
    dispatcher: FocusDispatcher,
    isPrimaryInitialFocus: Boolean,
) {
    val ready = readiness.ready
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(
            text = "PEGASUS",
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.SectionSize,
                color = Crystal.InkDim,
            ),
        )
        BasicText(
            text = if (ready) "READY TO PLAY" else "FINISH SETUP",
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 44.sp,
                color = if (ready) Crystal.Good else Crystal.Joystick,
            ),
        )
        BasicText(
            text = homeStatsLine(readiness),
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.SectionSize,
                color = Crystal.Ink,
            ),
        )
        if (readiness.systemCount > 0) {
            val launcherLine = homeLauncherLine(readiness)
            BasicText(
                text = launcherLine,
                style = TextStyle(
                    fontFamily = Crystal.Mono,
                    fontSize = Crystal.SectionSize,
                    color = if (readiness.issueCount == 0) Crystal.Ink else Crystal.Joystick,
                ),
            )
        }
        val libraryLine = homeLibraryLine(readiness)
        BasicText(
            text = libraryLine,
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.BodySize,
                color = Crystal.InkDim,
            ),
        )
        if (ready) {
            CrystalButton(
                key = "home-primary",
                testTag = "home-primary",
                label = "OPEN PEGASUS",
                onClick = onOpenPegasus,
                dispatcher = dispatcher,
                enabled = readiness.pegasusInstalled,
                requestInitialFocus = isPrimaryInitialFocus,
                modifier = Modifier.height(64.dp),
            )
        } else {
            CrystalButton(
                key = "home-primary",
                testTag = "home-primary",
                label = "MAKE READY",
                onClick = onMakeReady,
                dispatcher = dispatcher,
                requestInitialFocus = isPrimaryInitialFocus,
                modifier = Modifier.height(64.dp),
            )
            if (readiness.issueCount > 0) {
                val s = if (readiness.issueCount == 1) "" else "S"
                CrystalButton(
                    key = "home-review",
                    testTag = "home-review",
                    label = "REVIEW ${readiness.issueCount} ISSUE$s",
                    onClick = onReviewIssues,
                    dispatcher = dispatcher,
                    modifier = Modifier.height(56.dp),
                )
            }
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
