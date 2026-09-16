package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val dispatcher = remember { FocusDispatcher() }
    ScreenRoot(onBack = onBack, dispatcher = dispatcher, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CrystalHeader()
            SectionLabel("PEGASUS SETUP")

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
            CrystalButton(
                key = "pegasus-pick-config",
                label = "SELECT PEGASUS FOLDER",
                onClick = onPickConfig,
                dispatcher = dispatcher,
                requestInitialFocus = true,
            )

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
            CrystalButton(
                key = "pegasus-configure",
                label = "CONFIGURE LAUNCHERS",
                onClick = onConfigureLaunchers,
                dispatcher = dispatcher,
            )

            injectWarning?.let { StatusLine(it, Crystal.Bad) }
            CrystalButton(
                key = "pegasus-inject",
                label = if (injecting) "INJECTING…" else "INJECT / REFRESH PEGASUS LIBRARY",
                onClick = onInject,
                dispatcher = dispatcher,
                enabled = injectEnabled,
            )
            notice?.let { NoticeBlock(it, onDismissNotice, dispatcher) }

            SectionLabel("PEGASUS")
            if (pegasusInstalled) {
                CrystalButton(
                    key = "pegasus-open",
                    label = "OPEN PEGASUS",
                    onClick = onOpenPegasus,
                    dispatcher = dispatcher,
                )
            } else {
                DimLine("PEGASUS NOT INSTALLED — INJECT ANYWAY, THEN INSTALL PEGASUS TO USE IT.")
            }

            Spacer(Modifier.weight(1f))
            BackFooter()
        }
    }
}
