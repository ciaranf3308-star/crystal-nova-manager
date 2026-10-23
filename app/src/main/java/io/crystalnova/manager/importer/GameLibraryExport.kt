package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs

/**
 * Game List Export (u66, verbatim since u80): a literal, read-only JSON
 * dump of the REAL ROM folder tree. COPY ALL puts the JSON on the
 * clipboard so the user can paste it into ChatGPT.
 *
 * The contract is ZERO interpretation:
 * - Every folder under the ROM root appears, keyed by its real name.
 * - Every file appears, at every depth, with its real name and its
 *   full `/`-separated relative path.
 * - No exclusions: hidden entries (names starting with '.') are
 *   exported like everything else.
 * - No collapsing: directories ending in `.m3u` are descended into
 *   like any other directory — their contents are listed individually.
 * - No invented names: loose files sitting directly in the ROM root
 *   are keyed by the root folder's REAL name (see [LIBRARY_ROOT_KEY]
 *   for the nameless fallback).
 * - No mapping, no guessing, no title normalization, no extension
 *   stripping. Empty folders are included too.
 *
 * What the file manager shows is exactly what is exported.
 */

/** One real folder under the ROM root: files are `/`-separated paths relative to it, sorted. */
data class LibraryFolder(
    val name: String,
    val files: List<String>,
)

/**
 * Fallback key for loose files sitting directly in the ROM root, used
 * only when the root folder itself has no name. Normally the bucket is
 * keyed by the root's real name (see [scanGameLibrary]).
 */
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
 *       "roms": [
 *         "bios.bin"
 *       ]
 *     }
 *
 * ("roms" here is the real name of the ROM root folder, under which
 * loose root files are collected.)
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
 * READ ONLY: only root()/children()/isDirectory()/name() are used —
 * no rename, move, delete, or write of any kind. The scan is a
 * literal dump (see the file KDoc): no exclusions, no collapsing, no
 * invented names. Every real folder is included, even when empty;
 * loose files at the root are collected under the root folder's real
 * name ([ThemeFs.name] of the root), falling back to
 * [LIBRARY_ROOT_KEY] only when the root has no name.
 */
fun scanGameLibrary(romsFs: ThemeFs?): LibraryScan? {
    val fs = romsFs ?: return null
    return try {
        val root = fs.root() ?: return null
        val folders = mutableListOf<LibraryFolder>()
        val rootFiles = mutableListOf<String>()
        for ((name, node) in fs.children(root).sortedBy { it.first }) {
            if (fs.isDirectory(node)) {
                folders.add(LibraryFolder(name, walkFolder(fs, node)))
            } else {
                rootFiles.add(name)
            }
        }
        // The root bucket always exists: omitting things is what
        // caused this bug in the first place.
        val rootKey = fs.name(root)?.takeIf { it.isNotBlank() } ?: LIBRARY_ROOT_KEY
        folders.add(LibraryFolder(rootKey, rootFiles.sorted()))
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
