package io.crystalnova.manager.scraper.provider

import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Region

/**
 * Query for artwork/metadata. Provider-agnostic; each provider maps the
 * platform slug onto its own naming (e.g. Libretro playlist names).
 */
data class ScrapeQuery(
    val platformSlug: String,
    /** Display title: Pegasus metadata title, else filename-derived. */
    val title: String,
    /** ROM file name with extension, e.g. "Mario Golf (E).gba". */
    val fileName: String,
    val region: Region,
)

/** One downloadable artwork candidate, in try-order. */
data class ArtworkCandidate(
    val url: String,
    val region: Region? = null,
)

/**
 * Keyless-or-keyed artwork source. Implementations return ordered
 * candidate URLs per slot; the scrape job tries them in order and keeps
 * the first successful download.
 */
interface ArtworkProvider {
    val id: String
    val displayName: String
    /** True when the provider needs a user-supplied API key. */
    val requiresApiKey: Boolean

    suspend fun artworkFor(query: ScrapeQuery): Map<AssetSlot, List<ArtworkCandidate>>
}

/** Structured game metadata (title, description, credits…). */
data class GameMetadata(
    val title: String?,
    val description: String?,
    val developer: String?,
    val publisher: String?,
    val releaseYear: String?,
    val genre: String?,
    val players: String?,
    val providerGameId: String?,
)

/**
 * Structured metadata source. The U2 working stack uses Pegasus files;
 * a future TheGamesDB (or other) implementation slots in here without
 * touching the rest of the scraper.
 */
interface MetadataProvider {
    val id: String
    val displayName: String
    val requiresApiKey: Boolean
    suspend fun lookup(query: ScrapeQuery): GameMetadata?
}
