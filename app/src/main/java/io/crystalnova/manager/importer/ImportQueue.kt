package io.crystalnova.manager.importer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persistent import queue. Every mutation is written to app-private
 * JSON immediately, so classifications, duplicates, and run progress
 * survive a process restart mid-import.
 *
 * Malformed rows are dropped defensively rather than nuking the
 * whole queue; a fully unreadable file yields an empty queue.
 */
class ImportQueue(private val file: File) {

    private val _items = MutableStateFlow<List<ArchiveItem>>(emptyList())
    val items: StateFlow<List<ArchiveItem>> = _items.asStateFlow()

    @Synchronized
    fun load() {
        _items.value = readQueue()
    }

    @Synchronized
    fun add(item: ArchiveItem) {
        _items.value = _items.value + item
        persist()
    }

    @Synchronized
    fun update(id: String, transform: (ArchiveItem) -> ArchiveItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    /** Moves the item to the front, preserving it; no-op when absent. */
    @Synchronized
    fun moveToFront(id: String) {
        val current = _items.value
        val item = current.firstOrNull { it.id == id } ?: return
        _items.value = listOf(item) + current.filterNot { it.id == id }
        persist()
    }

    /** Removes finished/ignored rows, keeping anything still in play. */
    @Synchronized
    fun clearFinished() {
        _items.value = _items.value.filter {
            it.stage != ImportStage.COMPLETE && it.stage != ImportStage.SKIPPED
        }
        persist()
    }

    @Synchronized
    fun clearAll() {
        _items.value = emptyList()
        persist()
    }

    private fun readQueue(): List<ArchiveItem> {
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val rows = JsonCodec.parseArray(json) ?: return emptyList()
            rows.mapNotNull { decodeItem(it as? Map<String, Any?>) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JsonCodec.stringifyArray(_items.value.map { encodeItem(it) }))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Best effort: the queue is in-memory truth; a failed
            // write must not crash an import run.
        }
    }

    // ------------------------------------------------------------------
    // Codec
    // ------------------------------------------------------------------

    private fun encodeItem(item: ArchiveItem): Map<String, Any?> = linkedMapOf(
        "id" to item.id,
        "archiveUri" to item.archiveUri,
        "archiveName" to item.archiveName,
        "displayTitle" to item.displayTitle,
        "archiveKind" to item.archiveKind.name,
        "archiveBytes" to item.archiveBytes,
        "detection" to encodeDetection(item.detection),
        "platform" to item.platform?.name,
        "plan" to item.plan?.let { encodePlan(it) },
        "stage" to item.stage.name,
        "failure" to item.failure?.name,
        "failureDetail" to item.failureDetail,
        "inspectFailed" to item.inspectFailed,
        "relativePath" to item.relativePath,
        "duplicatePolicy" to item.duplicatePolicy?.name,
        "extractedBytes" to item.extractedBytes,
        "sourceMissing" to item.sourceMissing,
        "addedAt" to item.addedAt,
    )

    private fun encodeDetection(d: Detection): Map<String, Any?> = linkedMapOf(
        "platform" to d.platform?.name,
        "confidence" to d.confidence.name,
        "signals" to d.signals,
        "recognizedAsGame" to d.recognizedAsGame,
        "manual" to d.manual,
    )

    private fun encodePlan(p: ImportPlan): Map<String, Any?> = linkedMapOf(
        "target" to when (val t = p.target) {
            is ImportTarget.SingleFile -> linkedMapOf("type" to "single", "fileName" to t.fileName)
            is ImportTarget.GameFolder -> linkedMapOf("type" to "folder", "folderName" to t.folderName)
        },
        "payloadFileCount" to p.payloadFileCount,
        "payloadBytes" to p.payloadBytes,
        "unwrapDepth" to p.unwrapDepth,
    )

    private fun decodeItem(map: Map<String, Any?>?): ArchiveItem? {
        if (map == null) return null
        return try {
            val detection = decodeDetection(map["detection"] as? Map<String, Any?>)
                ?: return null
            val plan = (map["plan"] as? Map<String, Any?>)?.let { decodePlan(it) }
            ArchiveItem(
                id = map["id"] as? String ?: return null,
                archiveUri = map["archiveUri"] as? String ?: return null,
                archiveName = map["archiveName"] as? String ?: "",
                displayTitle = map["displayTitle"] as? String ?: "",
                archiveKind = enumValueOf<ArchiveKind>((map["archiveKind"] as? String) ?: "ZIP"),
                archiveBytes = (map["archiveBytes"] as? Number)?.toLong() ?: 0L,
                detection = detection,
                platform = (map["platform"] as? String)?.let { runCatching { enumValueOf<PlatformId>(it) }.getOrNull() },
                plan = plan,
                stage = runCatching { enumValueOf<ImportStage>((map["stage"] as? String) ?: "WAITING") }.getOrDefault(ImportStage.WAITING),
                failure = (map["failure"] as? String)?.let { runCatching { enumValueOf<ImportFailureReason>(it) }.getOrNull() },
                failureDetail = map["failureDetail"] as? String,
                inspectFailed = (map["inspectFailed"] as? Boolean) ?: false,
                relativePath = map["relativePath"] as? String ?: "",
                duplicatePolicy = (map["duplicatePolicy"] as? String)?.let { runCatching { enumValueOf<DuplicatePolicy>(it) }.getOrNull() },
                extractedBytes = (map["extractedBytes"] as? Number)?.toLong() ?: 0L,
                sourceMissing = (map["sourceMissing"] as? Boolean) ?: false,
                addedAt = (map["addedAt"] as? Number)?.toLong() ?: 0L,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeDetection(map: Map<String, Any?>?): Detection? {
        if (map == null) return null
        return Detection(
            platform = (map["platform"] as? String)?.let { runCatching { enumValueOf<PlatformId>(it) }.getOrNull() },
            confidence = runCatching { enumValueOf<Confidence>((map["confidence"] as? String) ?: "UNKNOWN") }.getOrDefault(Confidence.UNKNOWN),
            signals = (map["signals"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            recognizedAsGame = (map["recognizedAsGame"] as? Boolean) ?: true,
            manual = (map["manual"] as? Boolean) ?: false,
        )
    }

    private fun decodePlan(map: Map<String, Any?>): ImportPlan? {
        val targetMap = map["target"] as? Map<String, Any?> ?: return null
        val target = when (targetMap["type"] as? String) {
            "single" -> ImportTarget.SingleFile((targetMap["fileName"] as? String) ?: return null)
            "folder" -> ImportTarget.GameFolder((targetMap["folderName"] as? String) ?: return null)
            else -> return null
        }
        return ImportPlan(
            target = target,
            payloadFileCount = (map["payloadFileCount"] as? Number)?.toInt() ?: 0,
            payloadBytes = (map["payloadBytes"] as? Number)?.toLong() ?: 0L,
            unwrapDepth = (map["unwrapDepth"] as? Number)?.toInt() ?: 0,
        )
    }
}

/**
 * Lightweight import history, persisted as a capped JSON array.
 * Technical logs live in the notification/log stream; this is the
 * glanceable "what happened" record shown on the results screen.
 */
class ImportHistoryRepository(private val file: File, private val cap: Int = 200) {

    private val _entries = MutableStateFlow<List<ImportHistoryEntry>>(emptyList())
    val entries: StateFlow<List<ImportHistoryEntry>> = _entries.asStateFlow()

    @Synchronized
    fun load() {
        _entries.value = readHistory()
    }

    @Synchronized
    fun record(entry: ImportHistoryEntry) {
        _entries.value = (listOf(entry) + _entries.value).take(cap)
        persist()
    }

    private fun readHistory(): List<ImportHistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val rows = JsonCodec.parseArray(file.readText()) ?: return emptyList()
            rows.mapNotNull { row ->
                val map = row as? Map<String, Any?> ?: return@mapNotNull null
                try {
                    ImportHistoryEntry(
                        id = map["id"] as? String ?: return@mapNotNull null,
                        title = map["title"] as? String ?: "",
                        platform = (map["platform"] as? String)?.let { runCatching { enumValueOf<PlatformId>(it) }.getOrNull() },
                        archiveName = map["archiveName"] as? String ?: "",
                        outcome = runCatching { enumValueOf<ImportOutcome>((map["outcome"] as? String) ?: "SUCCESS") }.getOrDefault(ImportOutcome.SUCCESS),
                        reason = (map["reason"] as? String)?.let { runCatching { enumValueOf<ImportFailureReason>(it) }.getOrNull() },
                        finishedAt = (map["finishedAt"] as? Number)?.toLong() ?: 0L,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val rows = _entries.value.map { e ->
                linkedMapOf<String, Any?>(
                    "id" to e.id,
                    "title" to e.title,
                    "platform" to e.platform?.name,
                    "archiveName" to e.archiveName,
                    "outcome" to e.outcome.name,
                    "reason" to e.reason?.name,
                    "finishedAt" to e.finishedAt,
                )
            }
            file.writeText(JsonCodec.stringifyArray(rows))
        } catch (_: Exception) {
            // Best effort.
        }
    }
}
