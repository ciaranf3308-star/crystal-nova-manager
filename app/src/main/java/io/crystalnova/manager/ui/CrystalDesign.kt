package io.crystalnova.manager.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.crystalnova.manager.R
import io.crystalnova.manager.data.KeyValueStore

/**
 * Crystal Nova design language, shared with the Pegasus theme:
 * deep-sea background, tile surfaces, viewfinder frame blue, cream
 * selection treatment, Departure Mono throughout. Deliberately NOT
 * Material — this should feel like Crystal firmware.
 *
 * The four identity colors (background, accent, cream, joystick) are
 * user-customizable (Settings → APPEARANCE); everything else derives
 * from them so a custom palette stays coherent. [CrystalPalette.DEFAULT]
 * carries the exact shipped hexes, so the stock look is pixel-identical
 * to the pre-customization builds.
 */
data class CrystalPalette(
    val background: Color,
    val accent: Color,
    val cream: Color,
    val joystick: Color,
    val tile: Color,
    val tileDeep: Color,
    val frameDim: Color,
    val creamInk: Color,
    val ink: Color,
    val inkDim: Color,
    val divider: Color,
    val keycap: Color,
    val good: Color,
    val bad: Color,
) {
    companion object {
        val DEFAULT = CrystalPalette(
            background = Color(0xFF0A1929),
            accent = Color(0xFF7BA7D9),
            cream = Color(0xFFF0EBDC),
            joystick = Color(0xFFFFC93C),
            tile = Color(0xFF0E2236),
            tileDeep = Color(0xFF0B1B2D),
            frameDim = Color(0xFF3E5A7A),
            creamInk = Color(0xFF1B2C4E),
            ink = Color(0xFFE9F1F6),
            inkDim = Color(0xFF9DB4C6),
            divider = Color(0xFF8FA5B8),
            keycap = Color(0xFFCFD9E2),
            good = Color(0xFF9FD6A8),
            bad = Color(0xFFE08A8A),
        )

        /**
         * Builds a full palette from the four user-picked identity
         * colors. Status colors (good/bad) stay fixed — they are
         * semantic, not identity.
         */
        fun derive(background: Color, accent: Color, cream: Color, joystick: Color): CrystalPalette {
            val ink = Color.White.mix(cream, 0.12f)
            val inkDim = ink.mix(accent, 0.38f)
            return CrystalPalette(
                background = background,
                accent = accent,
                cream = cream,
                joystick = joystick,
                tile = background.mix(accent, 0.10f).shiftV(0.025f),
                tileDeep = background.shiftV(-0.03f),
                frameDim = accent.shiftS(-0.35f).shiftV(-0.28f),
                creamInk = background.shiftV(0.10f),
                ink = ink,
                inkDim = inkDim,
                divider = inkDim.mix(accent, 0.25f),
                keycap = Color.White.mix(accent, 0.28f),
                good = DEFAULT.good,
                bad = DEFAULT.bad,
            )
        }
    }
}

/** #rrggbb, lowercase. The palette is opaque; alpha is ignored. */
fun Color.toHex(): String {
    val r = (this.red * 255).toInt().coerceIn(0, 255)
    val g = (this.green * 255).toInt().coerceIn(0, 255)
    val b = (this.blue * 255).toInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}

/** Parses #rrggbb (case-insensitive); null on anything else. Pure JVM. */
fun parseHexColor(s: String): Color? {
    if (!s.matches(Regex("^#[0-9a-fA-F]{6}$"))) return null
    val v = s.substring(1).toLong(16)
    return Color(
        red = ((v shr 16) and 0xFF).toFloat() / 255f,
        green = ((v shr 8) and 0xFF).toFloat() / 255f,
        blue = (v and 0xFF).toFloat() / 255f,
    )
}

/** HSV components of this color: h in [0,360), s/v in [0,1]. Pure JVM. */
fun Color.toHsv(): FloatArray {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == red -> 60f * (((green - blue) / d) % 6)
        max == green -> 60f * (((blue - red) / d) + 2)
        else -> 60f * (((red - green) / d) + 4)
    }
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(if (h < 0) h + 360f else h, s, max)
}

/** Builds an opaque color from HSV. Pure JVM. */
fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hh = (((h % 360) + 360) % 360) / 60f
    val ss = s.coerceIn(0f, 1f)
    val vv = v.coerceIn(0f, 1f)
    val c = vv * ss
    val x = c * (1 - kotlin.math.abs(hh % 2 - 1))
    val m = vv - c
    val (r, g, b) = when (hh.toInt().coerceIn(0, 5)) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

/** Linear blend toward [other] by [t] (0 = this, 1 = other). */
fun Color.mix(other: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * k,
        green = green + (other.green - green) * k,
        blue = blue + (other.blue - blue) * k,
        alpha = 1f,
    )
}

/** Shifts the HSV value (brightness) by [d]; clamps to [0,1]. */
fun Color.shiftV(d: Float): Color {
    val (h, s, v) = toHsv().let { Triple(it[0], it[1], it[2]) }
    return hsvToColor(h, s, v + d)
}

/** Shifts the HSV saturation by [d]; clamps to [0,1]. */
fun Color.shiftS(d: Float): Color {
    val (h, s, v) = toHsv().let { Triple(it[0], it[1], it[2]) }
    return hsvToColor(h, s + d, v)
}

object Crystal {
    var palette by mutableStateOf(CrystalPalette.DEFAULT)
        private set

    val Background get() = palette.background
    val Tile get() = palette.tile
    val TileDeep get() = palette.tileDeep
    val Frame get() = palette.accent
    val FrameDim get() = palette.frameDim
    val Cream get() = palette.cream
    val CreamInk get() = palette.creamInk
    val Ink get() = palette.ink
    val InkDim get() = palette.inkDim
    val Divider get() = palette.divider
    val Keycap get() = palette.keycap
    val Good get() = palette.good
    val Bad get() = palette.bad

    /**
     * Joystick yellow — the Retroid Pocket Nova's yellow sticks.
     * Used sparingly: focus rings, the masthead mark, update chrome.
     */
    val Joystick get() = palette.joystick

    val Mono: FontFamily = FontFamily(Font(R.font.departuremono_regular))

    // Firmware type scale (sp) — compact handheld density for the
    // 1280×960 Nova. Balanced for the small viewport, not a blind
    // mathematical scale-down of the old XL sizes.
    val TitleSize = 31.sp
    val SectionSize = 17.sp
    val BodySize = 15.sp
    val SmallSize = 13.sp
    val ButtonSize = 17.sp

    // -- Custom palette persistence ----------------------------------

    private const val KEY_BG = "appearance_background"
    private const val KEY_ACCENT = "appearance_accent"
    private const val KEY_CREAM = "appearance_cream"
    private const val KEY_JOYSTICK = "appearance_joystick"

    /** The four user-settable identity colors as #rrggbb, or nulls. */
    data class IdentityColors(
        val background: Color,
        val accent: Color,
        val cream: Color,
        val joystick: Color,
    )

    fun identityColors(): IdentityColors = IdentityColors(
        background = palette.background,
        accent = palette.accent,
        cream = palette.cream,
        joystick = palette.joystick,
    )

    fun hasCustomPalette(prefs: KeyValueStore): Boolean =
        prefs.getString(KEY_BG) != null

    /**
     * Applies a custom palette built from the four identity colors and
     * persists it. Call on the main thread.
     */
    fun applyCustomPalette(prefs: KeyValueStore, colors: IdentityColors) {
        prefs.putString(KEY_BG, colors.background.toHex())
        prefs.putString(KEY_ACCENT, colors.accent.toHex())
        prefs.putString(KEY_CREAM, colors.cream.toHex())
        prefs.putString(KEY_JOYSTICK, colors.joystick.toHex())
        palette = CrystalPalette.derive(
            background = colors.background,
            accent = colors.accent,
            cream = colors.cream,
            joystick = colors.joystick,
        )
    }

    /** Drops the custom palette and restores the shipped look. */
    fun resetToDefault(prefs: KeyValueStore) {
        prefs.remove(KEY_BG)
        prefs.remove(KEY_ACCENT)
        prefs.remove(KEY_CREAM)
        prefs.remove(KEY_JOYSTICK)
        palette = CrystalPalette.DEFAULT
    }

    /**
     * Restores the persisted custom palette at startup. Partial or
     * invalid persisted values fall back to the default look rather
     * than a half-applied theme.
     */
    fun loadPersistedPalette(prefs: KeyValueStore) {
        val bg = prefs.getString(KEY_BG)?.let(::parseHexColor)
        val accent = prefs.getString(KEY_ACCENT)?.let(::parseHexColor)
        val cream = prefs.getString(KEY_CREAM)?.let(::parseHexColor)
        val joystick = prefs.getString(KEY_JOYSTICK)?.let(::parseHexColor)
        palette = if (bg != null && accent != null && cream != null && joystick != null) {
            CrystalPalette.derive(bg, accent, cream, joystick)
        } else {
            CrystalPalette.DEFAULT
        }
    }
}
