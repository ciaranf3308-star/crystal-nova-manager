package io.crystalnova.manager.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.KeyValueStore
import java.net.URLDecoder

/** The two independent SAF trees the scraper uses: ROMs (source) and scraped media (sink). */
enum class LocationKind { ROM, MEDIA }

/** Readiness of one storage location, for the scraper UI. */
sealed interface LocationState {
    data object NotConfigured : LocationState
    data class Ready(val displayPath: String, val isRemovable: Boolean) : LocationState
    data object AccessLost : LocationState
}

/** Both locations at a glance. */
data class StorageSummary(val rom: LocationState, val media: LocationState)

/**
 * Owns the two independent persisted SAF tree locations used by the
 * scraper: the ROM/games folder (legacy pref `games_tree_uri`, so
 * existing installs keep their folder) and the new media folder
 * (`media_tree_uri`) where scraped artwork, cache and index.json live.
 *
 * When a media folder is adopted or cleared, the theme bridge file
 * `crystal-media-bridge.json` in the *themes root* is rewritten so the
 * Pegasus theme finds the media without a reinstall. The bridge is just
 * a file write — no theme changes required.
 *
 * [bridgeWriter]/[bridgeDeleter] are seams for unit tests; null means
 * the SAF-backed defaults. [logger] is a seam for the same reason:
 * android.util.Log throws under JVM unit tests, so tests inject a
 * no-op while production keeps Logcat.
 */
class StorageLocations(
    private val context: Context?,
    private val prefs: KeyValueStore,
    internal var bridgeWriter: ((themesTreeUri: String, bytes: ByteArray) -> Boolean)? = null,
    internal var bridgeDeleter: ((themesTreeUri: String) -> Boolean)? = null,
    private val logger: (tag: String, msg: String, err: Throwable?) -> Unit =
        { tag, msg, err -> Log.w(tag, msg, err) },
) {
    companion object {
        /**
         * Reuses the legacy games-folder pref so existing installs keep
         * their folder with zero migration.
         */
        const val KEY_ROM_TREE_URI = "games_tree_uri"
        const val KEY_MEDIA_TREE_URI = "media_tree_uri"
        const val BRIDGE_FILE_NAME = "crystal-media-bridge.json"
        private const val TAG = "StorageLocations"
        private const val INTERNAL_ROOT = "/storage/emulated/0"
    }

    // ---------- pure helpers (no Android calls) ----------

    /**
     * Filesystem-style path for a persisted SAF tree URI, e.g.
     * `content://…/tree/primary%3ACrystalNova%2FMedia` →
     * `/storage/emulated/0/CrystalNova/Media`; `1234-ABCD:…` →
     * `/storage/1234-ABCD/…`. Null when the URI is not a parseable
     * SAF tree URI.
     *
     * Deliberately parses the raw string instead of calling
     * `DocumentsContract.getTreeDocumentId(Uri.parse(…))` so this stays
     * pure JVM (android.jar stubs throw under unit tests).
     */
    fun canonicalPath(treeUri: String): String? {
        val docId = treeDocumentId(treeUri) ?: return null
        val colon = docId.indexOf(':')
        val volume = if (colon < 0) docId else docId.substring(0, colon)
        if (volume.isEmpty()) return null
        val rest = if (colon < 0) "" else docId.substring(colon + 1).trimStart('/')
        return if (volume == "primary") {
            "$INTERNAL_ROOT${if (rest.isEmpty()) "" else "/$rest"}"
        } else {
            "/storage/$volume${if (rest.isEmpty()) "" else "/$rest"}"
        }
    }

    /** True when the tree lives on removable storage (volume id != `primary`). */
    fun isRemovable(treeUri: String): Boolean {
        val docId = treeDocumentId(treeUri) ?: return false
        val colon = docId.indexOf(':')
        val volume = if (colon < 0) docId else docId.substring(0, colon)
        return volume.isNotEmpty() && volume != "primary"
    }

    /**
     * Friendly label for a tree URI, e.g. `INTERNAL STORAGE
     * /CrystalNova/Media` or `SD CARD /CrystalNova/Media`.
     * `LOCATION UNAVAILABLE` when unparseable. NEVER a raw content:// URI.
     */
    fun displayPath(treeUri: String): String {
        val canonical = canonicalPath(treeUri) ?: return "LOCATION UNAVAILABLE"
        return if (canonical.startsWith(INTERNAL_ROOT)) {
            "INTERNAL STORAGE ${canonical.removePrefix(INTERNAL_ROOT).ifEmpty { "/" }}"
        } else {
            val afterVolume = canonical.removePrefix("/storage/").substringAfter('/', "")
            "SD CARD /$afterVolume"
        }
    }

    /**
     * Bridge JSON the Pegasus theme reads from the themes root:
     * `{"version":1,"mediaRoot":"<path>","updated":<epochSeconds>}`.
     * [updatedSeconds] defaults to now; pass a fixed value in tests.
     */
    fun bridgeJson(mediaFsPath: String, updatedSeconds: Long = System.currentTimeMillis() / 1000): String =
        "{\"version\":1,\"mediaRoot\":\"${jsonEscape(mediaFsPath)}\"," +
            "\"updated\":$updatedSeconds}"

    /** Extracts the (URL-decoded) tree document id, or null when not a tree URI. */
    private fun treeDocumentId(treeUri: String): String? {
        val marker = "/tree/"
        val idx = treeUri.indexOf(marker)
        if (idx < 0) return null
        val encoded = treeUri.substring(idx + marker.length).substringBefore('/')
        if (encoded.isEmpty()) return null
        return try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    // ---------- persistence ----------

    private fun keyFor(kind: LocationKind): String =
        if (kind == LocationKind.ROM) KEY_ROM_TREE_URI else KEY_MEDIA_TREE_URI

    fun romTreeUri(): String? = prefs.getString(KEY_ROM_TREE_URI)

    fun mediaTreeUri(): String? = prefs.getString(KEY_MEDIA_TREE_URI)

    /**
     * Takes the persistable read+write grant, persists the URI, and for
     * MEDIA rewrites the theme bridge (needs [themesTreeUri] for that).
     * Returns false on SecurityException so the caller can show a retry
     * notice; never throws.
     */
    fun adoptTreeUri(
        cr: ContentResolver,
        uri: Uri,
        kind: LocationKind,
        themesTreeUri: String? = null,
    ): Boolean {
        return try {
            cr.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            adoptTreeUriString(uri.toString(), kind, themesTreeUri)
        } catch (e: SecurityException) {
            logger(TAG, "persistable permission denied for $kind", e)
            false
        } catch (e: Exception) {
            logger(TAG, "adopt $kind failed", e)
            false
        }
    }

    /**
     * Pure part of [adoptTreeUri]: persist the URI string, then rewrite
     * the bridge for MEDIA. Separated so unit tests can exercise it
     * without a ContentResolver; callers passing a real SAF tree always
     * go through [adoptTreeUri] first.
     */
    fun adoptTreeUriString(uriString: String, kind: LocationKind, themesTreeUri: String? = null): Boolean {
        return try {
            prefs.putString(keyFor(kind), uriString)
            if (kind == LocationKind.MEDIA) writeBridge(themesTreeUri)
            true
        } catch (e: Exception) {
            logger(TAG, "adoptTreeUriString failed", e)
            false
        }
    }

    /** Removes the pref; for MEDIA also removes/rewrites the bridge so the theme falls back. */
    fun clearLocation(kind: LocationKind, themesTreeUri: String? = null) {
        try {
            prefs.remove(keyFor(kind))
            if (kind == LocationKind.MEDIA) writeBridge(themesTreeUri)
        } catch (e: Exception) {
            logger(TAG, "clearLocation failed", e)
        }
    }

    /**
     * True when the pref is set AND the persisted grant still reads.
     * False on any exception — revocation reads as "no access", never as
     * configured.
     */
    fun hasAccess(kind: LocationKind): Boolean {
        val uri = prefs.getString(keyFor(kind)) ?: return false
        return try {
            val doc = DocumentFile.fromTreeUri(context!!, Uri.parse(uri))
            doc != null && doc.canRead()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when the pref is set AND the persisted grant still carries
     * BOTH read and write ([ContentResolver.persistedUriPermissions]).
     * v19: BUILD PEGASUS LIBRARY requires the ROM root to be WRITABLE
     * — a read-only grant can scan but cannot receive the game-dir
     * metafile. SecurityException reads as "no grant", never as
     * configured.
     */
    fun hasWriteAccess(kind: LocationKind): Boolean {
        val uriString = prefs.getString(keyFor(kind)) ?: return false
        val ctx = context ?: return false
        return try {
            val uri = Uri.parse(uriString)
            ctx.contentResolver.persistedUriPermissions.any { p ->
                p.uri == uri && p.isReadPermission && p.isWritePermission
            }
        } catch (e: SecurityException) {
            logger(TAG, "persisted write-grant probe failed", e)
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Friendly path, `NOT CONFIGURED`, or `ACCESS LOST — RESELECT`. Never a raw content:// URI. */
    fun displayPathFor(kind: LocationKind): String {
        val uri = prefs.getString(keyFor(kind)) ?: return "NOT CONFIGURED"
        return if (hasAccess(kind)) displayPath(uri) else "ACCESS LOST — RESELECT"
    }

    fun summary(): StorageSummary =
        StorageSummary(stateFor(LocationKind.ROM), stateFor(LocationKind.MEDIA))

    private fun stateFor(kind: LocationKind): LocationState {
        val uri = prefs.getString(keyFor(kind)) ?: return LocationState.NotConfigured
        return if (hasAccess(kind)) {
            LocationState.Ready(displayPath(uri), isRemovable(uri))
        } else {
            LocationState.AccessLost
        }
    }

    // ---------- theme bridge ----------

    /**
     * Writes `crystal-media-bridge.json` into the **themes root** (beside
     * `crystal-nova-pegasus-theme/`, not inside it) via SAF: atomic-ish
     * (write tmp + swap over target). Never throws — catch-all returns
     * false.
     *
     * Cases:
     * - media configured + canonical path available → write the bridge.
     * - media configured but path unavailable → skip (leave any existing
     *   bridge alone) and return false.
     * - media NOT configured → delete the bridge if present (the theme
     *   then uses its legacy lookup). No theme reinstall needed.
     */
    fun writeBridge(themesTreeUri: String?): Boolean {
        return try {
            val themes = themesTreeUri ?: return false
            val mediaUri = mediaTreeUri()
            if (mediaUri == null) {
                return (bridgeDeleter ?: ::defaultBridgeDelete)(themes)
            }
            val path = canonicalPath(mediaUri)
                ?: return false.also {
                    logger(TAG, "writeBridge: media path unavailable — leaving existing bridge", null)
                }
            val bytes = bridgeJson(path).toByteArray()
            (bridgeWriter ?: ::defaultBridgeWrite)(themes, bytes)
        } catch (e: Exception) {
            logger(TAG, "writeBridge failed", e)
            false
        }
    }

    private fun defaultBridgeWrite(themesTreeUri: String, bytes: ByteArray): Boolean {
        return try {
            val fs = SafThemeFs(context!!) { themesTreeUri }
            val root = fs.root() ?: return false
            // SAF rename cannot overwrite, so delete the old bridge first:
            // a failed swap then leaves the previous bridge intact, never
            // a half-written file.
            fs.find(root, "$BRIDGE_FILE_NAME.tmp")?.let { fs.deleteRecursively(it) }
            val tmp = fs.createFile(root, "$BRIDGE_FILE_NAME.tmp")
            try {
                fs.openOutput(tmp).use { it.write(bytes) }
            } catch (e: Exception) {
                fs.deleteRecursively(tmp)
                throw e
            }
            fs.find(root, BRIDGE_FILE_NAME)?.let { fs.deleteRecursively(it) }
            fs.rename(tmp, BRIDGE_FILE_NAME)
        } catch (e: Exception) {
            logger(TAG, "bridge write failed", e)
            false
        }
    }

    private fun defaultBridgeDelete(themesTreeUri: String): Boolean {
        return try {
            val fs = SafThemeFs(context!!) { themesTreeUri }
            val root = fs.root() ?: return false
            fs.find(root, BRIDGE_FILE_NAME)?.let { fs.deleteRecursively(it) }
            true
        } catch (e: Exception) {
            logger(TAG, "bridge delete failed", e)
            false
        }
    }
}
