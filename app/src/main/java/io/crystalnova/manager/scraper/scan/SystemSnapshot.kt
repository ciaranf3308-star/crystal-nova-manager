package io.crystalnova.manager.scraper.scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight persistence of the last successful scan's system summary.
 *
 * After a restart or APK update the in-memory scan is gone; without
 * this the library grid (and the Pegasus Setup counts) fall back to
 * `0 SYSTEMS` until the next scan. The snapshot stores only
 * folder/slug/label/game-count per system — never the ROM catalogue.
 * Pegasus injection still performs its own authoritative scan.
 *
 * Pure encode/decode; the store/restore wiring lives in ScraperManager.
 */
object SystemSnapshot {
    fun encode(systems: List<DiscoveredSystem>): String =
        JSONArray(
            systems.map { s ->
                JSONObject()
                    .put("folder", s.sourceFolderName)
                    .put("slug", s.platformSlug)
                    .put("label", s.label)
                    .put("games", s.gameCount)
            },
        ).toString()

    /**
     * Decodes a snapshot. Returns null on any malformed input — the
     * caller treats that as "no snapshot", never as a failure.
     */
    fun decode(json: String): List<DiscoveredSystem>? =
        try {
            JSONArray(json).let { arr ->
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    DiscoveredSystem(
                        platformSlug = o.getString("slug"),
                        label = o.getString("label"),
                        gameCount = o.getInt("games"),
                        sourceFolderName = o.getString("folder"),
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
}

/**
 * Snapshot-restore decision for the library grid. Restores the cached
 * system summary only when there is no live scan result and a games
 * folder is picked — it never clobbers live data and never invents
 * systems for an unconfigured install. Pure for unit tests.
 */
internal fun restoredSystems(
    live: List<DiscoveredSystem>,
    gamesFolderPicked: Boolean,
    snapshotJson: String?,
): List<DiscoveredSystem> {
    if (live.isNotEmpty() || !gamesFolderPicked) return live
    if (snapshotJson.isNullOrBlank()) return live
    return SystemSnapshot.decode(snapshotJson) ?: live
}
