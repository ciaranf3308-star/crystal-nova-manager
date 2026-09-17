package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.crystalnova.manager.pegasus.LauncherProfile
import io.crystalnova.manager.pegasus.LauncherSource

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
    /** The effective profile in force, null when NOT CONFIGURED. */
    val profile: LauncherProfile? = null,
    /** How the stored choice came to be (USER manual pick vs AUTO assistant). */
    val source: LauncherSource = LauncherSource.USER,
)

/**
 * PEGASUS SETUP: a compact status dashboard, not an endless page.
 * Three status rows (games / emulators / pegasus config), then the
 * actions with the setup assistant as the obvious primary:
 * AUTO-CONFIGURE, REVIEW n ISSUES, REFRESH LIBRARY, BUILD PEGASUS
 * LIBRARY, OPEN PEGASUS. Per-system launcher configuration lives on
 * its own list screen as the advanced manual override.
 *
 * Never fakes configuration: unconfigured systems are listed as NOT
 * CONFIGURED, uninstalled emulator apps as NOT INSTALLED, and
 * injection stays partial — configured systems inject, the rest skip
 * and are reported, never silently.
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
    autoConfiguring: Boolean = false,
    onAutoConfigure: () -> Unit = {},
) {
    val withGames = systems.filter { it.gameCount > 0 }
    val needSetup = withGames.count {
        it.launcherStatus == "NOT CONFIGURED" || !it.launcherInstalled
    }
    val readyCount = withGames.size - needSetup
    val totalGames = withGames.sumOf { it.gameCount }
    ScreenScaffold(
        routeKey = "pegasus-setup",
        title = "PEGASUS SETUP",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = when {
            !configReady -> "pegasus-setup-config"
            needSetup > 0 -> "pegasus-setup-autoconfigure"
            else -> "pegasus-setup-inject"
        },
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
                            label = "GAMES",
                            value = "${withGames.size} SYSTEMS · ${"%,d".format(totalGames)} GAMES",
                            valueColor = Crystal.Ink,
                        )
                        SummaryRow(
                            label = "EMULATORS",
                            value = if (needSetup == 0) {
                                if (readyCount == 0) "NO GAMES SCANNED" else "$readyCount READY"
                            } else {
                                "$readyCount READY · $needSetup NEED ATTENTION"
                            },
                            valueColor = if (needSetup == 0) Crystal.Good else Crystal.Bad,
                        )
                        SummaryRow(
                            label = "PEGASUS",
                            value = if (configReady) "CONFIG READY" else configStatus,
                            valueColor = if (configReady) Crystal.Good else Crystal.Bad,
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
                key = "pegasus-setup-autoconfigure",
                testTag = "pegasus-setup-autoconfigure",
                label = if (autoConfiguring) "AUTO-CONFIGURING…" else "AUTO-CONFIGURE",
                onClick = onAutoConfigure,
                enabled = needSetup > 0 && !autoConfiguring,
            )
            if (needSetup > 0) {
                control(
                    key = "pegasus-setup-launchers",
                    testTag = "pegasus-setup-launchers",
                    label = "REVIEW $needSetup ISSUES",
                    onClick = onConfigureLaunchers,
                )
            }
            control(
                key = "pegasus-setup-refresh",
                testTag = "pegasus-setup-refresh",
                label = "REFRESH LIBRARY",
                onClick = onRescan,
            )

            injectWarning?.let { section { StatusLine(it, Crystal.Bad) } }
            control(
                key = "pegasus-setup-inject",
                testTag = "pegasus-setup-inject",
                label = if (injecting) "INJECTING…" else "BUILD PEGASUS LIBRARY",
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
