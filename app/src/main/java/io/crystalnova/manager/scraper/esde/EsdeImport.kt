package io.crystalnova.manager.scraper.esde

import io.crystalnova.manager.scraper.match.TitleNormalizer
import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.scraper.scan.RomEntry

/**
 * ES-DE media importer — production architecture.
 *
 * The read-only ES-DE export on the SD card is matched against the
 * authoritative ROM library (index.json), and selected real assets are
 * copied into the SAME media tree the scraper uses:
 * `games/<platform>/<gameId>/<slot>.png`, via
 * [ScraperStorage.saveAsset], with per-game manifest + top-level
 * index.json updates. The theme resolver is untouched: it keeps reading
 * the same `file://` URLs it already renders.
 *
 * Nothing here touches storage or the network: matching, incremental
 * decisions and the pre-import report are pure and JVM-tested. SAF I/O
 * lives in [EsdeImportRunner].
 */
object EsdeImport {

    /** Marks assets imported from the user's ES-DE export. */
    const val PROVIDER_ID = "esde-import"

    /** Image extensions considered when matching ES-DE media files. */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")

    /**
     * ES-DE media directory names per Crystal slot, in preference order.
     * Only these five slots are imported (no titlescreens for now).
     */
    val SLOT_DIRS: Map<AssetSlot, List<String>> = mapOf(
        AssetSlot.BOX_FRONT to listOf("covers"),
        AssetSlot.BOX_BACK to listOf("backcovers"),
        AssetSlot.CLEAR_LOGO to listOf("wheel", "marquees"),
        AssetSlot.PHYSICAL_MEDIA to listOf("physicalmedia"),
        AssetSlot.SCREENSHOT to listOf("screenshots"),
    )

    /**
     * ES-DE system folder candidates for a Crystal platform slug, in
     * priority order. ES-DE's canonical folder names don't always match
     * ours: GameCube lives under `gc`, 3DS under `3ds`, and Sega Genesis
     * under `megadrive` (we use `genesis`). The importer searches every
     * candidate; the first hit wins, so a mixed export (both `genesis/`
     * and `megadrive/`) still resolves.
     */
    fun esdeSystemDirs(platformSlug: String): List<String> = when (platformSlug) {
        "genesis" -> listOf("genesis", "megadrive")
        "gamecube" -> listOf("gc")
        "n3ds" -> listOf("3ds")
        else -> listOf(platformSlug)
    }

    /** Backwards-compatible: the primary ES-DE folder for a platform slug. */
    fun esdeSystemDir(platformSlug: String): String = esdeSystemDirs(platformSlug).first()

    /** One game from the authoritative ROM library (the ROM scan). */
    data class RomGame(
        val platform: String,
        val gameId: String,
        val title: String,
        val fileName: String,
    )

    /**
     * Builds a pre-scan game from a ROM-scan entry. The gameId follows
     * the scraper/index convention (slug of the ROM file name) so the
     * import writes manifests and index entries the scraper itself
     * would recognize.
     */
    fun romGameFromEntry(entry: RomEntry): RomGame = RomGame(
        platform = entry.platformSlug,
        gameId = TitleNormalizer.slugify(entry.fileName),
        title = romBasename(entry.fileName),
        fileName = entry.fileName,
    )

    /** ROM file name without folders and without extension — ES-DE names media after this. */
    fun romBasename(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')

    /** One ES-DE media file matched for a slot. */
    data class FoundAsset(
        val slot: AssetSlot,
        /** Export-relative path, e.g. `media/ps2/covers/x.png` (for provenance). */
        val relativePath: String,
        val fileName: String,
        val byteLength: Long,
    )

    /**
     * What the import will do with one (game, slot). Copy decisions are
     * the only ones that write; everything else is a reported skip.
     */
    enum class Decision(val isCopy: Boolean) {
        COPY_NEW(isCopy = true),
        COPY_UPGRADE(isCopy = true),
        COPY_CHANGED(isCopy = true),
        SKIP_USER(isCopy = false),
        SKIP_UPTODATE(isCopy = false),
        SKIP_REAL_OTHER(isCopy = false),
        SKIP_SOURCE_GONE(isCopy = false),
        SKIP_NO_SOURCE(isCopy = false),
    }

    /**
     * Incremental decision for one slot.
     *
     * @param found the ES-DE media matched for this slot, or null.
     * @param existing current manifest provenance for the slot, or null.
     * @param existingLength byte length of the stored slot file, or null
     *   when the manifest claims an asset but the file is absent.
     */
    fun decide(
        found: FoundAsset?,
        existing: AssetProvenance?,
        existingLength: Long?,
    ): Decision {
        if (found == null) {
            // The export no longer has this file but we imported it before:
            // keep the copy, say so explicitly.
            if (existing != null && existing.sourceType == SourceType.REAL &&
                existing.provider == PROVIDER_ID
            ) {
                return Decision.SKIP_SOURCE_GONE
            }
            return Decision.SKIP_NO_SOURCE
        }
        if (existing == null) return Decision.COPY_NEW
        if (existing.sourceType == SourceType.USER) return Decision.SKIP_USER
        if (existing.sourceType == SourceType.GENERATED) return Decision.COPY_UPGRADE
        // Existing REAL art.
        if (existing.provider != PROVIDER_ID) return Decision.SKIP_REAL_OTHER
        if (existingLength == null) return Decision.COPY_CHANGED // dest missing: restore
        return if (found.byteLength != existingLength) Decision.COPY_CHANGED
        else Decision.SKIP_UPTODATE
    }

    fun decisionReason(d: Decision): String = when (d) {
        Decision.COPY_NEW -> "new"
        Decision.COPY_UPGRADE -> "replaces generated"
        Decision.COPY_CHANGED -> "source changed"
        Decision.SKIP_USER -> "kept: user art wins"
        Decision.SKIP_UPTODATE -> "up to date"
        Decision.SKIP_REAL_OTHER -> "kept: existing real art"
        Decision.SKIP_SOURCE_GONE -> "kept: ES-DE source gone"
        Decision.SKIP_NO_SOURCE -> "no ES-DE media"
    }

    /** One planned slot action. */
    data class SlotPlan(
        val game: RomGame,
        val slot: AssetSlot,
        val found: FoundAsset?,
        val decision: Decision,
    ) {
        val reason: String get() = decisionReason(decision)
    }

    /** Result of the pre-scan: everything the report and the import need. */
    /**
     * A group of ES-DE media files that were not matched to any ROM game
     * during prescan, grouped by (system, normalized basename). Used by
     * the manual matching UI so the user can pair them with games.
     */
    data class UnmatchedMediaGroup(
        val esdeSystem: String,
        val platform: String,
        val key: String,
        val displayName: String,
        val files: List<UnmatchedMediaFile>,
    )

    data class UnmatchedMediaFile(
        val slot: AssetSlot,
        val relPath: String,
        val fileName: String,
        val byteLength: Long,
    )

    data class ImportPlan(
        val games: List<RomGame>,
        val slotPlans: List<SlotPlan>,
        /** Games with at least one ES-DE asset found (primary or gamelist fallback). */
        val matchedGameIds: Set<String>,
        /** Games with no ES-DE asset in any slot. */
        val unmatchedGames: List<RomGame>,
        /** ES-DE media files not claimed by any game, grouped for manual matching. */
        val unmatchedMediaGroups: List<UnmatchedMediaGroup>,
    ) {
        val toWrite: List<SlotPlan> get() = slotPlans.filter { it.decision.isCopy }
        val totalBytes: Long get() = toWrite.sumOf { it.found?.byteLength ?: 0L }

        fun foundBySlot(slot: AssetSlot): Int =
            slotPlans.count { it.slot == slot && it.found != null }

        fun writeBySlot(slot: AssetSlot): Int =
            slotPlans.count { it.slot == slot && it.decision.isCopy }

        fun skipCount(): Int = slotPlans.count { !it.decision.isCopy }

        /** Human-readable pre-import report. Shown BEFORE anything is copied. */
        fun reportText(): String = buildString {
            appendLine("ES-DE MEDIA IMPORT — PRE-SCAN REPORT")
            appendLine()
            appendLine("ROMs scanned: ${games.size}")
            appendLine("Games matched: ${matchedGameIds.size}")
            appendLine("Games unmatched: ${unmatchedGames.size}")
            appendLine("Unmatched media groups: ${unmatchedMediaGroups.size}")
            appendLine()
            appendLine("Assets found by slot:")
            for (slot in SLOT_DIRS.keys) {
                appendLine("  ${slot.name}: ${foundBySlot(slot)}")
            }
            appendLine()
            appendLine("Assets that will be written:")
            for (slot in SLOT_DIRS.keys) {
                appendLine("  ${slot.name}: ${writeBySlot(slot)}")
            }
            appendLine("Total to write: ${toWrite.size} files")
            appendLine()
            val skips = slotPlans.filter { !it.decision.isCopy && it.decision != Decision.SKIP_NO_SOURCE }
            appendLine("Conflicts/skips: ${skips.size}")
            skips.groupBy { it.decision }.forEach { (d, list) ->
                appendLine("  ${decisionReason(d)}: ${list.size}")
            }
            appendLine()
            appendLine("Exact bytes to copy: $totalBytes")
            appendLine("Media size increase: ${formatBytes(totalBytes)}")
            if (toWrite.isEmpty()) {
                appendLine()
                appendLine("Nothing to do — the library is already up to date.")
            }
        }

        companion object {
            fun formatBytes(bytes: Long): String {
                if (bytes < 1024) return "$bytes B"
                val kb = bytes / 1024.0
                if (kb < 1024) return "%.1f KB".format(kb)
                val mb = kb / 1024.0
                if (mb < 1024) return "%.1f MB".format(mb)
                return "%.2f GB".format(mb / 1024.0)
            }
        }
    }

    // ------------------------------------------------------------------
    // Gamelist fallback.
    //
    // For ROMs unmatched by basename, the export's gamelist.xml files may
    // name media explicitly (<thumbnail>/<image>). Parsed with the
    // platform XML pull parser by the runner; this is the pure parse.
    // ------------------------------------------------------------------

    /** Explicit media named by one gamelist <game> entry. */
    data class GamelistMedia(val thumbnail: String?, val image: String?)

    /**
     * Parses an ES-DE gamelist.xml into rom-basename (lowercased, no
     * extension) -> explicit media. Never throws: garbage returns an
     * empty map.
     */
    fun parseGamelist(xml: String): Map<String, GamelistMedia> {
        val out = mutableMapOf<String, GamelistMedia>()
        try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            // No external entities: this parses a user-local file, but
            // there is no reason to ever resolve a DTD here.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val games = doc.getElementsByTagName("game")
            for (i in 0 until games.length) {
                val game = games.item(i) as? org.w3c.dom.Element ?: continue
                fun tag(name: String): String? {
                    val nodes = game.getElementsByTagName(name)
                    if (nodes.length == 0) return null
                    return nodes.item(0).textContent?.trim()?.ifEmpty { null }
                }
                val path = tag("path") ?: continue
                val base = romBasename(path).lowercase()
                if (base.isEmpty()) continue
                out[base] = GamelistMedia(
                    thumbnail = tag("thumbnail"),
                    image = tag("image"),
                )
            }
        } catch (_: Exception) {
            // A malformed gamelist only disables the fallback.
        }
        return out
    }

    /**
     * Resolves a gamelist media reference into export-relative candidate
     * paths, in preference order. ES-DE has used more than one convention
     * over the years (export-root-relative, system-media-relative,
     * gamelist-dir-relative), so the runner tries each candidate and takes
     * the first one that exists. Null entries and `..` escapes above the
     * export root are never returned.
     */
    fun resolveGamelistMediaCandidates(esdeSystemDirs: List<String>, ref: String): List<String> {
        var r = ref.trim().replace('\\', '/')
        if (r.isEmpty()) return emptyList()
        val raws = LinkedHashSet<String>()
        // 1. Export-root-relative, e.g. media/ps2/covers/x.png
        raws.add(r)
        for (esdeSystemDir in esdeSystemDirs) {
            // 2. Relative to the system's media folder, e.g. covers/x.png
            if (!r.startsWith("media/")) raws.add("media/$esdeSystemDir/$r")
            // 3. Relative to the gamelist's own directory (gamelists/<sys>/),
            //    e.g. ../media/covers/x.png
            raws.add("gamelists/$esdeSystemDir/$r")
        }
        return raws.mapNotNull(::normalizeExportPath).distinct()
    }

    /** Backwards-compatible single-folder resolution. */
    fun resolveGamelistMediaCandidates(esdeSystemDir: String, ref: String): List<String> =
        resolveGamelistMediaCandidates(listOf(esdeSystemDir), ref)

    /** Lexically normalizes an export-relative path; null when it escapes the root. */
    private fun normalizeExportPath(path: String): String? {
        var p = path
        while (p.startsWith("./")) p = p.removePrefix("./")
        while (p.startsWith("/")) p = p.removePrefix("/")
        val parts = ArrayDeque<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** Backwards-compatible single resolution: the first candidate. */
    fun resolveGamelistMedia(esdeSystemDir: String, ref: String): String? =
        resolveGamelistMediaCandidates(esdeSystemDir, ref).firstOrNull()

    /** Multi-folder resolution: the first candidate across all folders. */
    fun resolveGamelistMedia(esdeSystemDirs: List<String>, ref: String): String? =
        resolveGamelistMediaCandidates(esdeSystemDirs, ref).firstOrNull()
}
