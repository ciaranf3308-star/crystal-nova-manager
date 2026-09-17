package io.crystalnova.manager.pegasus

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.KeyValueStore

/**
 * Whether the persisted Pegasus config root is one Pegasus actually
 * reads.
 *
 * v18: on the real Nova the persisted SAF tree was
 * `/storage/emulated/0/Emulation/pegasus-frontend` — a folder Pegasus
 * never reads — while `PegasusConfig.hasAccess()` only checked
 * readability, so the dashboard claimed CONFIG READY for a root
 * Pegasus would never see. `validity()` checks the tree's real
 * document ID against the two config roots Pegasus Android supports;
 * `hasAccess()` intentionally stays a pure readability check.
 */
enum class ConfigValidity {
    /** A real Pegasus config root with a live persisted grant. */
    VALID,

    /** No config root has ever been picked. */
    NOT_SELECTED,

    /** A folder was picked, but it is not a Pegasus config root. */
    WRONG_FOLDER,

    /** The right folder was picked, but the persisted grant is gone. */
    ACCESS_LOST,
}

/**
 * Pure validator for Pegasus config-root document IDs. JVM-testable:
 * takes the document-id string (e.g. `primary:pegasus-frontend`),
 * never a Uri — production derives it via
 * `DocumentsContract.getTreeDocumentId`.
 *
 * VALID iff the volume-relative path is exactly `pegasus-frontend`
 * (legacy root, any volume: internal storage or SD card) or exactly
 * `Android/data/org.pegasus_frontend.android/files/pegasus-frontend`
 * (the current app-specific default). The split is on the FIRST `:`
 * (volume vs path); the last segment alone is NOT enough —
 * `primary:Emulation/pegasus-frontend` is a different folder Pegasus
 * never reads, and is WRONG_FOLDER.
 */
object PegasusConfigRoots {
    private const val LEGACY_ROOT = "pegasus-frontend"
    private const val APP_ROOT =
        "android/data/org.pegasus_frontend.android/files/pegasus-frontend"

    fun validateDocumentId(documentId: String): ConfigValidity {
        val colon = documentId.indexOf(':')
        if (colon < 0) return ConfigValidity.WRONG_FOLDER
        val relative = documentId.substring(colon + 1)
            .split('/').filter { it.isNotEmpty() }
            .joinToString("/").lowercase()
        return if (relative == LEGACY_ROOT || relative == APP_ROOT) {
            ConfigValidity.VALID
        } else {
            ConfigValidity.WRONG_FOLDER
        }
    }
}

/**
 * The Pegasus config root: a SEPARATE persisted SAF tree permission
 * from the themes folder and the ROM/media folders. The user picks it
 * once (normally the `pegasus-frontend` config dir, e.g.
 * `/storage/emulated/0/pegasus-frontend` on the Nova); the Manager
 * then owns exactly one file under it:
 * `metafiles/crystal-nova.metadata.pegasus.txt`.
 *
 * Never assumes the themes-folder grant covers the config dir — on the
 * Nova the config root is a different tree and needs its own grant.
 *
 * [logger] is the injectable seam: android.util.Log throws under JVM
 * unit tests, so tests pass a no-op while production keeps Logcat.
 */
class PegasusConfig(
    private val context: Context?,
    private val prefs: KeyValueStore,
    private val logger: (tag: String, msg: String, err: Throwable?) -> Unit =
        { tag, msg, err -> Log.w(tag, msg, err) },
) {
    companion object {
        const val KEY_TREE_URI = "pegasus_config_tree_uri"
        private const val TAG = "PegasusConfig"
    }

    fun treeUri(): String? = prefs.getString(KEY_TREE_URI)

    /**
     * Takes the persistable read+write grant and persists the URI.
     * Returns false on SecurityException so the caller can show a
     * retry notice; never throws.
     */
    fun adoptTreeUri(cr: ContentResolver, uri: Uri): Boolean {
        return try {
            cr.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            adoptTreeUriString(uri.toString())
        } catch (e: SecurityException) {
            logger(TAG, "persistable permission denied for pegasus config root", e)
            false
        } catch (e: Exception) {
            logger(TAG, "adopt pegasus config root failed", e)
            false
        }
    }

    /**
     * Pure part of [adoptTreeUri]: persist the URI string. Separated so
     * unit tests can exercise it without a ContentResolver.
     */
    fun adoptTreeUriString(uriString: String): Boolean {
        return try {
            prefs.putString(KEY_TREE_URI, uriString)
            true
        } catch (e: Exception) {
            logger(TAG, "adoptTreeUriString failed", e)
            false
        }
    }

    fun clear() {
        try {
            prefs.remove(KEY_TREE_URI)
        } catch (e: Exception) {
            logger(TAG, "clear failed", e)
        }
    }

    /**
     * JVM-testable seams. [documentIdOf] defaults to
     * `DocumentsContract.getTreeDocumentId` on the persisted tree URI;
     * [grantStillHeld] defaults to the persistedUriPermissions lookup.
     * Unit tests override both — the Android framework is unavailable
     * on the JVM.
     */
    internal var documentIdOf: (uriString: String) -> String? = { uriString ->
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uriString)) }.getOrNull()
    }
    internal var grantStillHeld: (uriString: String) -> Boolean = ::defaultGrantHeld

    /**
     * Config-root validity — separate from [hasAccess], which stays a
     * pure readability check. NOT_SELECTED when nothing was picked;
     * WRONG_FOLDER when the picked tree is not one of the two config
     * roots Pegasus reads; ACCESS_LOST when the path is right but the
     * persisted grant is gone; VALID otherwise.
     *
     * Never throws: an unparseable document ID reads as WRONG_FOLDER,
     * any grant-probe failure reads as ACCESS_LOST.
     */
    fun validity(): ConfigValidity {
        val uriString = prefs.getString(KEY_TREE_URI) ?: return ConfigValidity.NOT_SELECTED
        val documentId = try {
            documentIdOf(uriString)
        } catch (_: Exception) {
            null
        } ?: return ConfigValidity.WRONG_FOLDER
        if (PegasusConfigRoots.validateDocumentId(documentId) != ConfigValidity.VALID) {
            return ConfigValidity.WRONG_FOLDER
        }
        val held = try {
            grantStillHeld(uriString)
        } catch (_: Exception) {
            false
        }
        return if (held) ConfigValidity.VALID else ConfigValidity.ACCESS_LOST
    }

    /**
     * Takes the persistable read+write grant WITHOUT persisting the
     * URI. The repair flow takes the grant first, validates the picked
     * tree, and persists only when the tree is a real Pegasus config
     * root — a bad pick can never overwrite a previously valid root.
     */
    fun takeGrant(cr: ContentResolver, uri: Uri): Boolean {
        return try {
            cr.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        } catch (e: SecurityException) {
            logger(TAG, "persistable permission denied for pegasus config root", e)
            false
        } catch (e: Exception) {
            logger(TAG, "grant pegasus config root failed", e)
            false
        }
    }

    /**
     * Guarded probe of the persisted grant: the URI must appear in
     * persistedUriPermissions with read+write. SecurityException reads
     * as "no grant", never as configured.
     */
    private fun defaultGrantHeld(uriString: String): Boolean {
        val ctx = context ?: return false
        return try {
            val uri = Uri.parse(uriString)
            ctx.contentResolver.persistedUriPermissions.any { p ->
                p.uri == uri && p.isReadPermission && p.isWritePermission
            }
        } catch (e: SecurityException) {
            logger(TAG, "persisted grant probe failed", e)
            false
        }
    }

    /**
     * True when the pref is set AND the persisted grant still reads.
     * False on any exception — revocation reads as "no access", never
     * as configured.
     */
    fun hasAccess(): Boolean {
        val uri = prefs.getString(KEY_TREE_URI) ?: return false
        return try {
            val doc = DocumentFile.fromTreeUri(context!!, Uri.parse(uri))
            doc != null && doc.canRead()
        } catch (_: Exception) {
            false
        }
    }
}
