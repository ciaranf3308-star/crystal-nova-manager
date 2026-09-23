package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Android/SAF surface the engine needs. Production implementation
 * lives in MainActivity-adjacent code; tests use a fake.
 */
interface ImporterEnvironment {
    fun downloadsListing(): DownloadsListing
    fun archiveOpener(): ArchiveStreamOpener
    fun romsFs(): ThemeFs?
    fun archiveExists(uri: String): Boolean
    fun deleteArchive(uri: String): Boolean
    fun romsFreeBytes(): Long
}

enum class GrantKind { DOWNLOADS, ROMS }

data class GrantProbe(
    val downloadsOk: Boolean,
    val romsOk: Boolean,
    val freeBytes: Long,
)

data class ItemConflict(
    val itemId: String,
    val title: String,
    val platform: PlatformId,
    val existingName: String,
    val resolution: DuplicatePolicy,
)

data class ReadyGroup(val platform: PlatformId, val items: List<ArchiveItem>)

enum class RowState { DONE, FAILED, ACTIVE, PENDING, SKIPPED }
data class QueueRow(val title: String, val state: RowState)

data class ImportingGame(
    val title: String,
    val platform: PlatformId,
    val stage: ImportStage,
    /** 0..1, or null when the stage has no byte progress. */
    val progress: Float?,
    val detail: String?,
)

data class FailedRow(
    val itemId: String,
    val title: String,
    val platform: PlatformId?,
    val reason: ImportFailureReason,
)

/** Confirmation of the most recent platform tap on the classify screen. */
data class LastPick(
    val itemId: String,
    val title: String,
    val platformLabel: String,
)

/** All UI state for the importer flow. */
sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Scanning(val done: Int, val total: Int) : ImportUiState
    data class Classifying(
        val needsReview: List<ArchiveItem>,
        val autoIdentified: List<ArchiveItem>,
        val notRecognized: List<ArchiveItem>,
        val unreadable: List<ArchiveItem>,
        val missing: List<ArchiveItem>,
        val unsupportedCount: Int,
        /** Most recent platform tap (null when none, or after undo). */
        val lastPick: LastPick?,
    ) : ImportUiState {
        val actionable: Int get() = needsReview.size + autoIdentified.size
    }

    data class ConflictReview(
        val conflicts: List<ItemConflict>,
        val default: DuplicatePolicy,
    ) : ImportUiState

    data class Ready(
        val groups: List<ReadyGroup>,
        val totalGames: Int,
        val estimatedBytes: Long,
        val freeBytes: Long,
        val storageOk: Boolean,
    ) : ImportUiState

    data class Importing(
        val current: ImportingGame?,
        val index: Int,
        val total: Int,
        val rows: List<QueueRow>,
        val log: List<String>,
        val cancelRequested: Boolean,
    ) : ImportUiState

    data class Results(
        val succeeded: Int,
        val failed: List<FailedRow>,
        val skipped: Int,
        val deletedSources: Int,
        val total: Int,
        val history: List<ImportHistoryEntry>,
        val cancelled: Boolean,
    ) : ImportUiState

    data class Error(val message: String, val grant: GrantKind?) : ImportUiState
}

/**
 * The import orchestrator. Owns the whole transaction; the
 * foreground service is a thin lifecycle/notification wrapper that
 * calls [startImport].
 *
 * - Everything runs on the injected [ioDispatcher]; the one-at-a-time
 *   loop is a plain sequential `for` — there is no code path that can
 *   extract or copy two archives concurrently.
 * - The queue is persisted on every transition, so a restart
 *   mid-import resumes with honest FAILED/WAITING states rather than
 *   a half-written ROM folder.
 * - The source archive is deleted LAST, only after the destination
 *   verifies. Any extraction/copy/permission/verification failure
 *   keeps the source archive and moves on to the next item.
 */
class ImportEngine(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val env: ImporterEnvironment,
    private val queue: ImportQueue,
    private val inspector: ArchiveInspector,
    private val detector: PlatformDetector,
    private val extractor: ArchiveExtractor,
    private val mapping: PlatformMapping,
    private val settings: ImporterSettings,
    private val history: ImportHistoryRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    /** Archive URIs -> queue items; the Activity reads counts from here. */
    val queueItems: StateFlow<List<ArchiveItem>> = queue.items

    private var scanJob: Job? = null
    private var importJob: Job? = null
    private var reviewJob: Job? = null

    /** Most recent classify-screen platform pick; cleared by undo/reclassify. */
    private var lastPickedId: String? = null
    private var lastPick: LastPick? = null
    private var cancelAfterCurrent = false
    private var lastUnsupportedCount = 0

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun stamp(): String = timeFmt.format(Date(clock()))

    // ------------------------------------------------------------------
    // Grants
    // ------------------------------------------------------------------

    fun probeGrants(): GrantProbe {
        val downloadsOk = try {
            env.downloadsListing().listFiles()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        var romsOk = false
        var free = 0L
        try {
            val roms = env.romsFs()
            romsOk = roms?.root() != null
            if (romsOk) free = env.romsFreeBytes()
        } catch (_: SecurityException) {
            romsOk = false
        } catch (_: Exception) {
            romsOk = false
        }
        return GrantProbe(downloadsOk, romsOk, free)
    }

    // ------------------------------------------------------------------
    // Scan + classify
    // ------------------------------------------------------------------

    /**
     * Lists Downloads, inspects every new archive, and classifies the
     * complete batch before anything runs. Already-queued archives
     * are not re-inspected; vanished ones are flagged SOURCE_MISSING.
     */
    fun scan() {
        if (scanJob?.isActive == true || importJob?.isActive == true) return
        scanJob = scope.launch(ioDispatcher) {
            _uiState.value = ImportUiState.Scanning(0, 0)
            val outcome = try {
                ArchiveScanner(env.downloadsListing()).scan()
            } catch (_: SecurityException) {
                _uiState.value = ImportUiState.Error(
                    "DOWNLOADS ACCESS LOST — GRANT IT AGAIN",
                    GrantKind.DOWNLOADS,
                )
                return@launch
            } catch (e: Exception) {
                _uiState.value = ImportUiState.Error(
                    "COULD NOT READ DOWNLOADS: ${e.message}",
                    GrantKind.DOWNLOADS,
                )
                return@launch
            }

            val known = queue.items.value.associateBy { it.archiveUri }.toMutableMap()
            val scannedUris = outcome.archives.map { it.uri }.toSet()
            for ((uri, item) in known) {
                if (uri !in scannedUris && !env.archiveExists(uri)) {
                    queue.update(item.id) { it.copy(sourceMissing = true) }
                }
            }

            var done = 0
            for (ref in outcome.archives) {
                done++
                _uiState.value = ImportUiState.Scanning(done, outcome.archives.size)
                if (known.containsKey(ref.uri)) continue
                val item = try {
                    buildItem(ref)
                } catch (e: ArchiveReadException) {
                    ArchiveItem(
                        id = UUID.randomUUID().toString(),
                        archiveUri = ref.uri,
                        archiveName = ref.name,
                        displayTitle = cleanDisplayTitle(ref.name),
                        archiveKind = ref.kind,
                        archiveBytes = ref.size,
                        detection = Detection(null, Confidence.UNKNOWN, recognizedAsGame = true),
                        platform = null,
                        plan = null,
                        inspectFailed = true,
                        relativePath = ref.relativePath,
                        failureDetail = e.message,
                        addedAt = clock(),
                    )
                } catch (e: SecurityException) {
                    _uiState.value = ImportUiState.Error(
                        "DOWNLOADS ACCESS LOST — GRANT IT AGAIN",
                        GrantKind.DOWNLOADS,
                    )
                    return@launch
                }
                queue.add(item)
                known[ref.uri] = item
            }
            lastUnsupportedCount = outcome.unsupported.size
            emitClassifying()
        }
    }

    private fun buildItem(ref: ArchiveRef): ArchiveItem {
        val inspected = inspector.inspect(ref)
        val entries = inspected.inspection.entries
        // The detector numbers only non-directory entries (it filters
        // first, then indexes), so the reader must use that same list.
        // The offset is honored against the inspector's captured
        // window — a read past the window simply yields no signal.
        val files = entries.filter { !it.isDirectory }
        val reader = PlatformDetector.HeaderReader { index, offset, length ->
            val window = files.getOrNull(index)?.path?.let { inspected.headers[it] }
            if (window == null || offset < 0 || offset >= window.size) {
                null
            } else {
                val from = offset.toInt()
                val to = minOf(window.size, from + length.coerceAtLeast(0))
                window.copyOfRange(from, to)
            }
        }
        val detection = detector.detect(ref.name, entries, reader)
        val plan = if (detection.recognizedAsGame) {
            inspector.planPayload(inspected.inspection, ref.name)
        } else {
            null
        }
        val platform =
            if (settings.autoIdentify && detection.confidence == Confidence.CONFIRMED) {
                detection.platform
            } else {
                null
            }
        return ArchiveItem(
            id = UUID.randomUUID().toString(),
            archiveUri = ref.uri,
            archiveName = ref.name,
            displayTitle = cleanDisplayTitle(ref.name),
            archiveKind = ref.kind,
            archiveBytes = ref.size,
            detection = detection,
            platform = platform,
            plan = plan,
            inspectFailed = detection.recognizedAsGame && plan == null,
            relativePath = ref.relativePath,
            failureDetail = if (detection.recognizedAsGame && plan == null) {
                "Archive holds no importable files"
            } else {
                null
            },
            addedAt = clock(),
        )
    }

    private fun emitClassifying() {
        val items = queue.items.value
        _uiState.value = ImportUiState.Classifying(
            needsReview = items.filter { it.needsReview },
            autoIdentified = items.filter { it.importable && !it.needsReview && it.stage == ImportStage.WAITING },
            notRecognized = items.filter { !it.inspectFailed && !it.sourceMissing && !it.detection.recognizedAsGame && it.stage != ImportStage.SKIPPED },
            unreadable = items.filter { it.inspectFailed && it.stage != ImportStage.SKIPPED },
            missing = items.filter { it.sourceMissing && it.stage != ImportStage.SKIPPED && it.stage != ImportStage.COMPLETE },
            unsupportedCount = lastUnsupportedCount,
            lastPick = lastPick,
        )
    }

    /** Tap-a-platform: saves immediately and advances (no save button). */
    fun setPlatform(itemId: String, platform: PlatformId) {
        val title = queue.items.value.firstOrNull { it.id == itemId }?.displayTitle ?: itemId
        queue.update(itemId) {
            it.copy(
                platform = platform,
                detection = it.detection.copy(
                    platform = platform,
                    confidence = Confidence.CONFIRMED,
                    manual = true,
                ),
            )
        }
        lastPickedId = itemId
        lastPick = LastPick(itemId, title, platform.labels().long.uppercase())
        if (_uiState.value is ImportUiState.Classifying) emitClassifying()
    }

    /**
     * Re-asks the last platform pick: clears it and moves the item to
     * the front of the queue so it classifies next. No-op when nothing
     * was picked (or the item already left the flow).
     */
    fun undoLastPick() {
        val id = lastPickedId ?: return
        val item = queue.items.value.firstOrNull { it.id == id }
        lastPickedId = null
        lastPick = null
        if (item != null && item.stage == ImportStage.WAITING) {
            queue.update(id) { it.copy(platform = null) }
            queue.moveToFront(id)
        }
        if (_uiState.value is ImportUiState.Classifying) emitClassifying()
    }

    /**
     * Sends one already-classified item back to the classify screen as
     * the first item, so a wrong console pick can be fixed. Clearing
     * [ArchiveItem.platform] is sufficient to return it to
     * [ArchiveItem.needsReview]; detection is left as-is (the classify
     * screen's detector-guess hint only renders for
     * [Confidence.LIKELY], so no stale hint appears).
     */
    fun reclassifyItem(itemId: String) {
        queue.update(itemId) { it.copy(platform = null) }
        queue.moveToFront(itemId)
        lastPickedId = null
        lastPick = null
    }

    fun skipItem(itemId: String) {
        queue.update(itemId) { it.copy(stage = ImportStage.SKIPPED, failure = null, failureDetail = null) }
        if (_uiState.value is ImportUiState.Classifying) emitClassifying()
    }

    /** "Not a game" — drops the item from the flow without deleting anything. */
    fun ignoreNotAGame(itemId: String) {
        queue.update(itemId) {
            it.copy(
                stage = ImportStage.SKIPPED,
                detection = it.detection.copy(recognizedAsGame = false),
            )
        }
        if (_uiState.value is ImportUiState.Classifying) emitClassifying()
    }

    fun removeItem(itemId: String) {
        queue.remove(itemId)
        if (_uiState.value is ImportUiState.Classifying) emitClassifying()
    }

    fun backToClassifying() = emitClassifying()
    fun backToIdle() {
        _uiState.value = ImportUiState.Idle
    }

    // ------------------------------------------------------------------
    // Review
    // ------------------------------------------------------------------

    /**
     * Computes duplicate conflicts and the storage preflight, then
     * either asks for conflict resolutions or lands on the Ready
     * screen. Never starts an import that clearly cannot complete.
     *
     * Runs on [ioDispatcher]: it walks the ROM tree over SAF, which
     * must never happen on the main thread. A no-op refresh when
     * there is nothing actionable.
     */
    fun prepareImport() {
        if (importJob?.isActive == true || reviewJob?.isActive == true) return
        reviewJob = scope.launch(ioDispatcher) {
            prepareImportBlocking()
        }
    }

    private fun prepareImportBlocking() {
        val roms = env.romsFs()
        if (roms == null) {
            _uiState.value = ImportUiState.Error(
                "ROM FOLDER NOT GRANTED — PICK IT IN SETTINGS",
                GrantKind.ROMS,
            )
            return
        }
        val root = try {
            roms.root()
        } catch (_: SecurityException) {
            _uiState.value = ImportUiState.Error(
                "ROM FOLDER ACCESS LOST — GRANT IT AGAIN",
                GrantKind.ROMS,
            )
            return
        }
        if (root == null) {
            _uiState.value = ImportUiState.Error(
                "ROM FOLDER NOT GRANTED — PICK IT IN SETTINGS",
                GrantKind.ROMS,
            )
            return
        }

        val candidates = queue.items.value
            .filter { it.importable && it.stage == ImportStage.WAITING }
        if (candidates.isEmpty()) {
            emitClassifying()
            return
        }

        val conflicts = mutableListOf<ItemConflict>()
        for (item in candidates) {
            val platform = item.platform ?: continue
            val targetName = targetNameOf(item) ?: continue
            val platformDir = roms.find(root, mapping.folderFor(platform))
                ?.takeIf { roms.isDirectory(it) }
            if (platformDir != null && roms.find(platformDir, targetName) != null) {
                conflicts += ItemConflict(
                    itemId = item.id,
                    title = item.displayTitle,
                    platform = platform,
                    existingName = targetName,
                    resolution = item.duplicatePolicy ?: settings.duplicateDefault,
                )
            }
        }
        val unresolved = conflicts.filter { item ->
            queue.items.value.firstOrNull { it.id == item.itemId }?.duplicatePolicy == null
        }
        if (unresolved.isNotEmpty()) {
            _uiState.value = ImportUiState.ConflictReview(conflicts, settings.duplicateDefault)
            return
        }

        val estimated = candidates.sumOf { it.plan?.payloadBytes ?: 0L }
        val free = try {
            env.romsFreeBytes()
        } catch (_: Exception) {
            0L
        }
        val storageOk = free >= (estimated * 11) / 10 // 10% headroom.

        val groups = candidates.groupBy { it.platform!! }
            .toList()
            .sortedBy { (platform, _) ->
                PlatformMapping.ORDERED.indexOf(platform).takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
            .map { (platform, items) -> ReadyGroup(platform, items.sortedBy { it.displayTitle }) }

        _uiState.value = ImportUiState.Ready(
            groups = groups,
            totalGames = candidates.size,
            estimatedBytes = estimated,
            freeBytes = free,
            storageOk = storageOk,
        )
    }

    /** Stamps the user's per-conflict choices, then re-runs the review. */
    fun confirmConflicts(conflicts: List<ItemConflict>) {
        for (c in conflicts) {
            queue.update(c.itemId) { it.copy(duplicatePolicy = c.resolution) }
        }
        prepareImport()
    }

    private fun targetNameOf(item: ArchiveItem): String? = when (val t = item.plan?.target) {
        is ImportTarget.SingleFile -> t.fileName
        is ImportTarget.GameFolder -> t.folderName
        null -> null
    }

    // ------------------------------------------------------------------
    // The import run: strictly sequential, one archive at a time.
    // ------------------------------------------------------------------

    fun startImport() {
        val ready = _uiState.value as? ImportUiState.Ready ?: return
        if (!ready.storageOk || importJob?.isActive == true) return
        cancelAfterCurrent = false
        importJob = scope.launch(ioDispatcher) { runImport() }
    }

    /** Safe cancellation: finishes the current game, then stops. */
    fun cancelAfterCurrent() {
        cancelAfterCurrent = true
        val state = _uiState.value as? ImportUiState.Importing
        if (state != null) {
            _uiState.value = state.copy(cancelRequested = true)
        }
    }

    fun retryFailed() {
        if (importJob?.isActive == true) return
        for (item in queue.items.value) {
            if (item.stage == ImportStage.FAILED) {
                queue.update(item.id) {
                    it.copy(stage = ImportStage.WAITING, failure = null, failureDetail = null)
                }
            }
        }
        prepareImport()
    }

    fun changePlatformAndRetry(itemId: String, platform: PlatformId) {
        setPlatform(itemId, platform)
        queue.update(itemId) {
            if (it.stage == ImportStage.FAILED) {
                it.copy(stage = ImportStage.WAITING, failure = null, failureDetail = null)
            } else {
                it
            }
        }
        prepareImport()
    }

    fun dismissResults() {
        queue.clearFinished()
        val remaining = queue.items.value.any {
            it.stage != ImportStage.COMPLETE && it.stage != ImportStage.SKIPPED
        }
        if (remaining) emitClassifying() else _uiState.value = ImportUiState.Idle
    }

    /**
     * Skips one failed row and refreshes the results screen. Failed
     * sources are always kept, so skipping is purely a queue decision.
     * When no failures remain this falls through to [dismissResults].
     */
    fun skipFailedItem(itemId: String) {
        skipItem(itemId)
        reviewFailures()
    }

    /**
     * Rebuilds the Results screen from FAILED items still in the queue
     * — after a restart, after skipping one failure, or from the hub's
     * RESUME. The run totals are carried over from the previous
     * Results snapshot when there is one; otherwise they describe the
     * failures on hand.
     */
    fun reviewFailures() {
        val failed = queue.items.value.filter { it.stage == ImportStage.FAILED }
        if (failed.isEmpty()) {
            dismissResults()
            return
        }
        val prev = _uiState.value as? ImportUiState.Results
        _uiState.value = ImportUiState.Results(
            succeeded = prev?.succeeded ?: 0,
            failed = failed.map {
                FailedRow(
                    it.id,
                    it.displayTitle,
                    it.platform,
                    it.failure ?: ImportFailureReason.INTERNAL_ERROR,
                )
            },
            skipped = queue.items.value.count { it.stage == ImportStage.SKIPPED },
            deletedSources = prev?.deletedSources ?: 0,
            total = prev?.total ?: failed.size,
            history = history.entries.value.take(30),
            cancelled = prev?.cancelled ?: false,
        )
    }

    private sealed interface ItemResult {
        data object Success : ItemResult
        data object SkippedDuplicate : ItemResult
        data class Failed(val reason: ImportFailureReason, val detail: String?) : ItemResult
    }

    private suspend fun runImport() {
        val roms = env.romsFs()
        if (roms == null) {
            _uiState.value = ImportUiState.Error("ROM FOLDER NOT GRANTED — PICK IT IN SETTINGS", GrantKind.ROMS)
            return
        }
        val root = try {
            roms.root()
        } catch (_: SecurityException) {
            _uiState.value = ImportUiState.Error("ROM FOLDER ACCESS LOST — GRANT IT AGAIN", GrantKind.ROMS)
            return
        }
        if (root == null) {
            _uiState.value = ImportUiState.Error("ROM FOLDER NOT GRANTED — PICK IT IN SETTINGS", GrantKind.ROMS)
            return
        }

        val items = queue.items.value.filter { it.importable && it.stage == ImportStage.WAITING }
        val runIds = items.map { it.id }.toSet()
        val total = items.size
        val log = ArrayDeque<String>()
        fun logLine(msg: String) {
            log.addLast("${stamp()} $msg")
            while (log.size > 60) log.removeFirst()
        }

        val platformDirs = mutableMapOf<PlatformId, FsNode>()
        fun platformDir(platform: PlatformId): FsNode =
            platformDirs.getOrPut(platform) {
                val folder = mapping.folderFor(platform)
                val existing = roms.find(root, folder)
                if (existing != null && roms.isDirectory(existing)) existing
                else roms.mkdir(root, folder)
            }

        var activeId: String? = null
        var deletedSources = 0
        var cancelled = false

        fun emit(current: ImportingGame?, index: Int) {
            val rows = queue.items.value
                .filter { it.id in runIds }
                .sortedBy { item -> items.indexOfFirst { it.id == item.id } }
                .map { item ->
                    val state = when {
                        item.stage == ImportStage.COMPLETE -> RowState.DONE
                        item.stage == ImportStage.FAILED -> RowState.FAILED
                        item.stage == ImportStage.SKIPPED -> RowState.SKIPPED
                        item.id == activeId -> RowState.ACTIVE
                        else -> RowState.PENDING
                    }
                    QueueRow(item.displayTitle, state)
                }
            _uiState.value = ImportUiState.Importing(
                current = current,
                index = index,
                total = total,
                rows = rows,
                log = log.toList(),
                cancelRequested = cancelAfterCurrent,
            )
        }

        // Crash recovery: drop stale staging dirs, stale single-file
        // temps, and reconcile interrupted REPLACE backups before
        // touching anything.
        for (platform in items.mapNotNull { it.platform }.toSet()) {
            val dir = try {
                platformDir(platform)
            } catch (_: Exception) {
                continue
            }
            try {
                for ((name, node) in roms.children(dir)) {
                    when {
                        name.startsWith(STAGING_PREFIX) && roms.isDirectory(node) ->
                            roms.deleteRecursively(node)
                        name.startsWith(TMP_PREFIX) && !roms.isDirectory(node) ->
                            roms.deleteRecursively(node)
                        name.startsWith(BACKUP_PREFIX) -> {
                            val original = name.removePrefix(BACKUP_PREFIX)
                            if (roms.find(dir, original) == null) {
                                roms.rename(node, original) // restore interrupted replace
                            } else {
                                roms.deleteRecursively(node) // stale backup
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Best effort; the per-item flow re-checks everything.
            }
        }

        for ((index, snapshot) in items.withIndex()) {
            if (cancelAfterCurrent) {
                cancelled = true
                logLine("Cancel requested — stopping after the current game.")
                break
            }
            val item = queue.items.value.firstOrNull { it.id == snapshot.id } ?: continue
            if (!item.importable || item.stage != ImportStage.WAITING) continue
            activeId = item.id
            logLine("[${index + 1}/$total] ${item.displayTitle}")

            val setStage: (ImportStage, String?) -> Unit = { stage, detail ->
                queue.update(item.id) { it.copy(stage = stage) }
                val platform = queue.items.value.firstOrNull { it.id == item.id }?.platform
                emit(
                    ImportingGame(
                        title = item.displayTitle,
                        platform = platform ?: item.platform!!,
                        stage = stage,
                        progress = null,
                        detail = detail,
                    ),
                    index,
                )
            }
            var lastEmittedBytes = 0L
            var lastPersistedBytes = -1L
            var lastProgressStage: ImportStage? = null
            val onBytes: (Long) -> Unit = { done ->
                val stage = queue.items.value
                    .firstOrNull { it.id == item.id }?.stage
                // Persist byte progress sparingly, and only during the
                // extract phase: every write rewrites the whole queue
                // JSON (a 4 GB game writes 130k+ 32 KiB chunks), and
                // during copying the counter restarts at 0 — persisting
                // that backwards would corrupt crash-recovery state.
                // Stage transitions persist anyway, so crash recovery
                // never depends on this.
                if (stage == ImportStage.EXTRACTING &&
                    (lastPersistedBytes < 0 || done - lastPersistedBytes >= 5_242_880)
                ) {
                    lastPersistedBytes = done
                    queue.update(item.id) { it.copy(extractedBytes = done) }
                }
                // Progress display tracks the extract and copy phases.
                // The copy counter restarts at 0, so the emission
                // baseline resets on every stage change.
                val progressStage = when (stage) {
                    ImportStage.EXTRACTING, ImportStage.COPYING -> stage
                    else -> null
                }
                if (progressStage != null) {
                    if (progressStage != lastProgressStage) {
                        lastProgressStage = progressStage
                        lastEmittedBytes = 0L
                    }
                    if (done - lastEmittedBytes >= 1_048_576) {
                        lastEmittedBytes = done
                        val planBytes = (item.plan?.payloadBytes ?: 0L).coerceAtLeast(1L)
                        val platform = queue.items.value.firstOrNull { it.id == item.id }?.platform
                            ?: item.platform!!
                        emit(
                            ImportingGame(
                                title = item.displayTitle,
                                platform = platform,
                                stage = progressStage,
                                progress = (done.toFloat() / planBytes).coerceIn(0f, 1f),
                                detail = "${formatBytes(done)} of ~${formatBytes(planBytes)}",
                            ),
                            index,
                        )
                    }
                }
            }

            val result = try {
                importOne(roms, ::platformDir, item, setStage, onBytes, ::logLine)
            } catch (e: SecurityException) {
                queue.update(item.id) {
                    it.copy(stage = ImportStage.FAILED, failure = ImportFailureReason.PERMISSION_DENIED)
                }
                history.record(
                    ImportHistoryEntry(
                        id = UUID.randomUUID().toString(),
                        title = item.displayTitle,
                        platform = item.platform,
                        archiveName = item.archiveName,
                        outcome = ImportOutcome.FAILED,
                        reason = ImportFailureReason.PERMISSION_DENIED,
                        finishedAt = clock(),
                    ),
                )
                _uiState.value = ImportUiState.Error(
                    "ROM FOLDER ACCESS LOST — GRANT IT AGAIN",
                    GrantKind.ROMS,
                )
                return
            } catch (e: Exception) {
                ItemResult.Failed(ImportFailureReason.INTERNAL_ERROR, e.message)
            }

            when (result) {
                is ItemResult.Success -> {
                    queue.update(item.id) {
                        it.copy(stage = ImportStage.COMPLETE, failure = null, failureDetail = null)
                    }
                    history.record(
                        ImportHistoryEntry(
                            id = UUID.randomUUID().toString(),
                            title = item.displayTitle,
                            platform = item.platform,
                            archiveName = item.archiveName,
                            outcome = ImportOutcome.SUCCESS,
                            reason = null,
                            finishedAt = clock(),
                        ),
                    )
                    // The source archive is deleted LAST — only after
                    // the destination verified. Any delete problem
                    // keeps the game (it imported fine) and just logs.
                    if (settings.deleteAfterSuccess) {
                        try {
                            if (env.deleteArchive(item.archiveUri)) {
                                deletedSources++
                                logLine("Deleted source ${item.archiveName}")
                            } else {
                                logLine("Could not delete source ${item.archiveName} — delete it by hand")
                            }
                        } catch (_: SecurityException) {
                            logLine("Source delete denied for ${item.archiveName} — keeping it")
                        } catch (_: Exception) {
                            logLine("Source delete failed for ${item.archiveName} — keeping it")
                        }
                    }
                    logLine("Imported ${item.displayTitle}")
                }
                is ItemResult.SkippedDuplicate -> {
                    queue.update(item.id) {
                        it.copy(
                            stage = ImportStage.SKIPPED,
                            failure = ImportFailureReason.DUPLICATE_SKIPPED,
                        )
                    }
                    history.record(
                        ImportHistoryEntry(
                            id = UUID.randomUUID().toString(),
                            title = item.displayTitle,
                            platform = item.platform,
                            archiveName = item.archiveName,
                            outcome = ImportOutcome.SKIPPED,
                            reason = ImportFailureReason.DUPLICATE_SKIPPED,
                            finishedAt = clock(),
                        ),
                    )
                    logLine("Skipped duplicate ${item.displayTitle}")
                }
                is ItemResult.Failed -> {
                    // The source archive is KEPT on any failure.
                    queue.update(item.id) {
                        it.copy(
                            stage = ImportStage.FAILED,
                            failure = result.reason,
                            failureDetail = result.detail,
                        )
                    }
                    history.record(
                        ImportHistoryEntry(
                            id = UUID.randomUUID().toString(),
                            title = item.displayTitle,
                            platform = item.platform,
                            archiveName = item.archiveName,
                            outcome = ImportOutcome.FAILED,
                            reason = result.reason,
                            finishedAt = clock(),
                        ),
                    )
                    logLine("FAILED ${item.displayTitle}: ${result.reason.label()}")
                    if (result.reason == ImportFailureReason.NOT_ENOUGH_STORAGE) {
                        logLine("Storage exhausted — stopping the run.")
                        break
                    }
                }
            }
            activeId = null
            emit(null, index + 1)
        }

        val final = queue.items.value.filter { it.id in runIds }
        _uiState.value = ImportUiState.Results(
            succeeded = final.count { it.stage == ImportStage.COMPLETE },
            failed = final.filter { it.stage == ImportStage.FAILED }.map {
                FailedRow(it.id, it.displayTitle, it.platform, it.failure ?: ImportFailureReason.INTERNAL_ERROR)
            },
            skipped = final.count { it.stage == ImportStage.SKIPPED },
            deletedSources = deletedSources,
            total = total,
            history = history.entries.value.take(30),
            cancelled = cancelled,
        )
    }

    /**
     * One archive, end to end:
     * inspect source -> extract (SingleFile: straight into the ROM
     * folder under a dot-prefixed temp name — one write, no staging
     * copy; GameFolder: into a staging dir) -> duplicate-aware
     * placement (temp/staging renamed to the final name) -> verify
     * destination -> clean up ->
     * (the caller deletes the source archive last).
     */
    private fun importOne(
        roms: ThemeFs,
        platformDir: (PlatformId) -> FsNode,
        item: ArchiveItem,
        setStage: (ImportStage, String?) -> Unit,
        onBytes: (Long) -> Unit,
        log: (String) -> Unit,
    ): ItemResult {
        val platform = item.platform ?: return ItemResult.Failed(ImportFailureReason.INTERNAL_ERROR, "no platform")
        val plan = item.plan ?: return ItemResult.Failed(ImportFailureReason.INTERNAL_ERROR, "no plan")
        val ref = ArchiveRef(item.archiveName, item.archiveUri, item.archiveBytes, item.archiveKind)

        // 1. Inspect source.
        setStage(ImportStage.INSPECTING, "Checking the archive is still there")
        if (!env.archiveExists(item.archiveUri)) {
            return ItemResult.Failed(ImportFailureReason.SOURCE_MISSING, "Archive no longer in Downloads")
        }

        val dir = try {
            platformDir(platform)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return ItemResult.Failed(ImportFailureReason.WRITE_FAILED, e.message)
        }

        // Single-file games extract straight into the destination
        // folder under a dot-prefixed temp name: one write instead of
        // extract-to-staging + copy. Multi-file games still stage.
        val singleFile = plan.target is ImportTarget.SingleFile
        val tmpName = TMP_PREFIX + item.id.take(8)
        val staging: FsNode? = if (singleFile) {
            null
        } else try {
            val stagingName = STAGING_PREFIX + item.id.take(8)
            roms.find(dir, stagingName)?.let { roms.deleteRecursively(it) }
            roms.mkdir(dir, stagingName)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return ItemResult.Failed(ImportFailureReason.EXTRACTION_FAILED, e.message)
        }
        if (singleFile) {
            // Drop any stale temp from an interrupted run; the name is
            // ours by construction, so nothing of the user's is at risk.
            try {
                roms.find(dir, tmpName)?.let { roms.deleteRecursively(it) }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                return ItemResult.Failed(ImportFailureReason.EXTRACTION_FAILED, e.message)
            }
        }

        // Discard partial work after a failure: the staging dir for
        // folder games, the temp file for single-file games.
        fun discardWork() {
            if (singleFile) {
                runCatching { roms.find(dir, tmpName) }.getOrNull()
                    ?.let { cleanupQuietly(roms, it) }
            } else {
                cleanupQuietly(roms, staging)
            }
        }

        // 2. Extract.
        setStage(ImportStage.EXTRACTING, "Extracting")
        val report = try {
            extractor.extract(
                roms,
                ref,
                staging ?: dir,
                plan,
                onProgress = { done, _ -> onBytes(done) },
                directFileName = if (singleFile) tmpName else null,
            )
        } catch (e: ArchiveReadException) {
            discardWork()
            return ItemResult.Failed(ImportFailureReason.EXTRACTION_FAILED, e.message)
        } catch (e: SecurityException) {
            discardWork()
            throw e
        } catch (e: Exception) {
            discardWork()
            return ItemResult.Failed(
                if (isNoSpace(e)) ImportFailureReason.NOT_ENOUGH_STORAGE else ImportFailureReason.EXTRACTION_FAILED,
                e.message,
            )
        }

        // 3. Duplicate-aware placement.
        val policy = item.duplicatePolicy ?: settings.duplicateDefault
        val baseName = targetNameOf(item)
            ?: return ItemResult.Failed(ImportFailureReason.INTERNAL_ERROR, "no target name").also {
                discardWork()
            }
        val existing = try {
            roms.find(dir, baseName)
        } catch (e: SecurityException) {
            discardWork()
            throw e
        }
        if (existing != null && policy == DuplicatePolicy.SKIP) {
            discardWork()
            return ItemResult.SkippedDuplicate
        }
        val finalName = if (existing != null && policy == DuplicatePolicy.KEEP_BOTH) {
            uniqueName(roms, dir, baseName, plan.target is ImportTarget.SingleFile)
        } else {
            baseName
        }

        // REPLACE: park the existing game aside first, so a failed
        // write can be rolled back instead of losing it.
        var backup: FsNode? = null
        var stagingConsumed = false
        var writtenTarget: FsNode? = null
        try {
            if (existing != null && policy == DuplicatePolicy.REPLACE) {
                val backupName = BACKUP_PREFIX + baseName
                roms.find(dir, backupName)?.let { roms.deleteRecursively(it) }
                if (!roms.rename(existing, backupName)) {
                    return ItemResult.Failed(ImportFailureReason.WRITE_FAILED, "Could not park the existing game").also {
                        discardWork()
                    }
                }
                backup = roms.find(dir, backupName)
            }

            val writtenBytes: Long
            when (plan.target) {
                is ImportTarget.SingleFile -> {
                    // The game already sits in the ROM folder under the
                    // temp name: a same-folder rename puts it in place —
                    // no second copy of the bytes.
                    val tmpNode = roms.find(dir, tmpName)
                        ?: return ItemResult.Failed(ImportFailureReason.EXTRACTION_FAILED, "Extracted file missing").also {
                            discardWork()
                            backup?.let { runCatching { roms.rename(it, baseName) } }
                        }
                    if (!roms.rename(tmpNode, finalName)) {
                        cleanupQuietly(roms, tmpNode)
                        backup?.let { runCatching { roms.rename(it, baseName) } }
                        return ItemResult.Failed(ImportFailureReason.WRITE_FAILED, "Could not move the game into place")
                    }
                    writtenBytes = report.bytesWritten
                    writtenTarget = roms.find(dir, finalName)
                }
                is ImportTarget.GameFolder -> {
                    val stageDir = staging
                        ?: return ItemResult.Failed(ImportFailureReason.INTERNAL_ERROR, "no staging dir").also {
                            discardWork()
                        }
                    // Only folder games have a copy phase: the rename
                    // is the write. Single-file games stay on EXTRACTING
                    // through their direct write, then VERIFYING.
                    setStage(ImportStage.COPYING, "Writing to ${mapping.folderFor(platform)}")
                    if (!roms.rename(stageDir, finalName)) {
                        return ItemResult.Failed(ImportFailureReason.WRITE_FAILED, "Could not move the game folder").also {
                            discardWork()
                        }
                    }
                    stagingConsumed = true
                    writtenBytes = report.bytesWritten
                    writtenTarget = roms.find(dir, finalName)
                }
            }

            // 4. Verify the destination.
            setStage(ImportStage.VERIFYING, "Verifying")
            val verified = verifyDestination(roms, writtenTarget, plan, report, writtenBytes)
            if (!verified) {
                // Roll back: drop the bad write, restore the backup.
                writtenTarget?.let { cleanupQuietly(roms, it) }
                backup?.let { roms.rename(it, baseName) }
                return ItemResult.Failed(ImportFailureReason.VERIFICATION_FAILED, "Destination did not match the staged payload")
            }

            // 5. Clean up.
            setStage(ImportStage.CLEANING, "Tidying up")
            if (!stagingConsumed) discardWork()
            backup?.let { cleanupQuietly(roms, it) }
            return ItemResult.Success
        } catch (e: SecurityException) {
            if (!stagingConsumed) discardWork()
            backup?.let { runCatching { roms.rename(it, baseName) } }
            throw e
        } catch (e: Exception) {
            if (!stagingConsumed) discardWork()
            writtenTarget?.let { cleanupQuietly(roms, it) }
            backup?.let { runCatching { roms.rename(it, baseName) } }
            return ItemResult.Failed(
                if (isNoSpace(e)) ImportFailureReason.NOT_ENOUGH_STORAGE else ImportFailureReason.WRITE_FAILED,
                e.message,
            )
        }
    }

    private fun cleanupQuietly(roms: ThemeFs, node: FsNode?) {
        if (node == null) return
        try {
            roms.deleteRecursively(node)
        } catch (_: Exception) {
        }
    }

    private fun verifyDestination(
        roms: ThemeFs,
        target: FsNode?,
        plan: ImportPlan,
        report: ArchiveExtractor.ExtractReport,
        singleFileBytes: Long,
    ): Boolean {
        if (target == null) return false
        return when (plan.target) {
            is ImportTarget.SingleFile -> {
                !roms.isDirectory(target) &&
                    roms.length(target) == singleFileBytes &&
                    singleFileBytes > 0
            }
            is ImportTarget.GameFolder -> {
                if (!roms.isDirectory(target)) return false
                val (files, bytes) = walkStats(roms, target)
                files == report.fileCount && bytes == report.bytesWritten && bytes > 0
            }
        }
    }

    private fun walkStats(roms: ThemeFs, node: FsNode): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<FsNode>()
        stack.add(node)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            if (roms.isDirectory(n)) {
                for ((_, child) in roms.children(n)) stack.add(child)
            } else {
                files++
                bytes += roms.length(n)
            }
        }
        return files to bytes
    }

    private fun uniqueName(roms: ThemeFs, dir: FsNode, base: String, isFile: Boolean): String {
        if (roms.find(dir, base) == null) return base
        val stem: String
        val ext: String
        if (isFile && '.' in base) {
            stem = base.substringBeforeLast('.')
            ext = "." + base.substringAfterLast('.')
        } else {
            stem = base
            ext = ""
        }
        for (i in 2..99) {
            val candidate = "$stem ($i)$ext"
            if (roms.find(dir, candidate) == null) return candidate
        }
        // Practically unreachable; fail loudly rather than overwrite.
        throw ArchiveReadException("Could not find a free name for duplicate $base")
    }

    private fun isNoSpace(e: Exception): Boolean {
        val msg = (e.message ?: "").lowercase(Locale.US)
        return "enospc" in msg || "no space" in msg || "no enough space" in msg
    }

    companion object {
        internal const val STAGING_PREFIX = ".import-"
        private const val BACKUP_PREFIX = ".replace-backup-"
        /** Temp name for direct single-file writes; dot-prefixed so the game-list export and ES-DE ignore it. */
        private const val TMP_PREFIX = ".importing-"
    }
}
