package io.crystalnova.manager.scraper.esde

import io.crystalnova.manager.pegasus.MetafileGenerator
import io.crystalnova.manager.scraper.match.TitleNormalizer
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.provider.PegasusMetadataReader
import org.json.JSONObject

/**
 * Media Health Check (u34).
 *
 * Diagnoses why games render BLANK artwork in Pegasus even when the
 * Manager reports them as matched. Read-only: it never writes the
 * index, the media tree, or any metafile.
 *
 * It replays the Pegasus theme's exact artwork-resolution chain
 * (components/CrystalAssets.js) for every game in every
 * `*.metadata.pegasus.txt` on the card:
 *
 *  1. `platformSlug(shortName)`: trim + lowercase the collection's
 *     `shortname:`; identity when it is a known platform slug, else
 *     the SHORTNAME_ALIASES table, else "" — an empty slug kills art
 *     for the whole collection.
 *  2. `themeKey = platformSlug(shortName) + "/" + slugify(basename of
 *     game.files[0])`, falling back to `slugify(game title)` when the
 *     game has no file. slugify is the Manager's [TitleNormalizer],
 *     which the theme mirrors bit-for-bit — never reimplement it.
 *  3. `byId[themeKey]`, falling back to
 *     `byTitle[platformSlug + "/" + slugify(title)]`.
 *  4. The theme trusts the index: it builds the asset URL only when
 *     the entry's `assets[]` claims the slot. A claimed slot whose
 *     PNG is missing (or zero bytes) on disk 404s and the game
 *     renders blank.
 *
 * Anything the theme would render blank is classified accordingly,
 * plus duplicate `collection:` declarations across metafiles (the
 * "three Sega Genesis" symptom) and Manager→theme shortname
 * round-trip failures are detected.
 */
object MediaHealthCheck {

    /** Exact port of the theme's PLATFORM_SLUGS. */
    val PLATFORM_SLUGS: Set<String> = setOf(
        "nes", "snes", "n64", "gamecube", "gb", "gbc", "gba", "nds", "n3ds",
        "genesis", "mastersystem", "gamegear", "segacd", "saturn", "dreamcast",
        "psx", "ps2", "psp", "atari2600", "atari7800", "lynx", "wonderswan",
        "ngp", "virtualboy", "pcengine", "3do", "amiga", "c64", "arcade",
    )

    /** Exact port of the theme's SHORTNAME_ALIASES. */
    val SHORTNAME_ALIASES: Map<String, String> = mapOf(
        "gc" to "gamecube",
        "md" to "genesis", "megadrive" to "genesis",
        "sms" to "mastersystem",
        "gg" to "gamegear",
        "megacd" to "segacd",
        "ps1" to "psx", "psone" to "psx",
        "3ds" to "n3ds",
        "tg16" to "pcengine", "turbografx" to "pcengine", "turbografx16" to "pcengine",
        "a2600" to "atari2600", "a7800" to "atari7800",
        "ws" to "wonderswan", "wsc" to "wonderswan",
        "vboy" to "virtualboy",
        "neogeo" to "arcade", "mame" to "arcade", "fba" to "arcade",
    )

    /** Slot stem (index `assets[]` value) -> AssetSlot, e.g. "front" -> BOX_FRONT. */
    val SLOT_BY_STEM: Map<String, AssetSlot> =
        AssetSlot.values().associateBy { it.fileName.substringBefore('.') }

    private val CLEAN_SLUG = Regex("^[a-z0-9-]+$")

    /**
     * Exact port of the theme's `platformSlug(shortName)`.
     * Returns "" when the shortname maps to nothing — the theme then
     * produces an empty game key and the whole collection goes blank.
     */
    fun platformSlug(shortName: String?): String {
        if (shortName == null) return ""
        val k = shortName.trim().lowercase()
        if (k in PLATFORM_SLUGS) return k
        return SHORTNAME_ALIASES[k] ?: ""
    }

    /** Basename of a ROM path, extension kept — slugify strips it, exactly like the theme. */
    fun romBasename(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Exact mirror of the theme's `gameKey(game, shortName)`:
     * `platformSlug(shortName) + "/" + slugify(basename(files[0]))`,
     * title-derived when the game has no file. "" when unresolvable.
     */
    fun themeKey(
        shortName: String?,
        files: List<String>,
        title: String?,
    ): String {
        val plat = platformSlug(shortName)
        if (plat.isEmpty()) return ""
        val base = files.firstOrNull()?.let(::romBasename)?.takeIf { it.isNotEmpty() }
        val id = if (base != null) TitleNormalizer.slugify(base)
        else TitleNormalizer.slugify(title.orEmpty())
        if (id.isEmpty()) return ""
        return "$plat/$id"
    }

    /** One index.json entry, platform normalized exactly like the theme's parseIndex(). */
    data class IndexEntry(
        val platform: String,
        val gameId: String,
        val title: String,
        /** Slot stems from the index `assets[]` array, e.g. "front". */
        val assets: Set<String>,
    )

    /** byId + byTitle maps, built with the theme's parseIndex() rules (last-wins, bad entries dropped). */
    data class IndexMaps(
        val byId: Map<String, IndexEntry>,
        val byTitle: Map<String, IndexEntry>,
    )

    fun parseIndexMaps(json: String): IndexMaps? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("version", 0) != 1) return null
            val games = root.optJSONObject("games") ?: return IndexMaps(emptyMap(), emptyMap())
            val byId = LinkedHashMap<String, IndexEntry>()
            val byTitle = LinkedHashMap<String, IndexEntry>()
            val keys = games.keys()
            while (keys.hasNext()) {
                val rawKey = keys.next()
                val e = games.optJSONObject(rawKey) ?: continue
                val plat = platformSlug(e.optString("platform", ""))
                val gid = e.optString("gameId", "")
                if (!CLEAN_SLUG.matches(plat) || !CLEAN_SLUG.matches(gid)) continue
                val assets = LinkedHashSet<String>()
                val list = e.optJSONArray("assets")
                if (list != null) {
                    for (i in 0 until list.length()) assets.add(list.optString(i))
                }
                val entry = IndexEntry(
                    platform = plat,
                    gameId = gid,
                    title = e.optString("title", ""),
                    assets = assets,
                )
                byId["$plat/$gid"] = entry
                byTitle["$plat/${TitleNormalizer.slugify(entry.title)}"] = entry
            }
            IndexMaps(byId, byTitle)
        } catch (_: Exception) {
            null
        }
    }

    /** Why the theme renders this game blank (or not). */
    enum class GameHealth {
        /** Resolves and every claimed slot has a real file. */
        OK,
        /** Metafile shortname maps to "" — the whole collection is invisible to the theme. */
        SHORTNAME_UNMAPPED,
        /** No byId hit (titleFallbackHit notes whether the title fallback would have saved it). */
        NO_INDEX_ENTRY,
        /** Index hit, but one or more claimed slots have no (or empty) PNG on disk. */
        ASSET_FILE_MISSING,
    }

    data class GameFinding(
        /** Display path of the metafile, e.g. "GC/crystal-nova.metadata.pegasus.txt". */
        val metafile: String,
        val collection: String?,
        val shortname: String?,
        val title: String,
        /** The key the theme computed ("" when unresolvable). */
        val themeKey: String,
        val titleFallbackHit: Boolean,
        val health: GameHealth,
        /** Slot stems claimed by the index but missing/empty on disk. */
        val missingSlots: List<String>,
    )

    /**
     * Classifies one metafile game exactly as the theme would resolve it.
     *
     * @param assetBytes returns the on-disk byte length of a slot file,
     *   or null when the file is absent. Zero counts as missing.
     */
    fun classify(
        metafile: String,
        entry: PegasusMetadataReader.Entry,
        byId: Map<String, IndexEntry>,
        byTitle: Map<String, IndexEntry>,
        assetBytes: (platform: String, gameId: String, slotStem: String) -> Long?,
    ): GameFinding {
        val plat = platformSlug(entry.shortname)
        if (plat.isEmpty()) {
            return GameFinding(
                metafile = metafile,
                collection = entry.collection,
                shortname = entry.shortname,
                title = entry.title,
                themeKey = "",
                titleFallbackHit = false,
                health = GameHealth.SHORTNAME_UNMAPPED,
                missingSlots = emptyList(),
            )
        }
        val key = themeKey(entry.shortname, entry.files, entry.title)
        val hit = byId[key]
        if (hit == null) {
            val fallbackKey = "$plat/${TitleNormalizer.slugify(entry.title)}"
            return GameFinding(
                metafile = metafile,
                collection = entry.collection,
                shortname = entry.shortname,
                title = entry.title,
                themeKey = key,
                titleFallbackHit = byTitle[fallbackKey] != null,
                health = GameHealth.NO_INDEX_ENTRY,
                missingSlots = emptyList(),
            )
        }
        val missing = hit.assets
            .sorted()
            .filter { stem ->
                val bytes = assetBytes(hit.platform, hit.gameId, stem)
                bytes == null || bytes <= 0L
            }
        return GameFinding(
            metafile = metafile,
            collection = entry.collection,
            shortname = entry.shortname,
            title = entry.title,
            themeKey = key,
            titleFallbackHit = false,
            health = if (missing.isEmpty()) GameHealth.OK else GameHealth.ASSET_FILE_MISSING,
            missingSlots = missing,
        )
    }

    /** A Manager platform slug whose written shortname the theme cannot map back. */
    data class ShortnameMismatch(
        val slug: String,
        /** What the Manager writes into `shortname:` (MetafileGenerator.shortnameFor). */
        val writtenShortname: String,
        /** What the theme's platformSlug() reads back ("" = whole collection blank). */
        val themeReadsAs: String,
    )

    /**
     * Verifies the Manager→theme shortname round-trip for every known
     * platform slug: `platformSlug(shortnameFor(slug))` must equal the
     * slug, or every game of that system renders blank in Pegasus.
     */
    fun checkShortnameRoundTrip(): List<ShortnameMismatch> =
        PLATFORM_SLUGS.sorted().mapNotNull { slug ->
            val written = MetafileGenerator.shortnameFor(slug)
            val readBack = platformSlug(written)
            if (readBack != slug) ShortnameMismatch(slug, written, readBack) else null
        }

    /** Same `collection:` value declared in 2+ metafiles — the duplicate-system symptom. */
    data class DuplicateCollection(
        val collection: String,
        val metafiles: List<String>,
    )

    fun findDuplicateCollections(
        metafileCollections: Map<String, Set<String>>,
    ): List<DuplicateCollection> =
        metafileCollections
            .filter { (_, files) -> files.size > 1 }
            .map { (collection, files) ->
                DuplicateCollection(collection, files.sorted())
            }
            .sortedBy { it.collection.lowercase() }

    /** Index claims slots whose PNGs are missing/empty on disk, for entries no metafile covered. */
    data class IndexDrift(
        val key: String,
        val title: String,
        val missingSlots: List<String>,
    )

    data class PlatformSummary(
        val platform: String,
        val games: Int,
        val ok: Int,
        val shortnameUnmapped: Int,
        val noIndexEntry: Int,
        val assetFileMissing: Int,
    ) {
        val blank: Int get() = shortnameUnmapped + noIndexEntry + assetFileMissing
    }

    data class HealthReport(
        val metafilesScanned: Int,
        val metafilePaths: List<String>,
        val gamesChecked: Int,
        val findings: List<GameFinding>,
        val duplicates: List<DuplicateCollection>,
        val shortnameMismatches: List<ShortnameMismatch>,
        val indexDrift: List<IndexDrift>,
        val errors: List<String>,
    ) {
        fun problemFindings(): List<GameFinding> =
            findings.filter { it.health != GameHealth.OK }

        fun platformSummaries(): List<PlatformSummary> {
            data class Acc(var games: Int = 0, var ok: Int = 0, var unmapped: Int = 0, var noIdx: Int = 0, var miss: Int = 0)
            val acc = LinkedHashMap<String, Acc>()
            for (f in findings) {
                // Group by the theme-resolved platform when available,
                // else the raw shortname, else the collection name.
                val plat = f.themeKey.substringBefore('/').takeIf { it.isNotEmpty() }
                    ?: f.shortname?.takeIf { it.isNotBlank() }
                    ?: f.collection
                    ?: "?"
                val a = acc.getOrPut(plat) { Acc() }
                a.games++
                when (f.health) {
                    GameHealth.OK -> a.ok++
                    GameHealth.SHORTNAME_UNMAPPED -> a.unmapped++
                    GameHealth.NO_INDEX_ENTRY -> a.noIdx++
                    GameHealth.ASSET_FILE_MISSING -> a.miss++
                }
            }
            return acc.map { (plat, a) ->
                PlatformSummary(plat, a.games, a.ok, a.unmapped, a.noIdx, a.miss)
            }.sortedBy { it.platform }
        }
    }
}
