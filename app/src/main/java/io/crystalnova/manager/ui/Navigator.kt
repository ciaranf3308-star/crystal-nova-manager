package io.crystalnova.manager.ui

import androidx.compose.runtime.mutableStateListOf

/**
 * App destinations. The manager is a small set of focused screens on a
 * simple back stack:
 *
 *   HOME → LIBRARY → SYSTEM(slug) → PROGRESS
 *   HOME → THEME
 *   HOME → SETTINGS
 *   any → DIAGNOSTICS (overlay destination; B pops back)
 *
 * Controller B pops exactly one level from any child destination and
 * never exits; only B on HOME exits the app.
 */
sealed interface Dest {
    data object Home : Dest
    data object Library : Dest
    data class System(val slug: String, val label: String) : Dest
    data object Progress : Dest
    data object Theme : Dest
    data object Settings : Dest
    data object Diagnostics : Dest
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
