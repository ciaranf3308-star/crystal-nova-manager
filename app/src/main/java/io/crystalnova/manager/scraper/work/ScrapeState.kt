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

data class IndexEntry(
    val key: String,
    val title: String,
    val platform: String,
    val completeness: Completeness,
    val real: Int,
    val generated: Int,
)
