package io.crystalnova.manager.ui

import androidx.compose.runtime.mutableStateListOf

/**
 * App destinations. The manager is the Nova's companion control
 * centre — not a frontend builder (u44 pivot: iiSU is the frontend,
 * Pegasus is legacy/fallback, the Crystal Launcher is frozen):
 *
 *   HOME → THEME (Crystal iiSU pack manager)
 *   HOME → ASSETS (artwork library placeholder)
 *   HOME → LIBRARY (thin on-demand ROM inventory)
 *   HOME → SYSTEM → { DIAGNOSTICS, BIOS, INSTALLED EMULATORS }
 *   HOME → SETTINGS → SETTINGS/ROM | SETTINGS/MEDIA | SETTINGS/ESDE-IMPORT |
 *           SETTINGS/THEMES | SETTINGS/CHANNEL | SETTINGS/APPEARANCE
 *   any → DIAGNOSTICS (overlay destination; B pops back)
 *
 * Controller B pops exactly one level from any child destination and
 * never exits; only B on HOME exits the app.
 *
 * Archived (u44): Pegasus setup/launchers (Dest.PegasusSetup,
 * Dest.PegasusLaunchers, Dest.PegasusLauncher) and the scraper
 * provider UI (Dest.System, Dest.Progress). The screens stay in the
 * codebase, unreferenced, until the new direction is proven.
 */
sealed interface Dest {
    data object Home : Dest
    /** ROMS: thin on-demand ROM inventory (no scraping, no metadata). */
    data object Library : Dest
    /** THEME: the Crystal iiSU pack manager. */
    data object Theme : Dest
    /** ASSETS: Crystal artwork library (placeholder until the catalog lands). */
    data object Assets : Dest
    /** SYSTEM: device-management hub (diagnostics, BIOS, emulators). */
    data object SystemHub : Dest
    /** SYSTEM → read-only installed-emulator inventory. */
    data object InstalledEmulators : Dest
    data object Settings : Dest
    /** ROM LIBRARY child of SETTINGS. */
    data object SettingsRom : Dest
    /** MEDIA LIBRARY child of SETTINGS. */
    data object SettingsMedia : Dest
    /** ES-DE MEDIA IMPORT child of SETTINGS (copy real art into the library). */
    data object SettingsEsdeImport : Dest
    /** MANUAL MEDIA MATCH child of ES-DE IMPORT (pair unmatched games with media). */
    data object SettingsEsdeManualMatch : Dest
    /** MEDIA HEALTH CHECK report child of ES-DE IMPORT (why games render blank). */
    data object SettingsEsdeHealthReport : Dest
    /** APPEARANCE child of SETTINGS (recolor the Crystal identity). */
    data object SettingsAppearance : Dest
    /** THEME STORAGE child of SETTINGS. */
    data object SettingsThemes : Dest
    /** UPDATE CHANNEL child of SETTINGS. */
    data object SettingsChannel : Dest
    data object Diagnostics : Dest
    /** v24: BIOS / firmware setup. */
    data object Bios : Dest
}

/**
 * Pure navigation logic, Compose-agnostic except for the observable
 * stack state (a SnapshotStateList so composables recompose on
 * navigation). Held by MainActivity; screens never mutate it directly.
 */
class Navigator {
    private val _stack = mutableStateListOf<Dest>(Dest.Home)
    val stack: List<Dest> get() = _stack
    val current: Dest get() = _stack.last()

    fun navigate(d: Dest) {
        if (_stack.lastOrNull() != d) _stack.add(d)
    }

    /**
     * Pops one level. Returns true when the app should exit (B pressed
     * on HOME — the stack is already at its root).
     */
    fun onBack(): Boolean =
        if (_stack.size > 1) {
            _stack.removeLast()
            false
        } else {
            true
        }
}
