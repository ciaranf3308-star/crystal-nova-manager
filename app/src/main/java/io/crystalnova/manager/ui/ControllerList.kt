package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Controller-aware scrolling infrastructure.
 *
 * Root problem this fixes: the old [FocusDispatcher] tracked which
 * control held D-pad focus and forwarded gamepad A to it, but NEVER
 * scrolled the viewport — focus happily moved to controls below the
 * fold (e.g. THEME's UPDATE APP) with no way to see them. Compose's
 * focus traversal does not auto-scroll scrollables, so every screen
 * needs explicit focus-to-viewport wiring. That is what this file
 * provides: [ControllerList] / [ControllerGrid] plus
 * [Modifier.controllerScrollItem].
 *
 * ## How the focus-to-viewport mechanism works
 *
 * Each focusable control carries [Modifier.controllerScrollItem],
 * which does two things:
 *
 * 1. Records the control's measured height ([ControllerScrollEngine.recordHeight]).
 * 2. On `onFocusChanged(isFocused = true)`, asks the engine to
 *    [ControllerScrollEngine.requestScroll].
 *
 * The engine first checks whether the item is already *comfortably*
 * visible — fully inside the viewport with a margin of 1/6 of the
 * viewport height on each edge. If it is, nothing happens: this keeps
 * D-pad walking through a visible list free of constant re-centering
 * animation ("swimmy" focus).
 *
 * Otherwise it calls `animateScrollToItem(index, scrollOffset)` with
 * `scrollOffset = (viewportHeight - itemHeight) / 2`, landing the
 * focused control centered in the viewport — never flush against the
 * bottom edge, never invisible. When heights are not measured yet it
 * falls back to a quarter-viewport inset, still comfortably inside.
 *
 * Rapid D-pad movement cancels the in-flight scroll animation before
 * starting the next one, so focus never lags behind the animation
 * queue.
 *
 * ## The viewport-boundary problem (and its fix)
 *
 * Scroll-on-focus alone is not enough: on Android, `LazyColumn` /
 * `LazyVerticalGrid` compose **zero** items beyond the viewport
 * (`defaultLazyListBeyondBoundsItemCount() == 0`). Compose's 2D focus
 * search does ask the lazy layout to compose beyond-bounds items when
 * D-pad focus moves past the visible edge, but the budget is 0 — so the
 * next row is never composed, focus search finds nothing, and focus gets
 * stuck on the last visible row even though more rows exist below. The
 * engine above never fires because focus never arrives.
 *
 * [handleControllerBoundaryKey] closes that gap: [ControllerList] and
 * [ControllerGrid] intercept DPAD_UP/DOWN in the preview phase. When the
 * focused control sits on the first/last visible row and more items exist
 * beyond it, the key is consumed, the next row is scrolled to the
 * comfortable (centered) position via
 * [ControllerScrollEngine.scrollToComfortable], and focus is then moved in
 * that direction — default traversal finds the freshly composed row.
 * Anything else falls through to default traversal untouched.
 *
 * ## Why animateScrollToItem and not BringIntoViewRequester
 *
 * BringIntoViewRequester only guarantees *visibility*: it scrolls the
 * minimum distance, which parks the item flush against the viewport
 * edge — exactly what the requirements forbid ("NEVER flush against
 * the bottom edge"). Explicit `animateScrollToItem` with a computed
 * centered offset gives deterministic, comfortable placement, and the
 * comfort check above gives the minimum-scroll behavior for free.
 *
 * ## Index bookkeeping
 *
 * [ControllerListContent] / [ControllerGridContent] allocate one
 * monotonically increasing index per emitted item, in emission order —
 * which is exactly how LazyColumn/LazyVerticalGrid assign their item
 * indices. Buttons nested inside a panel/section share their section's
 * index (the whole section scrolls into view); panels are short by
 * construction, so their buttons always land comfortably visible.
 * Screens must only emit items through the DSL (`section`, `control`);
 * never call the raw `item {}` on the delegated scope, or indices
 * will desynchronize from the lazy layout.
 */

/**
 * The scrolling engine shared by the list and grid states. Holds the
 * measured viewport/item heights and serializes scroll-on-focus
 * requests. Compose-agnostic except for `mutableStateOf` (viewport
 * height is read during composition by absolutely nobody — it is only
 * read inside [requestScroll], so the state wrapper is just convenient
 * storage; no recomposition is triggered off it).
 */
class ControllerScrollEngine internal constructor(
    private val animateTo: suspend (index: Int, scrollOffset: Int) -> Unit,
    private val isComfortablyVisible: (index: Int, insetPx: Int) -> Boolean,
) {
    /** Measured viewport height in px; written by the list/grid root. */
    var viewportHeightPx: Int by mutableStateOf(0)

    private val itemHeights = mutableMapOf<Int, Int>()
    private var scrollJob: Job? = null

    fun recordHeight(index: Int, heightPx: Int) {
        if (index >= 0 && heightPx > 0) itemHeights[index] = heightPx
    }

    /**
     * Scroll [index] into a comfortable viewport position. No-op when
     * the item is already comfortably visible. Cancels any in-flight
     * scroll from a previous focus event first.
     */
    fun requestScroll(index: Int, scope: CoroutineScope) {
        if (index < 0) return
        scrollJob?.cancel()
        scrollJob = scope.launch {
            val vp = viewportHeightPx
            val inset = (vp / 6).coerceAtLeast(0)
            if (vp > 0 && isComfortablyVisible(index, inset)) return@launch
            scrollToComfortable(index)
        }
    }

    /**
     * The centered placement [requestScroll] uses, as a suspend call.
     * Used by the D-pad boundary handler, which scrolls the *next* row
     * into view *before* moving focus to it (focus can never land on a
     * row the lazy list has not composed yet).
     */
    suspend fun scrollToComfortable(index: Int) {
        if (index < 0) return
        animateTo(index, comfortableOffset(index))
    }

    private fun comfortableOffset(index: Int): Int {
        val vp = viewportHeightPx
        val h = itemHeights[index]
        return if (vp > 0 && h != null && h > 0) {
            // Center the item: never flush to an edge, never invisible.
            ((vp - h) / 2).coerceAtLeast(0)
        } else {
            // Heights not measured yet — comfortable inset fallback.
            (vp / 4).coerceAtLeast(0)
        }
    }
}

/** Scroll state for [ControllerList]; see [rememberControllerListState]. */
class ControllerListState internal constructor(
    val lazyListState: LazyListState,
    val engine: ControllerScrollEngine,
)

/** Scroll state for [ControllerGrid]; see [rememberControllerGridState]. */
class ControllerGridState internal constructor(
    val lazyGridState: LazyGridState,
    val engine: ControllerScrollEngine,
)

/**
 * D-pad boundary crossing for [ControllerList]/[ControllerGrid].
 *
 * The deep reason this exists: on Android, `LazyColumn`/`LazyVerticalGrid`
 * compose **zero** items beyond the viewport
 * (`defaultLazyListBeyondBoundsItemCount() == 0`). Compose's 2D focus
 * search *does* ask the lazy layout to compose beyond-bounds items when
 * D-pad focus moves past the visible edge (`searchBeyondBounds`), but the
 * budget is 0 — so the next row is never composed, focus search finds
 * nothing, and focus gets stuck on the last visible row even though more
 * rows exist. The scroll-on-focus engine never fires because focus never
 * arrives. On the Nova this meant D-pad DOWN at the bottom of the visible
 * list was a dead end: below-fold rows (e.g. UPDATE APP) were unreachable.
 *
 * The fix: intercept DPAD_UP/DOWN at the list level (preview phase, before
 * default traversal). When the focused control sits on the first/last
 * visible row and more items exist beyond it, consume the key, scroll the
 * next row to the comfortable (centered) position, then move focus in that
 * direction — default traversal now finds the freshly composed row.
 * Anything else (focus not on a known control, not at the edge, no more
 * items) returns false so default traversal behaves exactly as before.
 *
 * @param focusedIndex scroll-index of the focused control, or null when
 *   focus is not on one of this list/grid's controls.
 * @param bottomRowFirstIndex first scroll-index of the last visible row
 *   (== [visibleLastIndex] for a list; derived from item offsets for a grid).
 * @param columnsPerRow items per row (1 for a list); how far to step the
 *   scroll target when crossing into the next row.
 */
internal fun handleControllerBoundaryKey(
    event: KeyEvent,
    focusedIndex: Int?,
    totalItemsCount: Int,
    visibleFirstIndex: Int,
    visibleLastIndex: Int,
    bottomRowFirstIndex: Int,
    columnsPerRow: Int,
    scope: CoroutineScope,
    focusManager: FocusManager,
    scrollToComfortable: suspend (Int) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val index = focusedIndex ?: return false
    if (visibleLastIndex < visibleFirstIndex) return false
    val forward = when (event.key) {
        Key.DirectionDown -> true
        Key.DirectionUp -> false
        else -> return false
    }
    val atEdge = if (forward) index >= bottomRowFirstIndex else index <= visibleFirstIndex
    val hasMore = if (forward) index < totalItemsCount - 1 else index > 0
    if (!atEdge || !hasMore) return false
    val step = columnsPerRow.coerceAtLeast(1)
    val target = if (forward) {
        (index + step).coerceAtMost(totalItemsCount - 1)
    } else {
        (index - step).coerceAtLeast(0)
    }
    scope.launch {
        // Scroll first (the row becomes composed), then let default
        // traversal move focus onto it. Rapid D-pad repeats cancel the
        // in-flight scroll via the list state's mutator mutex, so focus
        // never lags behind and a cancelled scroll never moves focus.
        scrollToComfortable(target)
        focusManager.moveFocus(if (forward) FocusDirection.Down else FocusDirection.Up)
    }
    return true
}

@Composable
fun rememberControllerListState(): ControllerListState {
    val lazyListState = rememberLazyListState()
    return remember(lazyListState) {
        ControllerListState(
            lazyListState,
            ControllerScrollEngine(
                animateTo = { index, offset ->
                    lazyListState.animateScrollToItem(index, offset)
                },
                isComfortablyVisible = { index, insetPx ->
                    val layout = lazyListState.layoutInfo
                    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                    item != null &&
                        item.offset >= layout.viewportStartOffset + insetPx &&
                        item.offset + item.size <= layout.viewportEndOffset - insetPx
                },
            ),
        )
    }
}

@Composable
fun rememberControllerGridState(): ControllerGridState {
    val lazyGridState = rememberLazyGridState()
    return remember(lazyGridState) {
        ControllerGridState(
            lazyGridState,
            ControllerScrollEngine(
                animateTo = { index, offset ->
                    lazyGridState.animateScrollToItem(index, offset)
                },
                isComfortablyVisible = { index, insetPx ->
                    val layout = lazyGridState.layoutInfo
                    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                    item != null &&
                        item.offset.y >= layout.viewportStartOffset + insetPx &&
                        item.offset.y + item.size.height <= layout.viewportEndOffset - insetPx
                },
            ),
        )
    }
}

/**
 * Attaches focus-to-viewport behavior to the focusable this modifier
 * is applied to. Apply it to the focusable composable itself (not a
 * non-focusable wrapper — `onFocusChanged` is only reliable on the
 * node that actually holds focus).
 *
 * A null [engine] returns the modifier unchanged, so call sites can
 * pass the engine straight through without branching.
 */
@Composable
fun Modifier.controllerScrollItem(engine: ControllerScrollEngine?, index: Int): Modifier {
    if (engine == null) return this
    val scope = rememberCoroutineScope()
    return remember(engine, index) {
        this
            .onSizeChanged { size -> engine.recordHeight(index, size.height) }
            .onFocusChanged { focusState ->
                if (focusState.isFocused) engine.requestScroll(index, scope)
            }
    }
}

/**
 * Receiver for a [ControllerList] content block. Delegates
 * [LazyListScope] so the list internals stay standard; screens must
 * emit content ONLY through [section] / [control] so the scroll-index
 * counter stays synchronized with the lazy layout's item indices.
 */
class ControllerListContent(
    private val engine: ControllerScrollEngine,
    private val dispatcher: FocusDispatcher,
    private val initialFocus: (Any?) -> Boolean,
    private val keyToIndex: MutableMap<Any?, Int>,
    lazyListScope: LazyListScope,
) : LazyListScope by lazyListScope {

    private var nextIndex = 0
    private fun allocIndex(): Int = nextIndex++

    /**
     * One static (non-focusable) row. Focusable controls inside must go
     * through [SectionScope.control] / [SectionScope.notice] so they
     * share this row's scroll index.
     */
    fun section(key: Any? = null, content: @Composable SectionScope.() -> Unit) {
        val index = allocIndex()
        if (key != null) keyToIndex[key] = index
        // Hoisted: the item{} lambda's scope receiver shadows this class,
        // so its private members are unreachable via implicit receiver inside.
        val eng = engine
        val disp = dispatcher
        val init = initialFocus
        val keyIdx = keyToIndex
        item(key = key) {
            SectionScope(eng, disp, index, init, keyIdx).content()
        }
    }

    /** One focusable full-width button occupying its own row. */
    fun control(
        key: Any?,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        danger: Boolean = false,
        requestInitialFocus: Boolean = initialFocus(key),
        modifier: Modifier = Modifier,
        testTag: String? = null,
    ) {
        section(key = key) {
            control(
                key = key,
                label = label,
                onClick = onClick,
                enabled = enabled,
                danger = danger,
                requestInitialFocus = requestInitialFocus,
                modifier = modifier,
                testTag = testTag,
            )
        }
    }
}

/**
 * The receiver inside a [ControllerListContent.section] block. Every
 * focusable created here scrolls the enclosing row into view when
 * focused (rows are short panels by construction, so their buttons
 * always land comfortably visible).
 */
class SectionScope(
    private val engine: ControllerScrollEngine,
    val dispatcher: FocusDispatcher,
    private val itemIndex: Int,
    private val initialFocus: (Any?) -> Boolean,
    private val keyToIndex: MutableMap<Any?, Int>,
) {
    @Composable
    fun control(
        key: Any?,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        danger: Boolean = false,
        requestInitialFocus: Boolean = initialFocus(key),
        modifier: Modifier = Modifier,
        testTag: String? = null,
    ) {
        if (key != null) keyToIndex[key] = itemIndex
        CrystalButton(
            key = key,
            label = label,
            onClick = onClick,
            dispatcher = dispatcher,
            modifier = modifier,
            enabled = enabled,
            requestInitialFocus = requestInitialFocus,
            danger = danger,
            scrollEngine = engine,
            scrollIndex = itemIndex,
            testTag = testTag,
        )
    }

    /** Dismissible bad-news notice, in the house style. */
    @Composable
    fun notice(notice: String, onDismiss: () -> Unit) {
        StatusLine(notice, Crystal.Bad)
        control(key = "dismiss-notice", label = "DISMISS", onClick = onDismiss)
    }

    /**
     * For a custom focusable (e.g. a text field): apply the returned
     * modifier to the focusable composable itself.
     */
    @Composable
    fun scrollModifier(): Modifier = Modifier.controllerScrollItem(engine, itemIndex)
}

/**
 * LazyColumn with controller semantics: D-pad focus on any item
 * scrolls it to a comfortable (centered) viewport position; touch
 * drag scrolls normally. Exactly one scroll container — screens must
 * not nest another scrollable inside.
 */
@Composable
fun ControllerList(
    state: ControllerListState,
    dispatcher: FocusDispatcher,
    modifier: Modifier = Modifier,
    initialFocus: (Any?) -> Boolean = { false },
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: ControllerListContent.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val boundaryScope = rememberCoroutineScope()
    // Scroll-index per control key, rebuilt as the content DSL runs.
    // The D-pad boundary handler reads it to find the focused control's
    // index; entries for disposed items are never read (only the focused
    // key is looked up) and are dropped on the next content pass.
    val keyToIndex = remember { mutableMapOf<Any?, Int>() }
    LazyColumn(
        state = state.lazyListState,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size -> state.engine.viewportHeightPx = size.height }
            .onPreviewKeyEvent { event ->
                val layout = state.lazyListState.layoutInfo
                val visible = layout.visibleItemsInfo
                if (visible.isEmpty()) return@onPreviewKeyEvent false
                handleControllerBoundaryKey(
                    event = event,
                    focusedIndex = keyToIndex[dispatcher.focusedKey],
                    totalItemsCount = layout.totalItemsCount,
                    visibleFirstIndex = visible.minOf { it.index },
                    visibleLastIndex = visible.maxOf { it.index },
                    // A list row is one item: the last visible row starts
                    // at the last visible item.
                    bottomRowFirstIndex = visible.maxOf { it.index },
                    columnsPerRow = 1,
                    scope = boundaryScope,
                    focusManager = focusManager,
                    scrollToComfortable = { index -> state.engine.scrollToComfortable(index) },
                )
            },
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
    ) {
        keyToIndex.clear()
        ControllerListContent(state.engine, dispatcher, initialFocus, keyToIndex, this).content()
    }
}

/**
 * Receiver for a [ControllerGrid] content block. Each [control] is one
 * grid cell with its own scroll index (no nesting inside cells).
 */
class ControllerGridContent(
    private val engine: ControllerScrollEngine,
    private val dispatcher: FocusDispatcher,
    private val initialFocus: (Any?) -> Boolean,
    private val keyToIndex: MutableMap<Any?, Int>,
    private val gridScope: LazyGridScope,
) {
    // NOTE: LazyGridScope is a sealed interface and cannot be implemented
    // (even via `by` delegation) outside its module, so the scope is held
    // and used explicitly instead of delegated.

    private var nextIndex = 0

    fun control(
        key: Any?,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        danger: Boolean = false,
        requestInitialFocus: Boolean = initialFocus(key),
        modifier: Modifier = Modifier,
        testTag: String? = null,
    ) {
        val index = nextIndex++
        if (key != null) keyToIndex[key] = index
        // Hoisted: the item{} lambda's scope receiver shadows this class,
        // so its private members are unreachable via implicit receiver inside.
        val eng = engine
        val disp = dispatcher
        gridScope.item(key = key) {
            CrystalButton(
                key = key,
                label = label,
                onClick = onClick,
                dispatcher = disp,
                modifier = modifier,
                enabled = enabled,
                requestInitialFocus = requestInitialFocus,
                danger = danger,
                scrollEngine = eng,
                scrollIndex = index,
                testTag = testTag,
            )
        }
    }
}

/**
 * LazyVerticalGrid with controller semantics — the grid counterpart
 * of [ControllerList]. Used for the LIBRARY and LAUNCHERS card grids
 * so D-pad focus on a below-the-fold card scrolls the grid.
 */
@Composable
fun ControllerGrid(
    state: ControllerGridState,
    dispatcher: FocusDispatcher,
    columns: GridCells,
    modifier: Modifier = Modifier,
    initialFocus: (Any?) -> Boolean = { false },
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    content: ControllerGridContent.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val boundaryScope = rememberCoroutineScope()
    val keyToIndex = remember { mutableMapOf<Any?, Int>() }
    LazyVerticalGrid(
        columns = columns,
        state = state.lazyGridState,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size -> state.engine.viewportHeightPx = size.height }
            .onPreviewKeyEvent { event ->
                val layout = state.lazyGridState.layoutInfo
                val visible = layout.visibleItemsInfo
                if (visible.isEmpty()) return@onPreviewKeyEvent false
                // Items sharing the last row's vertical offset form the
                // last visible row; their count is the row stride.
                val lastRowY = visible.maxOf { it.offset.y }
                val bottomRow = visible.filter { it.offset.y == lastRowY }
                handleControllerBoundaryKey(
                    event = event,
                    focusedIndex = keyToIndex[dispatcher.focusedKey],
                    totalItemsCount = layout.totalItemsCount,
                    visibleFirstIndex = visible.minOf { it.index },
                    visibleLastIndex = visible.maxOf { it.index },
                    bottomRowFirstIndex = bottomRow.minOf { it.index },
                    columnsPerRow = bottomRow.size,
                    scope = boundaryScope,
                    focusManager = focusManager,
                    scrollToComfortable = { index -> state.engine.scrollToComfortable(index) },
                )
            },
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalArrangement = horizontalArrangement,
    ) {
        keyToIndex.clear()
        ControllerGridContent(state.engine, dispatcher, initialFocus, keyToIndex, this).content()
    }
}
