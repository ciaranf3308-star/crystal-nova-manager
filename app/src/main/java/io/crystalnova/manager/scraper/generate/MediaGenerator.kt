package io.crystalnova.manager.scraper.generate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.crystalnova.manager.scraper.model.MediaKind

/**
 * Generated physical-media fallback. Only renders a platform-specific
 * shape when the platform mapping is known; UNKNOWN platforms get a
 * neutral GENERIC representation — never a false cartridge.
 */
object MediaGenerator {
    const val GENERATOR_ID = "crystal-media-v1"
    const val SIZE = 800

    data class Spec(
        val kind: MediaKind,
        val title: String,
        val platformLabel: String,
        val hasLogo: Boolean,
        val palette: ArtworkPalette.Palette,
    )

    fun layout(
        kind: MediaKind,
        title: String,
        platformSlug: String,
        platformLabel: String,
        hasLogo: Boolean,
        sampled: ArtworkPalette.Palette? = null,
    ): Spec = Spec(
        // UNKNOWN is rendered as neutral GENERIC, never as a cartridge.
        kind = if (kind == MediaKind.UNKNOWN) MediaKind.GENERIC else kind,
        title = title.ifBlank { "Unknown Title" },
        platformLabel = platformLabel,
        hasLogo = hasLogo,
        palette = sampled ?: ArtworkPalette.forPlatform(platformSlug),
    )

    fun render(spec: Spec, logo: Bitmap? = null): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (spec.kind) {
            MediaKind.DISC -> renderDisc(c, spec, logo)
            MediaKind.UMD -> renderUmd(c, spec, logo)
            MediaKind.CARTRIDGE -> renderCartridge(c, spec, logo)
            MediaKind.GENERIC, MediaKind.UNKNOWN -> renderGeneric(c, spec)
        }
        return bmp
    }

    private fun centerLabel(
        c: Canvas, spec: Spec, logo: Bitmap?,
        cx: Float, cy: Float, maxW: Float, maxH: Float,
    ) {
        if (spec.hasLogo && logo != null) {
            val scale = minOf(maxW / logo.width, maxH / logo.height, 1f)
            val w = logo.width * scale
            val h = logo.height * scale
            c.drawBitmap(
                logo, null,
                RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                Paint().apply { isFilterBitmap = true },
            )
        } else {
            val ink = Paint().apply {
                color = spec.palette.ink; isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            var size = 64f
            ink.textSize = size
            val lines = TextWrap.wrap(spec.title, maxW, 3) { ink.measureText(it) }
            while (lines.any { ink.measureText(it) > maxW } && size > 24f) {
                size -= 4f; ink.textSize = size
            }
            var y = cy - (lines.size - 1) * size * 0.6f
            for (line in TextWrap.wrap(spec.title, maxW, 3) { ink.measureText(it) }) {
                c.drawText(line, cx, y, ink); y += size * 1.2f
            }
        }
    }

    private fun renderDisc(c: Canvas, spec: Spec, logo: Bitmap?) {
        val s = SIZE.toFloat()
        val p = spec.palette
        // Disc body.
        c.drawCircle(s / 2, s / 2, s * 0.46f, Paint().apply { color = 0xFF1B1B1F.toInt() })
        c.drawCircle(s / 2, s / 2, s * 0.46f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 10f; color = p.accent
        })
        // Data sheen ring.
        c.drawCircle(s / 2, s / 2, s * 0.34f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 26f; color = 0xFF2E2E35.toInt()
        })
        // Label.
        c.drawCircle(s / 2, s / 2, s * 0.24f, Paint().apply { color = p.dark })
        c.drawCircle(s / 2, s / 2, s * 0.24f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; color = p.accent
        })
        centerLabel(c, spec, logo, s / 2, s / 2, s * 0.4f, s * 0.4f)
        // Hub.
        c.drawCircle(s / 2, s / 2, s * 0.06f, Paint().apply { color = 0xFF0C0C0E.toInt() })
        c.drawCircle(s / 2, s / 2, s * 0.06f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 5f; color = p.muted
        })
    }

    private fun renderUmd(c: Canvas, spec: Spec, logo: Bitmap?) {
        val s = SIZE.toFloat()
        val p = spec.palette
        // UMD shell: rounded square casing.
        val shell = RectF(s * 0.08f, s * 0.08f, s * 0.92f, s * 0.92f)
        c.drawRoundRect(shell, 48f, 48f, Paint().apply { color = 0xFF232326.toInt() })
        c.drawRoundRect(shell, 48f, 48f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 8f; color = p.accent
        })
        // Visible disc window.
        c.drawCircle(s / 2, s * 0.44f, s * 0.26f, Paint().apply { color = 0xFF101012.toInt() })
        c.drawCircle(s / 2, s * 0.44f, s * 0.13f, Paint().apply { color = p.dark })
        centerLabel(c, spec, logo, s / 2, s * 0.44f, s * 0.2f, s * 0.2f)
        // Title strip.
        val ink = Paint().apply {
            color = p.ink; isAntiAlias = true; textAlign = Paint.Align.CENTER; textSize = 44f
        }
        val lines = TextWrap.wrap(spec.title, s * 0.7f, 2) { ink.measureText(it) }
        var y = s * 0.80f
        for (line in lines) {
            c.drawText(line, s / 2, y, ink); y += 54f
        }
    }

    private fun renderCartridge(c: Canvas, spec: Spec, logo: Bitmap?) {
        val s = SIZE.toFloat()
        val p = spec.palette
        val body = RectF(s * 0.18f, s * 0.06f, s * 0.82f, s * 0.94f)
        // Body.
        c.drawRoundRect(body, 36f, 36f, Paint().apply { color = 0xFF2A2A2E.toInt() })
        c.drawRoundRect(body, 36f, 36f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 8f; color = p.accent
        })
        // Grip ridges.
        val ridge = Paint().apply { color = 0xFF1B1B1E.toInt() }
        for (i in 0..4) {
            val y = s * 0.78f + i * 26f
            c.drawRect(s * 0.24f, y, s * 0.76f, y + 12f, ridge)
        }
        // Label.
        val label = RectF(s * 0.24f, s * 0.14f, s * 0.76f, s * 0.68f)
        c.drawRoundRect(label, 18f, 18f, Paint().apply { color = p.dark })
        // Platform stripe.
        c.drawRect(s * 0.24f, s * 0.14f, s * 0.76f, s * 0.14f + 44f,
            Paint().apply { color = p.primary })
        val plat = Paint().apply {
            color = p.ink; isAntiAlias = true; textAlign = Paint.Align.CENTER
            textSize = 30f; isFakeBoldText = true
        }
        c.drawText(spec.platformLabel.uppercase().take(12), s * 0.5f, s * 0.14f + 33f, plat)
        centerLabel(c, spec, logo, s * 0.5f, s * 0.46f, s * 0.44f, s * 0.34f)
    }

    private fun renderGeneric(c: Canvas, spec: Spec) {
        val s = SIZE.toFloat()
        val p = spec.palette
        // Neutral media glyph: rounded square + plain ring. Honest placeholder.
        val box = RectF(s * 0.12f, s * 0.12f, s * 0.88f, s * 0.88f)
        c.drawRoundRect(box, 64f, 64f, Paint().apply { color = p.dark })
        c.drawRoundRect(box, 64f, 64f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 8f; color = p.muted
        })
        c.drawCircle(s / 2, s * 0.42f, s * 0.18f, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 14f; color = p.accent
        })
        c.drawCircle(s / 2, s * 0.42f, s * 0.05f, Paint().apply { color = p.accent })
        val ink = Paint().apply {
            color = p.muted; isAntiAlias = true; textAlign = Paint.Align.CENTER; textSize = 40f
        }
        val lines = TextWrap.wrap(spec.title, s * 0.66f, 2) { ink.measureText(it) }
        var y = s * 0.74f
        for (line in lines) {
            c.drawText(line, s / 2, y, ink); y += 52f
        }
    }
}
