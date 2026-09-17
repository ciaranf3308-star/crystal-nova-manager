package io.crystalnova.manager.scraper.work

import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.scan.DiscoveredSystem

/** Per-game live status during a scrape run. */
data class GameScrapeStatus(
    val title: String,
    val system: String,
    val stage: String,
    val front: SlotState = SlotState.PENDING,
    val spine: SlotState = SlotState.PENDING,
    val back: SlotState = SlotState.PENDING,
    val media: SlotState = SlotState.PENDING,
)

enum class SlotState { PENDING, WORKING, REAL, GENERATED, FAILED, SKIPPED }

/** Aggregate progress for the SCRAPER UI. */
data class ScrapeProgress(
    val total: Int,
    val done: Int,
    val succeeded: Int,
    val partial: Int,
    val failed: Int,
    val unmatched: Int,
    val current: GameScrapeStatus? = null,
    val cancelled: Boolean = false,
)

/** Dashboard stats read from index.json (no manifest reads needed). */
data class ScraperStats(
    val systems: List<DiscoveredSystem> = emptyList(),
    val totalGames: Int = 0,
    val complete: Int = 0,
    val partial: Int = 0,
    val unmatched: Int = 0,
    val realAssets: Int = 0,
    val generatedAssets: Int = 0,
) {
    companion object {
        fun fromEntries(entries: Collection<IndexEntry>): ScraperStats {
            var complete = 0
            var partial = 0
            var unmatched = 0
            var real = 0
            var generated = 0
            for (e in entries) {
                when (e.completeness) {
                    Completeness.COMPLETE_CASE, Completeness.COMPLETE_CASE_AND_MEDIA -> complete++
                    Completeness.NO_MATCH -> unmatched++
                    else -> partial++
                }
                real += e.real
                generated += e.generated
            }
            return ScraperStats(
                totalGames = entries.size,
                complete = complete,
                partial = partial,
                unmatched = unmatched,
                realAssets = real,
                generatedAssets = generated,
            )
        }
    }
}

/** Per-platform dashboard stats: index completeness joined with the live scan for label + game count. */
data class SystemStats(
    val slug: String,
    val label: String,
    val games: Int,
    val complete: Int,
    val partial: Int,
    val unmatched: Int,
) {
    companion object {
        /**
         * Builds one [SystemStats] per discovered system from index
         * [entries] (completeness via the same mapping as
         * [ScraperStats.fromEntries]) joined with [systems] for label and
         * live game count. Systems with no index entries yet report zeros.
         */
        fun perSystem(
            entries: Collection<IndexEntry>,
            systems: List<DiscoveredSystem>,
        ): Map<String, SystemStats> {
            val byPlatform = entries.groupBy { it.platform }
            return systems.associate { system ->
                val platformEntries = byPlatform[system.platformSlug].orEmpty()
                var complete = 0
                var partial = 0
                var unmatched = 0
                for (e in platformEntries) {
                    when (e.completeness) {
                        Completeness.COMPLETE_CASE,
                        Completeness.COMPLETE_CASE_AND_MEDIA -> complete++
                        Completeness.NO_MATCH -> unmatched++
                        else -> partial++
                    }
                }
                system.platformSlug to SystemStats(
                    slug = system.platformSlug,
                    label = system.label,
                    games = system.gameCount,
                    complete = complete,
                    partial = partial,
                    unmatched = unmatched,
                )
            }
        }
    }
}

data class IndexEntry(
    val key: String,
    val title: String,
    val platform: String,
    val completeness: Completeness,
    val real: Int,
    val generated: Int,
)

/**
 * Point-in-time diagnostics for the hidden Diagnostics screen.
 * Assembled by ScraperManager; every field is safe to render as-is.
 */
data class ScraperDiagnostics(
    val gamesFolderUri: String?,
    val mediaFolderUri: String? = null,
    val indexFound: Boolean,
    val indexParseOk: Boolean,
    val indexGames: Int,
    val scannedGames: Int,
    val systems: List<DiscoveredSystem>,
    val stats: ScraperStats,
    val notice: String?,
    val lastError: String?,
    val lastProgress: ScrapeProgress?,
    /** The media root proved WRITABLE (real create/write/read/delete probe) right now. */
    val mediaAccess: Boolean = false,
    /** Whether the Pegasus theme can find the media root. */
    val bridgeStatus: BridgeStatus = BridgeStatus.NOT_REQUIRED,
    /**
     * One representative scraped game with per-slot file presence, so
     * Diagnostics proves the theme-visible path end to end — not just
     * that bytes were written. Null when the index has no games.
     */
    val representative: MediaGameReport? = null,
)

/**
 * Theme bridge state for `crystal-media-bridge.json`:
 * - PRESENT: the theme reads the media root through the bridge.
 * - NOT_REQUIRED: no dedicated media folder — the theme falls back to
 *   its legacy `../crystal-nova-data/` lookup beside the themes root.
 * - FAILED: a media folder is configured but the bridge file is absent,
 *   so the theme cannot find the media. Surfaced, never silent.
 */
enum class BridgeStatus { PRESENT, NOT_REQUIRED, FAILED }

/** One asset slot's theme-visible path, presence, and provenance. */
data class AssetPresence(
    /** Theme-visible relative path, e.g. `games/gba/mario-golf/front.png`. */
    val path: String,
    val present: Boolean,
    /** REAL / GENERATED / USER, or null when the slot was never stored. */
    val provenance: String?,
)

/** End-to-end media visibility report for one scraped game. */
data class MediaGameReport(
    val platform: String,
    val gameId: String,
    val title: String,
    val completeness: String,
    val front: AssetPresence,
    val spine: AssetPresence,
    val back: AssetPresence,
    val media: AssetPresence,
)
