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

    val Mono: FontFamily = FontFamily(Font(R.font.departuremono_regular))

    // Firmware type scale (sp)
    val TitleSize = 44.sp
    val SectionSize = 22.sp
    val BodySize = 20.sp
    val SmallSize = 16.sp
    val ButtonSize = 24.sp
}
