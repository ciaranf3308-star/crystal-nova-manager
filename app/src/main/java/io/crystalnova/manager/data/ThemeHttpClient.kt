package io.crystalnova.manager.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the ES-DE theme pipeline: fetches the catalog JSON and
 * downloads pack ZIPs.
 *
 * The manager's existing clients each do half of this job —
 * [DevManifestHttpClient] fetches manifests but cannot download, and
 * [ReleaseAssetHttpClient] downloads but rejects non-APK files. This
 * client follows both, with the same single-CDN-hop validation for
 * every redirect (GitHub `releases/download/…` 302s to its release
 * CDN). Theme ZIPs are verified by SHA-256 against the catalog before
 * use — the transport is not the trust boundary.
 *
 * Pure JVM: no Android APIs.
 */
class ThemeHttpClient : HttpClient {

    companion object {
        private const val MAX_REDIRECTS = 5
    }

    private val hopCheck = ReleaseAssetHttpClient()

    @Throws(IOException::class)
    override fun get(url: String): HttpResponse {
        var current = url
        var cdnHopUsed = false
        var redirects = 0
        while (true) {
            hopCheck.checkDownloadHop(current, startUrl = url, cdnHopUsed = cdnHopUsed)
            if (current != url) cdnHopUsed = true
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location header")
                    if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.readBytes() ?: ByteArray(0)
                return HttpResponse(code, body, emptyMap())
            } finally {
                conn.disconnect()
            }
        }
    }

    @Throws(IOException::class)
    override fun download(
        url: String,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        var current = url
        var cdnHopUsed = false
        var redirects = 0
        while (true) {
            hopCheck.checkDownloadHop(current, startUrl = url, cdnHopUsed = cdnHopUsed)
            if (current != url) cdnHopUsed = true
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location header")
                    if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                    continue
                }
                if (code !in 200..299) throw IOException("Theme download failed: HTTP $code")
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

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "CrystalNovaManager/1.0")
        }
}
