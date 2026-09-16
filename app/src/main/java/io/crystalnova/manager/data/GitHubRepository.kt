package io.crystalnova.manager.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub endpoints and the security boundary around them.
 *
 * Only these hosts are ever contacted, and repository-scoped paths must
 * stay inside ciaranf3308-star/crystal-nova-pegasus-theme:
 *   - api.github.com             → commit SHA lookup
 *   - raw.githubusercontent.com → crystal-version.json
 *   - codeload.github.com        → ZIP download (serves the archive directly;
 *                                  no CDN hop is followed)
 */
object GitHubEndpoints {
    const val OWNER = "ciaranf3308-star"
    const val REPO = "crystal-nova-pegasus-theme"

    private val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "raw.githubusercontent.com",
        "github.com",
        "codeload.github.com",
    )
    private val REPO_PATH_HOSTS = setOf(
        "api.github.com",
        "raw.githubusercontent.com",
        "github.com",
        "codeload.github.com",
    )

    fun commitApi(branch: String): String =
        "https://api.github.com/repos/$OWNER/$REPO/commits/$branch"

    fun versionFile(branch: String): String =
        "https://raw.githubusercontent.com/$OWNER/$REPO/$branch/crystal-version.json"

    fun zipball(branch: String): String =
        "https://codeload.github.com/$OWNER/$REPO/zip/refs/heads/$branch"

    /** Throws [SecurityException] when the URL leaves the allowed boundary. */
    fun checkAllowed(url: String) {
        val u = try {
            URL(url)
        } catch (e: Exception) {
            throw SecurityException("Malformed URL", e)
        }
        if (!u.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("Only https is allowed")
        }
        val host = u.host.lowercase()
        if (host !in ALLOWED_HOSTS) {
            throw SecurityException("Host not allowed: ${u.host}")
        }
        if (host in REPO_PATH_HOSTS && "/$OWNER/$REPO" !in u.path) {
            throw SecurityException("URL escapes $OWNER/$REPO: ${u.path}")
        }
    }
}

/**
 * Plain HttpURLConnection client with manual redirect handling so every
 * hop is re-checked against [GitHubEndpoints.checkAllowed]. No GitHub
 * account or token is required for this public repository.
 */
class UrlConnectionHttpClient : HttpClient {
    companion object {
        private const val MAX_REDIRECTS = 5
    }

    override fun get(url: String): HttpResponse = getInternal(url)

    private fun getInternal(url: String): HttpResponse {
        var current = url
        repeat(MAX_REDIRECTS) {
            GitHubEndpoints.checkAllowed(current)
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
            GitHubEndpoints.checkAllowed(current)
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
            readTimeout = 30_000
            setRequestProperty("User-Agent", "CrystalNovaManager/1.0")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
}
