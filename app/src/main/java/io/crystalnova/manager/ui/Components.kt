package io.crystalnova.manager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
 */
class FocusDispatcher {
    var focusedKey: Any? = null
        private set
    private val actions = mutableMapOf<Any?, () -> Unit>()

    fun register(key: Any?, action: () -> Unit) {
        actions[key] = action
    }

    fun onFocused(key: Any?) {
        focusedKey = key
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
            .padding(20.dp),
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
 * cream selection treatment from the Pegasus theme.
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
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(key, onClick) { dispatcher.register(key, onClick) }
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
        focused -> Crystal.Cream
        danger -> Crystal.Bad
        else -> Crystal.Frame
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) dispatcher.onFocused(key)
            }
            .focusable(enabled = enabled)
            .clip(RoundedCornerShape(2.dp))
            .background(bg)
            .border(2.dp, borderColor, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 16.dp),
    ) {
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
