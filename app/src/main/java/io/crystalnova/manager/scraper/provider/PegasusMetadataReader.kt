package io.crystalnova.manager.scraper.provider

import io.crystalnova.manager.scraper.match.TitleNormalizer

/**
 * Reads Pegasus `metadata.pegasus.txt` files found in/near the ROM tree.
 * READ-ONLY: the manager never writes these files.
 *
 * Parsing model (official Pegasus syntax):
 * - A file is a sequence of *collections* and *games*. Blank lines and
 *   `#` comments separate/annotate entries.
 * - Collection-level keys appear before the first `game:` of the
 *   collection (or between games): `collection:` (display name),
 *   `shortname:` (platform slug, e.g. `gba`), `launch:` (default launch
 *   command for the collection's games).
 * - Each game starts with `game: Display Title` and ends at the next
 *   blank line (or the next `game:` / end of file).
 * - `file: path` attaches a single ROM file to the game; several `file:`
 *   lines accumulate. `files:` (usually empty value) starts a file list:
 *   every following *indented* non-empty line is one file, until a
 *   non-indented line or a blank line ends the list.
 * - A `launch:` line *inside* a game block overrides the collection's
 *   launch for that game.
 * - Any other indented continuation line appends to the previous key's
 *   value (multi-line values, e.g. `description:`).
 * - Games inherit the most recent `collection:` / `shortname:` /
 *   `launch:` seen, so several collections can live in one file.
 *
 * Legacy forms preserved (the Manager's old format treated `game:` as a
 * filename and took the title from a separate `title:` field):
 * - A game block with NO `file:`/`files:` lines whose `game:` value
 *   looks like a filename (basename with a short alphanumeric
 *   extension, e.g. `Mario Golf (E).gba`) is read as one file and its
 *   display title comes from `title:` when present, else the `game:`
 *   value itself.
 * - A `title:` line inside an official-syntax game block is still
 *   honoured as extra metadata but never overrides the `game:` title.
 *
 * Pure Kotlin/JVM — no android.* imports, so this is unit-testable.
 */
object PegasusMetadataReader {

    /** One parsed game entry, bound to its collection. */
    data class Entry(
        /** Display title from `game:` (or legacy `title:`). */
        val title: String,
        /** ROM files belonging to the game, from `file:` / `files:`. */
        val files: List<String>,
        val developer: String?,
        val publisher: String?,
        val genre: String?,
        val players: String?,
        val description: String?,
        val release: String?,
        /** Display name from the surrounding `collection:` line. */
        val collection: String?,
        /** Platform slug from the surrounding `shortname:` line. */
        val shortname: String?,
        /** Game-level `launch:` if present, else the collection's. */
        val launch: String?,
    )

    private val fileLikeExt = Regex("[A-Za-z0-9]{1,5}")

    /** True when the value smells like a ROM filename, not a title. */
    private fun looksLikeFile(value: String): Boolean {
        val base = value.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return dot > 0 && fileLikeExt.matches(base.substring(dot + 1))
    }

    fun parse(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var collection: String? = null
        var shortname: String? = null
        var launch: String? = null

        // Open game block, or null between games.
        var title: String? = null
        val files = mutableListOf<String>()
        val fields = mutableMapOf<String, String>()
        var gameLaunch: String? = null
        var inFilesList = false
        // Last game-block key seen, for multi-line continuation.
        var lastKey: String? = null

        fun resetGame() {
            title = null
            files.clear()
            fields.clear()
            gameLaunch = null
            inFilesList = false
            lastKey = null
        }

        fun flushGame() {
            val t = title?.takeIf { it.isNotEmpty() } ?: run { resetGame(); return }
            val finalTitle: String
            val finalFiles: List<String>
            if (files.isEmpty() && looksLikeFile(t)) {
                // Legacy form: `game:` carried the filename.
                finalFiles = listOf(t)
                finalTitle = fields["title"] ?: t
            } else {
                finalTitle = t
                finalFiles = files.toList()
            }
            entries += Entry(
                title = finalTitle,
                files = finalFiles,
                developer = fields["developer"],
                publisher = fields["publisher"],
                genre = fields["genre"],
                players = fields["players"],
                description = fields["description"],
                release = fields["release"],
                collection = collection,
                shortname = shortname,
                launch = gameLaunch ?: launch,
            )
            resetGame()
        }

        fun appendToField(key: String, line: String) {
            if (key == "game") {
                title = (title?.let { "$it\n$line" } ?: line)
            } else {
                fields[key] = fields[key]?.let { "$it\n$line" } ?: line
            }
        }

        for (rawLine in text.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                flushGame()
                continue
            }
            if (trimmed.startsWith("#")) continue

            val indented = rawLine.isNotEmpty() && rawLine[0].isWhitespace()
            val hasKey = ':' in trimmed

            if (indented && !hasKey) {
                when {
                    inFilesList && title != null -> files += trimmed
                    lastKey != null && title != null -> appendToField(lastKey!!, trimmed)
                    // Stray indented line: ignore.
                }
                continue
            }
            if (!hasKey) continue // Non-indented line without `key:`: ignore.

            val key = trimmed.substringBefore(':').trim().lowercase()
            val value = trimmed.substringAfter(':').trim()
            when (key) {
                "game" -> {
                    flushGame()
                    title = value
                    lastKey = "game"
                }
                "file" -> {
                    if (title != null && value.isNotEmpty()) files += value
                    lastKey = null // `file:` lines accumulate; no continuation.
                }
                "files" -> {
                    if (title != null) {
                        // Tolerant: `files: some/path` on one line counts too.
                        if (value.isNotEmpty()) files += value
                        inFilesList = true
                    }
                    lastKey = null
                }
                "collection" -> {
                    if (title == null) {
                        if (value.isNotEmpty()) collection = value
                    } else if (value.isNotEmpty()) {
                        fields[key] = value
                        lastKey = key
                    }
                }
                "shortname" -> {
                    if (title == null) {
                        if (value.isNotEmpty()) shortname = value
                    } else if (value.isNotEmpty()) {
                        fields[key] = value
                        lastKey = key
                    }
                }
                "launch" -> {
                    if (title == null) {
                        if (value.isNotEmpty()) launch = value
                    } else if (value.isNotEmpty()) {
                        gameLaunch = value
                        lastKey = null
                    }
                }
                else -> {
                    if (title != null && value.isNotEmpty()) {
                        fields[key] = value
                        lastKey = key
                    } else {
                        lastKey = null
                    }
                }
            }
        }
        flushGame()
        return entries
    }

    /**
     * Match a ROM file name to an entry by basename of any of the entry's
     * files (exact, then normalized). Unchanged matching logic: multi-file
     * games simply match on any of their discs/files.
     */
    fun match(entries: List<Entry>, romFileName: String): Entry? {
        fun base(p: String) = p.substringAfterLast('/').substringAfterLast('\\')
        val want = base(romFileName)
        entries.firstOrNull { e -> e.files.any { base(it).equals(want, ignoreCase = true) } }
            ?.let { return it }
        val norm = TitleNormalizer.normalize(want)
        return entries.firstOrNull { e ->
            e.files.any { TitleNormalizer.normalize(base(it)) == norm }
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

/**
 * MetadataProvider backed by parsed Pegasus files (no key, read-only).
 *
 * Games carry their collection's `shortname`; the scanner already keys
 * [entriesByDir] by ROM directory, so lookup stays filename-based —
 * matching logic is unchanged. The title/files/platform data above flows
 * through [PegasusMetadataReader.toMetadata] and [ScrapeQuery].
 */
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
