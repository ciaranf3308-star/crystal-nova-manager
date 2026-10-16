package io.crystalnova.manager.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * u77: the hub headline ("5 TO REVIEW • 2 FAILED") is the first thing
 * Ciaran sees — these pin the pure [hubSummary] counts and the
 * headline wording so the status strip can never silently misreport
 * the queue again.
 */
class HubSummaryTest {

    private fun unreadableItem(id: String) = ArchiveItem(
        id = id,
        archiveUri = "uri:$id",
        archiveName = "$id.7z",
        displayTitle = id,
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

    private fun failedItem(id: String) = importableItem(id).copy(
        stage = ImportStage.FAILED,
        failure = ImportFailureReason.EXTRACTION_FAILED,
    )

    private fun notRecognizedItem(id: String) = ArchiveItem(
        id = id,
        archiveUri = "uri:$id",
        archiveName = "$id.7z",
        displayTitle = id,
        archiveKind = ArchiveKind.SEVEN_Z,
        archiveBytes = 100L,
        detection = Detection(null, Confidence.UNKNOWN, recognizedAsGame = false),
        platform = null,
        plan = null,
    )

    private fun missingItem(id: String) = needsReviewItem(id).copy(sourceMissing = true)

    @Test
    fun emptyQueue_headlineIsReady() {
        val s = hubSummary(emptyList())
        assertEquals(0, s.waiting)
        assertEquals("READY — NOTHING WAITING", s.headline())
    }

    @Test
    fun mixedQueue_countsAndHeadline() {
        val items = listOf(
            needsReviewItem("a"),
            needsReviewItem("b"),
            importableItem("c"),
            failedItem("d"),
            unreadableItem("e"),
            notRecognizedItem("f"),
            missingItem("g"),
        )
        val s = hubSummary(items)
        assertEquals(7, s.waiting)
        assertEquals(2, s.toReview)
        assertEquals(1, s.importable)
        assertEquals(1, s.failed)
        assertEquals(1, s.unreadable)
        assertEquals(1, s.notRecognized)
        assertEquals(1, s.missing)
        assertEquals(
            "2 TO REVIEW • 1 READY • 1 FAILED • 1 UNREADABLE • 1 NOT RECOGNIZED • 1 MISSING",
            s.headline(),
        )
    }

    @Test
    fun finishedSkippedAndClaimedAreInvisible() {
        val done = unreadableItem("a").copy(stage = ImportStage.COMPLETE)
        val skipped = needsReviewItem("b").copy(stage = ImportStage.SKIPPED)
        val claimed = needsReviewItem("c").copy(claimedByItemId = "parent")
        val s = hubSummary(listOf(done, skipped, claimed))
        assertEquals(0, s.waiting)
        assertEquals("READY — NOTHING WAITING", s.headline())
    }

    @Test
    fun onlyUnreadable_headlineNamesIt() {
        val s = hubSummary(listOf(unreadableItem("a"), unreadableItem("b")))
        assertEquals("2 UNREADABLE", s.headline())
    }
}
