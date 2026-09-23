package io.crystalnova.manager.importer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE) for the Game Importer.
 *
 * Android 11+ refuses to grant the Downloads *root* through the SAF
 * tree picker ("can't use this folder — to protect your privacy, choose
 * another folder"), which forced the Downloads/GameImport subfolder
 * workaround. With all-files access the importer scans the normal
 * shared Downloads directory directly via java.io.File — no picker,
 * no subfolder. This is a sideloaded personal utility, so Play Store
 * policy is irrelevant.
 *
 * Only Downloads *reading* moves to the direct path; ROM-root writes
 * stay on the existing SAF tree grant (unchanged). When access is not
 * granted (or on API < 30) the SAF/subfolder method is used
 * automatically — see [AndroidImporterEnvironment.selectDownloadsListing].
 */
object AllFilesAccess {

    /** True on API 30+ once the user granted "All files access". */
    fun hasAccess(): Boolean =
        hasAccess(Build.VERSION.SDK_INT, Environment.isExternalStorageManager())

    /**
     * Testable seam for [hasAccess]. The grant state is environment-
     * dependent (a Robolectric shadow default is not a real device), so
     * tests pin both inputs instead of depending on sandbox defaults.
     * Production behavior is unchanged.
     */
    internal fun hasAccess(sdkInt: Int, isExternalStorageManager: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.R && isExternalStorageManager

    /**
     * Intent for this app's "All files access" page in system Settings.
     * Falls back to the general all-files page when the per-app page
     * is not resolvable on this device.
     */
    fun requestIntent(context: Context): Intent {
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        val resolved = resolve(perApp, context)
        return if (resolved) perApp
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    private fun resolve(intent: Intent, context: Context): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ) != null
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }

    /** The normal shared Downloads directory, or null when unavailable. */
    @Suppress("DEPRECATION")
    fun downloadsDir(): File? = try {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

    /**
     * Direct File listing of [dir]. Only used when [hasAccess]; the
     * URIs use the `file:` scheme, which the existing
     * [ArchiveStreamOpener] and [DocumentFile]-based read/delete paths
     * already handle (ContentResolver and DocumentFile both accept
     * `file:` URIs), so extraction, verification and source deletion
     * work unchanged.
     *
     * The listing is recursive (archives in subfolders — e.g. the old
     * Downloads/GameImport workaround folder — are found) with a depth
     * cap of [MAX_SCAN_DEPTH] and hidden directories skipped. Read-only:
     * only [File.listFiles], names and lengths are touched.
     */
    fun directDownloadsListing(dir: File): DownloadsListing =
        object : DownloadsListing {
            override fun listFiles(): List<DownloadFile> = listRecursive(dir, "", 0)
        }

    /** How many subfolder levels below Downloads the direct scan descends. */
    internal const val MAX_SCAN_DEPTH = 4

    private fun listRecursive(dir: File, relativePath: String, depth: Int): List<DownloadFile> {
        val files = dir.listFiles() ?: return emptyList()
        val out = mutableListOf<DownloadFile>()
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth < MAX_SCAN_DEPTH) {
                    val rel = if (relativePath.isEmpty()) f.name else "$relativePath/${f.name}"
                    out += listRecursive(f, rel, depth + 1)
                }
            } else {
                out += DownloadFile(
                    name = f.name,
                    size = f.length(),
                    uri = Uri.fromFile(f).toString(),
                    isDirectory = false,
                    relativePath = relativePath,
                )
            }
        }
        return out
    }

    /**
     * Direct listing of the real Downloads dir. Throws
     * SecurityException when the directory is unavailable, so the
     * engine's existing "grant again" error path handles it.
     */
    fun downloadsListing(): DownloadsListing {
        val dir = downloadsDir()
            ?: throw SecurityException("Downloads directory not available")
        return directDownloadsListing(dir)
    }
}
