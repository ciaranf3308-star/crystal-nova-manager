package io.crystalnova.manager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle

/**
 * Routes controller input to the focused action.
 *
 * D-pad movement is handled by Compose focus traversal; gamepad A
 * (KEYCODE_BUTTON_A) is NOT mapped to click by the framework, so the
 * screen root forwards it to whichever button currently holds focus.
 * Touch works independently through clickable.
 *
 * The dispatcher also carries the focus-requester registry the
 * [ScreenScaffold] uses for per-route focus memory: every
 * [CrystalButton] registers its [androidx.compose.ui.focus.FocusRequester]
 * here, and [onFocusedListener] lets the scaffold record the last
 * focused key per route.
 */
class FocusDispatcher {
    var focusedKey: Any? = null
        private set
    private val actions = mutableMapOf<Any?, () -> Unit>()
    private val focusRequesters = mutableMapOf<Any?, androidx.compose.ui.focus.FocusRequester>()

    /**
     * Invoked on every focus change. The scaffold sets this to record
     * the last-focused control per route.
     */
    var onFocusedListener: ((Any?) -> Unit)? = null

    fun register(key: Any?, action: () -> Unit) {
        actions[key] = action
    }

    fun registerFocusRequester(
        key: Any?,
        requester: androidx.compose.ui.focus.FocusRequester,
    ) {
        focusRequesters[key] = requester
    }

    fun focusRequesterOf(key: Any?): androidx.compose.ui.focus.FocusRequester? =
        focusRequesters[key]

    fun onFocused(key: Any?) {
        focusedKey = key
        onFocusedListener?.invoke(key)
    }

    fun activateFocused() {
        actions[focusedKey]?.invoke()
    }
}

/**
 * Crystal panel: tile surface, thin frame border, and viewfinder-style
 * corner marks echoing the Pegasus theme's selection brackets.
 */
@Composable
fun CrystalPanel(
    modifier: Modifier = Modifier,
    cornerMarkLength: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Crystal.Tile)
            .border(2.dp, Crystal.Frame, RoundedCornerShape(2.dp))
            .padding(8.dp),
    ) {
        content()
        Canvas(modifier = Modifier.matchParentSize()) {
            val l = cornerMarkLength.toPx()
            val w = 3.dp.toPx()
            val c = Crystal.Frame
            // top-left
            drawLine(c, Offset(0f, 0f), Offset(l, 0f), strokeWidth = w)
            drawLine(c, Offset(0f, 0f), Offset(0f, l), strokeWidth = w)
            // top-right
            drawLine(c, Offset(size.width, 0f), Offset(size.width - l, 0f), strokeWidth = w)
            drawLine(c, Offset(size.width, 0f), Offset(size.width, l), strokeWidth = w)
            // bottom-left
            drawLine(c, Offset(0f, size.height), Offset(l, size.height), strokeWidth = w)
            drawLine(c, Offset(0f, size.height), Offset(0f, size.height - l), strokeWidth = w)
            // bottom-right
            drawLine(c, Offset(size.width, size.height), Offset(size.width - l, size.height), strokeWidth = w)
            drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - l), strokeWidth = w)
        }
    }
}

/**
 * Full-width Crystal button. Focused (D-pad) or pressed state uses the
 * cream selection treatment from the Pegasus theme; focused buttons
 * also get a heavier 3dp joystick-yellow frame so focus is unmissable
 * at Nova density.
 *
 * [scrollEngine]/[scrollIndex]: when set, D-pad focus on this button
 * scrolls it to a comfortable (centered) viewport position via the
 * shared [ControllerScrollEngine] — see ui/ControllerList.kt.
 *
 * [testTag]: when set, a stable `testTag` on the button node so the
 * phase-2 UI tests can address focusables by tag. The tag sits upstream
 * of focus/click in the modifier chain, on the button's own node.
 */
@Composable
fun CrystalButton(
    key: Any?,
    label: String,
    onClick: () -> Unit,
    dispatcher: FocusDispatcher,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    requestInitialFocus: Boolean = false,
    danger: Boolean = false,
    scrollEngine: ControllerScrollEngine? = null,
    scrollIndex: Int = 0,
    testTag: String? = null,
    /**
     * Optional secondary line (e.g. a game count under a system name),
     * rendered smaller and dimmer. Null keeps the classic single-line
     * button exactly as before.
     */
    subLabel: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scrollModifier = Modifier.controllerScrollItem(scrollEngine, scrollIndex)
    val tagModifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
    LaunchedEffect(key, onClick) {
        dispatcher.register(key, onClick)
        dispatcher.registerFocusRequester(key, focusRequester)
    }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) focusRequester.requestFocus()
    }
    val bg = when {
        !enabled -> Crystal.TileDeep
        focused -> Crystal.Cream
        else -> Crystal.Tile
    }
    val fg = when {
        !enabled -> Crystal.InkDim
        focused -> Crystal.CreamInk
        danger -> Crystal.Bad
        else -> Crystal.Ink
    }
    val borderColor = when {
        focused -> Crystal.Joystick
        danger -> Crystal.Bad
        else -> Crystal.Frame
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            // The focus-scroll observer sits upstream of the focus
            // target (before focusRequester/onFocusChanged/focusable)
            // so D-pad focus on this button is always observed and
            // scrolled to a comfortable viewport position. The test tag
            // rides the same node, above focus/click.
            .then(scrollModifier)
            .then(tagModifier)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) dispatcher.onFocused(key)
            }
            .focusable(enabled = enabled)
            .clip(RoundedCornerShape(2.dp))
            .background(bg)
            .border(
                if (focused) 3.dp else 2.dp,
                borderColor,
                RoundedCornerShape(2.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 14.dp),
    ) {
        if (subLabel == null) {
            BasicText(
                text = label,
                style = TextStyle(
                    fontFamily = Crystal.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = Crystal.ButtonSize,
                    color = fg,
                    textAlign = TextAlign.Center,
                ),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = label,
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = Crystal.ButtonSize,
                        color = fg,
                        textAlign = TextAlign.Center,
                    ),
                )
                BasicText(
                    text = subLabel,
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontSize = Crystal.SmallSize,
                        color = if (focused) Crystal.CreamInk else Crystal.InkDim,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/** [ B ] style keycap chip, matching the theme footer language. */
@Composable
fun Keycap(
    key: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp, 36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Crystal.Keycap),
        ) {
            BasicText(
                text = key,
                style = TextStyle(
                    fontFamily = Crystal.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = Crystal.SmallSize,
                    color = Crystal.CreamInk,
                ),
            )
        }
        BasicText(
            text = "  $label",
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.BodySize,
                color = Crystal.InkDim,
            ),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = Crystal.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = Crystal.SectionSize,
            color = Crystal.Divider,
            letterSpacing = 2.sp,
        ),
    )
}

@Composable
fun CrystalDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Crystal.FrameDim),
    )
}
