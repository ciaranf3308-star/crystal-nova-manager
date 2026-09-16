package io.crystalnova.manager.data

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Talks to GitHub for the Crystal Nova theme. No account, no token —
 * the repository is public.
 */
class GitHubRepository(
    private val http: HttpClient = UrlConnectionHttpClient(),
    val channel: UpdateChannel = UpdateChannel.Stable,
) {
    data class RemoteInfo(
        /** Full commit SHA from the GitHub API — authoritative "latest". */
        val sha: String,
        /** Parsed crystal-version.json, or null when missing/malformed. */
        val version: VersionInfo?,
    )

    /** Latest commit SHA + remote version marker. Throws on network failure. */
    @Throws(IOException::class)
    fun fetchLatest(): RemoteInfo {
        val sha = fetchSha()
        val version = fetchVersionFile()
        return RemoteInfo(sha = sha, version = version)
    }

    @Throws(IOException::class)
    fun fetchSha(): String {
        val resp = http.get(GitHubEndpoints.commitApi(channel.branch))
        if (resp.code != 200) throw IOException("GitHub API returned HTTP ${resp.code}")
        val sha = try {
            JSONObject(resp.bodyText()).optString("sha", "")
        } catch (e: Exception) {
            throw IOException("Malformed GitHub API response", e)
        }
        if (sha.length < 7) throw IOException("Malformed GitHub API response")
        return sha
    }

    /** Returns null when the file is missing or malformed — never throws for that. */
    fun fetchVersionFile(): VersionInfo? = try {
        val resp = http.get(GitHubEndpoints.versionFile(channel.branch))
        if (resp.code != 200) null else VersionInfo.parse(resp.bodyText())
    } catch (_: IOException) {
        null
    }

    @Throws(IOException::class)
    fun downloadZip(dest: File, onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit) {
        http.download(GitHubEndpoints.zipball(channel.branch), dest, onProgress)
    }
}
