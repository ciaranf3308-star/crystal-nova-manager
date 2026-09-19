package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.crystalnova.manager.diag.EmulatorPackageStatus

/**
 * SYSTEM: the device-management hub (u44 pivot). Rows lead to the
 * hidden Diagnostics screen, the BIOS/firmware screen, and the
 * read-only installed-emulator inventory. Nothing here configures or
 * launches games — iiSU owns the frontend.
 */
@Composable
fun SystemHubScreen(
    onDiagnostics: () -> Unit,
    onBios: () -> Unit,
    onInstalledEmulators: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "system",
        title = "SYSTEM",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "system-diagnostics",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                DimLine(
                    "DEVICE MANAGEMENT — DIAGNOSTICS, FIRMWARE, AND THE " +
                        "EMULATOR INVENTORY. iiSU OWNS GAME LAUNCHING.",
                )
            }
            control(
                key = "system-diagnostics",
                testTag = "system-diagnostics",
                label = "DIAGNOSTICS\nAPP + STORAGE + LIBRARY READOUT",
                onClick = onDiagnostics,
            )
            control(
                key = "system-bios",
                testTag = "system-bios",
                label = "BIOS / FIRMWARE\nREQUIRED FIRMWARE STATUS",
                onClick = onBios,
            )
            control(
                key = "system-emulators",
                testTag = "system-emulators",
                label = "INSTALLED EMULATORS\nREAD-ONLY PACKAGE INVENTORY",
                onClick = onInstalledEmulators,
            )
        }
    }
}

/**
 * SYSTEM → INSTALLED EMULATORS: a read-only inventory of the known
 * emulator packages and whether PackageManager sees them. Deliberately
 * informational only — iiSU manages its own emulator cores and this
 * screen never competes with that configuration.
 */
@Composable
fun InstalledEmulatorsScreen(
    packages: List<EmulatorPackageStatus>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "installed-emulators",
        title = "INSTALLED EMULATORS",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "emulators-back",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            section {
                DimLine(
                    "READ-ONLY INVENTORY — iiSU MANAGES ITS OWN EMULATOR " +
                        "CORES. ANYTHING NOT VISIBLE TO THE SYSTEM READS " +
                        "AS MISSING, NEVER AS INSTALLED.",
                )
            }
            if (packages.isEmpty()) {
                section {
                    StatusLine("NOT CHECKED YET", Crystal.InkDim)
                }
            } else {
                packages.forEach { pkg ->
                    section {
                        StatusLine(
                            "${emulatorDisplayName(pkg.packageName)}\n" +
                                if (pkg.installed) "INSTALLED" else "NOT INSTALLED",
                            if (pkg.installed) Crystal.Good else Crystal.InkDim,
                        )
                        DimLine(pkg.packageName)
                    }
                }
            }
            control(
                key = "emulators-back",
                testTag = "emulators-back",
                label = "BACK",
                onClick = onBack,
            )
        }
    }
}

/** Short human label for a known emulator package id. */
internal fun emulatorDisplayName(packageName: String): String = when (packageName) {
    "com.retroarch.aarch64" -> "RETROARCH (64-BIT)"
    "com.retroarch" -> "RETROARCH (32-BIT)"
    "xyz.aethersx2.android" -> "NETHERSX2"
    "org.ppsspp.ppsspp" -> "PPSSPP"
    "org.ppsspp.ppssppgold" -> "PPSSPP GOLD"
    "org.dolphinemu.dolphinemu" -> "DOLPHIN"
    "org.azahar_emu.azahar" -> "AZAHAR"
    "io.github.lime3ds.android" -> "LIME3DS"
    else -> packageName.uppercase()
}
