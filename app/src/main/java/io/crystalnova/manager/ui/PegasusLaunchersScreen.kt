package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * LAUNCHERS: every recognized system as one compact row — label, game
 * count, and current launcher status (curated default, your choice, or
 * NOT CONFIGURED). The focused row opens that system's launcher
 * picker. Systems with no games can still be pre-configured;
 * injection only emits systems that have games AND a configured
 * launcher.
 */
@Composable
fun PegasusLaunchersScreen(
    systems: List<PegasusSystemRow>,
    onSelectSystem: (slug: String, label: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "pegasus-launchers",
        title = "LAUNCHERS",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = systems.firstOrNull()?.let { "launcher-row-${it.slug}" },
    ) {
        // The controller-aware list is this screen's single scroll
        // container: D-pad focus on any row scrolls it comfortably into
        // view.
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            systems.forEach { sys ->
                val state = buildString {
                    append(sys.launcherStatus)
                    if (!sys.launcherInstalled) append(" · APP MISSING")
                    else if (sys.isDefault && sys.launcherStatus != "NOT CONFIGURED") append(" · DEFAULT")
                }
                control(
                    key = "launcher-row-${sys.slug}",
                    testTag = "launcher-row-${sys.slug}",
                    label = "${sys.label.uppercase()} · ${sys.gameCount} GAMES\n$state",
                    onClick = { onSelectSystem(sys.slug, sys.label) },
                )
            }
        }
    }
}
