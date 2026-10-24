package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunked clipboard export tests (u84): the chunking helper must split
 * a folder's files into consecutive, complete, non-overlapping chunks.
 * Pure JVM — no Android, no disk.
 */
class LibraryChunkingTest {

    private fun folderOf(n: Int, name: String = "psx"): LibraryFolder =
        LibraryFolder(name, (1..n).map { "game%03d.chd".format(it) })

    @Test fun `chunk size constant is 50`() {
        assertEquals(50, CHUNK_SIZE)
    }

    @Test fun `exact splits are 50 50 remainder`() {
        val chunks = chunkLibraryFolder(folderOf(120))
        assertEquals(3, chunks.size)
        assertEquals(50, chunks[0].files.size)
        assertEquals(50, chunks[1].files.size)
        assertEquals(20, chunks[2].files.size)
    }

    @Test fun `folder smaller than one chunk yields exactly one chunk`() {
        val folder = folderOf(7)
        val chunks = chunkLibraryFolder(folder)
        assertEquals(1, chunks.size)
        assertEquals(folder.files, chunks[0].files)
    }

    @Test fun `folder of exactly one chunk yields exactly one full chunk`() {
        val chunks = chunkLibraryFolder(folderOf(50))
        assertEquals(1, chunks.size)
        assertEquals(50, chunks[0].files.size)
    }

    @Test fun `empty folder yields zero chunks`() {
        assertTrue(chunkLibraryFolder(LibraryFolder("empty", emptyList())).isEmpty())
    }

    @Test fun `chunks are consecutive and cover the full list exactly once`() {
        val folder = folderOf(123)
        val chunks = chunkLibraryFolder(folder)
        // Flattening in order reproduces the original list verbatim:
        // nothing dropped, nothing duplicated, nothing reordered.
        assertEquals(folder.files, chunks.flatMap { it.files })
        // No chunk exceeds the limit.
        assertTrue(chunks.all { it.files.size <= CHUNK_SIZE })
    }

    @Test fun `every chunk keeps the folder name for the json render path`() {
        val chunks = chunkLibraryFolder(folderOf(110, "n3ds"))
        assertTrue(chunks.all { it.name == "n3ds" })
        // And the chunk renders as that folder's JSON restricted to
        // the chunk's files, through the same path as COPY ALL.
        val text = LibraryScan(listOf(chunks[1])).exportText
        assertTrue(text.contains("\"n3ds\""))
        assertTrue(text.contains("game051.chd"))
        assertTrue(text.contains("game100.chd"))
        assertTrue(!text.contains("game050.chd"))
        assertTrue(!text.contains("game101.chd"))
    }
}
