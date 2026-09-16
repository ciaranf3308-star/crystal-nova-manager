package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * LAUNCHERS: every recognized system, its game count, and its current
 * launcher status (curated default, your choice, or NOT CONFIGURED).
 * Selecting a system opens its launcher picker. Systems with no games
 * can still be pre-configured; injection only emits systems that have
 * games AND a configured launcher.
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
        fallbackFocusKey = systems.firstOrNull()?.let { "pegasus-sys-${it.slug}" },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DimLine(
                "ONE LAUNCHER PER SYSTEM. RETROARCH + CORE, A VERIFIED STANDALONE " +
                    "EMULATOR, OR YOUR OWN COMMAND. NOTHING IS CHOSEN FOR YOU.",
            )
            // The grid is this screen's single scroll container.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                ControllerGrid(
                    state = gridState,
                    dispatcher = dispatcher,
                    columns = GridCells.Fixed(3),
                    initialFocus = ::isInitialFocus,
                ) {
                    systems.forEach { sys ->
                        val state = buildString {
                            append(sys.launcherStatus)
                            if (!sys.launcherInstalled) append(" · APP MISSING")
                        }
                        control(
                            key = "pegasus-sys-${sys.slug}",
                            label = "${sys.label.uppercase()}\n${sys.gameCount} GAMES\n$state",
                            onClick = { onSelectSystem(sys.slug, sys.label) },
                        )
                    }
                }
            }
        }
    }
}
