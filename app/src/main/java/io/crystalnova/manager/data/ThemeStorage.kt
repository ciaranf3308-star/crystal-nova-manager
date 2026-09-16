package io.crystalnova.manager.data

import java.io.File

/** Tiny key-value store so storage logic stays testable without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
}

class StorageException(
    message: String,
    cause: Throwable? = null,
    /**
     * True when the previous working theme is still live (or safely kept
     * as the backup) after this failure. False only when the live theme
     * may be gone — the UI must not claim otherwise.
     */
    val restored: Boolean = true,
) : Exception(message, cause)

data class InstallReport(val fileCount: Int, val version: VersionInfo?)
data class RollbackReport(val restoredVersion: VersionInfo?)

/**
 * Installed-theme storage behind the Storage Access Framework.
 *
 * The updater may ONLY modify:
 *   <chosen themes/>/crystal-nova-pegasus-theme/
 * It must never touch ROMs, metadata.pegasus.txt, scraped artwork, BIOS,
 * emulator configs, saves, or Pegasus configuration — this interface has
 * no operation that reaches outside the theme directory.
 */
interface ThemeStorage {
    /** Persisted SAF tree URI of the themes/ folder, or null if never chosen. */
    var treeUri: String?

    /** True when a persisted folder permission is available right now. */
    fun hasFolderAccess(): Boolean

    /** Version marker of the installed theme, or null if unreadable. */
    fun readInstalledVersion(): VersionInfo?

    /**
     * True when the live theme directory exists at all — even a legacy
     * manual install without crystal-version.json. A missing marker is a
     * valid legacy install, never an error.
     */
    fun hasThemeDir(): Boolean

    /** Version marker of the kept backup, or null if none/unreadable. */
    fun readBackupVersion(): VersionInfo?

    fun hasBackup(): Boolean

    /**
     * Installs a VALIDATED theme directory (see ZipValidator) using the
     * .new → verify → .backup → replace dance. Never called before
     * validation succeeds.
     */
    @Throws(StorageException::class)
    fun installValidatedTheme(sourceDir: File): InstallReport

    /** Restores the kept backup using the same safe replacement process. */
    @Throws(StorageException::class)
    fun rollback(): RollbackReport
}
