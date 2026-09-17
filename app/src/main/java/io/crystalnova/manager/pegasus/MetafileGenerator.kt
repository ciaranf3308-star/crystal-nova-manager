package io.crystalnova.manager.pegasus

/**
 * Renders the Manager-owned Pegasus metadata file:
 * `crystal-nova.metadata.pegasus.txt` at the TOP LEVEL of the ROM root
 * (a registered Pegasus game dir — Pegasus picks up `*.metadata.pegasus.txt`
 * files in its own game-dir scan). Pure Kotlin — no Android imports,
 * JVM-testable.
 *
 * Emission format (one collection per recognized system, blank line
 * between entries; official Pegasus syntax —
 * pegasus-frontend.org/docs/user-guide/meta-files/):
 *
 * ```
 * collection: Game Boy Advance
 * shortname: gba
 * launch: am start --user 0
 *   -n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture
 *   ...
 *
 * game: Display Title
 * file: /storage/emulated/0/ROMs/gba/game.gba
 *
 * game: Multi File Game
 * files:
 *   /storage/emulated/0/ROMs/psx/game.cue
 *   /storage/emulated/0/ROMs/psx/game2.cue
 * ```
 *
 * Rules honored here:
 * - `launch:` is the collection-level launch command from the
 *   launcher profile; multi-line values use two-space-indented
 *   continuation lines (additional value lines must start with
 *   whitespace per the official syntax).
 * - Paths are validated at emission: absolute canonical `/storage/...`
 *   filesystem paths only — `content://` URIs, other roots, `..`
 *   traversal, line breaks, and blanks are rejected outright, so the
 *   generator can never emit a `content://` URI.
 * - Entries are sorted (collections by name, games by title,
 *   case-insensitive) so regeneration is deterministic.
 */
object MetafileGenerator {
    const val METAFILES_DIR = "metafiles"
    const val FILE_NAME = "crystal-nova.metadata.pegasus.txt"

    data class Game(
        val title: String,
        /** One path → `file:`, several → two-space-indented `files:` list. */
        val files: List<String>,
    )

    data class Collection(
        val name: String,
        val shortname: String,
        /** First line = command head; rest = continuation lines. */
        val launchLines: List<String>,
        val games: List<Game>,
    )

    fun generate(collections: List<Collection>): String = buildString {
        collections.sortedBy { it.name.lowercase() }.forEachIndexed { ci, c ->
            require(c.launchLines.isNotEmpty()) { "collection '${c.name}' has no launch command" }
            if (ci > 0) append('\n')
            append("collection: ${c.name}\n")
            append("shortname: ${c.shortname}\n")
            append("launch: ${c.launchLines.first()}\n")
            c.launchLines.drop(1).forEach { line -> append("  $line\n") }
            c.games.sortedBy { it.title.lowercase() }.forEach { g ->
                require(g.files.isNotEmpty()) { "game '${g.title}' has no files" }
                g.files.forEach { requireCanonicalPath(it) }
                append('\n')
                append("game: ${g.title}\n")
                if (g.files.size == 1) {
                    append("file: ${g.files[0]}\n")
                } else {
                    append("files:\n")
                    g.files.forEach { f -> append("  $f\n") }
                }
            }
        }
    }

    /**
     * Every emitted path must be a canonical `/storage/...` filesystem
     * path. Rejects `content://` URIs, other roots, `..` traversal,
     * line breaks (metafile injection), and blanks. Throws
     * [IllegalArgumentException] naming the offense.
     */
    fun requireCanonicalPath(path: String): String {
        require(path.isNotBlank()) { "blank game path" }
        require(!path.contains('\n') && !path.contains('\r')) {
            "game path contains a line break"
        }
        require(!path.startsWith("content://", ignoreCase = true)) {
            "game path must be a filesystem path, not a content:// URI"
        }
        require(path.startsWith("/storage/")) {
            "game path is not a canonical /storage/... path: $path"
        }
        require(path.split('/').none { it == ".." }) {
            "game path escapes its root: $path"
        }
        return path
    }

    /**
     * Raw scanned entry before dedup/grouping: display title + canonical
     * `/storage/...` path.
     */
    data class RawEntry(val title: String, val path: String)

    /**
     * Pure defensive dedup + multi-disc grouping over raw entries.
     * The [LibraryScanner] already suppresses these, but this is the
     * backstop so a raw or hand-fed list can never emit a broken
     * metafile:
     * - CUE/BIN → CUE only (a `.bin` beside a same-stem `.cue` is data,
     *   not a game).
     * - M3U → M3U only (discs sharing the set's stem are suppressed).
     * - GDI suppresses numbered tracks; numbered track files
     *   (`track01.bin`, `Game (Track 2).bin`) never become games.
     * - Entries sharing a base title (disc suffixes) become one [Game]
     *   with a `files:` list, ordered by path.
     *
     * Returns games sorted by title (case-insensitive) for stable output.
     */
    fun organizeGames(entries: List<RawEntry>): List<Game> {
        if (entries.isEmpty()) return emptyList()
        val cueStems = entries
            .filter { extOf(it.path) == "cue" }
            .mapTo(mutableSetOf()) { dirOf(it.path) to normStem(stemOf(it.path)) }
        val m3uStems = entries
            .filter { extOf(it.path) == "m3u" }
            .mapTo(mutableSetOf()) { dirOf(it.path) to normStem(stemOf(it.path)) }

        val kept = entries.filter { e ->
            val dir = dirOf(e.path)
            val stem = normStem(stemOf(e.path))
            val ext = extOf(e.path)
            // Numbered track files never become games.
            if (TRACK_FILE.containsMatchIn(stemOf(e.path))) return@filter false
            // CUE/BIN → CUE only.
            if (ext == "bin" && (dir to stem) in cueStems) return@filter false
            // M3U → M3U only: suppress the set's own discs.
            if ((ext == "cue" || ext == "bin") && m3uStems.any { (mDir, mStem) ->
                    mDir == dir && (stem == mStem || isDiscOfSet(stem, mStem))
                }
            ) return@filter false
            true
        }

        return kept
            .groupBy { baseTitle(it.title) }
            .entries
            .sortedBy { it.key.lowercase() }
            .map { (base, discSet) ->
                val ordered = discSet.sortedBy { it.path }
                Game(
                    title = if (discSet.size > 1) base else ordered.first().title,
                    files = ordered.map { it.path },
                )
            }
    }

    private fun extOf(path: String): String =
        path.substringAfterLast('.', "").lowercase()

    private fun stemOf(path: String): String =
        path.substringAfterLast('/').substringBeforeLast('.')

    private fun dirOf(path: String): String =
        path.substringBeforeLast('/', "")

    private fun normStem(stem: String): String =
        stem.replace(Regex("[\\s._-]+"), " ").trim().lowercase()

    /** `track01`, `Game (Track 2)`, `game - track 3` — never a game. */
    private val TRACK_FILE =
        Regex("""(?i)(^|[\s._(\[-])track[\s._-]*\d{1,3}([\s._)\]-]|$)""")

    /** `final fantasy vii (disc 1)` is a disc of set `final fantasy vii`. */
    private fun isDiscOfSet(normEntryStem: String, normSetStem: String): Boolean {
        if (!normEntryStem.startsWith("$normSetStem ")) return false
        val rest = normEntryStem.removePrefix(normSetStem).trim()
        return rest.matches(Regex("""(?i)[(\[]?(disc|disk|cd|dvd|side)[\s._-]*\d{1,2}[)\]]?"""))
    }

    /**
     * Display title from a ROM file name: strip the extension, turn
     * underscores into spaces, collapse whitespace. Region tags are
     * kept (e.g. `Mario Golf - Advance Tour (E).gba` →
     * `Mario Golf - Advance Tour (E)`).
     */
    fun displayTitle(fileName: String): String {
        var s = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = s.lastIndexOf('.')
        if (dot > 0) s = s.substring(0, dot)
        return s.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Base title for multi-disc grouping: strips a trailing disc
     * marker such as ` (Disc 1)`, ` - Disk 2`, ` CD3`. Games sharing
     * (platform, base title) are emitted as one `game:` with a
     * `files:` list. Returns the title unchanged when there is no
     * disc marker.
     */
    fun baseTitle(title: String): String {
        val stripped = DISC_SUFFIX.replace(title, "").trim()
        return stripped.ifEmpty { title }
    }

    private val DISC_SUFFIX =
        Regex("""(?i)[\s._-]*[(\[]?(disc|disk|cd|dvd|side)[\s._-]*0?(\d{1,2})[)\]]?\s*$""")

    /**
     * Official shortname table (pegasus-frontend.org docs) keyed by our
     * platform slug; falls back to the slug itself for anything new.
     */
    fun shortnameFor(slug: String): String = when (slug) {
        "nes" -> "nes"
        "snes" -> "snes"
        "n64" -> "n64"
        "gamecube" -> "gc"
        "gb" -> "gb"
        "gbc" -> "gbc"
        "gba" -> "gba"
        "nds" -> "nds"
        "n3ds" -> "3ds"
        "genesis" -> "genesis"
        "mastersystem" -> "mastersystem"
        "gamegear" -> "gamegear"
        "segacd" -> "segacd"
        "saturn" -> "saturn"
        "dreamcast" -> "dreamcast"
        "psx" -> "psx"
        "ps2" -> "ps2"
        "psp" -> "psp"
        "atari2600" -> "atari2600"
        "atari7800" -> "atari7800"
        "lynx" -> "atarilynx"
        "wonderswan" -> "wonderswan"
        "ngp" -> "ngp"
        "virtualboy" -> "virtualboy"
        "pcengine" -> "pcengine"
        "3do" -> "3do"
        "amiga" -> "amiga"
        "c64" -> "c64"
        "arcade" -> "arcade"
        else -> slug
    }
}
