package io.crystalnova.manager.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the self-updater learned from the latest GitHub release.
 *
 * @property version release version without the leading "v", e.g. "1.0.2-u1".
 * @property tag the raw release tag, e.g. "v1.0.2-u1".
 * @property apkUrl the exact `browser_download_url` from the release JSON.
 *   It was fetched inside the endpoint boundary, so it is trusted verbatim
 *   as the download start URL.
 */
data class SelfUpdateInfo(
    val version: String,
    val tag: String,
    val apkUrl: String,
)

/**
 * Checks the manager's own GitHub releases for a newer build and
 * downloads the release APK.
 *
 * No account, no token — the repository is public. Stable channel only:
 * the `releases/latest` endpoint never returns drafts or prereleases.
 */
open class SelfUpdateChecker(
    private val http: HttpClient = UrlConnectionHttpClient(),
    private val assetHttp: HttpClient = ReleaseAssetHttpClient(),
) {
    /**
     * Returns the newer release, or null when this build is current.
     * Throws [IOException] on network failure — the caller treats that
     * as "could not check", never as an update.
     */
    @Throws(IOException::class)
    open fun check(currentVersion: String): SelfUpdateInfo? {
        val resp = http.get(GitHubEndpoints.managerLatestReleaseApi())
        if (resp.code != 200) throw IOException("GitHub API returned HTTP ${resp.code}")
        val info = try {
            parseRelease(resp.bodyText())
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Malformed GitHub release response", e)
        }
        // Pins the asset URL to github.com/<owner>/crystal-nova-manager/…
        GitHubEndpoints.checkAllowed(info.apkUrl)
        return if (compareVersions(info.version, currentVersion.trim()) > 0) info else null
    }

    /** Downloads the release APK to [dest] with progress. Throws on failure. */
    @Throws(IOException::class)
    open fun download(
        info: SelfUpdateInfo,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        assetHttp.download(info.apkUrl, dest, onProgress)
    }

    internal fun parseRelease(body: String): SelfUpdateInfo {
        val o = JSONObject(body)
        val tag = o.optString("tag_name", "").trim()
        val version = tag.removePrefix("v").trim()
        if (version.isEmpty()) throw IOException("Release has no tag_name")
        val assets = o.optJSONArray("assets") ?: throw IOException("Release $tag has no assets")
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (!a.optString("name", "").endsWith(".apk", ignoreCase = true)) continue
            val url = a.optString("browser_download_url", "").trim()
            if (url.isEmpty()) continue
            return SelfUpdateInfo(version = version, tag = tag, apkUrl = url)
        }
        throw IOException("Release $tag has no APK asset")
    }
}

/**
 * Downloads a release APK whose URL came from the trusted release JSON.
 *
 * GitHub answers the asset URL with a redirect to its release CDN
 * (`release-assets.githubusercontent.com`; `objects.githubusercontent.com`
 * has appeared in this chain historically and stays allowed). That single
 * CDN hop is the only exception to the endpoint boundary in the whole app
 * — the theme updater never follows it (codeload serves the theme ZIP
 * directly, and the boundary test pins that rejection). Every hop is
 * validated before use: https only, at most one CDN hop, no other host.
 * The downloaded file must start with the ZIP/APK magic or it is discarded.
 */
class ReleaseAssetHttpClient : HttpClient {

    companion object {
        /**
         * GitHub release-CDN hosts. In practice `releases/download/…`
         * 302-redirects to `release-assets.githubusercontent.com`, which
         * serves the file directly; `objects.githubusercontent.com` is
         * kept because it has appeared in this chain historically.
         */
        private val RELEASE_CDN_HOSTS = setOf(
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
        private const val MAX_REDIRECTS = 5
    }

    override fun get(url: String): HttpResponse =
        throw UnsupportedOperationException("ReleaseAssetHttpClient only downloads")

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
            checkDownloadHop(current, startUrl = url, cdnHopUsed = cdnHopUsed)
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
                if (code !in 200..299) throw IOException("App download failed: HTTP $code")
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
                if (!isApk(tmp)) {
                    tmp.delete()
                    throw IOException("Downloaded file is not an APK")
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

    /**
     * Validates one download hop. The start URL goes through the full
     * endpoint boundary; the single permitted redirect target is the
     * GitHub release CDN over https. Anything else throws.
     */
    fun checkDownloadHop(url: String, startUrl: String, cdnHopUsed: Boolean) {
        if (url == startUrl) {
            GitHubEndpoints.checkAllowed(url)
            return
        }
        if (cdnHopUsed) throw SecurityException("Second redirect during app download")
        val u = try {
            URL(url)
        } catch (e: Exception) {
            throw SecurityException("Malformed redirect URL", e)
        }
        if (!u.protocol.equals("https", ignoreCase = true) ||
            !RELEASE_CDN_HOSTS.contains(u.host.lowercase())
        ) {
            throw SecurityException("Unexpected redirect during app download: ${u.host}")
        }
    }

    /** APKs are ZIPs — sanity-check the magic before handing off to the installer. */
    private fun isApk(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            // APK downloads are multi-megabyte; a 30s stall timeout is
            // tight on slow handheld WiFi (the tiny manifest fetch is
            // unaffected — it uses its own client).
            readTimeout = 60_000
            setRequestProperty("User-Agent", "CrystalNovaManager/1.0")
        }
}
