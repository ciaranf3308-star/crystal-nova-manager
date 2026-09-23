package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.InMemoryThemeFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveExtractorTest {

    private val fixtures = ArchiveTestFixtures

    private fun planFor(
        target: ImportTarget,
        unwrapDepth: Int = 0,
        payloadBytes: Long = 100,
    ) = ImportPlan(target, payloadFileCount = 1, payloadBytes = payloadBytes, unwrapDepth = unwrapDepth)

    @Test fun `zip extracts with wrapper unwrapped`() {
        val zipBytes = fixtures.createZip(
            mapOf(
                "Game/Pokemon Emerald.gba" to ByteArray(2048) { 7 },
                "Game/readme.txt" to "hi".toByteArray(),
            ),
        )
        val fs = InMemoryThemeFs()
        val staging = fs.mkdir(fs.rootNode, "staging")
        val extractor = ArchiveExtractor(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to zipBytes)),
        )
        // The plan says one wrapper level ("Game/") is stripped, and the
        // payload is a game folder (two files).
        val plan = planFor(ImportTarget.GameFolder("Pokemon Emerald"), unwrapDepth = 1)
        val report = extractor.extract(
            fs,
            ArchiveRef("game.zip", "u1", zipBytes.size.toLong(), ArchiveKind.ZIP),
            staging,
            plan,
        )
        assertEquals(2, report.fileCount)
        assertEquals(2050L, report.bytesWritten)
        val names = fs.children(staging).map { it.first }.toSet()
        assertEquals(setOf("Pokemon Emerald.gba", "readme.txt"), names)
        // Content is intact.
        val rom = fs.find(staging, "Pokemon Emerald.gba")!!
        assertEquals(2048L, fs.length(rom))
        assertTrue(fs.openInput(rom).readBytes().all { it == 7.toByte() })
    }

    @Test fun `zip-slip entries are skipped, not extracted`() {
        val zipBytes = fixtures.createZip(
            mapOf(
                "rom.gba" to ByteArray(100),
                "../evil.bin" to ByteArray(50),
                "/abs.bin" to ByteArray(50),
            ),
        )
        val fs = InMemoryThemeFs()
        val staging = fs.mkdir(fs.rootNode, "staging")
        val extractor = ArchiveExtractor(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to zipBytes)),
        )
        val report = extractor.extract(
            fs,
            ArchiveRef("game.zip", "u1", zipBytes.size.toLong(), ArchiveKind.ZIP),
            staging,
            planFor(ImportTarget.SingleFile("rom.gba")),
        )
        assertEquals(1, report.fileCount)
        assertEquals(setOf("rom.gba"), fs.children(staging).map { it.first }.toSet())
        // Nothing escaped to the fs root.
        assertEquals(
            setOf("staging"),
            fs.children(fs.rootNode).map { it.first }.toSet(),
        )
    }

    @Test fun `7z extracts preserving nested structure`() {
        val file7z = fixtures.create7z(
            mapOf(
                "disc/game.bin" to ByteArray(4096) { 9 },
                "disc/game.cue" to "cue-data".toByteArray(),
            ),
        )
        val fs = InMemoryThemeFs()
        val staging = fs.mkdir(fs.rootNode, "staging")
        val extractor = ArchiveExtractor(
            ArchiveTestFixtures.FakeOpener(sevenZs = mapOf("u7" to file7z)),
        )
        val report = extractor.extract(
            fs,
            ArchiveRef("game.7z", "u7", file7z.length(), ArchiveKind.SEVEN_Z),
            staging,
            // No unwrap: the "disc/" folder is real payload structure.
            planFor(ImportTarget.GameFolder("Final Fantasy VII")),
        )
        assertEquals(2, report.fileCount)
        val disc = fs.find(staging, "disc")!!
        assertTrue(fs.isDirectory(disc))
        assertEquals(
            setOf("game.bin", "game.cue"),
            fs.children(disc).map { it.first }.toSet(),
        )
        assertEquals(4096L, fs.length(fs.find(disc, "game.bin")!!))
    }

    @Test fun `progress callback reports bytes`() {
        val zipBytes = fixtures.createZip(
            mapOf("rom.gba" to ByteArray(10_000)),
        )
        val fs = InMemoryThemeFs()
        val staging = fs.mkdir(fs.rootNode, "staging")
        val extractor = ArchiveExtractor(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to zipBytes)),
        )
        var lastDone = 0L
        extractor.extract(
            fs,
            ArchiveRef("game.zip", "u1", zipBytes.size.toLong(), ArchiveKind.ZIP),
            staging,
            planFor(ImportTarget.SingleFile("rom.gba"), payloadBytes = 10_000),
        ) { done, _ -> lastDone = done }
        assertEquals(10_000L, lastDone)
    }

    @Test fun `zip streams per-chunk progress for one large entry`() {
        // A single giant entry (like a PS2 ISO) must report progress
        // continuously during its write — not once at the very end.
        val big = ByteArray(1_200_000) { 3 }
        val zipBytes = fixtures.createZip(mapOf("game.iso" to big))
        val fs = InMemoryThemeFs()
        val staging = fs.mkdir(fs.rootNode, "staging")
        val extractor = ArchiveExtractor(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to zipBytes)),
        )
        val seen = mutableListOf<Long>()
        extractor.extract(
            fs,
            ArchiveRef("game.zip", "u1", zipBytes.size.toLong(), ArchiveKind.ZIP),
            staging,
            planFor(ImportTarget.SingleFile("game.iso"), payloadBytes = big.size.toLong()),
        ) { done, total ->
            seen += done
            assertEquals(big.size.toLong(), total)
        }
        assertTrue("expected many progress callbacks, got ${seen.size}", seen.size > 10)
        assertTrue(
            "byte counts must strictly increase",
            seen.zipWithNext().all { (a, b) -> b > a },
        )
        assertEquals(big.size.toLong(), seen.last())
    }

    @Test fun `7z streams per-chunk progress for one large entry`() {
        val big = ByteArray(1_200_000) { 5 }
        val file7z = fixtures.create7z(mapOf("game.iso" to big))
        val fs = InMemoryThemeFs()
        val staging = fs.mkdir(fs.rootNode, "staging")
        val extractor = ArchiveExtractor(
            ArchiveTestFixtures.FakeOpener(sevenZs = mapOf("u7" to file7z)),
        )
        val seen = mutableListOf<Long>()
        extractor.extract(
            fs,
            ArchiveRef("game.7z", "u7", file7z.length(), ArchiveKind.SEVEN_Z),
            staging,
            planFor(ImportTarget.SingleFile("game.iso"), payloadBytes = big.size.toLong()),
        ) { done, total ->
            seen += done
            assertEquals(big.size.toLong(), total)
        }
        assertTrue("expected many progress callbacks, got ${seen.size}", seen.size > 10)
        assertTrue(
            "byte counts must strictly increase",
            seen.zipWithNext().all { (a, b) -> b > a },
        )
        assertEquals(big.size.toLong(), seen.last())
    }

    @Test fun `existing files are truncated and rewritten`() {
        val zipBytes = fixtures.createZip(mapOf("rom.gba" to ByteArray(100) { 1 }))
        val fs = InMemoryThemeFs()
        val staging = fs.mkdir(fs.rootNode, "staging")
        // Seed a stale file with the same name (retry scenario).
        val stale = fs.createFile(staging, "rom.gba")
        fs.openOutput(stale).use { it.write(ByteArray(9999) { 2 }) }
        val extractor = ArchiveExtractor(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to zipBytes)),
        )
        extractor.extract(
            fs,
            ArchiveRef("game.zip", "u1", zipBytes.size.toLong(), ArchiveKind.ZIP),
            staging,
            planFor(ImportTarget.SingleFile("rom.gba")),
        )
        assertEquals(100L, fs.length(fs.find(staging, "rom.gba")!!))
    }

    @Test fun `empty-after-unwrap entries are skipped`() {
        // A wrapper dir entry "Game/" unwraps to nothing: skipped.
        val zipBytes = fixtures.createZip(
            mapOf(
                "Game/" to ByteArray(0),
                "Game/rom.gba" to ByteArray(64),
            ),
        )
        val fs = InMemoryThemeFs()
        val staging = fs.mkdir(fs.rootNode, "staging")
        val extractor = ArchiveExtractor(
            ArchiveTestFixtures.FakeOpener(zips = mapOf("u1" to zipBytes)),
        )
        val report = extractor.extract(
            fs,
            ArchiveRef("game.zip", "u1", zipBytes.size.toLong(), ArchiveKind.ZIP),
            staging,
            planFor(ImportTarget.SingleFile("rom.gba"), unwrapDepth = 1),
        )
        assertEquals(1, report.fileCount)
        assertFalse(fs.children(staging).map { it.first }.contains("Game"))
    }
}
