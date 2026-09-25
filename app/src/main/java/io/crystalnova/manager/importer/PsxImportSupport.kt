package io.crystalnova.manager.importer

import java.io.File

/**
 * Device surface the PS1 normalize pipeline needs, injected into
 * [ImportEngine] so tests can fake it. The production implementation
 * is [AndroidPsxImportSupport]; unit tests use an in-memory fake.
 */
interface PsxImportSupport {
    /**
     * A fresh, empty private temp dir for one queue item
     * (`cacheDir/psx-<itemId>/`). The engine deletes it when the item
     * finishes or fails.
     */
    fun newTempDir(itemId: String): File

    /** The chdman backend, or null when the device cannot convert. */
    fun converter(): ChdConverter?

    /** Free bytes on the temp volume (preflight: ~2.5x payload). */
    fun tempFreeBytes(): Long

    /** Deletes stale `psx-*` temp dirs left by a killed run. */
    fun cleanStaleTempDirs()
}
