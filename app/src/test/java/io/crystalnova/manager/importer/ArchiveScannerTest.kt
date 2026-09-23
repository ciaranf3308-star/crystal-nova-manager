package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveScannerTest {

    private fun listing(vararg files: DownloadFile): DownloadsListing =
        object : DownloadsListing {
            override fun listFiles(): List<DownloadFile> = files.toList()
        }

    private fun file(name: String, size: Long = 1000): DownloadFile =
        DownloadFile(name, size, "uri:$name", isDirectory = false)

    @Test fun `picks zip and 7z, largest first`() {
        val scanner = ArchiveScanner(
            listing(
                file("small.zip", 100),
                file("big.7z", 4_000_000_000L),
                file("mid.ZIP", 500),
            ),
        )
        val outcome = scanner.scan()
        assertEquals(
            listOf("big.7z", "mid.ZIP", "small.zip"),
            outcome.archives.map { it.name },
        )
        assertTrue(outcome.unsupported.isEmpty())
    }

    @Test fun `rar is reported unsupported, non-archives ignored`() {
        val scanner = ArchiveScanner(
            listing(
                file("Pokemon Emerald.zip"),
                file("Metal Gear Solid 2 Substance.7z"),
                file("game.rar"),
                file("photo.jpg"),
                file("invoice.pdf"),
                file("bios.zip"),
                DownloadFile("folder", 0, "uri:folder", isDirectory = true),
            ),
        )
        val outcome = scanner.scan()
        assertEquals(
            setOf("Pokemon Emerald.zip", "Metal Gear Solid 2 Substance.7z", "bios.zip"),
            outcome.archives.map { it.name }.toSet(),
        )
        assertEquals(listOf("game.rar"), outcome.unsupported.map { it.name })
        assertEquals(ArchiveKind.RAR_UNSUPPORTED, outcome.unsupported.single().kind)
    }

    @Test fun `empty downloads scans clean`() {
        val outcome = ArchiveScanner(listing()).scan()
        assertTrue(outcome.archives.isEmpty())
        assertTrue(outcome.unsupported.isEmpty())
    }

    @Test(expected = SecurityException::class)
    fun `revoked grant surfaces as SecurityException`() {
        val revoked = object : DownloadsListing {
            override fun listFiles(): List<DownloadFile> = throw SecurityException("revoked")
        }
        ArchiveScanner(revoked).scan()
    }
}
