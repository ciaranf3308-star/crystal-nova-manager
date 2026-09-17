package io.crystalnova.manager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.crystalnova.manager.R

/**
 * Crystal Nova design language, shared with the Pegasus theme:
 * deep-sea background, tile surfaces, viewfinder frame blue, cream
 * selection treatment, Departure Mono throughout. Deliberately NOT
 * Material — this should feel like Crystal firmware.
 */
object Crystal {
    val Background = Color(0xFF0A1929)
    val Tile = Color(0xFF0E2236)
    val TileDeep = Color(0xFF0B1B2D)
    val Frame = Color(0xFF7BA7D9)
    val FrameDim = Color(0xFF3E5A7A)
    val Cream = Color(0xFFF0EBDC)
    val CreamInk = Color(0xFF1B2C4E)
    val Ink = Color(0xFFE9F1F6)
    val InkDim = Color(0xFF9DB4C6)
    val Divider = Color(0xFF8FA5B8)
    val Keycap = Color(0xFFCFD9E2)
    val Good = Color(0xFF9FD6A8)
    val Bad = Color(0xFFE08A8A)
    /**
     * Joystick yellow — the Retroid Pocket Nova's yellow sticks.
     * Used sparingly: focus rings, the masthead mark, update chrome.
     */
    val Joystick = Color(0xFFFFC93C)

    val Mono: FontFamily = FontFamily(Font(R.font.departuremono_regular))

    // Firmware type scale (sp) — compact handheld density for the
    // 1280×960 Nova. Balanced for the small viewport, not a blind
    // mathematical scale-down of the old XL sizes.
    val TitleSize = 31.sp
    val SectionSize = 17.sp
    val BodySize = 15.sp
    val SmallSize = 13.sp
    val ButtonSize = 17.sp
}
