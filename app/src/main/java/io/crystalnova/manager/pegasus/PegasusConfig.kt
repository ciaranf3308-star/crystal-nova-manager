package io.crystalnova.manager.pegasus

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.KeyValueStore

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
