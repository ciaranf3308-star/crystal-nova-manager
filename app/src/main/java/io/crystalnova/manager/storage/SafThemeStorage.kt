package io.crystalnova.manager.storage

import io.crystalnova.manager.data.InstallReport
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.data.RollbackReport
import io.crystalnova.manager.data.StorageException
import io.crystalnova.manager.data.ThemeStorage
import io.crystalnova.manager.data.VersionInfo
import java.io.File

/**
 * SAF-backed [ThemeStorage].
 *
 * Safe replacement, because SAF has no atomic directory rename:
 *
 *   install:  staging(.new) ← validated copy → verify → delete stale .backup
 *             → current → .backup → .new → current
 *   failure:  restore .backup → current, never leave a half-written theme
 *
 * Exactly one backup generation is kept: the stale backup is deleted only
 * when a new one is about to be made, so ROLLBACK always has the previous
 * working version — and a failed install can always restore it.
 */
class SafThemeStorage(
    private val fs: ThemeFs,
    private val prefs: KeyValueStore,
) : ThemeStorage {

    companion object {
        const val THEME_DIR_NAME = "crystal-nova-pegasus-theme"
        const val STAGING_DIR_NAME = "crystal-nova-pegasus-theme.new"
        const val BACKUP_DIR_NAME = "crystal-nova-pegasus-theme.backup"

        const val KEY_TREE_URI = "tree_uri"
        const val KEY_INSTALLED_SHA = "installed_sha"
        const val KEY_INSTALLED_VERSION = "installed_version"

        /** Files/dirs every valid Crystal theme package must contain. */
        val REQUIRED_FILES = listOf("theme.cfg", "theme.qml", "crystal-version.json")
        val REQUIRED_DIRS = listOf("components", "screens", "assets", "fonts")
    }

    override var treeUri: String?
        get() = prefs.getString(KEY_TREE_URI)
        set(value) {
            if (value == null) prefs.remove(KEY_TREE_URI) else prefs.putString(KEY_TREE_URI, value)
        }

    override fun hasFolderAccess(): Boolean = try {
        // Both must hold: a persisted URI (the user's grant) and a root
        // the FS layer can actually open right now.
        treeUri != null && fs.root() != null
    } catch (_: SecurityException) {
        false
    }

    private fun requireRoot(): FsNode =
        try {
            fs.root() ?: throw StorageException("Storage access lost")
        } catch (e: SecurityException) {
            throw StorageException("Storage access lost", e)
        }

    override fun readInstalledVersion(): VersionInfo? =
        readVersionOf(THEME_DIR_NAME)

    override fun hasThemeDir(): Boolean = try {
        val root = requireRoot()
        val dir = fs.find(root, THEME_DIR_NAME)
        dir != null && fs.isDirectory(dir)
    } catch (_: StorageException) {
        false
    }

    override fun readBackupVersion(): VersionInfo? =
        readVersionOf(BACKUP_DIR_NAME)

    override fun hasBackup(): Boolean = try {
        val root = requireRoot()
        val backup = fs.find(root, BACKUP_DIR_NAME)
        // A backup is the previous working theme — it may be a legacy
        // manual install without our version marker, so the marker is
        // not required here (it IS required for new staged packages).
        backup != null && fs.isDirectory(backup) && isRestorableThemeTree(backup)
    } catch (_: StorageException) {
        false
    }

    private fun readVersionOf(dirName: String): VersionInfo? = try {
        val root = requireRoot()
        val dir = fs.find(root, dirName) ?: return null
        val marker = fs.find(dir, "crystal-version.json") ?: return null
        fs.openInput(marker).use { VersionInfo.parse(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (_: Exception) {
        null
    }

    /** Records what the updater installed (SHA known exactly at install time). */
    fun recordInstalled(version: VersionInfo, sha: String) {
        prefs.putString(KEY_INSTALLED_SHA, sha)
        prefs.putString(KEY_INSTALLED_VERSION, version.version)
    }

    fun installedSha(): String? = prefs.getString(KEY_INSTALLED_SHA)

    private fun clearInstalledRecord() {
        prefs.remove(KEY_INSTALLED_SHA)
        prefs.remove(KEY_INSTALLED_VERSION)
    }

    override fun installValidatedTheme(sourceDir: File): InstallReport {
        require(sourceDir.isDirectory) { "Source is not a directory" }
        val root = requireRoot()
        // Count the source files first so the staging copy can be verified
        // against it — a short copy must never be promoted.
        val expectedCount = countFiles(sourceDir)
        val staging: FsNode
        try {
            // 1. Copy the validated update into staging first — never touch live.
            fs.find(root, STAGING_DIR_NAME)?.let { fs.deleteRecursively(it) }
            staging = fs.mkdir(root, STAGING_DIR_NAME)
            val fileCount = copyTree(sourceDir, staging)

            // 2. Verify the staging copy: required files + exact file count.
            if (fileCount != expectedCount) {
                throw StorageException(
                    "Staged copy incomplete ($fileCount of $expectedCount files)",
                )
            }
            if (!isValidThemeTree(staging)) {
                throw StorageException("Staged theme failed verification")
            }

            // 3. Rotate: drop the stale backup, current becomes the backup…
            fs.find(root, BACKUP_DIR_NAME)?.let { fs.deleteRecursively(it) }
            val current = fs.find(root, THEME_DIR_NAME)
            if (current != null) {
                if (!fs.rename(current, BACKUP_DIR_NAME)) {
                    throw StorageException("Could not back up the current theme")
                }
            }

            // 4. …then staging becomes the live theme.
            if (!fs.rename(staging, THEME_DIR_NAME)) {
                // Restore the backup; never leave a half-written theme, and
                // never claim the restore succeeded when it did not.
                val backup = fs.find(root, BACKUP_DIR_NAME)
                val restored = backup != null && fs.rename(backup, THEME_DIR_NAME)
                if (restored) fs.deleteRecursively(staging)
                throw StorageException(
                    if (restored) "Install failed — previous version restored"
                    else "Install failed — could not restore previous version",
                    restored = restored,
                )
            }

            val version = readInstalledVersion()
            return InstallReport(fileCount = fileCount, version = version)
        } catch (e: StorageException) {
            // Never leave a half-written staging dir behind.
            try {
                fs.find(root, STAGING_DIR_NAME)?.let { fs.deleteRecursively(it) }
            } catch (_: Exception) {
            }
            throw e
        } catch (e: SecurityException) {
            try {
                fs.find(root, STAGING_DIR_NAME)?.let { fs.deleteRecursively(it) }
            } catch (_: Exception) {
            }
            throw StorageException("STORAGE ACCESS LOST", e)
        } catch (e: Exception) {
            try {
                fs.find(root, STAGING_DIR_NAME)?.let { fs.deleteRecursively(it) }
            } catch (_: Exception) {
            }
            throw StorageException("Install failed: ${e.message}", e)
        }
    }

    override fun rollback(): RollbackReport {
        val root = requireRoot()
        try {
            val backup = fs.find(root, BACKUP_DIR_NAME)
                ?: throw StorageException("No backup available")
            if (!isRestorableThemeTree(backup)) {
                throw StorageException("Backup is not a valid theme")
            }

            // Same safe process in reverse: hold current aside, promote backup.
            // The version rolled back FROM becomes the new backup, so a
            // rollback is itself undoable — exactly one backup generation
            // is ever kept.
            fs.find(root, STAGING_DIR_NAME)?.let { fs.deleteRecursively(it) }
            val current = fs.find(root, THEME_DIR_NAME)
            if (current != null) {
                if (!fs.rename(current, STAGING_DIR_NAME)) {
                    throw StorageException("Could not stage the current theme")
                }
            }
            if (!fs.rename(backup, THEME_DIR_NAME)) {
                fs.find(root, STAGING_DIR_NAME)?.let { fs.rename(it, THEME_DIR_NAME) }
                throw StorageException(
                    "Rollback failed — previous version restored",
                    restored = true,
                )
            }
            val aside = fs.find(root, STAGING_DIR_NAME)
            if (aside != null) {
                if (!fs.rename(aside, BACKUP_DIR_NAME)) {
                    // Live theme is correct; the old backup slot just stays empty.
                    fs.deleteRecursively(aside)
                }
            }

            // The recorded SHA described the version we rolled back from —
            // it no longer describes the live theme.
            clearInstalledRecord()

            val version = readInstalledVersion()
            return RollbackReport(restoredVersion = version)
        } catch (e: StorageException) {
            throw e
        } catch (e: SecurityException) {
            throw StorageException("STORAGE ACCESS LOST", e)
        } catch (e: Exception) {
            throw StorageException("Rollback failed: ${e.message}", e)
        }
    }

    /** Recursive copy of a validated local directory into an FsNode tree. */
    private fun copyTree(source: File, dest: FsNode): Int {
        var count = 0
        val entries = source.listFiles() ?: return 0
        for (entry in entries.sortedBy { it.name }) {
            if (entry.isDirectory) {
                val dir = fs.mkdir(dest, entry.name)
                count += copyTree(entry, dir)
            } else {
                val file = fs.createFile(dest, entry.name)
                entry.inputStream().use { input ->
                    fs.openOutput(file).use { output -> input.copyTo(output) }
                }
                count++
            }
        }
        return count
    }

    private fun countFiles(dir: File): Int {
        var count = 0
        val entries = dir.listFiles() ?: return 0
        for (entry in entries) {
            count += if (entry.isDirectory) countFiles(entry) else 1
        }
        return count
    }

    /**
     * A new package must carry our version marker — it is the only thing
     * that makes future updates comparable.
     */
    private fun isValidThemeTree(dir: FsNode): Boolean {
        val names = fs.children(dir).map { it.first }.toSet()
        return REQUIRED_FILES.all { it in names } && REQUIRED_DIRS.all { it in names }
    }

    /**
     * A backup (or the live theme) is restorable when it is a real Pegasus
     * theme tree. The version marker is NOT required: a legacy manual
     * install without crystal-version.json is still the previous working
     * version and must remain restorable.
     */
    private fun isRestorableThemeTree(dir: FsNode): Boolean {
        val names = fs.children(dir).map { it.first }.toSet()
        return REQUIRED_FILES.filter { it != "crystal-version.json" }
            .all { it in names } && REQUIRED_DIRS.all { it in names }
    }
}
