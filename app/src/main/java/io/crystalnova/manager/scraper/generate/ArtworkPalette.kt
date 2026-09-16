package io.crystalnova.manager.scraper.generate

/**
 * Pure color logic for generated artwork. All functions operate on plain
 * ARGB int arrays so they stay unit-testable without the Android framework.
 */
object ArtworkPalette {

    data class Palette(
        val primary: Int,
        val dark: Int,
        val accent: Int,
        /** Cream text used across Crystal artwork. */
        val ink: Int = 0xFFF5E9D0.toInt(),
        val muted: Int = 0xFFB8A88F.toInt(),
    )

    /** Platform brand colors; UNKNOWN platforms fall back to neutral. */
    fun forPlatform(slug: String): Palette = when (slug) {
        "gba" -> Palette(0xFF4A3B8C.toInt(), 0xFF241D4D.toInt(), 0xFF8F7BFF.toInt())
        "gbc" -> Palette(0xFF7A4A9E.toInt(), 0xFF3D2350.toInt(), 0xFFC77DFF.toInt())
        "gb" -> Palette(0xFF5A6E7F.toInt(), 0xFF2B353F.toInt(), 0xFF9FB6C9.toInt())
        "nds", "n3ds" -> Palette(0xFFB03A2E.toInt(), 0xFF5E1F18.toInt(), 0xFFFF6B5B.toInt())
        "nes" -> Palette(0xFF8C8C8C.toInt(), 0xFF3A3A3A.toInt(), 0xFFD23C2E.toInt())
        "snes" -> Palette(0xFF5B5B8C.toInt(), 0xFF2A2A44.toInt(), 0xFF9D9DE8.toInt())
        "n64" -> Palette(0xFF2E6E4E.toInt(), 0xFF173A29.toInt(), 0xFF5BD68A.toInt())
        "gamecube" -> Palette(0xFF4E3B8C.toInt(), 0xFF28204D.toInt(), 0xFF8F7BFF.toInt())
        "genesis", "mastersystem", "gamegear", "segacd", "saturn", "dreamcast" ->
            Palette(0xFF1E5AA8.toInt(), 0xFF102E57.toInt(), 0xFF4D9FFF.toInt())
        "psx" -> Palette(0xFF6E6E6E.toInt(), 0xFF333333.toInt(), 0xFF9ECBFF.toInt())
        "ps2" -> Palette(0xFF2E4A7A.toInt(), 0xFF16263F.toInt(), 0xFF6B9BFF.toInt())
        "psp" -> Palette(0xFF3A3A3A.toInt(), 0xFF1A1A1A.toInt(), 0xFFB0B0B0.toInt())
        else -> Palette(0xFF4A4238.toInt(), 0xFF242019.toInt(), 0xFFC9A86A.toInt())
    }

    /**
     * Samples dominant colors from ARGB pixels (row-major, [width]x[height]).
     * Quantizes to 4 bits/channel, skips near-black/near-white, returns the
     * most saturated populous bucket as primary plus dark/accent variants.
     */
    fun sampleFromPixels(pixels: IntArray, width: Int, height: Int): Palette {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return forPlatform("unknown")
        val buckets = HashMap<Int, Int>()
        val step = maxOf(1, (width * height) / 4000) // ~4k samples max
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            if (lum in 24..232) {
                val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                buckets[key] = (buckets[key] ?: 0) + 1
            }
            i += step
        }
        val top = buckets.maxByOrNull { it.value } ?: return forPlatform("unknown")
        val r = ((top.key shr 8) and 0xF) * 16 + 8
        val g = ((top.key shr 4) and 0xF) * 16 + 8
        val b = (top.key and 0xF) * 16 + 8
        val primary = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return Palette(
            primary = primary,
            dark = shade(primary, 0.45f),
            accent = shade(primary, 1.35f),
        )
    }

    private fun shade(argb: Int, factor: Float): Int {
        val r = (((argb shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
