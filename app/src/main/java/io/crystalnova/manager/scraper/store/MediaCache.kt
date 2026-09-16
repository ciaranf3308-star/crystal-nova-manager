package io.crystalnova.manager.scraper.store

import io.crystalnova.manager.data.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

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
     * the persistent cache. Returns null on network failure. Suspends so a
     * scrape cancellation aborts an in-flight download promptly instead of
     * waiting out the connect/read timeouts.
     */
    suspend fun fetch(url: String): File? {
        val key = keyFor(url)
        val cached: ByteArray? = try {
            storage.readCache(key)
        } catch (e: SecurityException) {
            // Revoked grant: abort, don't masquerade as a cache miss.
            throw e
        } catch (_: Exception) { null }
        val bytes: ByteArray = if (cached != null && cached.isNotEmpty()) {
            cached
        } else {
            val downloaded = downloadBytes(url) ?: return null
            try {
                storage.writeCache(key, downloaded)
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) { /* best-effort */ }
            downloaded
        }
        return stage(key, bytes)
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        val tmp = File(stagingDir, "dl-${System.nanoTime()}.tmp")
        val job = coroutineContext[Job]
        return try {
            // Cooperative cancellation: the progress callback runs per
            // buffer chunk inside the client's read loop, so a cancelled
            // scrape aborts the socket promptly via CancellationException.
            http.download(url, tmp) { _, _ -> job?.ensureActive() }
            if (tmp.length() == 0L) null else tmp.readBytes()
        } catch (e: CancellationException) {
            // Never swallow cancellation as a mere download failure.
            throw e
        } catch (e: SecurityException) {
            // Never swallow revocation as a mere download failure either.
            throw e
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
