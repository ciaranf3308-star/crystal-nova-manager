package io.crystalnova.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared screen infrastructure for the destination stack. Visual
 * identity lives in [Crystal] and ui/Components.kt; this file only
 * carries the controller-key plumbing and small text helpers every
 * screen reuses.
 */

/**
 * Forwards gamepad A / D-pad center / Enter to the focused control via
 * [dispatcher] and gamepad B to [onBack]. Applied once by
 * [ScreenScaffold] at the screen root; the scaffold owns the
 * [FocusDispatcher] so registrations never leak across destinations.
 * Touch works independently through clickable.
 *
 * [passThroughAWhen]: when true, the A/center/Enter press is NOT
 * consumed here and falls through to the focused composable. Screens
 * with an editable text field set this while the field is focused so
 * the IME still receives the press that opens it.
 */
fun Modifier.controllerKeys(
    dispatcher: FocusDispatcher,
    onBack: () -> Unit,
    passThroughAWhen: () -> Boolean = { false },
): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
    when (e.key) {
        Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            if (passThroughAWhen()) return@onPreviewKeyEvent false
            dispatcher.activateFocused()
            true
        }
        Key.ButtonB -> {
            onBack()
            true
        }
        else -> false
    }
}

// NOTE: the old ScreenRoot (per-screen dispatcher + 48/32dp padding) and
// BackFooter (manual B-key legend) are gone: every screen now goes through
// ScreenScaffold (ui/ScreenScaffold.kt), which owns the dispatcher, the
// per-route focus memory, compact Nova density, and the footer hint bar.

/** "CRYSTAL NOVA / MANAGER" masthead, shared by the main destinations. */
@Composable
fun CrystalHeader() {
    Column {
        BasicText(
            text = "CRYSTAL NOVA",
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = Crystal.TitleSize,
                color = Crystal.Ink,
            ),
        )
        BasicText(
            text = "MANAGER",
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontSize = Crystal.TitleSize,
                color = Crystal.Joystick,
            ),
        )
    }
}

@Composable
fun StatusLine(text: String, color: Color = Crystal.Ink) {
    BasicText(
        text = text,
        style = TextStyle(
            fontFamily = Crystal.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = Crystal.BodySize,
            color = color,
        ),
    )
}

@Composable
fun DimLine(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            fontFamily = Crystal.Mono,
            fontSize = Crystal.SmallSize,
            color = Crystal.InkDim,
        ),
    )
}

// NOTE: BackFooter (the manual per-screen B-key legend) is gone: the
// scaffold renders the pinned footer hint bar (A SELECT · B BACK /
// B EXIT) on every screen.
