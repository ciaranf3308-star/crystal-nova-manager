package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * u73 regression guard: the hub offered RESUME QUEUE whenever anything
 * was waiting, but a queue of only unreadable (inspectFailed) items
 * made it a dead button — prepareImport() finds zero importable
 * candidates and lands back on "SCAN FOUND NOTHING TO IMPORT",
 * looping the user forever. [hubQueueDecision] centralizes the
 * resume decision so the hub only offers resume when something can
 * actually move forward, and always surfaces the dead items for
 * removal.
 */
class HubQueueDecisionTest {

    private fun unreadableItem(id: String, name: String) = ArchiveItem(
        id = id,
        archiveUri = "uri:$id",
        archiveName = name,
        displayTitle = name.substringBeforeLast('.'),
        archiveKind = ArchiveKind.SEVEN_Z,
        archiveBytes = 100L,
        detection = Detection(null, Confidence.UNKNOWN, recognizedAsGame = true),
        platform = null,
        plan = null,
        inspectFailed = true,
        failureDetail = "Could not read 7z: boom",
    )

    private fun needsReviewItem(id: String) = ArchiveItem(
        id = id,
        archiveUri = "uri:$id",
        archiveName = "$id.zip",
        displayTitle = id,
        archiveKind = ArchiveKind.ZIP,
        archiveBytes = 100L,
        detection = Detection(null, Confidence.LIKELY, recognizedAsGame = true),
        platform = null,
        plan = null,
    )

    private fun importableItem(id: String) = ArchiveItem(
        id = id,
        archiveUri = "uri:$id",
        archiveName = "$id.zip",
        displayTitle = id,
        archiveKind = ArchiveKind.ZIP,
        archiveBytes = 100L,
        detection = Detection(PlatformId.N3DS, Confidence.CONFIRMED, recognizedAsGame = true),
        platform = PlatformId.N3DS,
        plan = ImportPlan(
            target = ImportTarget.SingleFile(fileName = "$id.zip"),
            payloadFileCount = 1,
            payloadBytes = 100L,
        ),
    )

    @Test
    fun onlyUnreadableItems_offersNoResume_andListsThemAll() {
        val items = listOf(
            unreadableItem("a", "mario.7z"),
            unreadableItem("b", "zelda.7z"),
            unreadableItem("c", "kirby.7z"),
        )
        val decision = hubQueueDecision(items)
        // The u73 dead-button case: resume must not be offered.
        assertFalse(decision.canResume)
        // Every dead item is exposed for the escape hatch.
        assertEquals(listOf("a", "b", "c"), decision.unreadable.map { it.id })
    }

    @Test
    fun mixedQueueWithNeedsReview_offersResume() {
        val items = listOf(
            unreadableItem("a", "mario.7z"),
            needsReviewItem("b"),
        )
        val decision = hubQueueDecision(items)
        assertTrue(decision.canResume)
        assertEquals(listOf("a"), decision.unreadable.map { it.id })
    }

    @Test
    fun importableItem_offersResume_andIsNotUnreadable() {
        val decision = hubQueueDecision(listOf(importableItem("a")))
        assertTrue(decision.canResume)
        assertTrue(decision.unreadable.isEmpty())
    }

    @Test
    fun emptyQueue_offersNoResume() {
        val decision = hubQueueDecision(emptyList())
        assertFalse(decision.canResume)
        assertTrue(decision.unreadable.isEmpty())
    }

    @Test
    fun finishedItemsAreIgnored() {
        val done = unreadableItem("a", "mario.7z").copy(stage = ImportStage.COMPLETE)
        val skipped = unreadableItem("b", "zelda.7z").copy(stage = ImportStage.SKIPPED)
        val decision = hubQueueDecision(listOf(done, skipped))
        assertFalse(decision.canResume)
        assertTrue(decision.unreadable.isEmpty())
    }
}
