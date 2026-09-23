package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.ThemeFs

/**
 * Game List Export (u56): a read-only scan of the configured ROM
 * folders into a plain-text list grouped by console. COPY ALL puts
 * the text on the clipboard so the user can paste it into ChatGPT.
 *
 * Deliberately minimal: no database, no metadata, no scraper, no
 * internet, no ES-DE metadata parsing, no title normalization beyond
 * dropping the file extension. The single dedup rule: a `.bin` is
 * skipped when a same-basename `.cue` exists in the same folder.
 */

/** One console's games, in platform-mapping order. */
data class LibrarySystem(
    val platform: PlatformId,
    val games: List<String>,
)

/** Read-only scan result. Systems with zero games are omitted. */
data class LibraryScan(val systems: List<LibrarySystem>) {
    val totalGames: Int get() = systems.sumOf { it.games.size }
    val systemCount: Int get() = systems.size
    val exportText: String get() = GameLibraryExport.render(systems)
}

object GameLibraryExport {

    /**
     * "Pokemon Emerald.zip" -> "Pokemon Emerald". Only the LAST
     * extension is removed; nothing else is normalized.
     */
    fun displayName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /**
     * One folder's filenames -> game display names. Applies the
     * single dedup rule: a `.bin` is dropped when a same-basename
     * `.cue` exists (case-insensitive). A lone `.bin` is kept.
     */
    fun gameNames(fileNames: List<String>): List<String> {
        val lower = fileNames.map { it.lowercase() }.toSet()
        return fileNames
            .filter { name ->
                val l = name.lowercase()
                !(l.endsWith(".bin") && lower.contains(l.dropLast(4) + ".cue"))
            }
            .map(::displayName)
    }

    /**
     * Exact export text: system header (full display name, uppercased),
     * one game per line, a blank line between systems, no trailing
     * blank line.
     */
    fun render(systems: List<LibrarySystem>): String =
        systems.joinToString("\n\n") { system ->
            (listOf(system.platform.labels().long.uppercase()) + system.games)
                .joinToString("\n")
        }
}

/**
 * Read-only scan of every distinct mapped ROM folder under the ROM
 * root. Returns null when the ROM root is not granted or vanished
 * (the UI shows the grant hint).
 *
 * READ ONLY: only root/find/children/isDirectory are used — no
 * rename, move, delete, or write of any kind. Non-hidden files only;
 * subdirectories are not descended into. Genesis and Mega Drive share
 * one default folder: the first platform in mapping order wins so its
 * games are never double-counted.
 */
fun scanGameLibrary(mapping: PlatformMapping, romsFs: ThemeFs?): LibraryScan? {
    val fs = romsFs ?: return null
    return try {
        val root = fs.root() ?: return null
        val seenFolders = mutableSetOf<String>()
        val systems = mutableListOf<LibrarySystem>()
        for ((platform, folder) in mapping.snapshot()) {
            if (!seenFolders.add(folder)) continue
            val dir = fs.find(root, folder) ?: continue
            if (!fs.isDirectory(dir)) continue
            val names = fs.children(dir)
                .filter { (name, node) -> !fs.isDirectory(node) && !name.startsWith('.') }
                .map { it.first }
            val games = GameLibraryExport.gameNames(names)
            if (games.isNotEmpty()) systems.add(LibrarySystem(platform, games))
        }
        LibraryScan(systems)
    } catch (_: SecurityException) {
        // Grant revoked mid-scan: the UI shows the grant hint.
        null
    }
}
