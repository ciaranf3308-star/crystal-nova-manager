package io.crystalnova.manager.ui

import androidx.compose.runtime.mutableStateListOf

/**
 * App destinations (u50: THE STRIP). The manager is a single-screen
 * appliance now: HOME is the Crystal ES-DE theme updater + the
 * manager self-update route. B on HOME exits the app.
 *
 * The Navigator keeps its stack shape in case destinations return
 * later, but there is exactly one destination to navigate to.
 */
sealed interface Dest {
    data object Home : Dest
}

/**
 * Pure navigation logic, Compose-agnostic except for the observable
 * stack state (a SnapshotStateList so composables recompose on
 * navigation). Held by MainActivity.
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
