package io.crystalnova.manager.scraper.model

/**
 * The internal asset slots Crystal always exposes per game. The renderer
 * (and the future physical-case view) reads these slots and never cares
 * whether an asset is a real scan, a generated fallback, or user-supplied.
 */
enum class AssetSlot(val fileName: String) {
    BOX_FRONT("front.png"),
    BOX_SPINE("spine.png"),
    BOX_BACK("back.png"),
    PHYSICAL_MEDIA("media.png"),
    FULL_COVER("fullcover.png"),
    CLEAR_LOGO("logo.png"),
    SCREENSHOT("screenshot.png"),
}

/** Where an asset came from. Stored per asset in the game manifest. */
enum class SourceType {
    /** Authentic scan/photo from a provider. */
    REAL,
    /** Built by a Crystal generator (spine/back/media fallbacks). */
    GENERATED,
    /** Supplied by the user. Always wins; never auto-overwritten. */
    USER,
}

/** Physical media shape for generated media art. UNKNOWN never renders as a cartridge. */
enum class MediaKind {
    DISC,
    CARTRIDGE,
    UMD,
    GENERIC,
    UNKNOWN,
}

/** Game region. UNKNOWN is valid — v1 does not deep-parse disc images. */
enum class Region(val code: String) {
    EUROPE("EUR"),
    USA("USA"),
    JAPAN("JPN"),
    WORLD("WLD"),
    ASIA("ASI"),
    KOREA("KOR"),
    AUSTRALIA("AUS"),
    UNKNOWN("UNK"),
}

/**
 * Provenance for one asset file. Every asset carries this in the game
 * manifest so replacement/refresh can reason about it later.
 */
data class AssetProvenance(
    val sourceType: SourceType,
    /** Provider id, e.g. "libretro", "pegasus", or "crystal" for generated. */
    val provider: String,
    val providerGameId: String? = null,
    val region: Region? = null,
    val sourceUrl: String? = null,
    /** Path relative to crystal-nova-data/, e.g. games/psx/abc/front.png */
    val localPath: String,
    /** SHA-256 of the stored file, when known. */
    val sha256: String? = null,
    /** Generator id for GENERATED assets, e.g. "crystal-spine-v1". */
    val generatedBy: String? = null,
)

/** How completely a game is covered. Generated fallbacks count as complete. */
enum class Completeness {
    NO_MATCH,
    METADATA_ONLY,
    FRONT_ONLY,
    PARTIAL_CASE,
    COMPLETE_CASE,
    COMPLETE_CASE_AND_MEDIA,
}

/** Confidence of the provider link. Weak fuzzy matches are never auto-accepted. */
sealed interface MatchConfidence {
    data object Stored : MatchConfidence
    data object ExactTitle : MatchConfidence
    data class Fuzzy(val score: Double) : MatchConfidence
    data object Manual : MatchConfidence
    data object None : MatchConfidence
}

/**
 * One scraped game. Identity is platform + normalized relative ROM path
 * (plus cached size/mtime) — full ROM hashing is on-demand only.
 */
data class ScrapedGame(
    val platform: String,
    /** Slug derived from the ROM basename, e.g. "mario-golf-advance-tour". */
    val gameId: String,
    /** ROM path relative to the games root, e.g. "gba/Mario Golf (E).gba". */
    val romRelativePath: String,
    val fileSize: Long = -1,
    val lastModified: Long = -1,
    val title: String,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val releaseYear: String? = null,
    val genre: String? = null,
    val players: String? = null,
    val region: Region = Region.UNKNOWN,
    /** Provider link, e.g. provider="libretro", providerGameId="<system>/<name>". */
    val provider: String? = null,
    val providerGameId: String? = null,
    val confidence: MatchConfidence = MatchConfidence.None,
    val assets: Map<AssetSlot, AssetProvenance> = emptyMap(),
) {
    val completeness: Completeness
        get() {
            val hasFront = assets.containsKey(AssetSlot.BOX_FRONT)
            val hasSpine = assets.containsKey(AssetSlot.BOX_SPINE)
            val hasBack = assets.containsKey(AssetSlot.BOX_BACK)
            val hasMedia = assets.containsKey(AssetSlot.PHYSICAL_MEDIA)
            return when {
                assets.isEmpty() && provider == null -> Completeness.NO_MATCH
                !hasFront -> Completeness.METADATA_ONLY
                hasFront && hasSpine && hasBack && hasMedia -> Completeness.COMPLETE_CASE_AND_MEDIA
                hasFront && hasSpine && hasBack -> Completeness.COMPLETE_CASE
                hasFront && (hasSpine || hasBack || hasMedia) -> Completeness.PARTIAL_CASE
                else -> Completeness.FRONT_ONLY
            }
        }

    val realAssetCount: Int get() = assets.values.count { it.sourceType == SourceType.REAL }
    val generatedAssetCount: Int get() = assets.values.count { it.sourceType == SourceType.GENERATED }
}
