package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import io.crystalnova.manager.storage.LocationState

/**
 * Shared screen infrastructure for the destination stack. Visual
 * identity lives in [Crystal] and ui/Components.kt; this file only
 * carries the controller-key plumbing and small text helpers every
 * screen reuses.
 */

/**
 * Forwards gamepad A / D-pad center / Enter to the focused control via
 * [dispatcher] and gamepad B to [onBack]. Applied at each screen root;
 * every screen creates its own [FocusDispatcher] so registrations never
 * leak across destinations. Touch works independently through clickable.
 */
fun Modifier.controllerKeys(
    dispatcher: FocusDispatcher,
    onBack: () -> Unit,
): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
    when (e.key) {
        Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
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

/**
 * Common screen shell: full-bleed background, controller keys, and the
 * standard 48/32dp padding. Each screen owns its [FocusDispatcher] and
 * gives its first meaningful control `requestInitialFocus = true`.
 */
@Composable
fun ScreenRoot(
    onBack: () -> Unit,
    dispatcher: FocusDispatcher,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Crystal.Background)
            .controllerKeys(dispatcher, onBack)
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        content()
    }
}

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
                color = Crystal.Divider,
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

/**
 * One-line friendly display for a storage location. Never a raw
 * content:// URI: [LocationState.Ready] carries the parsed display
 * path (e.g. "INTERNAL STORAGE /Roms" or "SD CARD /Media").
 */
fun friendlyLocation(loc: LocationState): String = when (loc) {
    is LocationState.NotConfigured -> "NOT CONFIGURED"
    is LocationState.Ready -> loc.displayPath
    is LocationState.AccessLost -> "ACCESS LOST — RESELECT"
}

/** Dismissible bad-news notice, in the house style. */
@Composable
fun NoticeBlock(
    notice: String,
    onDismiss: () -> Unit,
    dispatcher: FocusDispatcher,
) {
    StatusLine(notice, Crystal.Bad)
    CrystalButton(
        key = "dismiss-notice",
        label = "DISMISS",
        onClick = onDismiss,
        dispatcher = dispatcher,
    )
}

/** Standard child-screen footer: the B-key legend. */
@Composable
fun BackFooter(label: String = "BACK") {
    Column {
        CrystalDivider()
        Keycap(key = "B", label = label)
    }
}

/**
 * Safety-net screen: shown when a destination needs payload that isn't
 * ready (e.g. Diagnostics before its IO read completes). Never blank.
 */
@Composable
fun PlaceholderScreen(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dispatcher = androidx.compose.runtime.remember { FocusDispatcher() }
    ScreenRoot(onBack = onBack, dispatcher = dispatcher, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 96.dp),
        ) {
            CrystalHeader()
            StatusLine(label, Crystal.Divider)
            CrystalButton(
                key = "placeholder-back",
                label = "BACK",
                onClick = onBack,
                dispatcher = dispatcher,
                requestInitialFocus = true,
            )
        }
    }
}
