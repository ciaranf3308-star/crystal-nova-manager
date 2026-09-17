package io.crystalnova.manager.scraper.store

import io.crystalnova.manager.data.HttpClient
import io.crystalnova.manager.data.HttpResponse
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain HttpURLConnection client for the scraper's artwork providers.
 *
 * Deliberately NOT the updater's
 * [io.crystalnova.manager.data.UrlConnectionHttpClient]: that client
 * enforces [io.crystalnova.manager.data.GitHubEndpoints.checkAllowed]
 * (GitHub hosts only, repo-path-pinned), which rejects the Libretro
 * thumbnail CDN (thumbnails.libretro.com) and the libretro-thumbnails
 * mirror — wiring the scraper through it silently downgrades every game
 * to generated art. This client has no endpoint boundary; it only
 * requires https and follows a bounded redirect chain.
 *
 * The timeout defaults are a bounded budget for artwork traffic: the
 * provider candidate ladder is tried serially, so one dead thumbnail
 * URL must fail fast (~6s connect / ~12s read) instead of stalling a
 * game for ~45s. Successful downloads are unaffected — once bytes flow,
 * the read timeout only fires on a stalled socket.
 */
class ScraperHttpClient(
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 12_000,
    /**
     * Opens the connection for [url]. Injectable so tests can script
     * redirects/statuses/bodies with a fake [HttpURLConnection] and stay
     * fully offline while exercising the production redirect, timeout,
     * and header logic. Production passes real URL connections.
     */
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) : HttpClient {
    companion object {
        private const val MAX_REDIRECTS = 5
    }

    override fun get(url: String): HttpResponse {
        var current = url
        repeat(MAX_REDIRECTS) {
            requireHttps(current)
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location header")
                } else {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.readBytes() ?: ByteArray(0)
                    val headers = conn.headerFields.filterKeys { it != null }
                        .mapKeys { it.key!! }
                    return HttpResponse(code, body, headers)
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    override fun download(url: String, dest: File, onProgress: (Long, Long?) -> Unit) {
        var current = url
        var redirects = 0
        while (true) {
            requireHttps(current)
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location header")
                    if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                    continue
                }
                if (code !in 200..299) throw IOException("Download failed: HTTP $code")
                val total = conn.getHeaderField("Content-Length")?.toLongOrNull()
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            // The caller's progress callback may throw
                            // CancellationException to abort promptly; it
                            // propagates out of the read loop here and the
                            // connection is disconnected by the finally.
                            onProgress(done, total)
                        }
                    }
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                onProgress(dest.length(), dest.length())
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun requireHttps(url: String) {
        val protocol = try {
            URL(url).protocol
        } catch (e: Exception) {
            throw IOException("Malformed URL: $url", e)
        }
        if (!protocol.equals("https", ignoreCase = true)) {
            throw IOException("Only https URLs are allowed: $url")
        }
    }

    private fun open(url: String): HttpURLConnection =
        connectionFactory(url).apply {
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", "CrystalNovaManager/1.0")
        }
}
