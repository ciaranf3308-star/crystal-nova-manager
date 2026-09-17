package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.crystalnova.manager.pegasus.LauncherSource

/**
 * LAUNCHERS: systems with games as compact single-line rows —
 * label, game count, launcher, and a glanceable READY / NOT
 * CONFIGURED / APP MISSING status. The focused row opens that
 * system's launcher picker (the advanced manual override).
 *
 * Zero-game systems are hidden by default behind SHOW ALL: injection
 * only emits systems that have games AND a configured launcher, so
 * they are noise in the normal journey. D-pad focus on any row
 * scrolls it into view via the controller list.
 */
@Composable
fun PegasusLaunchersScreen(
    systems: List<PegasusSystemRow>,
    onSelectSystem: (slug: String, label: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAll by remember { mutableStateOf(false) }
    val withGames = systems.filter { it.gameCount > 0 }
    val visible = if (showAll) systems else withGames
    val hiddenCount = systems.size - withGames.size
    ScreenScaffold(
        routeKey = "pegasus-launchers",
        title = "LAUNCHERS",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = visible.firstOrNull()?.let { "launcher-row-${it.slug}" },
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            visible.forEach { sys ->
                val state = when {
                    sys.launcherStatus == "NOT CONFIGURED" -> "NOT CONFIGURED"
                    !sys.launcherInstalled -> "APP MISSING"
                    sys.source == LauncherSource.AUTO -> "READY · AUTO"
                    sys.isDefault -> "READY · DEFAULT"
                    else -> "READY"
                }
                val launcher = sys.profile?.shortLabel() ?: "—"
                control(
                    key = "launcher-row-${sys.slug}",
                    testTag = "launcher-row-${sys.slug}",
                    label = "${sys.label.uppercase()} · ${sys.gameCount} GAMES · " +
                        "$launcher · $state",
                    onClick = { onSelectSystem(sys.slug, sys.label) },
                )
            }
            if (!showAll && hiddenCount > 0) {
                control(
                    key = "launchers-show-all",
                    testTag = "launchers-show-all",
                    label = "SHOW ALL ($hiddenCount WITHOUT GAMES)",
                    onClick = { showAll = true },
                )
            }
            if (showAll && hiddenCount > 0) {
                control(
                    key = "launchers-hide-empty",
                    testTag = "launchers-hide-empty",
                    label = "HIDE SYSTEMS WITHOUT GAMES",
                    onClick = { showAll = false },
                )
            }
        }
    }
}
