package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs

/**
 * Game List Export (u66): a faithful, read-only JSON dump of the REAL
 * ROM folder tree. COPY ALL puts the JSON on the clipboard so the user
 * can paste it into ChatGPT.
 *
 * The old version walked the platform→folder mapping and silently
 * skipped every real folder the mapping didn't know (a 3DS folder
 * vanished entirely) while relabelling folders through stale
 * mappings. This version walks the ACTUAL children of the ROM root:
 * no mapping, no guessing, no filtering beyond hidden entries, no
 * title normalization, no extension stripping. What you see in your
 * file manager is what you get.
 *
 * The ONLY exclusion: entries whose name starts with '.' (system
 * temps like `.importing-*`, `.nomedia`). Everything else — every
 * folder, every file, every extension — is exported as-is, including
 * folders that are completely empty.
 */

/** One real folder under the ROM root: files are `/`-separated paths relative to it, sorted. */
data class LibraryFolder(
    val name: String,
    val files: List<String>,
)

/** Key under which loose files sitting directly in the ROM root are collected. */
const val LIBRARY_ROOT_KEY = "_root"

/** Read-only scan result: folders sorted by name, files sorted within each folder. */
data class LibraryScan(val folders: List<LibraryFolder>) {
    val totalFiles: Int get() = folders.sumOf { it.files.size }
    val folderCount: Int get() = folders.size
    val exportText: String get() = renderJson(folders)
}

/**
 * Pretty-prints the folder tree as JSON with 2-space indent:
 *
 *     {
 *       "3ds": [
 *         "Pokemon Omega Ruby (USA).3ds",
 *         "sub/My Game.3ds"
 *       ],
 *       "_root": [
 *         "bios.bin"
 *       ]
 *     }
 *
 * Keys and paths are escaped with [JsonCodec.writeString].
 */
fun renderJson(folders: List<LibraryFolder>): String = buildString {
    append("{\n")
    folders.forEachIndexed { folderIndex, folder ->
        append("  ").append(JsonCodec.writeString(folder.name)).append(": [\n")
        folder.files.forEachIndexed { fileIndex, file ->
            append("    ").append(JsonCodec.writeString(file))
            if (fileIndex < folder.files.size - 1) append(',')
            append('\n')
        }
        append("  ]")
        if (folderIndex < folders.size - 1) append(',')
        append('\n')
    }
    append('}')
}

/**
 * Read-only scan of the REAL children of the ROM root. Returns null
 * when the ROM root is not granted or vanished (the UI shows the grant
 * hint).
 *
 * READ ONLY: only root()/children()/isDirectory() are used — no
 * rename, move, delete, or write of any kind. Hidden entries (name
 * starts with '.') are the only exclusion. Every real folder is
 * included, even when empty; loose files at the root are collected
 * under [LIBRARY_ROOT_KEY].
 */
fun scanGameLibrary(romsFs: ThemeFs?): LibraryScan? {
    val fs = romsFs ?: return null
    return try {
        val root = fs.root() ?: return null
        val folders = mutableListOf<LibraryFolder>()
        val rootFiles = mutableListOf<String>()
        for ((name, node) in fs.children(root).sortedBy { it.first }) {
            if (name.startsWith('.')) continue
            if (fs.isDirectory(node)) {
                folders.add(LibraryFolder(name, walkFolder(fs, node)))
            } else {
                rootFiles.add(name)
            }
        }
        // The root bucket always exists: omitting things is what
        // caused this bug in the first place.
        folders.add(LibraryFolder(LIBRARY_ROOT_KEY, rootFiles.sorted()))
        LibraryScan(folders.sortedBy { it.name })
    } catch (_: SecurityException) {
        // Grant revoked mid-scan: the UI shows the grant hint.
        null
    }
}

/** Stack-based recursive walk: all files beneath [dir], as `/`-separated paths relative to it, sorted. */
private fun walkFolder(fs: ThemeFs, dir: FsNode): List<String> {
    val files = mutableListOf<String>()
    val stack = ArrayDeque<Pair<FsNode, String>>()
    stack.addLast(dir to "")
    while (stack.isNotEmpty()) {
        val (node, prefix) = stack.removeLast()
        for ((name, child) in fs.children(node).sortedBy { it.first }) {
            if (name.startsWith('.')) continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (fs.isDirectory(child)) {
                stack.addLast(child to rel)
            } else {
                files.add(rel)
            }
        }
    }
    return files.sorted()
}
