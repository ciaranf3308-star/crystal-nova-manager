package io.crystalnova.manager.scraper.provider

import io.crystalnova.manager.scraper.match.TitleNormalizer

/**
 * Reads Pegasus `metadata.pegasus.txt` files found in/near the ROM tree.
 * READ-ONLY: the manager never writes these files. Entries are matched to
 * ROMs by file basename; the entry's title/description/credits become the
 * first metadata source for generated artwork and manifests.
 */
object PegasusMetadataReader {

    data class Entry(
        val gameFile: String,
        val title: String?,
        val developer: String?,
        val publisher: String?,
        val genre: String?,
        val players: String?,
        val description: String?,
        val release: String?,
    )

    /** Tolerant parser: entries start with `game:`, fields are `key: value`. */
    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var gameFile: String? = null
        val fields = mutableMapOf<String, String>()

        fun flush() {
            val g = gameFile ?: return
            entries += Entry(
                gameFile = g,
                title = fields["title"],
                developer = fields["developer"],
                publisher = fields["publisher"],
                genre = fields["genre"],
                players = fields["players"],
                description = fields["description"],
                release = fields["release"],
            )
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                if (line.isEmpty() && gameFile != null) {
                    flush(); gameFile = null; fields.clear()
                }
                continue
            }
            val key = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            if (key == "game") {
                if (gameFile != null) flush()
                gameFile = value
                fields.clear()
            } else if (gameFile != null && value.isNotEmpty()) {
                fields[key] = value
            }
        }
        flush()
        return entries
    }

    /** Match a ROM file name to an entry by basename (exact, then normalized). */
    fun match(entries: List<Entry>, romFileName: String): Entry? {
        val base = romFileName.substringAfterLast('/').substringAfterLast('\\')
        entries.firstOrNull {
            it.gameFile.substringAfterLast('/').substringAfterLast('\\')
                .equals(base, ignoreCase = true)
        }?.let { return it }
        val norm = TitleNormalizer.normalize(base)
        return entries.firstOrNull {
            TitleNormalizer.normalize(
                it.gameFile.substringAfterLast('/').substringAfterLast('\\'),
            ) == norm
        }
    }

    fun toMetadata(entry: Entry): GameMetadata = GameMetadata(
        title = entry.title,
        description = entry.description,
        developer = entry.developer,
        publisher = entry.publisher,
        releaseYear = entry.release?.take(4)?.takeIf { it.all(Char::isDigit) },
        genre = entry.genre,
        players = entry.players,
        providerGameId = null,
    )
}

/** MetadataProvider backed by parsed Pegasus files (no key, read-only). */
class PegasusFileMetadataProvider(
    private val entriesByDir: Map<String, List<PegasusMetadataReader.Entry>>,
) : MetadataProvider {
    override val id: String = "pegasus"
    override val displayName: String = "Pegasus Metadata"
    override val requiresApiKey: Boolean = false

    override suspend fun lookup(query: ScrapeQuery): GameMetadata? {
        // query.fileName carries the ROM's relative path; match within its dir.
        val dir = query.fileName.substringBeforeLast('/', "")
        val entries = entriesByDir[dir] ?: entriesByDir[""] ?: return null
        val entry = PegasusMetadataReader.match(entries, query.fileName) ?: return null
        return PegasusMetadataReader.toMetadata(entry)
    }
}
