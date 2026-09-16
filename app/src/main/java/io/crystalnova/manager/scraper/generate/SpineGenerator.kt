package io.crystalnova.manager.scraper.generate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import io.crystalnova.manager.scraper.model.Region

/**
 * Generated spine fallback. Clean Crystal design — platform band, vertical
 * title, region code. No watermark: provenance lives in the manifest.
 */
object SpineGenerator {
    const val WIDTH = 200
    const val HEIGHT = 1000
    const val GENERATOR_ID = "crystal-spine-v1"

    data class Spec(
        val title: String,
        val platformLabel: String,
        val regionCode: String,
        val palette: ArtworkPalette.Palette,
    )

    fun layout(
        title: String,
        platformSlug: String,
        platformLabel: String,
        region: Region,
        sampled: ArtworkPalette.Palette? = null,
    ): Spec = Spec(
        title = title.ifBlank { "Unknown Title" },
        platformLabel = platformLabel,
        regionCode = region.code,
        palette = sampled ?: ArtworkPalette.forPlatform(platformSlug),
    )

    fun render(spec: Spec): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = spec.palette

        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), 0f,
                p.dark, p.primary, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)

        // Accent edge.
        c.drawRect(0f, 0f, 10f, HEIGHT.toFloat(), Paint().apply { color = p.accent })

        val ink = Paint().apply {
            color = p.ink; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        // Platform label, top.
        ink.textSize = 44f; ink.isFakeBoldText = true
        c.drawText(spec.platformLabel.uppercase().take(8), WIDTH / 2f, 90f, ink)

        // Vertical title, centered.
        c.save()
        c.rotate(-90f, WIDTH / 2f, HEIGHT / 2f)
        ink.textSize = 64f
        val title = spec.title
        val maxW = HEIGHT - 320f
        var size = 64f
        while (ink.measureText(title) > maxW && size > 28f) {
            size -= 4f; ink.textSize = size
        }
        c.drawText(title, WIDTH / 2f, HEIGHT / 2f + size * 0.35f, ink)
        c.restore()

        // Region code, bottom.
        ink.textSize = 40f; ink.isFakeBoldText = false
        ink.color = p.muted
        c.drawText(spec.regionCode, WIDTH / 2f, HEIGHT - 60f, ink)
        return bmp
    }
}
