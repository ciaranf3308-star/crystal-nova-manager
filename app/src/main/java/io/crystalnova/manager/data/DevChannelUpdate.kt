package io.crystalnova.manager.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * DEV / CANDIDATE channel for the manager's self-updater.
 *
 * The rolling `dev-latest` prerelease carries a manifest.json next to
 * the APK. This file holds the manifest model, its parser, the update
 * decision, and the checker that fetches and verifies it — all pure
 * JVM Kotlin: no org.json (stubbed under unit tests), no Android APIs,
 * no logging.
 */
const val DEV_MANIFEST_URL =
    "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/manifest.json"

/** Asset name CI publishes on the rolling prerelease. */
const val DEV_APK_NAME = "crystal-nova-manager-dev.apk"

/**
 * Parsed dev-latest manifest.json, as written by the release workflow.
 */
data class DevUpdateManifest(
    val versionName: String,
    val versionCode: Int,
    val apkSha256: String,
    val apkUrl: String,
    val commitSha: String,
    val builtAt: String,
)

/**
 * Minimal JSON object parser for the known-flat manifest shape.
 *
 * Handles string values (with standard escapes) and bare scalars
 * (numbers, booleans, null — kept verbatim as strings). Returns null
 * on any malformed input — never throws, never guesses.
 */
internal fun parseFlatJsonObject(json: String): Map<String, String>? {
    val s = json.trim()
    if (s.length < 2 || !s.startsWith("{") || !s.endsWith("}")) return null
    val map = LinkedHashMap<String, String>()
    var i = 1
    val end = s.length

    fun skipWs() {
        while (i < end && s[i].isWhitespace()) i++
    }

    fun parseString(): String? {
        if (i >= end || s[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < end) {
            val c = s[i]
            when {
                c == '"' -> {
                    i++
                    return sb.toString()
                }
                c == '\\' -> {
                    i++
                    if (i >= end) return null
                    when (val e = s[i]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            if (i + 4 >= end) return null
                            val code = s.substring(i + 1, i + 5).toIntOrNull(16)
                                ?: return null
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                    i++
                }
                c == '\n' || c == '\r' -> return null
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** Reads a bare scalar (number/true/false/null), tolerating nesting. */
    fun parseBare(): String? {
        val start = i
        var depth = 0
        while (i < end) {
            val c = s[i]
            when {
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> {
                    if (depth == 0) break
                    depth--
                }
                c == ',' && depth == 0 -> break
                c == '"' -> return null // strings must go through parseString
            }
            i++
        }
        if (i == start) return null
        return s.substring(start, i).trim()
    }

    skipWs()
    if (i < end && s[i] == '}') return map // empty object
    while (true) {
        skipWs()
        val key = parseString() ?: return null
        skipWs()
        if (i >= end || s[i] != ':') return null
        i++
        skipWs()
        val parsed: String? = if (i < end && s[i] == '"') parseString() else parseBare()
        val value = parsed ?: return null
        map[key] = value
        skipWs()
        if (i >= end) return null
        when (s[i]) {
            ',' -> {
                i++
                continue
            }
            '}' -> {
                i++
                skipWs()
                return if (i == end) map else null
            }
            else -> return null
        }
    }
}

/**
 * Parses a dev manifest body. Returns null when the JSON is malformed
 * or any required field is missing/blank/invalid — the caller treats
 * that as "could not check", never as an update.
 */
fun parseDevManifest(json: String): DevUpdateManifest? {
    val map = parseFlatJsonObject(json) ?: return null
    fun field(name: String): String? = map[name]?.takeIf { it.isNotBlank() }
    val versionName = field("versionName") ?: return null
    val versionCode = map["versionCode"]?.toIntOrNull() ?: return null
    val apkSha256 = field("apkSha256")
        ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) } ?: return null
    val apkUrl = field("apkUrl") ?: return null
    val commitSha = field("commitSha") ?: return null
    val builtAt = field("builtAt") ?: return null
    return DevUpdateManifest(
        versionName = versionName,
        versionCode = versionCode,
        apkSha256 = apkSha256,
        apkUrl = apkUrl,
        commitSha = commitSha,
        builtAt = builtAt,
    )
}

/**
 * Pure update decision for the DEV channel: the manifest's versionCode
 * is the authority, compared against the installed versionCode from
 * the package manager / BuildConfig.
 */
fun isDevUpdateAvailable(manifest: DevUpdateManifest, installedVersionCode: Int): Boolean =
    manifest.versionCode > installedVersionCode

/** Lowercase hex SHA-256 of a file. Pure JVM. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Fetches the dev manifest over plain HTTPS (no auth). GitHub answers
 * `releases/download/…` with a 302 to its release CDN, so this client
 * reuses [ReleaseAssetHttpClient]'s single-CDN-hop validation for every
 * redirect — the same hop exception the APK downloader already relies
 * on, and nothing wider. The general [GitHubEndpoints.checkAllowed]
 * boundary would reject the CDN host, so plain [UrlConnectionHttpClient]
 * cannot fetch this URL.
 */
class DevManifestHttpClient : HttpClient {
    companion object {
        private const val MAX_REDIRECTS = 5
    }

    private val hopCheck = ReleaseAssetHttpClient()

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

    override fun download(
        url: String,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Unit = throw UnsupportedOperationException("DevManifestHttpClient only fetches the manifest")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "CrystalNovaManager/1.0")
        }
}

/**
 * Checks the rolling dev-latest manifest for a newer manager build and
 * downloads its APK with SHA-256 verification.
 *
 * No account, no token — the repository is public.
 */
open class DevUpdateChecker(
    private val manifestHttp: HttpClient = DevManifestHttpClient(),
    private val assetHttp: HttpClient = ReleaseAssetHttpClient(),
) {
    /**
     * Returns the dev manifest when it describes a newer build than
     * [installedVersionCode], or null when current. Throws [IOException]
     * on network failure or a malformed manifest — the caller treats
     * that as "could not check", never as an update.
     */
    @Throws(IOException::class)
    open fun check(installedVersionCode: Int): DevUpdateManifest? {
        // Cache-buster: GitHub's CDN aggressively caches the rolling
        // dev-latest manifest. Appending a timestamp forces a fresh fetch.
        val url = "$DEV_MANIFEST_URL?t=${System.currentTimeMillis()}"
        val resp = manifestHttp.get(url)
        if (resp.code != 200) throw IOException("Dev manifest returned HTTP ${resp.code}")
        val manifest = parseDevManifest(resp.bodyText())
            ?: throw IOException("Malformed dev manifest")
        // Pins the APK URL to the manager repo, exactly like the stable
        // checker's release-JSON pinning.
        GitHubEndpoints.checkAllowed(manifest.apkUrl)
        return if (isDevUpdateAvailable(manifest, installedVersionCode)) manifest else null
    }

    /**
     * Downloads the manifest's APK to [dest] and verifies its SHA-256
     * against the manifest before returning. A mismatch deletes the
     * file and throws — the installer never sees an unverified APK.
     */
    @Throws(IOException::class)
    open fun download(
        manifest: DevUpdateManifest,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        // Cache-buster for the APK too — same CDN issue as the manifest.
        val url = "${manifest.apkUrl}?t=${System.currentTimeMillis()}"
        assetHttp.download(url, dest, onProgress)
        if (!sha256Hex(dest).equals(manifest.apkSha256, ignoreCase = true)) {
            dest.delete()
            throw IOException("Dev APK checksum mismatch")
        }
    }
}
