package io.crystalnova.manager.scraper

import io.crystalnova.manager.data.GitHubEndpoints
import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.provider.LibretroProvider
import io.crystalnova.manager.scraper.provider.ScrapeQuery
import io.crystalnova.manager.scraper.store.ScraperHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Regression pin for the scraper network boundary bug: the updater's
 * UrlConnectionHttpClient enforces GitHubEndpoints.checkAllowed (GitHub
 * hosts only, repo-path-pinned), which rejects the Libretro artwork CDN —
 * so wiring the scraper through it silently downgraded every game to
 * generated art. The scraper's own client must accept the real provider
 * URL shapes. (The FakeHttpClient-based tests mask this wiring entirely,
 * which is why this test goes through the production client.)
 *
 * All download/redirect behavior below runs through a scripted fake
 * HttpURLConnection: fully offline, but exercising the production
 * client's real redirect chain, timeout wiring, and header handling.
 */
class ScraperHttpClientTest {

    /** Scripted HttpURLConnection: status, headers, and body per URL. */
    private class FakeConnection(
        url: URL,
        private val script: Map<String, Scripted>,
    ) : HttpURLConnection(url) {
        data class Scripted(
            val code: Int,
            val location: String? = null,
            val body: ByteArray = ByteArray(0),
            val errorBody: ByteArray = ByteArray(0),
        )

        private val scripted: Scripted =
            script[url.toString()] ?: Scripted(404, errorBody = "nope".toByteArray())
        var disconnected = false

        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = scripted.code
        override fun getHeaderField(name: String?): String? =
            // Consult the scripted header map (case-insensitively), not
            // the superclass default: the client reads Content-Length
            // through this single-header accessor.
            getHeaderFields().entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()
        override fun getHeaderFields(): Map<String, List<String>> =
            @Suppress("UNCHECKED_CAST")
            (buildMap<String?, List<String>> {
                // Null key = the HTTP status line, per URLConnection contract.
                put(null, listOf("HTTP/1.1 ${scripted.code}"))
                scripted.location?.let { put("Location", listOf(it)) }
                if (scripted.code in 200..299) {
                    put("Content-Length", listOf(scripted.body.size.toString()))
                }
            } as Map<String, List<String>>)
        override fun getInputStream(): InputStream {
            if (scripted.code !in 200..299) throw IOException("no input on ${scripted.code}")
            return ByteArrayInputStream(scripted.body)
        }
        override fun getErrorStream(): InputStream =
            ByteArrayInputStream(scripted.errorBody)
    }

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x2A,
    )

    private fun clientFor(script: Map<String, FakeConnection.Scripted>) =
        ScraperHttpClient { url -> FakeConnection(URL(url), script) }

    private fun libretroUrls(): List<String> = runBlocking {
        val candidates = LibretroProvider().artworkFor(
            ScrapeQuery(
                platformSlug = "gba",
                title = "Mario Golf Advance Tour",
                fileName = "Mario Golf - Advance Tour (E).gba",
                region = Region.EUROPE,
            ),
        )
        assertTrue("expected provider candidates", candidates.isNotEmpty())
        val urls = candidates.values.flatten().map { it.url }.distinct()
        assertTrue("expected https provider urls", urls.all { it.startsWith("https://") })
        urls
    }

    @Test fun `provider url shapes are rejected by the updater endpoint boundary`() {
        // Documents WHY the scraper must never use UrlConnectionHttpClient:
        // both Libretro URL shapes throw SecurityException there.
        var checked = 0
        for (url in libretroUrls()) {
            try {
                GitHubEndpoints.checkAllowed(url)
                fail("expected the updater boundary to reject $url")
            } catch (_: SecurityException) {
                checked++
            }
        }
        assertTrue("expected several provider urls, checked $checked", checked >= 2)
    }

    @Test fun `scraper client downloads provider url shapes without boundary rejection`() {
        // The real Libretro URL strings, but served by the fake: proves the
        // production client applies no GitHub endpoint boundary to them.
        val urls = libretroUrls().take(4)
        val script = urls.associateWith { FakeConnection.Scripted(200, body = png) }
        val client = clientFor(script)
        for (url in urls) {
            val tmp = File.createTempFile("scraper-client-test", ".tmp")
            try {
                client.download(url, tmp) { _, _ -> }
                assertEquals(png.toList(), tmp.readBytes().toList())
            } finally {
                tmp.delete()
            }
        }
    }

    @Test fun `get follows a redirect chain and returns the body`() {
        val script = mapOf(
            "https://cdn.example/a.png" to FakeConnection.Scripted(
                302, location = "https://cdn.example/b.png",
            ),
            "https://cdn.example/b.png" to FakeConnection.Scripted(200, body = png),
        )
        val resp = clientFor(script).get("https://cdn.example/a.png")
        assertEquals(200, resp.code)
        assertEquals(png.toList(), resp.body.toList())
    }

    @Test fun `too many redirects fail`() {
        val script = mapOf(
            "https://cdn.example/loop.png" to FakeConnection.Scripted(
                302, location = "https://cdn.example/loop.png",
            ),
        )
        try {
            clientFor(script).get("https://cdn.example/loop.png")
            fail("expected IOException for redirect loop")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test fun `redirect without location fails`() {
        val script = mapOf(
            "https://cdn.example/bare.png" to FakeConnection.Scripted(301),
        )
        try {
            clientFor(script).get("https://cdn.example/bare.png")
            fail("expected IOException for missing Location")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test fun `error status surfaces the error body`() {
        val script = mapOf(
            "https://cdn.example/gone.png" to FakeConnection.Scripted(
                404, errorBody = "gone".toByteArray(),
            ),
        )
        val resp = clientFor(script).get("https://cdn.example/gone.png")
        assertEquals(404, resp.code)
        assertEquals("gone", resp.body.toString(Charsets.UTF_8))
    }

    @Test fun `download writes the bytes to the destination file`() {
        val script = mapOf(
            "https://cdn.example/front.png" to FakeConnection.Scripted(200, body = png),
        )
        val tmp = File.createTempFile("scraper-client-test", ".tmp")
        try {
            var lastDone = -1L
            var lastTotal: Long? = null
            clientFor(script).download("https://cdn.example/front.png", tmp) { done, total ->
                lastDone = done
                lastTotal = total
            }
            assertEquals(png.toList(), tmp.readBytes().toList())
            assertEquals(png.size.toLong(), lastDone)
            assertEquals(png.size.toLong(), lastTotal)
        } finally {
            tmp.delete()
        }
    }

    @Test fun `scraper client still requires https`() {
        // No fake needed: the https check runs before any connection.
        val client = clientFor(emptyMap())
        val tmp = File.createTempFile("scraper-client-test", ".tmp")
        try {
            try {
                client.download("http://example.com/a.png", tmp) { _, _ -> }
                fail("expected IOException for plain http")
            } catch (_: IOException) {
                // expected
            }
        } finally {
            tmp.delete()
        }
    }

    @Test fun `artwork traffic uses a bounded miss budget`() {
        // The provider candidate ladder is tried serially: one dead
        // thumbnail URL must fail fast (~6s connect / ~12s read) instead
        // of stalling a game for ~45s. Successful downloads are
        // unaffected — the read timeout only fires on a stalled socket.
        var seen: FakeConnection? = null
        val url = "https://example.com/front.png"
        val client = ScraperHttpClient { u ->
            FakeConnection(URL(u), mapOf(url to FakeConnection.Scripted(200, body = png)))
                .also { seen = it }
        }
        client.get(url)
        assertEquals(6_000, seen!!.connectTimeout)
        assertEquals(12_000, seen!!.readTimeout)
    }
}
