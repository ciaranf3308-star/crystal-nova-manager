package io.crystalnova.manager.importer

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.storage.SafThemeFs
import io.crystalnova.manager.storage.ThemeFs
import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream

/**
 * Production [ImporterEnvironment] over the Storage Access Framework.
 *
 * Two persisted tree grants, following the existing
 * `adoptEsdeThemesDir` pattern (ACTION_OPEN_DOCUMENT_TREE +
 * takePersistableUriPermission + URI in SharedPreferences +
 * revalidate every run + SecurityException becomes "grant again" UI):
 *
 * - Downloads (read): scanned for game archives — direct java.io.File
 *   scan of the normal Downloads directory when "All files access" is
 *   granted (see [AllFilesAccess]), otherwise the SAF tree grant
 *   fallback (a subfolder such as Downloads/GameImport, since Android
 *   11+ will not grant the Downloads root via the picker).
 * - ROM root (read/write): platform folders + staging live here, always
 *   via the SAF tree grant.
 *
 * No READ/WRITE_EXTERNAL_STORAGE. MANAGE_EXTERNAL_STORAGE is declared
 * and used only for the direct Downloads scan above.
 */
class AndroidImporterEnvironment(private val context: Context) : ImporterEnvironment {

    companion object {
        const val KEY_DOWNLOADS_TREE_URI = "importer.downloads_tree_uri"
        const val KEY_ROMS_TREE_URI = "importer.roms_tree_uri"
        private const val PREFS = "crystal-nova-manager"
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun downloadsTreeUri(): String? = prefs().getString(KEY_DOWNLOADS_TREE_URI, null)
    fun romsTreeUri(): String? = prefs().getString(KEY_ROMS_TREE_URI, null)

    fun setDownloadsTreeUri(uri: String) {
        prefs().edit().putString(KEY_DOWNLOADS_TREE_URI, uri).apply()
    }

    fun setRomsTreeUri(uri: String) {
        prefs().edit().putString(KEY_ROMS_TREE_URI, uri).apply()
    }

    private fun requireTree(uriString: String?, what: String): DocumentFile {
        if (uriString == null) throw SecurityException("$what folder not granted")
        val doc = try {
            DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: throw SecurityException("$what folder not accessible")
        try {
            if (!doc.canRead()) throw SecurityException("$what folder not readable")
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            throw SecurityException("$what folder not readable")
        }
        return doc
    }

    override fun downloadsListing(): DownloadsListing =
        selectDownloadsListing(AllFilesAccess.hasAccess())

    /**
     * Download-source selection. Direct File scan when "All files
     * access" is granted; the persisted SAF tree grant otherwise.
     * Internal for unit tests (the grant check itself needs a device).
     */
    internal fun selectDownloadsListing(allFilesGranted: Boolean): DownloadsListing =
        if (allFilesGranted) AllFilesAccess.downloadsListing() else safDownloadsListing()

    private fun safDownloadsListing(): DownloadsListing = object : DownloadsListing {
        override fun listFiles(): List<DownloadFile> {
            val root = requireTree(downloadsTreeUri(), "Downloads")
            return root.listFiles().map { doc ->
                DownloadFile(
                    name = doc.name ?: "unknown",
                    size = doc.length(),
                    uri = doc.uri.toString(),
                    isDirectory = doc.isDirectory,
                )
            }
        }
    }

    override fun archiveOpener(): ArchiveStreamOpener = object : ArchiveStreamOpener {
        override fun openInput(uri: String): InputStream =
            try {
                context.contentResolver.openInputStream(Uri.parse(uri))
                    ?: throw ArchiveReadException("Cannot open archive")
            } catch (e: SecurityException) {
                throw e
            } catch (e: ArchiveReadException) {
                throw e
            } catch (e: Exception) {
                throw ArchiveReadException("Cannot open archive: ${e.message}", e)
            }

        override fun openChannel(uri: String): ChannelHandle {
            val parsed = Uri.parse(uri)
            try {
                val pfd = context.contentResolver.openFileDescriptor(parsed, "r")
                    ?: throw ArchiveReadException("Cannot open archive")
                // 7z needs random access to its entry table; SAF
                // documents backed by real files give a seekable
                // channel. Everything closes with the handle.
                val stream = FileInputStream(pfd.fileDescriptor)
                return ChannelHandle(
                    stream.channel,
                    Closeable {
                        runCatching { stream.close() }
                        runCatching { pfd.close() }
                    },
                )
            } catch (e: SecurityException) {
                throw e
            } catch (e: ArchiveReadException) {
                throw e
            } catch (e: Exception) {
                throw ArchiveReadException("Cannot open archive: ${e.message}", e)
            }
        }
    }

    override fun romsFs(): ThemeFs? {
        if (romsTreeUri() == null) return null
        return SafThemeFs(context) { romsTreeUri() }
    }

    override fun archiveExists(uri: String): Boolean {
        return try {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.exists() == true
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override fun deleteArchive(uri: String): Boolean {
        return try {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() == true
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Best-effort free-space preflight. SAF tree URIs do not expose a
     * volume path for statvfs, so this reports the shared-storage
     * volume — an approximation when the ROM root lives on removable
     * media. The engine's mid-run ENOSPC abort is the real backstop.
     */
    override fun romsFreeBytes(): Long {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return 0L
            StatFs(dir.path).availableBytes
        } catch (_: Exception) {
            0L
        }
    }
}
