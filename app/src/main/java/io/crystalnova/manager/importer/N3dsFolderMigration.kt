package io.crystalnova.manager.importer

import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs

/**
 * u75 one-time correction: the importer's N3DS ROM folder default was
 * "3ds"; ES-DE's actual 3DS system folder is "n3ds", so 3DS imports
 * landed in a folder ES-DE never looks at.
 *
 * Two halves, both idempotent:
 * 1. Stored mapping ([PlatformMapping.migrateN3dsDefault]): a stored
 *    "3ds" becomes "n3ds". Anything else is left alone.
 * 2. Already-imported files: `<rom-root>/3ds/` is moved recursively
 *    into the mapped N3DS folder (`<rom-root>/n3ds/` by default;
 *    follows a user-customized mapping), created when missing,
 *    preserving structure. Colliding names are SKIPPED — never
 *    overwritten. The old folder is removed when left empty.
 *
 * Guarded by [FLAG_KEY]: once the file move has run (or there was
 * nothing to move), it never runs again. When the ROM root is not
 * granted, nothing is marked done — the next startup retries.
 */
object N3dsFolderMigration {

    /** Persisted once the file move has completed (or had nothing to do). */
    const val FLAG_KEY = "migration.n3ds_folder_v1"

    const val OLD_FOLDER = PlatformMapping.LEGACY_N3DS_FOLDER

    data class Result(
        val movedFiles: Int,
        val skippedFiles: Int,
        /** True when the work is complete and the flag was stored. */
        val done: Boolean,
    )

    /**
     * Runs the correction. Never throws — any unexpected failure
     * leaves the flag unset so a later startup retries.
     */
    fun run(
        prefs: KeyValueStore,
        mapping: PlatformMapping,
        romsFs: () -> ThemeFs?,
    ): Result {
        mapping.migrateN3dsDefault()
        if (prefs.getString(FLAG_KEY) != null) return Result(0, 0, done = true)
        return try {
            // Destination follows the (possibly user-customized) mapping;
            // by default this is "n3ds" after the correction above.
            val newFolder = mapping.folderFor(PlatformId.N3DS)
            if (newFolder == OLD_FOLDER) {
                // User deliberately keeps "3ds" — nothing to move.
                prefs.putString(FLAG_KEY, "done")
                return Result(0, 0, done = true)
            }
            moveFiles(prefs, romsFs(), newFolder)
        } catch (_: Exception) {
            // Leave the flag unset: a later startup retries.
            Result(0, 0, done = false)
        }
    }

    private fun moveFiles(prefs: KeyValueStore, fs: ThemeFs?, newFolder: String): Result {
        if (fs == null) return Result(0, 0, done = false)
        val root = try {
            fs.root()
        } catch (_: SecurityException) {
            null
        } ?: return Result(0, 0, done = false) // ROM root not granted — retry later.
        val src = fs.find(root, OLD_FOLDER)?.takeIf { fs.isDirectory(it) }
        if (src == null) {
            prefs.putString(FLAG_KEY, "done")
            return Result(0, 0, done = true)
        }
        var dst = fs.find(root, newFolder)
        if (dst == null) dst = fs.mkdir(root, newFolder)
        if (!fs.isDirectory(dst)) {
            // Destination exists as a file — pathological; mark done
            // rather than retrying a hopeless state on every startup.
            prefs.putString(FLAG_KEY, "done")
            return Result(0, 0, done = true)
        }
        var moved = 0
        var skipped = 0
        moveDir(fs, src, dst) { m, s -> moved += m; skipped += s }
        if (fs.children(src).isEmpty()) fs.deleteRecursively(src)
        prefs.putString(FLAG_KEY, "done")
        return Result(moved, skipped, done = true)
    }

    private fun moveDir(
        fs: ThemeFs,
        srcDir: FsNode,
        dstDir: FsNode,
        onFile: (moved: Int, skipped: Int) -> Unit,
    ) {
        for ((name, child) in fs.children(srcDir)) {
            if (fs.isDirectory(child)) {
                var dstChild = fs.find(dstDir, name)
                if (dstChild == null) dstChild = fs.mkdir(dstDir, name)
                if (!fs.isDirectory(dstChild)) {
                    onFile(0, 1) // Name taken by a file — skip the subtree.
                    continue
                }
                moveDir(fs, child, dstChild, onFile)
                if (fs.children(child).isEmpty()) fs.deleteRecursively(child)
            } else {
                if (fs.find(dstDir, name) != null) {
                    onFile(0, 1) // Collision — never overwrite.
                    continue
                }
                val dst = fs.createFile(dstDir, name)
                try {
                    fs.openInput(child).use { input ->
                        fs.openOutput(dst).use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                    // Partial copy — remove the fragment, keep the source.
                    runCatching { fs.deleteRecursively(dst) }
                    onFile(0, 1)
                    continue
                }
                fs.deleteRecursively(child)
                onFile(1, 0)
            }
        }
    }
}
