package io.crystalnova.manager.importer

/**
 * Chunked clipboard export (u84): COPY ALL exceeds the clipboard
 * character limit for large libraries, and per-folder COPY (u83) still
 * exceeds it for folders with many games. Splitting each folder into
 * small consecutive chunks keeps every single copy tiny enough for
 * both the clipboard and a chat input window, so the user can paste
 * chunk after chunk into an LLM until the whole library is across.
 *
 * Pure JVM — unit-tested without Android.
 */

/** Files per chunk. One named constant so the size can be tuned later. */
const val CHUNK_SIZE = 50

/**
 * Splits one folder's file list into consecutive chunks of at most
 * [CHUNK_SIZE] files, in order. Each chunk keeps the folder's name so
 * it renders through the exact same single-folder JSON path as COPY
 * ALL and per-folder COPY — the chunk text is just that folder's JSON
 * restricted to the chunk's files.
 *
 * An empty folder yields zero chunks (no dead controls); a folder
 * smaller than one chunk yields exactly one chunk. Flattening the
 * chunks in order reproduces the folder's file list exactly once —
 * nothing dropped, nothing duplicated.
 */
fun chunkLibraryFolder(folder: LibraryFolder): List<LibraryFolder> {
    if (folder.files.isEmpty()) return emptyList()
    return folder.files.chunked(CHUNK_SIZE).map { LibraryFolder(folder.name, it) }
}
