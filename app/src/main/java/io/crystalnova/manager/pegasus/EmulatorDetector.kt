package io.crystalnova.manager.pegasus

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Detects which known emulator packages are installed via
 * PackageManager. The manifest `<queries>` block keeps every
 * [LauncherPresets.knownEmulatorPackages] entry visible on Android
 * 11+; anything not visible reads as missing, never as installed.
 *
 * NameNotFoundException (and any other lookup failure) means MISSING —
 * detection never throws, so the setup dashboard can always render.
 */
class EmulatorDetector(private val context: Context) {

    fun isInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    /** Installed subset of [LauncherPresets.knownEmulatorPackages]. */
    fun installedPackages(): Set<String> =
        LauncherPresets.knownEmulatorPackages.filterTo(mutableSetOf(), ::isInstalled)

    /**
     * Every known emulator candidate with its install state, in
     * [LauncherPresets.knownEmulatorPackages] order. Surfaced in the
     * hidden Diagnostics screen so a mismatch between "RetroArch is
     * installed" and "APP MISSING" can be traced to the exact package
     * ID the device reports.
     */
    fun detectionReport(): List<Pair<String, Boolean>> =
        LauncherPresets.knownEmulatorPackages.map { it to isInstalled(it) }
}
