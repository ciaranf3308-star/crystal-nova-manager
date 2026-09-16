package io.crystalnova.manager.scraper.store

import io.crystalnova.manager.data.HttpClient
import java.io.File
import java.security.MessageDigest

/**
 * Two-level download cache for provider artwork.
 *
 * The persistent level lives in SAF-backed `crystal-nova-data/cache/`
 * (see [ScraperStorage]) keyed by SHA-256 of the URL, so a re-scrape or
 * app restart never re-downloads. The returned [File] is disposable
 * staging under the app-private cache dir — callers hand it to bitmap
 * decoders that need a real file; it may be wiped at any time and is
 * re-staged from the persistent level on demand.
 *
 * A failed download never poisons the persistent cache: null is returned
 * and nothing is written.
 */
class MediaCache(
    private val storage: ScraperStorage,
    private val stagingDir: File,
    private val http: HttpClient,
) {
    init { stagingDir.mkdirs() }

    /** Hex SHA-256 of [url]; also the persistent cache key. */
    fun keyFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".bin"
    }

    /**
     * Returns a staged file for [url], downloading first when absent from
     * the persistent cache. Returns null on network failure.
     */
    fun fetch(url: String): File? {
        val key = keyFor(url)
        val cached: ByteArray? = try { storage.readCache(key) } catch (_: Exception) { null }
        val bytes: ByteArray = if (cached != null && cached.isNotEmpty()) {
            cached
        } else {
            val downloaded = downloadBytes(url) ?: return null
            try { storage.writeCache(key, downloaded) } catch (_: Exception) { /* best-effort */ }
            downloaded
        }
        return stage(key, bytes)
    }

    private fun downloadBytes(url: String): ByteArray? {
        val tmp = File(stagingDir, "dl-${System.nanoTime()}.tmp")
        return try {
            http.download(url, tmp) { _, _ -> }
            if (tmp.length() == 0L) null else tmp.readBytes()
        } catch (_: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    private fun stage(key: String, bytes: ByteArray): File? {
        return try {
            val dest = File(stagingDir, key)
            dest.writeBytes(bytes)
            dest
        } catch (_: Exception) { null }
    }

    /** Clears disposable staging only; the persistent SAF cache is kept. */
    fun clearStaging() {
        stagingDir.listFiles()?.forEach { it.delete() }
    }
}
