package io.crystalnova.manager.importer

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

/**
 * `file:`-URI handling for [AndroidImporterEnvironment.archiveExists]
 * and [AndroidImporterEnvironment.deleteArchive].
 *
 * Regression test for the "ARCHIVE GONE" failures: when "All files
 * access" is granted, Downloads are listed via direct java.io.File
 * scan ([AllFilesAccess.downloadsListing]) producing `file:` URIs.
 * `DocumentFile.fromSingleUri` is built for `content:` URIs — for a
 * `file:` URI its internal query throws, which the old code swallowed,
 * so every direct-scan archive reported "missing" and imports failed
 * with [ImportFailureReason.SOURCE_MISSING] ("ARCHIVE GONE") while the
 * archives sat untouched in Downloads.
 *
 * The `content:`-scheme behavior is unchanged: unknown content URIs
 * still return false without throwing.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidImporterEnvironmentTest {

    private fun env(): AndroidImporterEnvironment =
        AndroidImporterEnvironment(RuntimeEnvironment.getApplication())

    private fun tempDir(): File = Files.createTempDirectory("env-file-uri-test").toFile()

    private fun touch(dir: File, name: String, bytes: ByteArray = ByteArray(128)): File =
        File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun `archiveExists true for existing file-scheme uri`() {
        val file = touch(tempDir(), "game.zip")
        assertTrue(env().archiveExists(Uri.fromFile(file).toString()))
    }

    @Test
    fun `archiveExists true for nested direct-scan listing uri`() {
        // Mirrors AllFilesAccess.downloadsListing: recursive scan of a
        // subfolder, Uri.fromFile(...).toString().
        val sub = File(tempDir(), "GameImport").apply { mkdirs() }
        val file = touch(sub, "Super Mario Sunshine.zip.7z")
        assertTrue(env().archiveExists(Uri.fromFile(file).toString()))
    }

    @Test
    fun `archiveExists false for missing file-scheme uri`() {
        val missing = File(tempDir(), "gone.zip")
        assertFalse(missing.exists())
        assertFalse(env().archiveExists(Uri.fromFile(missing).toString()))
    }

    @Test
    fun `archiveExists false for file-scheme uri without a path`() {
        assertFalse(env().archiveExists("file://"))
    }

    @Test
    fun `deleteArchive deletes a real file and reports true`() {
        val file = touch(tempDir(), "delete-me.zip")
        val uri = Uri.fromFile(file).toString()
        assertTrue(file.exists())
        assertTrue(env().deleteArchive(uri))
        assertFalse(file.exists())
    }

    @Test
    fun `deleteArchive false for missing file-scheme uri`() {
        val missing = File(tempDir(), "gone.zip")
        assertFalse(env().deleteArchive(Uri.fromFile(missing).toString()))
    }

    @Test
    fun `content-scheme unknown uri still returns false without throwing`() {
        assertFalse(env().archiveExists("content://does.not.exist.example/doc/123"))
        assertFalse(env().deleteArchive("content://does.not.exist.example/doc/123"))
    }
}
