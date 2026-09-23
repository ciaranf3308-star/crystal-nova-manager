package io.crystalnova.manager.importer

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared archive-building helpers for the importer I/O tests.
 * Builds REAL zip/7z bytes (java.util.zip + commons-compress) so the
 * inspector/extractor run their production code paths on the JVM.
 */
object ArchiveTestFixtures {

    fun createZip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun create7z(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("importer-test", ".7z")
        file.deleteOnExit()
        SevenZOutputFile(file).use { out ->
            for ((name, bytes) in entries) {
                val entry = SevenZArchiveEntry()
                entry.name = name
                out.putArchiveEntry(entry)
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return file
    }

    /** [ArchiveStreamOpener] serving prebuilt bytes/files by URI. */
    class FakeOpener(
        private val zips: Map<String, ByteArray> = emptyMap(),
        private val sevenZs: Map<String, File> = emptyMap(),
    ) : ArchiveStreamOpener {
        override fun openInput(uri: String) =
            ByteArrayInputStream(zips[uri] ?: error("no zip bytes for $uri"))

        override fun openChannel(uri: String): ChannelHandle {
            val file = sevenZs[uri] ?: error("no 7z file for $uri")
            val stream = FileInputStream(file)
            return ChannelHandle(stream.channel, stream)
        }
    }

    /** A realistic GameCube disc header window: magic word at 0x1C. */
    fun gcMagic(): ByteArray = ByteArray(64).also { win ->
        win[0x1C] = 0xC2.toByte()
        win[0x1D] = 0x33
        win[0x1E] = 0x9F.toByte()
        win[0x1F] = 0x3D
        win[0x20] = 0x47 // "GMRE" game id follows the magic
        win[0x21] = 0x4D
        win[0x22] = 0x52
        win[0x23] = 0x45
    }
}

class ArchiveInspectorTest {

    private val fixtures = ArchiveTestFixtures

    @Test fun `zip inspection lists entries with real uncompressed sizes`() {
        val zipBytes = fixtures.createZip(
            mapOf(
                "Pokemon Emerald.gba" to ByteArray(1024) { it.toByte() },
                "readme.txt" to "hi".toByteArray(),
            ),
        )
        val inspector = ArchiveInspector(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to zipBytes)),
        )
        val (inspection, _) = inspector.inspect(
            ArchiveRef("game.zip", "u1", zipBytes.size.toLong(), ArchiveKind.ZIP),
        )
        assertEquals(2, inspection.entries.size)
        assertEquals(setOf("gba", "txt"), inspection.extensions)
        // Sizes stream in from data descriptors: ZipInputStream
        // reports -1 until the entry is drained, so the inspector
        // drains and counts every entry.
        assertEquals(1026L, inspection.totalUncompressedBytes)
    }

    @Test fun `zip header capture feeds the detector`() {
        val zipBytes = fixtures.createZip(
            mapOf("game.iso" to fixtures.gcMagic()),
        )
        val inspector = ArchiveInspector(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to zipBytes)),
        )
        val inspected = inspector.inspect(
            ArchiveRef("game.zip", "u1", zipBytes.size.toLong(), ArchiveKind.ZIP),
        )
        val header = inspected.headers["game.iso"]
        assertTrue(header != null && header.size == ArchiveInspector.HEADER_WINDOW_BYTES)
        assertEquals(0xC2.toByte(), header!![0x1C])

        // Wire it into the detector like the engine does: the
        // detector indexes the filtered file list, not the raw entries,
        // and the offset is honored against the captured window.
        val detector = PlatformDetector()
        val entries = inspected.inspection.entries
        val files = entries.filter { !it.isDirectory }
        val reader = PlatformDetector.HeaderReader { index, offset, length ->
            val window = files.getOrNull(index)?.path?.let { inspected.headers[it] }
            if (window == null || offset >= window.size) {
                null
            } else {
                window.copyOfRange(offset.toInt(), minOf(window.size, offset.toInt() + length))
            }
        }
        val detection = detector.detect("game.zip", entries, reader)
        assertEquals(PlatformId.GAMECUBE, detection.platform)
        assertEquals(Confidence.CONFIRMED, detection.confidence)
    }

    @Test fun `7z inspection lists entries`() {
        val file7z = fixtures.create7z(
            mapOf(
                "game/SLUS-20554.iso" to ByteArray(64),
                "game/SLUS-20554.cue" to "cue".toByteArray(),
            ),
        )
        val inspector = ArchiveInspector(
            ArchiveTestFixtures.FakeOpener(sevenZs = mapOf("u7" to file7z)),
        )
        val (inspection, headers) = inspector.inspect(
            ArchiveRef("game.7z", "u7", file7z.length(), ArchiveKind.SEVEN_Z),
        )
        assertEquals(2, inspection.entries.size)
        assertEquals(setOf("iso", "cue"), inspection.extensions)
        assertTrue(headers.containsKey("game/SLUS-20554.iso"))
    }

    @Test(expected = ArchiveReadException::class)
    fun `truncated zip raises ArchiveReadException`() {
        val zipBytes = fixtures.createZip(mapOf("a.gba" to ByteArray(100)))
        // Cut mid-entry-data (the local file header is 35 bytes): the
        // inflater genuinely runs out of stream here. A cut that only
        // drops the central directory still drains cleanly and is not
        // what this test is for.
        val broken = zipBytes.copyOf(40)
        val inspector = ArchiveInspector(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to broken)),
        )
        inspector.inspect(
            ArchiveRef("game.zip", "u1", broken.size.toLong(), ArchiveKind.ZIP),
        )
    }

    @Test fun `plan unwraps a single wrapper folder to a single file`() {
        val inspection = ArchiveInspection(
            kind = ArchiveKind.ZIP,
            entries = listOf(
                ArchiveEntryInfo("Game/", 0, true),
                ArchiveEntryInfo("Game/Pokemon Emerald.gba", 16_777_216, false),
            ),
            totalUncompressedBytes = 16_777_216,
            extensions = setOf("gba"),
            topLevelNames = listOf("Game"),
        )
        val plan = ArchiveInspector(
            ArchiveTestFixtures.FakeOpener(),
        ).planPayload(inspection, "Pokemon Emerald.zip")!!
        assertEquals(
            ImportTarget.SingleFile("Pokemon Emerald.gba"),
            plan.target,
        )
        assertEquals(1, plan.unwrapDepth)
        assertEquals(1, plan.payloadFileCount)
    }

    @Test fun `plan keeps bin-cue together in a game folder`() {
        val inspection = ArchiveInspection(
            kind = ArchiveKind.ZIP,
            entries = listOf(
                ArchiveEntryInfo("game.bin", 700_000_000L, false),
                ArchiveEntryInfo("game.cue", 2_000, false),
            ),
            totalUncompressedBytes = 700_002_000L,
            extensions = setOf("bin", "cue"),
            topLevelNames = listOf("game.bin", "game.cue"),
        )
        val plan = ArchiveInspector(
            ArchiveTestFixtures.FakeOpener(),
        ).planPayload(inspection, "Final Fantasy VII (Disc 1) [SCUS-94163].zip")!!
        val target = plan.target as ImportTarget.GameFolder
        assertEquals("Final Fantasy VII", target.folderName)
        assertEquals(2, plan.payloadFileCount)
        assertEquals(0, plan.unwrapDepth)
    }

    @Test fun `plan does not unwrap when a root file exists`() {
        val inspection = ArchiveInspection(
            kind = ArchiveKind.ZIP,
            entries = listOf(
                ArchiveEntryInfo("Game/rom.gba", 100, false),
                ArchiveEntryInfo("readme.txt", 10, false),
            ),
            totalUncompressedBytes = 110,
            extensions = setOf("gba", "txt"),
            topLevelNames = listOf("Game", "readme.txt"),
        )
        val plan = ArchiveInspector(
            ArchiveTestFixtures.FakeOpener(),
        ).planPayload(inspection, "game.zip")!!
        assertEquals(0, plan.unwrapDepth)
        assertTrue(plan.target is ImportTarget.GameFolder)
    }

    @Test fun `empty archive has no plan`() {
        val inspection = ArchiveInspection(
            kind = ArchiveKind.ZIP,
            entries = emptyList(),
            totalUncompressedBytes = 0,
            extensions = emptySet(),
            topLevelNames = emptyList(),
        )
        assertNull(
            ArchiveInspector(ArchiveTestFixtures.FakeOpener())
                .planPayload(inspection, "empty.zip"),
        )
    }

    @Test fun `sanitizeEntryPath blocks traversal`() {
        assertNull(sanitizeEntryPath("../evil.bin"))
        assertNull(sanitizeEntryPath("/abs/path.bin"))
        assertNull(sanitizeEntryPath("C:\\win\\path.bin"))
        assertNull(sanitizeEntryPath("a/../../b.bin"))
        assertEquals(listOf("a", "b.bin"), sanitizeEntryPath("a/b.bin"))
        assertEquals(listOf("a", "b.bin"), sanitizeEntryPath("a\\b.bin"))
        assertEquals(listOf("b.bin"), sanitizeEntryPath("./b.bin"))
    }

    @Test fun `unwrapPayloadPaths handles deep nesting`() {
        val (paths, depth) = unwrapPayloadPaths(
            listOf("a/b/c/rom.gba"),
        )
        assertEquals(listOf("rom.gba"), paths)
        assertEquals(3, depth)
    }
}
