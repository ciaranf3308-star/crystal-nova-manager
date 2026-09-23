package io.crystalnova.manager.importer

import android.content.Context
import android.os.Environment
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

/**
 * All-files-access (MANAGE_EXTERNAL_STORAGE) tests.
 *
 * - The direct File listing feeds the existing [ArchiveScanner] with
 *   game archives only: unrelated files in Downloads are never
 *   touched, only listed and filtered by extension.
 * - Below API 30 the grant can never apply (SAF fallback).
 * - The SAF/subfolder fallback path is preserved when access is not
 *   granted.
 */
@RunWith(RobolectricTestRunner::class)
class AllFilesAccessTest {

    private fun appContext(): Context = RuntimeEnvironment.getApplication()

    private fun tempDir(): File = Files.createTempDirectory("allfiles-test").toFile()

    private fun touch(dir: File, name: String, size: Int = 64): File =
        File(dir, name).apply { writeBytes(ByteArray(size)) }

    @Test
    fun `direct listing feeds scanner with game archives only`() {
        val dir = tempDir()
        touch(dir, "big.7z", 5000)
        touch(dir, "game.zip", 1000)
        touch(dir, "photo.jpg")
        touch(dir, "notes.txt")
        touch(dir, "game.rar")
        File(dir, "subfolder").mkdir()

        val outcome = ArchiveScanner(AllFilesAccess.directDownloadsListing(dir)).scan()

        assertEquals(listOf("big.7z", "game.zip"), outcome.archives.map { it.name })
        assertEquals(listOf("game.rar"), outcome.unsupported.map { it.name })
        // Unrelated files are listed, never opened or modified.
        assertTrue(File(dir, "photo.jpg").exists())
        assertTrue(File(dir, "notes.txt").exists())
        assertTrue(File(dir, "subfolder").isDirectory)
        // file: URIs flow through the existing ContentResolver /
        // DocumentFile open + delete paths unchanged.
        assertTrue(outcome.archives.all { it.uri.startsWith("file:") })
        assertTrue(outcome.archives.all { it.uri.contains("game.zip") || it.uri.contains("big.7z") })
    }

    @Test
    fun `direct listing of empty dir scans clean`() {
        val outcome = ArchiveScanner(AllFilesAccess.directDownloadsListing(tempDir())).scan()
        assertTrue(outcome.archives.isEmpty())
        assertTrue(outcome.unsupported.isEmpty())
    }

    @Test
    fun `direct listing surfaces unreadable dir as empty, not a crash`() {
        val missing = File(tempDir(), "nope")
        val outcome = ArchiveScanner(AllFilesAccess.directDownloadsListing(missing)).scan()
        assertTrue(outcome.archives.isEmpty())
    }

    @Test
    fun `no all-files access below api 30`() {
        // Even with the manager flag set, the grant can never apply below API 30.
        assertFalse(AllFilesAccess.hasAccess(29, true))
    }

    @Test
    fun `not granted by default`() {
        assertFalse(AllFilesAccess.hasAccess(34, false))
    }

    @Test
    fun `granted on api 30+ once the user allows it`() {
        assertTrue(AllFilesAccess.hasAccess(30, true))
        assertTrue(AllFilesAccess.hasAccess(34, true))
    }

    @Test
    fun `request intent falls back to the general settings page when per-app page unresolvable`() {
        val intent = AllFilesAccess.requestIntent(appContext())
        assertEquals(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, intent.action)
    }

    @Test
    fun `fallback selection keeps SAF path when not granted`() {
        val env = AndroidImporterEnvironment(appContext())
        // No grant stored: the SAF listing must refuse, exactly as
        // before — the fallback path is intact.
        try {
            env.selectDownloadsListing(false).listFiles()
            throw AssertionError("expected SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("not granted"))
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `direct selection lists the shared downloads dir`() {
        val raw = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        raw.mkdirs()
        val dir = AllFilesAccess.downloadsDir()
        assertNotNull(dir)
        val probe = File(dir, "direct-path-probe.zip").apply { writeBytes(ByteArray(32)) }
        try {
            val env = AndroidImporterEnvironment(appContext())
            val names = env.selectDownloadsListing(true).listFiles().map { it.name }
            assertTrue(names.contains("direct-path-probe.zip"))
        } finally {
            probe.delete()
        }
    }
}
