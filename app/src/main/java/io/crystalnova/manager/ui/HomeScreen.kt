package io.crystalnova.manager.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.crystalnova.manager.updater.AppUpdateState

/**
 * HOME state for the control-centre era (u44 pivot). The Manager no
 * longer builds a frontend — iiSU is the frontend, Pegasus is
 * legacy/fallback, the Crystal Launcher is frozen — so HOME reports
 * the companion state that matters:
 *
 * - iiSU installed/version (PackageManager, never invented)
 * - the active Crystal iiSU pack (placeholder: none installed yet)
 * - the thin ROM inventory (from the discovered library, never the
 *   scraper/artwork index)
 */
data class HomeStatus(
    val systemCount: Int,
    val totalGames: Int,
    val romReady: Boolean,
    val iisuInstalled: Boolean,
    /** iiSU versionName, e.g. "0.0.7.4" — null when not installed. */
    val iisuVersion: String?,
    /** Active Crystal pack name — null until the pack library lands. */
    val packName: String?,
    /** Active Crystal pack version — null until the pack library lands. */
    val packVersion: String?,
)

/**
 * Builds HOME's status from the discovered ROM library counts, the
 * iiSU package check, and the (currently empty) installed-pack list.
 * Everything is derived from real device state — never invented.
 */
fun buildHomeStatus(
    systems: List<Pair<String, Int>>,
    romReady: Boolean,
    iisuInstalled: Boolean,
    iisuVersion: String?,
    packName: String? = null,
    packVersion: String? = null,
): HomeStatus = HomeStatus(
    systemCount = systems.size,
    totalGames = systems.sumOf { it.second },
    romReady = romReady,
    iisuInstalled = iisuInstalled,
    iisuVersion = iisuVersion,
    packName = packName,
    packVersion = packVersion,
)

/**
 * The exact HOME status strings, as rendered by HomeScreen. Extracted
 * so the wiring tests pin what the user actually reads — the
 * composition below must call these rather than re-templating.
 */
fun homeIisuLine(s: HomeStatus): String = when {
    s.iisuInstalled && s.iisuVersion != null -> "iiSU v${s.iisuVersion} · INSTALLED"
    s.iisuInstalled -> "iiSU · INSTALLED"
    else -> "iiSU NOT INSTALLED"
}

fun homePackLine(s: HomeStatus): String =
    if (s.packName != null) "CRYSTAL PACK: ${s.packName.uppercase()} v${s.packVersion ?: "?"}"
    else "CRYSTAL PACK: NONE INSTALLED"

fun homeRomsLine(s: HomeStatus): String = when {
    !s.romReady -> "NO LIBRARY — PICK YOUR ROMS FOLDER"
    s.systemCount > 0 -> "${s.systemCount} SYSTEMS · ${s.totalGames} GAMES"
    else -> "LIBRARY EMPTY — SCAN YOUR ROMS"
}

/**
 * HOME: a fixed single-screen appliance dashboard — NO scrolling.
 * Everything visible at once on the 1280×960 Nova viewport:
 *
 * - header: CRYSTAL NOVA + version (5-tap opens DIAGNOSTICS)
 * - status band: CRYSTAL/NOVA branding plus the three honest lines
 *   (iiSU status, Crystal pack, ROM inventory) on the left; the
 *   LAUNCH iiSU action docked on the right (disabled when iiSU is
 *   not installed).
 * - manager-app update banner when an update is available (never buried)
 * - consolidated actions: THEME / ASSETS / ROMS / SYSTEM / SETTINGS —
 *   every destination visible at once in one row, no expander, no
 *   hidden taps
 * - pinned footer: A SELECT · B EXIT (from the scaffold)
 *
 * Everyday use never needs Diagnostics (still 5-tap hidden).
 */
@Composable
fun HomeScreen(
    status: HomeStatus,
    appVersion: String,
    appUpdate: AppUpdateState,
    onUpdateApp: () -> Unit,
    onLaunchIisu: () -> Unit,
    onTheme: () -> Unit,
    onAssets: () -> Unit,
    onRoms: () -> Unit,
    onSystem: () -> Unit,
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
        fallbackFocusKey = if (appUpdate is AppUpdateState.Available) "home-update-app" else "home-launch-iisu",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
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
            // The status band: branding + the three honest lines on the
            // left, LAUNCH iiSU docked on the right. Content-sized and
            // top-anchored — it never steals the screen from the actions.
            StatusBand(
                status = status,
                onLaunchIisu = onLaunchIisu,
                dispatcher = dispatcher,
                isLaunchInitialFocus = isInitialFocus("home-launch-iisu"),
            )
            // Manager-app self-update: surfaced HERE, never buried.
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
            // Consolidated actions: every destination visible at once in
            // one row of chunky tiles — no expander, no hidden taps.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CrystalButton(
                    key = "home-theme",
                    testTag = "home-theme",
                    label = "THEME",
                    subLabel = "CRYSTAL iiSU PACKS",
                    onClick = onTheme,
                    dispatcher = dispatcher,
                    requestInitialFocus = isInitialFocus("home-theme"),
                    modifier = Modifier.weight(1f).height(128.dp),
                )
                CrystalButton(
                    key = "home-assets",
                    testTag = "home-assets",
                    label = "ASSETS",
                    subLabel = "ARTWORK LIBRARY",
                    onClick = onAssets,
                    dispatcher = dispatcher,
                    requestInitialFocus = isInitialFocus("home-assets"),
                    modifier = Modifier.weight(1f).height(128.dp),
                )
                CrystalButton(
                    key = "home-roms",
                    testTag = "home-roms",
                    label = "ROMS",
                    subLabel = homeRomsLine(status),
                    onClick = onRoms,
                    dispatcher = dispatcher,
                    requestInitialFocus = isInitialFocus("home-roms"),
                    modifier = Modifier.weight(1f).height(128.dp),
                )
                CrystalButton(
                    key = "home-system",
                    testTag = "home-system",
                    label = "SYSTEM",
                    subLabel = "DEVICE MANAGEMENT",
                    onClick = onSystem,
                    dispatcher = dispatcher,
                    requestInitialFocus = isInitialFocus("home-system"),
                    modifier = Modifier.weight(1f).height(128.dp),
                )
                CrystalButton(
                    key = "home-settings",
                    testTag = "home-settings",
                    label = "SETTINGS",
                    subLabel = "MANAGER",
                    onClick = onSettings,
                    dispatcher = dispatcher,
                    requestInitialFocus = isInitialFocus("home-settings"),
                    modifier = Modifier.weight(1f).height(128.dp),
                )
            }
        }
    }
}

/**
 * The home hero: stacked CRYSTAL / NOVA branding with a crystalline
 * gradient, the three honest status lines below, and LAUNCH iiSU
 * docked on the right. The Manager is a companion control centre —
 * it never launches games itself.
 */
@Composable
private fun StatusBand(
    status: HomeStatus,
    onLaunchIisu: () -> Unit,
    dispatcher: FocusDispatcher,
    isLaunchInitialFocus: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0A0E27),
                        Color(0xFF0F1B3D),
                        Color(0xFF1A2B5C),
                    ),
                ),
            )
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Stacked CRYSTAL / NOVA hero branding.
                BasicText(
                    text = "CRYSTAL",
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp,
                        letterSpacing = 2.sp,
                        color = Color(0xFF7DD3FC),
                    ),
                )
                BasicText(
                    text = "NOVA",
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp,
                        letterSpacing = 8.sp,
                        color = Color(0xFF7DD3FC),
                    ),
                )
                // The three honest lines: iiSU, pack, ROM inventory.
                BasicText(
                    text = homeIisuLine(status),
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = if (status.iisuInstalled) Crystal.Good else Crystal.Joystick,
                    ),
                )
                BasicText(
                    text = homePackLine(status),
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontSize = Crystal.BodySize,
                        color = Crystal.InkDim,
                    ),
                )
                BasicText(
                    text = homeRomsLine(status),
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontSize = Crystal.BodySize,
                        color = Crystal.InkDim,
                    ),
                )
            }
            Column(
                modifier = Modifier.width(340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CrystalButton(
                    key = "home-launch-iisu",
                    testTag = "home-launch-iisu",
                    label = "LAUNCH iiSU",
                    onClick = onLaunchIisu,
                    dispatcher = dispatcher,
                    enabled = status.iisuInstalled,
                    requestInitialFocus = isLaunchInitialFocus,
                    modifier = Modifier.height(84.dp),
                )
                if (!status.iisuInstalled) {
                    BasicText(
                        text = "iiSU IS THE NOVA'S FRONTEND — INSTALL IT TO PLAY.",
                        style = TextStyle(
                            fontFamily = Crystal.Mono,
                            fontSize = Crystal.SmallSize,
                            color = Crystal.InkDim,
                        ),
                    )
                }
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
