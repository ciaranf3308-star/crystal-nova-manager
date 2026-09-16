package io.crystalnova.manager.scraper.generate

import android.graphics.Bitmap

/**
 * Seam between the scrape pipeline and Android bitmap rendering.
 * The production implementation draws with android.graphics; tests
 * inject a fake that returns canned PNG bytes. Keeping this interface
 * thin means [io.crystalnova.manager.scraper.work.ScrapeJob] never
 * touches the framework directly and stays JVM-testable.
 */
interface ArtRenderer {
    fun renderSpine(spec: SpineGenerator.Spec): ByteArray
    fun renderBack(spec: BackCoverGenerator.Spec, screenshot: Bitmap?): ByteArray
    fun renderMedia(spec: MediaGenerator.Spec, logo: Bitmap?): ByteArray
}

/** Production renderer: real android.graphics drawing. */
object AndroidArtRenderer : ArtRenderer {
    override fun renderSpine(spec: SpineGenerator.Spec): ByteArray =
        Bitmaps.encodePng(SpineGenerator.render(spec))

    override fun renderBack(spec: BackCoverGenerator.Spec, screenshot: Bitmap?): ByteArray =
        Bitmaps.encodePng(BackCoverGenerator.render(spec, screenshot))

    override fun renderMedia(spec: MediaGenerator.Spec, logo: Bitmap?): ByteArray =
        Bitmaps.encodePng(MediaGenerator.render(spec, logo))
}
