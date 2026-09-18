package io.crystalnova.manager.data

import org.json.JSONObject

/**
 * Machine-readable release marker (crystal-version.json).
 *
 * Kept separate: semantic [version] for display/channel gating and [commit]
 * for exact "what is installed" comparisons. A null return from [parse]
 * means the marker is malformed — never guess from it.
 */
data class VersionInfo(
    val version: String,
    val commit: String,
    val channel: String,
) {
    /** Short SHA for display, e.g. "8a86a06". */
    val shortCommit: String get() = commit.take(7)

    companion object {
        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_BETA = "beta"

        fun parse(json: String): VersionInfo? = try {
            val o = JSONObject(json)
            val version = o.optString("version", "").trim()
            // The theme repo writes "sha"; accept "commit" as an alias.
            val commit = o.optString("commit", "").trim()
                .ifEmpty { o.optString("sha", "").trim() }
            val channel = o.optString("channel", "").trim().ifEmpty { CHANNEL_STABLE }
            if (version.isEmpty() || commit.isEmpty()) null
            else VersionInfo(version = version, commit = commit, channel = channel)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Semantic-version comparison. Numeric segments compare numerically,
 * non-numeric segments compare lexically; missing segments are 0.
 * Returns negative if a < b, zero if equal, positive if a > b.
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split(".")
    val pb = b.trim().split(".")
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val sa = pa.getOrElse(i) { "0" }
        val sb = pb.getOrElse(i) { "0" }
        val na = sa.toIntOrNull()
        val nb = sb.toIntOrNull()
        val cmp = if (na != null && nb != null) na.compareTo(nb) else sa.compareTo(sb)
        if (cmp != 0) return cmp
    }
    return 0
}
