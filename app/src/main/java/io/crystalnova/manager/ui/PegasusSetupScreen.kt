package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * One recognized system and its Pegasus launcher state, as built by
 * MainActivity from the platform registry, the last library scan, the
 * stored launcher profiles, and the installed-apps check.
 */
data class PegasusSystemRow(
    val slug: String,
    val label: String,
    val gameCount: Int,
    /** [io.crystalnova.manager.pegasus.LauncherProfile.displayLabel], or "NOT CONFIGURED". */
    val launcherStatus: String,
    /** False when the profile names an emulator package that isn't installed. CUSTOM is always true. */
    val launcherInstalled: Boolean,
    /** True when the status comes from the curated default rather than an explicit user choice. */
    val isDefault: Boolean,
)

/**
 * PEGASUS SETUP: a compact summary, not an endless page. Three status
 * rows (config / library / launchers), then the actions: REFRESH
 * LIBRARY, CONFIGURE LAUNCHERS, INJECT / REFRESH, OPEN PEGASUS.
 * Per-system launcher configuration lives on its own list screen.
 *
 * Never fakes configuration: unconfigured systems are listed as NOT
 * CONFIGURED, uninstalled emulator apps as NOT INSTALLED, and
 * injection stays disabled until every system with games has a
 * launcher — a partial library is never emitted silently.
 */
@Composable
fun PegasusSetupScreen(
    configStatus: String,
    configReady: Boolean,
    systems: List<PegasusSystemRow>,
    injectEnabled: Boolean,
    injectWarning: String?,
    injecting: Boolean,
    notice: String?,
    pegasusInstalled: Boolean,
    onPickConfig: () -> Unit,
    onRescan: () -> Unit,
    onConfigureLaunchers: () -> Unit,
    onInject: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenPegasus: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val withGames = systems.filter { it.gameCount > 0 }
    val needSetup = withGames.count {
        it.launcherStatus == "NOT CONFIGURED" || !it.launcherInstalled
    }
    ScreenScaffold(
        routeKey = "pegasus-setup",
        title = "PEGASUS SETUP",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = if (configReady) "pegasus-setup-launchers" else "pegasus-setup-config",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SummaryRow(
                            label = "CONFIG",
                            value = if (configReady) "READY" else configStatus,
                            valueColor = if (configReady) Crystal.Good else Crystal.Bad,
                        )
                        SummaryRow(
                            label = "LIBRARY",
                            value = "${withGames.size} SYSTEMS · ${withGames.sumOf { it.gameCount }} GAMES",
                            valueColor = Crystal.Ink,
                        )
                        SummaryRow(
                            label = "LAUNCHERS",
                            value = if (needSetup == 0) "READY" else "$needSetup NEED SETUP",
                            valueColor = if (needSetup == 0) Crystal.Good else Crystal.Bad,
                        )
                    }
                }
            }
            if (!configReady) {
                control(
                    key = "pegasus-setup-config",
                    testTag = "pegasus-setup-config",
                    label = "SELECT PEGASUS FOLDER",
                    onClick = onPickConfig,
                )
            }
            control(
                key = "pegasus-setup-refresh",
                testTag = "pegasus-setup-refresh",
                label = "REFRESH LIBRARY",
                onClick = onRescan,
            )
            control(
                key = "pegasus-setup-launchers",
                testTag = "pegasus-setup-launchers",
                label = "CONFIGURE LAUNCHERS",
                onClick = onConfigureLaunchers,
            )

            injectWarning?.let { section { StatusLine(it, Crystal.Bad) } }
            control(
                key = "pegasus-setup-inject",
                testTag = "pegasus-setup-inject",
                label = if (injecting) "INJECTING…" else "INJECT / REFRESH PEGASUS LIBRARY",
                onClick = onInject,
                enabled = injectEnabled,
            )
            notice?.let { section { notice(it, onDismissNotice) } }

            if (pegasusInstalled) {
                control(
                    key = "pegasus-setup-open",
                    testTag = "pegasus-setup-open",
                    label = "OPEN PEGASUS",
                    onClick = onOpenPegasus,
                )
            } else {
                section {
                    DimLine("PEGASUS NOT INSTALLED — INJECT ANYWAY, THEN INSTALL PEGASUS TO USE IT.")
                }
            }
        }
    }
}

/** One two-column summary row: dim label left, status right. */
@Composable
private fun SummaryRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DimLine(label)
        StatusLine(value, valueColor)
    }
}
