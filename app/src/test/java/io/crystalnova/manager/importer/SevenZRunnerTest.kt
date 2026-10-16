package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Recording fake [SevenZRunner] for the inspector/extractor fallback
 * tests. The real ARM64 binary cannot execute on JVM CI — the CLI
 * contract it fakes here (`l -slt` format, `-so` byte streaming) was
 * verified against real 7zz output (see [parseSevenZSlt] test).
 */
class FakeSevenZRunner(
    var isAvailable: Boolean = true,
    var listResult: List<SevenZRunner.Entry> = emptyList(),
    var listError: Throwable? = null,
    var headerBytes: Map<String, ByteArray> = emptyMap(),
    var extractError: Throwable? = null,
    var extractWrites: Map<String, ByteArray> = emptyMap(),
) : SevenZRunner {
    var listCalls = 0
    val readCalls = mutableListOf<String>()
    var extractCalls = 0
    var cancelCalls = 0

    override val available: Boolean get() = isAvailable

    override fun list(archive: File): List<SevenZRunner.Entry> {
        listCalls++
        listError?.let { throw it }
        return listResult
    }

    override fun readFirstBytes(archive: File, entryPath: String, n: Int): ByteArray? {
        readCalls += entryPath
        return headerBytes[entryPath]?.take(n)?.toByteArray()
    }

    override fun extract(archive: File, destDir: File, onProgress: (Float) -> Unit): Boolean {
        extractCalls++
        extractError?.let { throw it }
        for ((path, bytes) in extractWrites) {
            val f = File(destDir, path)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        onProgress(1f)
        return true
    }

    override fun cancel() {
        cancelCalls++
    }
}

class SevenZSltParserTest {

    /**
     * Real `7zz l -slt` output captured from 7-Zip 23.01 (x64) — the
     * format is architecture-independent, so this pins the parser
     * against the genuine CLI contract.
     */
    private val realOutput = """
        |
        |7-Zip 23.01 (x64) : Copyright (c) 1999-2023 Igor Pavlov : 2023-06-20
        | 64-bit locale=C.UTF-8 Threads:2 OPEN_MAX:4096
        |
        |Scanning the drive for archives:
        |1 file, 254 bytes (1 KiB)
        |
        |Listing archive: test.7z
        |
        |--
        |Path = test.7z
        |Type = 7z
        |Physical Size = 254
        |Headers Size = 226
        |Method = LZMA2:12
        |Solid = +
        |Blocks = 1
        |
        |----------
        |Path = subdir
        |Size = 0
        |Packed Size = 0
        |Modified = 2026-10-16 21:48:26.8186647
        |Attributes = D drwxrwx---
        |CRC =
        |Encrypted = -
        |Method =
        |Block =
        |
        |Path = subdir/nested
        |Size = 0
        |Packed Size = 0
        |Modified = 2026-10-16 21:48:26.8201465
        |Attributes = D drwxrwx---
        |CRC =
        |Encrypted = -
        |Method =
        |Block =
        |
        |Path = subdir/file one.txt
        |Size = 12
        |Packed Size = 28
        |Modified = 2026-10-16 21:48:26.8201465
        |Attributes = A -rw-rw----
        |CRC = AF083B2D
        |Encrypted = -
        |Method = LZMA2:12
        |Block = 0
        |
        |Path = subdir/nested/deep.bin
        |Size = 8
        |Packed Size =
        |Modified = 2026-10-16 21:48:26.8201465
        |Attributes = A -rw-rw----
        |CRC = 1A489739
        |Encrypted = -
        |Method = LZMA2:12
        |Block = 0
        |
        |Path = top.txt
        |Size = 4
        |Packed Size =
        |Modified = 2026-10-16 21:48:26.8201465
        |Attributes = A -rw-rw----
        |CRC = 4978F422
        |Encrypted = -
        |Method = LZMA2:12
        |Block = 0
        |
    """.trimMargin()

    @Test fun `slt output parses entries, directories and skips the archive header`() {
        val entries = parseSevenZSlt(realOutput)
        assertEquals(5, entries.size)

        val dirs = entries.filter { it.isDirectory }.map { it.path }.toSet()
        assertEquals(setOf("subdir", "subdir/nested"), dirs)

        val files = entries.filter { !it.isDirectory }.associateBy { it.path }
        assertEquals(12L, files["subdir/file one.txt"]!!.size)
        assertEquals(8L, files["subdir/nested/deep.bin"]!!.size)
        assertEquals(4L, files["top.txt"]!!.size)
        // The archive header block (Path = test.7z, no Attributes) is skipped.
        assertTrue(entries.none { it.path == "test.7z" })
        assertFalse(entries.any { it.size < 0 })
    }

    @Test fun `slt parser never throws on garbage`() {
        assertEquals(0, parseSevenZSlt("").size)
        assertEquals(0, parseSevenZSlt("not an archive listing\nat all\n").size)
        assertEquals(0, parseSevenZSlt("Path = lonely\n\n").size)
    }
}
