package io.crystalnova.manager.scraper.generate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Generated back-cover fallback: an honest Crystal panel (title,
 * description, metadata rows, optional screenshot) — never a fake retail
 * replica. No watermark; provenance lives in the manifest.
 */
object BackCoverGenerator {
    const val GENERATOR_ID = "crystal-back-v1"

    data class MetaRow(val label: String, val value: String)

    data class Spec(
        val width: Int,
        val height: Int,
        val title: String,
        val description: String,
        val rows: List<MetaRow>,
        val hasScreenshot: Boolean,
        val palette: ArtworkPalette.Palette,
    )

    fun layout(
        title: String,
        description: String?,
        developer: String?,
        publisher: String?,
        releaseYear: String?,
        genre: String?,
        players: String?,
        platformLabel: String,
        platformSlug: String,
        hasScreenshot: Boolean,
        frontAspect: Float? = null,
        sampled: ArtworkPalette.Palette? = null,
    ): Spec {
        val rows = listOfNotNull(
            developer?.takeIf { it.isNotBlank() }?.let { MetaRow("DEVELOPER", it) },
            publisher?.takeIf { it.isNotBlank() }?.let { MetaRow("PUBLISHER", it) },
            releaseYear?.takeIf { it.isNotBlank() }?.let { MetaRow("RELEASED", it) },
            genre?.takeIf { it.isNotBlank() }?.let { MetaRow("GENRE", it) },
            players?.takeIf { it.isNotBlank() }?.let { MetaRow("PLAYERS", it) },
            MetaRow("PLATFORM", platformLabel),
        )
        val width = 768
        val height = if (frontAspect != null && frontAspect > 0.3f && frontAspect < 3f) {
            (width / frontAspect).toInt().coerceIn(512, 1400)
        } else 1024
        return Spec(
            width = width,
            height = height,
            title = title.ifBlank { "Unknown Title" },
            description = description?.ifBlank { null }
                ?: "No description available yet — metadata can be added later.",
            rows = rows,
            hasScreenshot = hasScreenshot,
            palette = sampled ?: ArtworkPalette.forPlatform(platformSlug),
        )
    }

    fun render(spec: Spec, screenshot: Bitmap? = null): Bitmap {
        val bmp = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = spec.palette
        val w = spec.width.toFloat()
        val h = spec.height.toFloat()

        c.drawColor(p.dark)
        // Accent top bar.
        c.drawRect(0f, 0f, w, 14f, Paint().apply { color = p.accent })

        val ink = Paint().apply { color = p.ink; isAntiAlias = true }
        val muted = Paint().apply { color = p.muted; isAntiAlias = true }
        var y = 96f
        val margin = 56f

        // Title.
        ink.textSize = 52f; ink.isFakeBoldText = true
        val titleLines = TextWrap.wrap(spec.title, w - margin * 2, 2) { ink.measureText(it) }
        for (line in titleLines) {
            c.drawText(line, margin, y, ink); y += 64f
        }
        y += 16f

        // Description.
        ink.textSize = 30f; ink.isFakeBoldText = false
        val descLines = TextWrap.wrap(spec.description, w - margin * 2, 14) { ink.measureText(it) }
        for (line in descLines) {
            c.drawText(line, margin, y, ink); y += 42f
        }
        y += 28f

        // Divider.
        c.drawRect(margin, y, w - margin, y + 3f, Paint().apply { color = p.accent })
        y += 52f

        // Metadata rows.
        muted.textSize = 26f
        ink.textSize = 30f
        for (row in spec.rows) {
            if (y > h - 120f) break
            c.drawText(row.label, margin, y, muted)
            val valueLines = TextWrap.wrap(row.value, w - margin * 2 - 260f, 2) { ink.measureText(it) }
            var vy = y
            for (v in valueLines) {
                c.drawText(v, margin + 260f, vy, ink); vy += 40f
            }
            y = maxOf(y + 48f, vy + 8f)
        }

        // Screenshot thumb, bottom.
        if (spec.hasScreenshot && screenshot != null && y < h - 200f) {
            val tw = w - margin * 2
            val th = (h - y - 56f).coerceAtMost(tw * 9f / 16f)
            if (th > 120f) {
                val rect = RectF(margin, h - 56f - th, w - margin, h - 56f)
                c.drawBitmap(screenshot, null, rect, Paint().apply { isFilterBitmap = true })
                c.drawRect(rect, Paint().apply {
                    style = Paint.Style.STROKE; strokeWidth = 4f; color = p.accent
                })
            }
        }
        return bmp
    }
}
