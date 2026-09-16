package io.crystalnova.manager.scraper

import io.crystalnova.manager.data.FakeHttpClient
import io.crystalnova.manager.data.HttpClient
import io.crystalnova.manager.data.HttpResponse
import io.crystalnova.manager.scraper.store.MediaCache
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.storage.InMemoryThemeFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.system.measureTimeMillis

/**
 * The download cache is persistent: bytes live in SAF-backed
 * crystal-nova-data/cache/, so a second fetch (or a wiped staging dir)
 * never hits the network again. Staging under the app-private dir is
 * disposable.
 */
class MediaCacheTest {

    private fun pngBytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64) { 7 }

    private fun httpServing(bytes: ByteArray, failUrls: Set<String> = emptySet()) =
        FakeHttpClient(downloadHandler = { url, dest ->
            if (url in failUrls) throw IOException("network down")
            dest.writeBytes(bytes)
        })

    private fun cache(
        storage: ScraperStorage,
        http: HttpClient,
        tmp: File,
    ) = MediaCache(storage, File(tmp, "stage").apply { mkdirs() }, http)

    @Test fun `fetch downloads once then serves from persistent SAF cache`() = runBlocking {
        val tmp = createTempDir("mediacache")
        val storage = ScraperStorage(InMemoryThemeFs())
        val http = httpServing(pngBytes())
        val cache = cache(storage, http, tmp)

        val first = cache.fetch("https://example.com/a.png")
        assertNotNull(first)
        assertTrue(first!!.exists())
        assertEquals(1, http.requested.count { it == "https://example.com/a.png" })

        // Second fetch: no network, same bytes, staged file returned.
        val second = cache.fetch("https://example.com/a.png")
        assertNotNull(second)
        assertArrayEquals(pngBytes(), second!!.readBytes())
        assertEquals(1, http.requested.count { it == "https://example.com/a.png" })

        // The bytes really live in crystal-nova-data/cache/.
        val key = cache.keyFor("https://example.com/a.png")
        assertArrayEquals(pngBytes(), storage.readCache(key))
    }

    @Test fun `wiped staging is re-staged from the persistent cache without network`() = runBlocking {
        val tmp = createTempDir("mediacache")
        val storage = ScraperStorage(InMemoryThemeFs())
        val http = httpServing(pngBytes())
        val cache = cache(storage, http, tmp)

        assertNotNull(cache.fetch("https://example.com/a.png"))
        assertEquals(1, http.requested.size)

        // Simulate the OS wiping the app-private cache dir.
        cache.clearStaging()
        val again = cache.fetch("https://example.com/a.png")
        assertNotNull(again)
        assertArrayEquals(pngBytes(), again!!.readBytes())
        assertEquals(1, http.requested.size)
    }

    @Test fun `network failure returns null and poisons nothing`() = runBlocking {
        val tmp = createTempDir("mediacache")
        val storage = ScraperStorage(InMemoryThemeFs())
        val http = httpServing(pngBytes(), failUrls = setOf("https://example.com/down.png"))
        val cache = cache(storage, http, tmp)

        assertNull(cache.fetch("https://example.com/down.png"))
        // Nothing persisted for the failed URL.
        assertNull(storage.readCache(cache.keyFor("https://example.com/down.png")))
        // A later success still works.
        assertNotNull(cache.fetch("https://example.com/a.png"))
    }

    @Test fun `different urls get different cache keys`() {
        val tmp = createTempDir("mediacache")
        val storage = ScraperStorage(InMemoryThemeFs())
        val cache = cache(storage, httpServing(pngBytes()), tmp)
        assertTrue(cache.keyFor("https://example.com/a.png") != cache.keyFor("https://example.com/b.png"))
    }

    @Test fun `cancel during download aborts promptly and stays cancelled`() = runBlocking {
        val tmp = createTempDir("mediacache-cancel")
        val storage = ScraperStorage(InMemoryThemeFs())
        // A download that would take ~16 minutes of chunks without
        // cancellation; each chunk runs the progress callback.
        val slowHttp = object : HttpClient {
            override fun get(url: String): HttpResponse = throw IOException("unused")
            override fun download(url: String, dest: File, onProgress: (Long, Long?) -> Unit) {
                repeat(10_000) { i ->
                    onProgress(i.toLong(), 10_000L)
                    Thread.sleep(100)
                }
                dest.writeBytes(byteArrayOf(1))
            }
        }
        val cache = cache(storage, slowHttp, tmp)

        val deferred = async(Dispatchers.Default) { cache.fetch("https://example.com/slow.png") }
        delay(300) // let the download get going
        deferred.cancel()
        var sawCancellation = false
        val elapsed = measureTimeMillis {
            try {
                deferred.await()
            } catch (_: CancellationException) {
                sawCancellation = true
            }
        }
        assertTrue("expected CancellationException, not a swallowed null", sawCancellation)
        assertTrue("cancel took ${elapsed}ms, expected a prompt abort", elapsed < 5_000)
        // The partial download must not poison the persistent cache.
        assertNull(storage.readCache(cache.keyFor("https://example.com/slow.png")))
    }
}
