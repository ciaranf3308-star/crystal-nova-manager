package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
 * PEGASUS SETUP: the config-folder grant, per-system launcher status
 * for systems that have games, the explicit INJECT / REFRESH action,
 * and OPEN PEGASUS. Never fakes configuration: unconfigured systems
 * are listed as NOT CONFIGURED, uninstalled emulator apps as
 * NOT INSTALLED, and injection stays disabled until every system with
 * games has a launcher — a partial library is never emitted silently.
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
    onConfigureLaunchers: () -> Unit,
    onInject: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenPegasus: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "pegasus-setup",
        title = "PEGASUS SETUP",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "pegasus-pick-config",
    ) {
        // This screen's single scroll container. Previously this was a
        // plain Column with no scroll at all — everything below the
        // fold was unreachable by touch and invisible to D-pad focus.
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                CrystalPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusLine("CONFIG FOLDER")
                        StatusLine(
                            configStatus,
                            if (configReady) Crystal.Good else Crystal.Bad,
                        )
                        DimLine(
                            "SELECT THE pegasus-frontend CONFIG FOLDER ON THIS DEVICE. " +
                                "THE MANAGER WRITES ONE FILE THERE: " +
                                "metafiles/crystal-nova.metadata.pegasus.txt — NOTHING ELSE IS TOUCHED.",
                        )
                        DimLine(
                            "NOTE: SOME EMULATORS NEED YOU TO OPEN THE EMULATOR ITSELF " +
                                "AND GRANT IT FOLDER ACCESS. THE MANAGER CANNOT GRANT " +
                                "ANOTHER APP'S PERMISSION.",
                        )
                    }
                }
            }
            control(
                key = "pegasus-pick-config",
                label = "SELECT PEGASUS FOLDER",
                onClick = onPickConfig,
            )

            section {
                SectionLabel("LAUNCHERS")
                val withGames = systems.filter { it.gameCount > 0 }
                if (withGames.isEmpty()) {
                    DimLine("NO GAMES SCANNED YET — SELECT YOUR ROM LIBRARY IN SETTINGS FIRST.")
                } else {
                    withGames.forEach { row ->
                        val state = buildString {
                            append(row.launcherStatus)
                            if (!row.launcherInstalled) append(" · APP NOT INSTALLED")
                            else if (row.isDefault && row.launcherStatus != "NOT CONFIGURED") append(" · DEFAULT")
                        }
                        StatusLine(
                            "${row.label.uppercase()} · ${row.gameCount} GAMES · $state",
                            if (row.launcherStatus == "NOT CONFIGURED" || !row.launcherInstalled) Crystal.Bad else Crystal.Ink,
                        )
                    }
                }
            }
            control(
                key = "pegasus-configure",
                label = "CONFIGURE LAUNCHERS",
                onClick = onConfigureLaunchers,
            )

            injectWarning?.let { section { StatusLine(it, Crystal.Bad) } }
            control(
                key = "pegasus-inject",
                label = if (injecting) "INJECTING…" else "INJECT / REFRESH PEGASUS LIBRARY",
                onClick = onInject,
                enabled = injectEnabled,
            )
            notice?.let { section { notice(it, onDismissNotice) } }

            section { SectionLabel("PEGASUS") }
            if (pegasusInstalled) {
                control(
                    key = "pegasus-open",
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
