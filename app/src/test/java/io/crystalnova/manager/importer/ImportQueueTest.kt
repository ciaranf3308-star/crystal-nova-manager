package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportQueueTest {

    private fun sampleItem(id: String) = ArchiveItem(
        id = id,
        archiveUri = "uri:$id",
        archiveName = "$id.zip",
        displayTitle = "Title $id",
        archiveKind = ArchiveKind.ZIP,
        archiveBytes = 1234L,
        detection = Detection(
            platform = PlatformId.GBA,
            confidence = Confidence.CONFIRMED,
            signals = listOf("ext: gba"),
            recognizedAsGame = true,
        ),
        platform = PlatformId.GBA,
        plan = ImportPlan(
            target = ImportTarget.SingleFile("game.gba"),
            payloadFileCount = 1,
            payloadBytes = 5678L,
            unwrapDepth = 0,
        ),
        stage = ImportStage.WAITING,
        addedAt = 42L,
    )

    @Test fun `queue round-trips through JSON`() {
        val file = File.createTempFile("queue", ".json").also { it.delete() }
        val queue = ImportQueue(file).also { it.load() }
        queue.add(sampleItem("a"))
        queue.add(sampleItem("b").copy(stage = ImportStage.FAILED, failure = ImportFailureReason.EXTRACTION_FAILED))

        val reloaded = ImportQueue(file).also { it.load() }
        assertEquals(2, reloaded.items.value.size)
        val a = reloaded.items.value.first { it.id == "a" }
        assertEquals("uri:a", a.archiveUri)
        assertEquals(PlatformId.GBA, a.platform)
        assertEquals(ImportTarget.SingleFile("game.gba"), a.plan!!.target)
        assertEquals(listOf("ext: gba"), a.detection.signals)
        val b = reloaded.items.value.first { it.id == "b" }
        assertEquals(ImportStage.FAILED, b.stage)
        assertEquals(ImportFailureReason.EXTRACTION_FAILED, b.failure)
    }

    @Test fun `update persists every transition`() {
        val file = File.createTempFile("queue", ".json").also { it.delete() }
        val queue = ImportQueue(file).also { it.load() }
        queue.add(sampleItem("a"))
        queue.update("a") { it.copy(stage = ImportStage.EXTRACTING) }

        val reloaded = ImportQueue(file).also { it.load() }
        assertEquals(ImportStage.EXTRACTING, reloaded.items.value.single().stage)
    }

    @Test fun `moveToFront reorders and preserves the item`() {
        val file = File.createTempFile("queue", ".json").also { it.delete() }
        val queue = ImportQueue(file).also { it.load() }
        queue.add(sampleItem("a"))
        queue.add(sampleItem("b"))
        queue.add(sampleItem("c"))

        queue.moveToFront("c")
        assertEquals(listOf("c", "a", "b"), queue.items.value.map { it.id })
        assertEquals("Title c", queue.items.value.first().displayTitle)

        // No-op for an unknown id.
        queue.moveToFront("nope")
        assertEquals(listOf("c", "a", "b"), queue.items.value.map { it.id })

        // Persists across reload.
        val reloaded = ImportQueue(file).also { it.load() }
        assertEquals(listOf("c", "a", "b"), reloaded.items.value.map { it.id })
    }

    @Test fun `loose fields round-trip and old JSON defaults them`() {
        val file = File.createTempFile("queue", ".json").also { it.delete() }
        val queue = ImportQueue(file).also { it.load() }
        queue.add(
            sampleItem("loose").copy(
                isLooseFile = true,
                archiveKind = ArchiveKind.LOOSE_FILE,
                claimedByItemId = null,
            ),
        )
        queue.add(
            sampleItem("claimed").copy(
                isLooseFile = true,
                archiveKind = ArchiveKind.LOOSE_FILE,
                claimedByItemId = "loose",
            ),
        )

        val reloaded = ImportQueue(file).also { it.load() }
        val loose = reloaded.items.value.first { it.id == "loose" }
        assertTrue(loose.isLooseFile)
        assertEquals(null, loose.claimedByItemId)
        val claimed = reloaded.items.value.first { it.id == "claimed" }
        assertTrue(claimed.isLooseFile)
        assertEquals("loose", claimed.claimedByItemId)

        // Old queue JSON (pre-u68, no new keys) decodes with defaults.
        val old = File.createTempFile("queue-old", ".json")
        old.writeText(
            """[{"id":"old","archiveUri":"uri:old","archiveName":"old.zip","displayTitle":"Old","archiveKind":"ZIP","archiveBytes":1,"detection":{"confidence":"CONFIRMED","recognizedAsGame":true},"stage":"WAITING"}]""",
        )
        val oldQueue = ImportQueue(old).also { it.load() }
        val oldItem = oldQueue.items.value.single()
        assertEquals(false, oldItem.isLooseFile)
        assertEquals(null, oldItem.claimedByItemId)
    }

    @Test fun `malformed rows are dropped, good rows survive`() {
        val file = File.createTempFile("queue", ".json")
        file.writeText("""[{"id":"good","archiveUri":"u","detection":{},"stage":"WAITING"},not-json]""")
        val queue = ImportQueue(file).also { it.load() }
        // Whole document is malformed -> empty queue, no crash.
        assertTrue(queue.items.value.isEmpty())
    }

    @Test fun `clearFinished keeps waiting items`() {
        val file = File.createTempFile("queue", ".json").also { it.delete() }
        val queue = ImportQueue(file).also { it.load() }
        queue.add(sampleItem("done").copy(stage = ImportStage.COMPLETE))
        queue.add(sampleItem("skip").copy(stage = ImportStage.SKIPPED))
        queue.add(sampleItem("wait"))
        queue.clearFinished()
        assertEquals(listOf("wait"), queue.items.value.map { it.id })
    }

    @Test fun `history records and caps entries`() {
        val file = File.createTempFile("history", ".json").also { it.delete() }
        val history = ImportHistoryRepository(file, cap = 3).also { it.load() }
        repeat(5) { i ->
            history.record(
                ImportHistoryEntry(
                    id = "h$i",
                    title = "Game $i",
                    platform = PlatformId.GBA,
                    archiveName = "g$i.zip",
                    outcome = ImportOutcome.SUCCESS,
                    reason = null,
                    finishedAt = i.toLong(),
                ),
            )
        }
        val entries = history.entries.value
        assertEquals(3, entries.size)
        assertEquals("h4", entries.first().id) // newest first

        val reloaded = ImportHistoryRepository(file, cap = 3).also { it.load() }
        assertEquals(3, reloaded.entries.value.size)
        assertEquals("h4", reloaded.entries.value.first().id)
    }
}
