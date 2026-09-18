package io.crystalnova.manager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntSize
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * APPEARANCE: recolor the four Crystal identity colors (background,
 * accent, cream, joystick yellow) with a D-pad-friendly HSB editor.
 * Everything else in the app derives from those four, so a custom
 * palette stays coherent. APPLY persists the palette, writes
 * `crystal-user-colors.json` for the Pegasus theme, and arms the
 * Pegasus restart gate — a cold restart of Pegasus picks the colors
 * up (theme ≥ 2026.09.18.11-user-colors required).
 */
enum class ColorSlot(val label: String) {
    BACKGROUND("BACKGROUND"),
    ACCENT("ACCENT"),
    CREAM("CREAM"),
    JOYSTICK("JOYSTICK"),
}

private data class ColorPreset(val name: String, val colors: Crystal.IdentityColors)

private val PRESETS = listOf(
    ColorPreset(
        "CRYSTAL",
        Crystal.IdentityColors(
            background = Color(0xFF0A1929),
            accent = Color(0xFF7BA7D9),
            cream = Color(0xFFF0EBDC),
            joystick = Color(0xFFFFC93C),
        ),
    ),
    ColorPreset(
        "EMBER",
        Crystal.IdentityColors(
            background = Color(0xFF170F0A),
            accent = Color(0xFFE07840),
            cream = Color(0xFFF5E8D8),
            joystick = Color(0xFFFFB020),
        ),
    ),
    ColorPreset(
        "VERDANT",
        Crystal.IdentityColors(
            background = Color(0xFF0A1A12),
            accent = Color(0xFF5FC98A),
            cream = Color(0xFFEAF2E4),
            joystick = Color(0xFFFFC93C),
        ),
    ),
    ColorPreset(
        "ULTRAVIOLET",
        Crystal.IdentityColors(
            background = Color(0xFF150F24),
            accent = Color(0xFF9A7BE0),
            cream = Color(0xFFECE4F5),
            joystick = Color(0xFFFFD23F),
        ),
    ),
    ColorPreset(
        "ARCTIC",
        Crystal.IdentityColors(
            background = Color(0xFF0E1B26),
            accent = Color(0xFF8FD0E0),
            cream = Color(0xFFF2F5F0),
            joystick = Color(0xFFFFC93C),
        ),
    ),
)

private fun Crystal.IdentityColors.forSlot(slot: ColorSlot): Color = when (slot) {
    ColorSlot.BACKGROUND -> background
    ColorSlot.ACCENT -> accent
    ColorSlot.CREAM -> cream
    ColorSlot.JOYSTICK -> joystick
}

private fun Crystal.IdentityColors.withSlot(slot: ColorSlot, color: Color): Crystal.IdentityColors =
    when (slot) {
        ColorSlot.BACKGROUND -> copy(background = color)
        ColorSlot.ACCENT -> copy(accent = color)
        ColorSlot.CREAM -> copy(cream = color)
        ColorSlot.JOYSTICK -> copy(joystick = color)
    }

@Composable
fun AppearanceScreen(
    themeReady: Boolean,
    lastSyncOk: Boolean?,
    onApplyColors: (Crystal.IdentityColors) -> Unit,
    onResetColors: () -> Unit,
    onOpenThemes: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(Crystal.identityColors()) }
    var slot by remember { mutableStateOf(ColorSlot.BACKGROUND) }
    var presetIndex by remember { mutableStateOf(0) }
    val current = draft.forSlot(slot)
    val hsv = remember(current) { current.toHsv() }

    fun updateCurrent(color: Color) {
        draft = draft.withSlot(slot, color)
    }

    ScreenScaffold(
        routeKey = "appearance",
        title = "APPEARANCE",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "appearance-slot-background",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            // Live four-well preview strip (static, not focusable).
            section {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ColorSlot.entries.forEach { s ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(draft.forSlot(s))
                                    .border(
                                        if (s == slot) 3.dp else 1.dp,
                                        if (s == slot) Crystal.Joystick else Crystal.FrameDim,
                                        RoundedCornerShape(2.dp),
                                    ),
                            )
                            BasicText(
                                text = s.label,
                                style = TextStyle(
                                    fontFamily = Crystal.Mono,
                                    fontSize = Crystal.SmallSize,
                                    color = if (s == slot) Crystal.Joystick else Crystal.InkDim,
                                ),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            // Slot pickers.
            ColorSlot.entries.forEach { s ->
                control(
                    key = "appearance-slot-${s.name.lowercase()}",
                    testTag = "appearance-slot-${s.name.lowercase()}",
                    label = "${s.label}\n${draft.forSlot(s).toHex()}${if (s == slot) "  ●" else ""}",
                    onClick = { slot = s },
                )
            }
            // HSB editor for the selected slot.
            section {
                val scope = this
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(current)
                            .border(2.dp, Crystal.Frame, RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = current.toHex().uppercase(),
                            style = TextStyle(
                                fontFamily = Crystal.Mono,
                                fontWeight = FontWeight.Bold,
                                fontSize = Crystal.SectionSize,
                                color = if (hsv[2] > 0.6f) Color(0xFF10202F) else Color.White,
                            ),
                        )
                    }
                    // Hue/saturation wheel (visual aid + touch target), drawn
                    // with sweep gradients so it needs no Android bitmap
                    // (bitmaps cannot be created under Robolectric).
                    // Latest values for the gesture block: the block is
                    // keyed on Unit (never restarts mid-drag), so it must
                    // read through updated state instead of capturing.
                    val latestBrightness = rememberUpdatedState(hsv[2])
                    val latestApply = rememberUpdatedState { c: Color -> updateCurrent(c) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(200.dp)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    applyWheelPosition(
                                        down.position, size,
                                        latestBrightness.value,
                                    ) { latestApply.value(it) }
                                    down.consume()
                                    var dragging = true
                                    while (dragging) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull()
                                        if (change == null || !change.pressed) {
                                            dragging = false
                                        } else {
                                            applyWheelPosition(
                                                change.position, size,
                                                latestBrightness.value,
                                            ) { latestApply.value(it) }
                                            change.consume()
                                        }
                                    }
                                }
                            },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            // Concentric rings: the outer ring is full
                            // saturation, the center is white. Each ring is
                            // a hue sweep at its own saturation.
                            val rings = 28
                            for (i in rings downTo 1) {
                                val sat = i / rings.toFloat()
                                drawCircle(
                                    brush = Brush.sweepGradient(
                                        List(13) { k -> hsvToColor((k * 30f) % 360f, sat, 1f) },
                                    ),
                                    radius = r * sat,
                                    center = center,
                                )
                            }
                            // Marker at the current hue/saturation.
                            val ang = Math.toRadians(hsv[0].toDouble())
                            val d = hsv[1] * r
                            val marker = Offset(
                                center.x + cos(ang).toFloat() * d,
                                center.y + sin(ang).toFloat() * d,
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 7f,
                                center = marker,
                            )
                            drawCircle(
                                color = Color.Black,
                                radius = 7f,
                                center = marker,
                                style = Stroke(width = 2f),
                            )
                        }
                    }
                    scope.adjustRow(
                        key = "appearance-hue",
                        label = "HUE",
                        valueText = "${hsv[0].toInt()}°",
                        fraction = hsv[0] / 360f,
                        onAdjust = { d -> updateCurrent(hsvToColor(hsv[0] + d * 360f, hsv[1], hsv[2])) },
                        testTag = "appearance-hue",
                    )
                    scope.adjustRow(
                        key = "appearance-sat",
                        label = "SATURATION",
                        valueText = "${(hsv[1] * 100).toInt()}%",
                        fraction = hsv[1],
                        onAdjust = { d -> updateCurrent(hsvToColor(hsv[0], hsv[1] + d, hsv[2])) },
                        testTag = "appearance-sat",
                    )
                    scope.adjustRow(
                        key = "appearance-bright",
                        label = "BRIGHTNESS",
                        valueText = "${(hsv[2] * 100).toInt()}%",
                        fraction = hsv[2],
                        onAdjust = { d -> updateCurrent(hsvToColor(hsv[0], hsv[1], hsv[2] + d)) },
                        testTag = "appearance-bright",
                    )
                    // Preset cycler: left/right steps through presets.
                    scope.adjustRow(
                        key = "appearance-preset",
                        label = "PRESET",
                        valueText = PRESETS[presetIndex].name,
                        fraction = presetIndex.toFloat() / (PRESETS.size - 1).coerceAtLeast(1),
                        onAdjust = { d ->
                            val next = (presetIndex + if (d > 0) 1 else -1)
                                .mod(PRESETS.size)
                            presetIndex = next
                            draft = PRESETS[next].colors
                        },
                        testTag = "appearance-preset",
                    )
                }
            }
            control(
                key = "appearance-apply",
                testTag = "appearance-apply",
                label = "APPLY COLORS\nWRITE TO THE APP + THE PEGASUS THEME",
                onClick = { onApplyColors(draft) },
            )
            control(
                key = "appearance-reset",
                testTag = "appearance-reset",
                label = "RESET TO DEFAULTS",
                danger = true,
                onClick = {
                    onResetColors()
                    draft = CrystalPalette.DEFAULT.let {
                        Crystal.IdentityColors(it.background, it.accent, it.cream, it.joystick)
                    }
                    presetIndex = 0
                },
            )
            section {
                when (lastSyncOk) {
                    true -> StatusLine("SYNCED — COLD-RESTART PEGASUS TO APPLY", Crystal.Good)
                    false -> StatusLine("WRITE FAILED — CHECK THE THEMES FOLDER", Crystal.Bad)
                    null -> StatusLine("APPLY WRITES THESE COLORS TO THE PEGASUS THEME")
                }
                if (!themeReady) {
                    StatusLine(
                        "THE INSTALLED THEME IS TOO OLD FOR CUSTOM COLORS",
                        Crystal.Bad,
                    )
                }
            }
            if (!themeReady) {
                control(
                    key = "appearance-update-theme",
                    testTag = "appearance-update-theme",
                    label = "UPDATE THEME\nGET COLOR SUPPORT",
                    onClick = onOpenThemes,
                )
            }
        }
    }
}

/**
 * D-pad-adjustable row: left/right nudges the value, the bar shows
 * position. Repeats naturally while the key is held.
 */
@Composable
fun SectionScope.adjustRow(
    key: Any?,
    label: String,
    valueText: String,
    fraction: Float,
    onAdjust: (Float) -> Unit,
    testTag: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val tagModifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(scrollModifier())
            .then(tagModifier)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) dispatcher.onFocused(key)
            }
            .focusable()
            .clip(RoundedCornerShape(2.dp))
            .background(if (focused) Crystal.Cream else Crystal.Tile)
            .border(
                if (focused) 3.dp else 2.dp,
                if (focused) Crystal.Joystick else Crystal.Frame,
                RoundedCornerShape(2.dp),
            )
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        onAdjust(-0.04f)
                        true
                    }
                    Key.DirectionRight -> {
                        onAdjust(0.04f)
                        true
                    }
                    else -> false
                }
            }
            .padding(vertical = 8.dp, horizontal = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val fg = if (focused) Crystal.CreamInk else Crystal.Ink
                BasicText(
                    text = label,
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontSize = Crystal.BodySize,
                        color = fg,
                    ),
                )
                BasicText(
                    text = valueText,
                    style = TextStyle(
                        fontFamily = Crystal.Mono,
                        fontSize = Crystal.BodySize,
                        color = fg,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (focused) Crystal.CreamInk.copy(alpha = 0.25f) else Crystal.TileDeep),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (focused) Crystal.CreamInk else Crystal.Frame),
                )
            }
        }
    }
}

/** Renders a hue/saturation wheel at full brightness, returned as a bitmap. */
/**
 * Maps a press/drag position on the hue/saturation wheel to a color,
 * keeping the current brightness. Positions outside the wheel are
 * ignored.
 */
private fun applyWheelPosition(
    pos: Offset,
    sizePx: IntSize,
    brightness: Float,
    onColor: (Color) -> Unit,
) {
    val cx = sizePx.width / 2f
    val cy = sizePx.height / 2f
    val dx = pos.x - cx
    val dy = pos.y - cy
    val r = minOf(cx, cy)
    val dist = sqrt(dx * dx + dy * dy)
    if (dist <= r) {
        val h = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360).toFloat()
        val s = (dist / r).coerceIn(0f, 1f)
        onColor(hsvToColor(h, s, brightness))
    }
}
