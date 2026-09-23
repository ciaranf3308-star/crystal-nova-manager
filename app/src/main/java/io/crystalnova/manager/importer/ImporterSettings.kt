package io.crystalnova.manager.importer

import io.crystalnova.manager.data.KeyValueStore

/**
 * Importer preferences, persisted via [KeyValueStore] next to the
 * platform mapping. Everything is reversible and user-editable from
 * the importer settings screen.
 */
class ImporterSettings(private val prefs: KeyValueStore) {

    companion object {
        private const val KEY_DELETE_AFTER_SUCCESS = "importer.delete_after_success"
        private const val KEY_AUTO_IDENTIFY = "importer.auto_identify"
        private const val KEY_CONFIRM_BEFORE_IMPORT = "importer.confirm_before_import"
        private const val KEY_DUPLICATE_DEFAULT = "importer.duplicate_default"
    }

    /** Delete the source archive after a verified import (default ON). */
    var deleteAfterSuccess: Boolean
        get() = prefs.getString(KEY_DELETE_AFTER_SUCCESS)?.toBooleanStrictOrNull() ?: true
        set(value) = prefs.putString(KEY_DELETE_AFTER_SUCCESS, value.toString())

    /**
     * Auto-file CONFIRMED detections straight into the queue, showing
     * only the uncertain ones for manual classification (default ON).
     */
    var autoIdentify: Boolean
        get() = prefs.getString(KEY_AUTO_IDENTIFY)?.toBooleanStrictOrNull() ?: true
        set(value) = prefs.putString(KEY_AUTO_IDENTIFY, value.toString())

    /** Show the review screen before running the queue (default ON). */
    var confirmBeforeImport: Boolean
        get() = prefs.getString(KEY_CONFIRM_BEFORE_IMPORT)?.toBooleanStrictOrNull() ?: true
        set(value) = prefs.putString(KEY_CONFIRM_BEFORE_IMPORT, value.toString())

    /** Default duplicate resolution when none was chosen per item. */
    var duplicateDefault: DuplicatePolicy
        get() = prefs.getString(KEY_DUPLICATE_DEFAULT)
            ?.let { runCatching { DuplicatePolicy.valueOf(it) }.getOrNull() }
            ?: DuplicatePolicy.SKIP
        set(value) = prefs.putString(KEY_DUPLICATE_DEFAULT, value.name)
}
