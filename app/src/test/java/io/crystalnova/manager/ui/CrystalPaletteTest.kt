package io.crystalnova.manager.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the APPEARANCE palette math: hex parsing,
 * HSV round-trips, and palette derivation. The shipped DEFAULT must
 * stay pixel-identical to the pre-customization builds.
 */
class CrystalPaletteTest {

    @Test
    fun `default palette keeps the shipped hexes`() {
        val d = CrystalPalette.DEFAULT
        assertEquals("#0a1929", d.background.toHex())
        assertEquals("#7ba7d9", d.accent.toHex())
        assertEquals("#f0ebdc", d.cream.toHex())
        assertEquals("#ffc93c", d.joystick.toHex())
        assertEquals("#0e2236", d.tile.toHex())
        assertEquals("#0b1b2d", d.tileDeep.toHex())
        assertEquals("#3e5a7a", d.frameDim.toHex())
        assertEquals("#1b2c4e", d.creamInk.toHex())
        assertEquals("#e9f1f6", d.ink.toHex())
        assertEquals("#9db4c6", d.inkDim.toHex())
        assertEquals("#8fa5b8", d.divider.toHex())
        assertEquals("#cfd9e2", d.keycap.toHex())
        assertEquals("#9fd6a8", d.good.toHex())
        assertEquals("#e08a8a", d.bad.toHex())
    }

    @Test
    fun `parseHexColor accepts rrggbb and rejects everything else`() {
        assertEquals(Color(0xFF0A1929), parseHexColor("#0a1929"))
        assertEquals(Color(0xFFFFC93C), parseHexColor("#FFC93C"))
        assertNull(parseHexColor("#0a192")) // too short
        assertNull(parseHexColor("#0a19290")) // too long
        assertNull(parseHexColor("0a1929")) // missing hash
        assertNull(parseHexColor("#zzzzzz")) // not hex
        assertNull(parseHexColor(""))
    }

    @Test
    fun `toHex round-trips through parseHexColor`() {
        listOf("#0a1929", "#7ba7d9", "#f0ebdc", "#ffc93c", "#ffffff", "#000000").forEach { hex ->
            val parsed = parseHexColor(hex)
            assertNotNull(parsed)
            assertEquals(hex, parsed!!.toHex())
        }
    }

    @Test
    fun `hsv round-trips the primaries`() {
        val red = hsvToColor(0f, 1f, 1f)
        assertEquals("#ff0000", red.toHex())
        val hsv = red.toHsv()
        assertEquals(0f, hsv[0], 0.5f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        val green = parseHexColor("#00ff00")!!.toHsv()
        assertEquals(120f, green[0], 0.5f)
        val blue = parseHexColor("#0000ff")!!.toHsv()
        assertEquals(240f, blue[0], 0.5f)
        // Grey has no hue and no saturation.
        val grey = parseHexColor("#808080")!!.toHsv()
        assertEquals(0f, grey[1], 0.01f)
    }

    @Test
    fun `derive keeps the four bases and stays opaque`() {
        val bg = parseHexColor("#170f0a")!!
        val accent = parseHexColor("#e07840")!!
        val cream = parseHexColor("#f5e8d8")!!
        val joystick = parseHexColor("#ffb020")!!
        val p = CrystalPalette.derive(bg, accent, cream, joystick)
        assertEquals(bg, p.background)
        assertEquals(accent, p.accent)
        assertEquals(cream, p.cream)
        assertEquals(joystick, p.joystick)
        listOf(
            p.tile, p.tileDeep, p.frameDim, p.creamInk, p.ink,
            p.inkDim, p.divider, p.keycap,
        ).forEach { c ->
            assertEquals(1f, c.alpha, 0.001f)
        }
        // Derived tiles stay near the background (same family).
        val bgHsv = bg.toHsv()
        val tileHsv = p.tile.toHsv()
        val hueDist = kotlin.math.abs(bgHsv[0] - tileHsv[0])
        assertTrue("tile hue drifted too far: $hueDist", hueDist < 30f || hueDist > 330f)
    }

    @Test
    fun `mix and shifts behave`() {
        val black = Color(0xFF000000)
        val white = Color(0xFFFFFFFF)
        // Mid-gray: 0.5 * 255 = 127.5 rounds to 128 in 8-bit (#808080).
        assertEquals("#808080", black.mix(white, 0.5f).toHex())
        assertEquals("#000000", black.mix(white, 0f).toHex())
        assertEquals("#ffffff", black.shiftV(2f).toHex()) // clamped
        assertEquals("#808080", parseHexColor("#808080")!!.shiftS(-1f).toHex()) // desaturated
    }
}
