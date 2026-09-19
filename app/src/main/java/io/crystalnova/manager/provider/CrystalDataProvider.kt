package io.crystalnova.manager.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

/**
 * Read-only bridge exposing the Manager-owned Crystal library to the
 * Crystal Launcher (`io.crystalnova.launcher`).
 *
 * The Manager is the SOLE owner of storage permission (its persisted SAF
 * grants never transfer to another app). This provider maps
 * data-root-relative paths to the Manager's SAF trees so the launcher
 * needs zero storage setup of its own:
 *
 *   content://io.crystalnova.manager.crystaldata/config.json
 *   content://io.crystalnova.manager.crystaldata/index.json
 *   content://io.crystalnova.manager.crystaldata/launcher/profiles.json
 *   content://io.crystalnova.manager.crystaldata/games/<platform>/<gameId>/<slot>.png
 *   content://io.crystalnova.manager.crystaldata/rom/<romRelativePath>
 *
 * Path mapping mirrors LauncherExport/ScraperStorage:
 * - everything except the `rom/` prefix resolves under the data root
 *   (dedicated media folder when `media_tree_uri` is set, otherwise
 *   `crystal-nova-data/` inside the themes tree);
 * - `rom/<rel>` resolves under the ROM tree (`games_tree_uri`).
 *
 * Security: the provider is exported, but every entry point verifies the
 * calling UID belongs to the launcher package. A signature-level
 * permission is NOT used because the two APKs are signed with different
 * certificates (Manager release key vs. launcher CI key). Package-name
 * checks via Binder UID cannot be spoofed by a third-party app: only the
 * installed app holding that package name passes.
 *
 * Read-only: insert/update/delete throw. `grantUriPermissions` is enabled
 * in the manifest so specific URIs (ROMs) can later be handed to emulator
 * apps with FLAG_GRANT_READ_URI_PERMISSION.
 */
class CrystalDataProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.crystalnova.manager.crystaldata"

        private const val LAUNCHER_PACKAGE = "io.crystalnova.launcher"
        private const val PREFS_NAME = "crystal-nova-manager"
        private const val KEY_THEMES_TREE_URI = "tree_uri"
        private const val KEY_MEDIA_TREE_URI = "media_tree_uri"
        private const val KEY_ROM_TREE_URI = "games_tree_uri"
        private const val DATA_DIR_NAME = "crystal-nova-data"
        private const val ROM_PREFIX = "rom"
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        checkCaller()
        require(mode == "r" || mode == "rt") { "CrystalDataProvider is read-only" }
        val ctx = context ?: throw FileNotFoundException("no context for $uri")
        val node = resolve(uri.pathSegments)
            ?: throw FileNotFoundException(uri.toString())
        if (node.isDirectory) throw FileNotFoundException("$uri is a directory")
        // We hold the persisted SAF grant; hand the caller a dup'd FD to
        // the underlying document. No copying, no temp files.
        return ctx.contentResolver.openFileDescriptor(node.uri, "r")
            ?: throw FileNotFoundException(uri.toString())
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        checkCaller()
        val node = resolve(uri.pathSegments) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        cursor.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> node.name
                OpenableColumns.SIZE -> node.length()
                else -> null
            }
        })
        return cursor
    }

    override fun getType(uri: Uri): String? {
        checkCaller()
        val ext = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("CrystalDataProvider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("CrystalDataProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("CrystalDataProvider is read-only")

    /** Only the Crystal Launcher may call this provider. */
    private fun checkCaller() {
        val ctx = context ?: throw SecurityException("no context")
        val packages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
        if (packages == null || !packages.contains(LAUNCHER_PACKAGE)) {
            throw SecurityException(
                "CrystalDataProvider is only callable by $LAUNCHER_PACKAGE"
            )
        }
    }

    /**
     * Maps provider path segments to a DocumentFile in the Manager's SAF
     * trees. `rom/<rel...>` -> ROM tree; everything else -> data root.
     */
    private fun resolve(segments: List<String>): DocumentFile? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (segments.firstOrNull() == ROM_PREFIX) {
            val romUri = prefs.getString(KEY_ROM_TREE_URI, null) ?: return null
            findInTree(ctx, romUri, segments.drop(1))
        } else {
            val mediaUri = prefs.getString(KEY_MEDIA_TREE_URI, null)
            if (mediaUri != null) {
                // Dedicated media folder: data tree roots at the SAF tree root.
                findInTree(ctx, mediaUri, segments)
            } else {
                // Legacy: crystal-nova-data/ beside the theme in the themes tree.
                val themesUri = prefs.getString(KEY_THEMES_TREE_URI, null) ?: return null
                findInTree(ctx, themesUri, listOf(DATA_DIR_NAME) + segments)
            }
        }
    }

    private fun findInTree(ctx: Context, treeUri: String, segments: List<String>): DocumentFile? {
        var node = try {
            DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))
        } catch (t: Throwable) {
            null
        } ?: return null
        for (seg in segments) {
            if (seg.isEmpty()) continue
            node = try {
                node.findFile(seg)
            } catch (t: Throwable) {
                null
            } ?: return null
        }
        return node
    }
}
