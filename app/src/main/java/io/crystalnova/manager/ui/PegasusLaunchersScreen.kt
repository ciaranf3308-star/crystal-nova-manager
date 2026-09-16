package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val dispatcher = remember { FocusDispatcher() }
    ScreenRoot(onBack = onBack, dispatcher = dispatcher, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("LAUNCHERS")
            DimLine(
                "ONE LAUNCHER PER SYSTEM. RETROARCH + CORE, A VERIFIED STANDALONE " +
                    "EMULATOR, OR YOUR OWN COMMAND. NOTHING IS CHOSEN FOR YOU.",
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = systems,
                    key = { sys -> "pegasus-${sys.slug}" },
                ) { sys ->
                    val state = buildString {
                        append(sys.launcherStatus)
                        if (!sys.launcherInstalled) append(" · APP MISSING")
                    }
                    CrystalButton(
                        key = "pegasus-sys-${sys.slug}",
                        label = "${sys.label.uppercase()}\n${sys.gameCount} GAMES\n$state",
                        onClick = { onSelectSystem(sys.slug, sys.label) },
                        dispatcher = dispatcher,
                        requestInitialFocus = sys == systems.firstOrNull(),
                    )
                }
            }
            BackFooter()
        }
    }
}
