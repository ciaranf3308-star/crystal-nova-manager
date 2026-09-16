package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.annotation.VisibleForTesting

/**
 * The single screen scaffold every destination goes through.
 *
 * Each screen gets:
 * - the Crystal background + masthead + section title (compact Nova
 *   density: 16dp horizontal / 12dp vertical padding — the old
 *   48/32dp extravagance is gone),
 * - a content slot with one sensible control holding initial focus,
 * - per-route focus memory: the last-focused control key is recorded
 *   when focus moves and restored when returning to the route,
 * - a pinned footer hint bar (`A SELECT · B BACK`, `B EXIT` on HOME),
 * - centralized controller-key handling: B pops exactly one level
 *   everywhere ([controllerKeys]), A activates the focused control via
 *   the screen-owned [FocusDispatcher].
 *
 * The scaffold owns the [FocusDispatcher], the [ControllerListState]
 * and the [ControllerGridState]; screens no longer create their own
 * dispatchers or wire focus hacks per screen.
 *
 * ## API for screens (worker B)
 *
 * ```
 * ScreenScaffold(
 *     routeKey = "settings",          // stable per destination; drives focus memory
 *     title = "SETTINGS",             // section title under the masthead
 *     onBack = onBack,                // B key → exactly one level
 *     isHome = true,                  // footer reads B EXIT instead of B BACK
 *     showMasthead = false,           // drop the CRYSTAL NOVA header if a screen owns its own
 *     titleTrailing = { ... },        // optional composable right of the title
 *     footerLabel = "BACK",           // custom B legend, e.g. "BACK (SCRAPE KEEPS RUNNING)"
 *     passThroughAWhen = { editing }, // let A fall through (text fields)
 *     fallbackFocusKey = "some-key",  // initial focus when no remembered key exists
 * ) {
 *     // this: ScaffoldContentScope
 *     ControllerList(state = listState, dispatcher = dispatcher,
 *                    initialFocus = ::isInitialFocus) {
 *         section { StatusLine("STATIC") }
 *         control(key = "do-thing", label = "DO THING", onClick = onDoThing)
 *         section {
 *             val s = this
 *             CrystalPanel(Modifier.fillMaxWidth()) {
 *                 Column(...) { s.control(key = "nested", ...) }
 *             }
 *         }
 *     }
 *     // or a plain Column for short screens; or ControllerGrid for card grids:
 *     // ControllerGrid(state = gridState, dispatcher = dispatcher,
 *     //                columns = GridCells.Fixed(3), initialFocus = ::isInitialFocus) {
 *     //     control(key = "card-x", label = "X", onClick = { ... })
 *     // }
 * }
 * ```
 *
 * Rules: one scroll container per screen (a [ControllerList], a
 * [ControllerGrid], or none for short screens) — never nest them.
 * Every focusable must be a `control(...)` (or carry
 * `Modifier.controllerScrollItem`) so D-pad focus always lands
 * comfortably in view.
 */

/**
 * Process-lifetime map of route key → last focused control key.
 * Written on every focus change via the dispatcher's listener, read
 * once per screen entry to restore focus.
 */
private val lastFocusByRoute = mutableMapOf<String, Any?>()

/**
 * Clears the per-route focus memory. Test-only: the map is process
 * lifetime, so without this one test's last-focused row leaks into the
 * next test's initial focus (e.g. a 6-row traversal leaves
 * `launcher-row-sys-5`, and a later 25-row test starts scrolled to the
 * middle with row 0 disposed — not found — instead of at the top).
 */
@VisibleForTesting
internal fun clearRouteFocusMemoryForTesting() {
    lastFocusByRoute.clear()
}

/** Receiver for the [ScreenScaffold] content slot. */
class ScaffoldContentScope(
    val dispatcher: FocusDispatcher,
    val listState: ControllerListState,
    val gridState: ControllerGridState,
    private val initialKey: Any?,
) {
    /**
     * True when [key] should take initial focus on entering the
     * screen: the remembered last-focused control for this route, or
     * the screen's [fallbackFocusKey][ScreenScaffold]. Pass
     * `::isInitialFocus` as the `initialFocus` argument of
     * [ControllerList]/[ControllerGrid] and their `control(...)`
     * entries pick this up automatically.
     */
    fun isInitialFocus(key: Any?): Boolean = key != null && key == initialKey
}

@Composable
fun ScreenScaffold(
    routeKey: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isHome: Boolean = false,
    showMasthead: Boolean = true,
    titleTrailing: (@Composable () -> Unit)? = null,
    footerLabel: String = "BACK",
    passThroughAWhen: () -> Boolean = { false },
    fallbackFocusKey: Any? = null,
    content: @Composable ScaffoldContentScope.() -> Unit,
) {
    val dispatcher = remember(routeKey) { FocusDispatcher() }
    // Centralized focus memory: every focus change on this screen is
    // recorded against the route key. Idempotent assignment.
    dispatcher.onFocusedListener = { key -> lastFocusByRoute[routeKey] = key }
    val listState = rememberControllerListState()
    val gridState = rememberControllerGridState()
    val initialKey = remember(routeKey) { lastFocusByRoute[routeKey] ?: fallbackFocusKey }

    // Safety net: if the remembered key has no live control (the
    // screen's state changed while we were away), fall back so D-pad
    // never starts with nothing focused.
    LaunchedEffect(routeKey, initialKey) {
        withFrameNanos { }
        withFrameNanos { }
        if (dispatcher.focusedKey == null) {
            val target = dispatcher.focusRequesterOf(initialKey)
                ?: fallbackFocusKey?.let { dispatcher.focusRequesterOf(it) }
            runCatching { target?.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Crystal.Background)
            .controllerKeys(dispatcher, onBack, passThroughAWhen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showMasthead) {
                CrystalHeader()
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(title)
                titleTrailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val scope = remember(dispatcher, listState, gridState, initialKey) {
                    ScaffoldContentScope(dispatcher, listState, gridState, initialKey)
                }
                scope.content()
            }
            Spacer(Modifier.height(8.dp))
            ScaffoldFooter(isHome = isHome, backLabel = footerLabel)
        }
    }
}

/** Pinned footer hint bar: A SELECT · B BACK (B EXIT on HOME). */
@Composable
private fun ScaffoldFooter(isHome: Boolean, backLabel: String) {
    Column {
        CrystalDivider()
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Keycap(key = "A", label = "SELECT")
            Keycap(key = "B", label = if (isHome) "EXIT" else backLabel)
        }
    }
}
