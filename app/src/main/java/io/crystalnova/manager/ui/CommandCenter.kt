package io.crystalnova.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Command-center design system (u89-command).
 *
 * Display tiles, zone headers and status strips for the command-center
 * screens, plus the focusable tall command tile (see
 * [ControllerGridContent.commandTile] in ControllerList.kt).
 *
 * LAYOUT ONLY: no behavior, no new colors — every token below already
 * exists in [Crystal]. Terse ALL-CAPS text is the caller's job, as with
 * every other Crystal screen.
 */

/**
 * Dense display-only cell. NO focus, NO click — pure status readout.
 * Several [StatusTile]s share a grid row (see
 * [ControllerGridContent.statTile], which emits it non-spanning).
 */
@Composable
fun StatusTile(
    label: String,
    value: String,
    subline: String? = null,
    barFraction: Float? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(Crystal.Tile)
            .border(2.dp, Crystal.Frame, RoundedCornerShape(2.dp))
            .padding(8.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = Crystal.SmallSize,
                color = Crystal.Divider,
                letterSpacing = 2.sp,
            ),
        )
        BasicText(
            text = value,
            style = TextStyle(
                fontFamily = Crystal.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = Crystal.ButtonSize,
                color = Crystal.Ink,
            ),
        )
        if (subline != null) {
            BasicText(
                text = subline,
                style = TextStyle(
                    fontFamily = Crystal.Mono,
                    fontSize = Crystal.SmallSize,
                    color = Crystal.InkDim,
                ),
            )
        }
        if (barFraction != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Crystal.TileDeep),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(barFraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(Crystal.Joystick),
                )
            }
        }
    }
}

/**
 * Zone header, built on the existing [SectionLabel]. Full width;
 * screens place it inside panel{} for full span.
 */
@Composable
fun ZoneHeader(text: String, modifier: Modifier = Modifier) {
    SectionLabel(text, modifier = modifier.fillMaxWidth())
}

/**
 * Slim single-line status strip. Screens place it inside panel{}.
 * [attention] switches the text to [Crystal.Bad]; otherwise Ink.
 */
@Composable
fun StatusStrip(
    text: String,
    attention: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .background(Crystal.TileDeep)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        style = TextStyle(
            fontFamily = Crystal.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = Crystal.BodySize,
            color = if (attention) Crystal.Bad else Crystal.Ink,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
