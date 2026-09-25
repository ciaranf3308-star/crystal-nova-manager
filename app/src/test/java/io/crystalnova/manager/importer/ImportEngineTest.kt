package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.InMemoryThemeFs
import io.crystalnova.manager.storage.ThemeFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * End-to-end engine tests on the JVM: real ZIP bytes through the
 * real inspector/detector/extractor, an in-memory ROM tree, and a
 * fake SAF environment. The engine runs on [Dispatchers.Unconfined],
 * so scan/import execute synchronously on the test thread —
 * deterministic without runTest.
 */
class ImportEngineTest {

    private class FakeEnv(
        var downloads: List<DownloadFile> = emptyList(),
        val zips: MutableMap<String, ByteArray> = mutableMapOf(),
        val loose: MutableMap<String, ByteArray> = mutableMapOf(),
        val roms: InMemoryThemeFs = InMemoryThemeFs(),
        var freeBytes: Long = 100L * 1024 * 1024 * 1024,
        var deleteHook: ((String) -> Unit)? = null,
    ) : ImporterEnvironment {
        val deleted = mutableListOf<String>()

        override fun downloadsListing() = object : DownloadsListing {
            override fun listFiles(): List<DownloadFile> = downloads
        }

        override fun archiveOpener() = ArchiveTestFixtures.FakeOpener(zips.toMap())

        override fun openDownload(uri: String): InputStream =
            ByteArrayInputStream(loose[uri] ?: throw ArchiveReadException("no loose bytes for $uri"))

        override fun romsFs(): ThemeFs = roms

        override fun archiveExists(uri: String): Boolean =
            downloads.any { it.uri == uri } || zips.containsKey(uri)

        override fun deleteArchive(uri: String): Boolean {
            deleteHook?.invoke(uri)
            deleted += uri
            downloads = downloads.filterNot { it.uri == uri }
            zips.remove(uri)
            loose.remove(uri)
            return true
        }

        override fun romsFreeBytes(): Long = freeBytes
    }

    private data class Harness(
        val env: FakeEnv,
        val engine: ImportEngine,
        val queue: ImportQueue,
        val history: ImportHistoryRepository,
        val queueFile: File,
        val historyFile: File,
    )

    private fun harness(
        env: FakeEnv,
        psxSupport: PsxImportSupport? = null,
        extractor: ArchiveExtractor? = null,
        settingsBlock: (ImporterSettings) -> Unit = {},
    ): Harness {
        val prefs = MapKeyValueStore()
        val settings = ImporterSettings(prefs).also(settingsBlock)
        val queueFile = File.createTempFile("queue", ".json").also { it.delete() }
        val historyFile = File.createTempFile("history", ".json").also { it.delete() }
        val queue = ImportQueue(queueFile).also { it.load() }
        val history = ImportHistoryRepository(historyFile).also { it.load() }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val engine = ImportEngine(
            scope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            env = env,
            queue = queue,
            inspector = ArchiveInspector(env.archiveOpener()),
            detector = PlatformDetector(),
            extractor = extractor ?: ArchiveExtractor(env.archiveOpener()),
            mapping = PlatformMapping(prefs),
            settings = settings,
            history = history,
            clock = { 0L },
            psxSupport = psxSupport,
        )
        return Harness(env, engine, queue, history, queueFile, historyFile)
    }

    private fun envWith(vararg zips: Triple<String, String, Map<String, ByteArray>>): FakeEnv {
        val downloads = mutableListOf<DownloadFile>()
        val bytesByUri = mutableMapOf<String, ByteArray>()
        for ((name, uri, entries) in zips) {
            val bytes = ArchiveTestFixtures.createZip(entries)
            downloads += DownloadFile(name, bytes.size.toLong(), uri, false)
            bytesByUri[uri] = bytes
        }
        return FakeEnv(downloads = downloads, zips = bytesByUri)
    }

    private fun envWithLoose(vararg files: Pair<String, ByteArray>): FakeEnv {
        val downloads = mutableListOf<DownloadFile>()
        val bytesByUri = mutableMapOf<String, ByteArray>()
        for ((name, bytes) in files) {
            val uri = "uri:$name"
            downloads += DownloadFile(name, bytes.size.toLong(), uri, false)
            bytesByUri[uri] = bytes
        }
        return FakeEnv(downloads = downloads, loose = bytesByUri)
    }

    private fun romFile(env: FakeEnv, folder: String, name: String) =
        env.roms.find(env.roms.find(env.roms.rootNode, folder)!!, name)

    // ------------------------------------------------------------------

    @Test fun `happy path imports one gba game end to end`() {
        val romBytes = ByteArray(1024) { 42 }
        val env = envWith(
            Triple("Pokemon Emerald (USA).zip", "uri:pk", mapOf("Pokemon Emerald.gba" to romBytes)),
        )
        val h = harness(env)

        h.engine.scan()
        val classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(1, classifying.autoIdentified.size)
        assertTrue(classifying.needsReview.isEmpty())

        h.engine.prepareImport()
        val ready = h.engine.uiState.value as ImportUiState.Ready
        assertEquals(1, ready.totalGames)
        assertTrue(ready.storageOk)

        h.engine.startImport()
        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertTrue(results.failed.isEmpty())
        assertEquals(1, results.deletedSources)
        assertFalse(results.cancelled)

        // Destination has the game with intact bytes.
        val dest = romFile(env, "gba", "Pokemon Emerald.gba")!!
        assertEquals(1024L, env.roms.length(dest))
        assertTrue(env.roms.openInput(dest).readBytes().all { it == 42.toByte() })

        // No staging leftovers.
        val gbaDir = env.roms.find(env.roms.rootNode, "gba")!!
        assertTrue(env.roms.children(gbaDir).none { (n, _) -> n.startsWith(".import-") })

        // Source deleted, queue + history persisted.
        assertEquals(listOf("uri:pk"), env.deleted)
        assertEquals(ImportStage.COMPLETE, h.engine.queueItems.value.single().stage)
        assertEquals(1, h.history.entries.value.size)
        assertEquals(ImportOutcome.SUCCESS, h.history.entries.value.single().outcome)
    }

    @Test fun `manual classification taps a platform and advances`() {
        val env = envWith(
            Triple("mystery.zip", "uri:mz", mapOf("mystery.gba" to ByteArray(100))),
        )
        val h = harness(env) { it.autoIdentify = false }

        h.engine.scan()
        var classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(1, classifying.needsReview.size)
        assertTrue(classifying.autoIdentified.isEmpty())

        val itemId = classifying.needsReview.single().id
        h.engine.setPlatform(itemId, PlatformId.GBA)

        classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertTrue(classifying.needsReview.isEmpty())
        val filed = classifying.autoIdentified.single()
        assertEquals(PlatformId.GBA, filed.platform)
        assertTrue(filed.detection.manual)
        assertEquals(Confidence.CONFIRMED, filed.detection.confidence)

        h.engine.prepareImport()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        h.engine.startImport()
        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
    }

    @Test fun `setPlatform records lastPick with title and platform label`() {
        val env = envWith(
            Triple("mystery.zip", "uri:mz", mapOf("mystery.gba" to ByteArray(100))),
        )
        val h = harness(env) { it.autoIdentify = false }

        h.engine.scan()
        var classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertTrue(classifying.lastPick == null)

        val itemId = classifying.needsReview.single().id
        h.engine.setPlatform(itemId, PlatformId.GBA)

        classifying = h.engine.uiState.value as ImportUiState.Classifying
        val pick = classifying.lastPick
        assertTrue(pick != null)
        assertEquals(itemId, pick!!.itemId)
        assertEquals("mystery", pick.title)
        assertEquals("GAME BOY ADVANCE", pick.platformLabel)
    }

    @Test fun `undoLastPick returns the item to needsReview first and second call is a no-op`() {
        val env = envWith(
            Triple("game-a.zip", "uri:a", mapOf("a.gba" to ByteArray(10))),
            Triple("game-b.zip", "uri:b", mapOf("b.gba" to ByteArray(10))),
        )
        val h = harness(env) { it.autoIdentify = false }

        h.engine.scan()
        var classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(2, classifying.needsReview.size)
        val firstId = classifying.needsReview[0].id
        val secondId = classifying.needsReview[1].id

        h.engine.setPlatform(firstId, PlatformId.GBA)
        classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(listOf(secondId), classifying.needsReview.map { it.id })

        h.engine.undoLastPick()
        classifying = h.engine.uiState.value as ImportUiState.Classifying
        // Back in the queue, first, with no platform and no pick recorded.
        assertEquals(listOf(firstId, secondId), classifying.needsReview.map { it.id })
        assertTrue(classifying.lastPick == null)
        val undone = classifying.needsReview.first()
        assertTrue(undone.platform == null)
        // The rest of the queue is untouched.
        assertEquals(secondId, h.engine.queueItems.value[1].id)

        // Second call: nothing to undo, state unchanged.
        h.engine.undoLastPick()
        classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(listOf(firstId, secondId), classifying.needsReview.map { it.id })
        assertTrue(classifying.lastPick == null)
    }

    @Test fun `reclassifyItem clears platform and moves the item to the front`() {
        val env = envWith(
            Triple("game-a.zip", "uri:a", mapOf("a.gba" to ByteArray(10))),
            Triple("game-b.zip", "uri:b", mapOf("b.gba" to ByteArray(10))),
        )
        val h = harness(env) { it.autoIdentify = false }

        h.engine.scan()
        var classifying = h.engine.uiState.value as ImportUiState.Classifying
        val aId = classifying.needsReview[0].id
        val bId = classifying.needsReview[1].id

        // Classify both, then send A back.
        h.engine.setPlatform(aId, PlatformId.GBA)
        h.engine.setPlatform(bId, PlatformId.GBC)
        h.engine.prepareImport()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)

        h.engine.reclassifyItem(aId)
        // Other items' platforms are intact.
        assertEquals(PlatformId.GBC, h.engine.queueItems.value.first { it.id == bId }.platform)

        h.engine.backToClassifying()
        classifying = h.engine.uiState.value as ImportUiState.Classifying
        // A is back in needsReview (first); B stays classified.
        assertEquals(listOf(aId), classifying.needsReview.map { it.id })
        assertTrue(classifying.needsReview.first().platform == null)
        assertEquals(listOf(bId), classifying.autoIdentified.map { it.id })
    }

    @Test fun `full loop - classify, review, reclassify, repick, review under new console`() {
        val env = envWith(
            Triple("game-a.zip", "uri:a", mapOf("a.gba" to ByteArray(10))),
            Triple("game-b.zip", "uri:b", mapOf("b.gba" to ByteArray(10))),
        )
        val h = harness(env) { it.autoIdentify = false }

        h.engine.scan()
        var classifying = h.engine.uiState.value as ImportUiState.Classifying
        val aId = classifying.needsReview[0].id
        val bId = classifying.needsReview[1].id

        // Wrong pick for A on purpose.
        h.engine.setPlatform(aId, PlatformId.GBA)
        h.engine.setPlatform(bId, PlatformId.GBC)
        h.engine.prepareImport()
        var ready = h.engine.uiState.value as ImportUiState.Ready
        assertEquals(PlatformId.GBA, ready.groups.first { g -> g.items.any { it.id == aId } }.platform)

        // Fix it: back to classify, A is first again.
        h.engine.reclassifyItem(aId)
        h.engine.backToClassifying()
        classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(aId, classifying.needsReview.first().id)

        // Re-pick correctly, review shows A under the new console.
        h.engine.setPlatform(aId, PlatformId.GBC)
        classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals("GAME BOY COLOR", classifying.lastPick!!.platformLabel)
        h.engine.prepareImport()
        ready = h.engine.uiState.value as ImportUiState.Ready
        assertEquals(PlatformId.GBC, ready.groups.first { g -> g.items.any { it.id == aId } }.platform)
        assertEquals(PlatformId.GBC, ready.groups.first { g -> g.items.any { it.id == bId } }.platform)
        assertEquals(2, ready.totalGames)
    }

    @Test fun `duplicate defaults to SKIP and keeps the source archive`() {
        val env = envWith(
            Triple("Pokemon Emerald (USA).zip", "uri:pk", mapOf("Pokemon Emerald.gba" to ByteArray(10) { 1 })),
        )
        // The user already owns this game.
        val gbaDir = env.roms.mkdir(env.roms.rootNode, "gba")
        val owned = env.roms.createFile(gbaDir, "Pokemon Emerald.gba")
        env.roms.openOutput(owned).use { it.write(ByteArray(10) { 9 }) }
        val h = harness(env)

        h.engine.scan()
        h.engine.prepareImport()
        val conflict = h.engine.uiState.value as ImportUiState.ConflictReview
        assertEquals(1, conflict.conflicts.size)
        assertEquals(DuplicatePolicy.SKIP, conflict.conflicts.single().resolution)

        h.engine.confirmConflicts(conflict.conflicts)
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(0, results.succeeded)
        assertEquals(1, results.skipped)
        // The owned file is untouched and the source archive is kept.
        assertTrue(env.roms.openInput(romFile(env, "gba", "Pokemon Emerald.gba")!!).readBytes().all { it == 9.toByte() })
        assertTrue(env.deleted.isEmpty())
        val item = h.engine.queueItems.value.single()
        assertEquals(ImportStage.SKIPPED, item.stage)
        assertEquals(ImportFailureReason.DUPLICATE_SKIPPED, item.failure)
    }

    @Test fun `duplicate REPLACE swaps the game and deletes the source`() {
        val env = envWith(
            Triple("Pokemon Emerald (USA).zip", "uri:pk", mapOf("Pokemon Emerald.gba" to ByteArray(10) { 1 })),
        )
        val gbaDir = env.roms.mkdir(env.roms.rootNode, "gba")
        val owned = env.roms.createFile(gbaDir, "Pokemon Emerald.gba")
        env.roms.openOutput(owned).use { it.write(ByteArray(10) { 9 }) }
        val h = harness(env)

        h.engine.scan()
        h.engine.prepareImport()
        val conflict = h.engine.uiState.value as ImportUiState.ConflictReview
        h.engine.confirmConflicts(
            conflict.conflicts.map { it.copy(resolution = DuplicatePolicy.REPLACE) },
        )
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertTrue(env.roms.openInput(romFile(env, "gba", "Pokemon Emerald.gba")!!).readBytes().all { it == 1.toByte() })
        assertEquals(listOf("uri:pk"), env.deleted)
        // No backup leftovers.
        assertTrue(env.roms.children(gbaDir).none { (n, _) -> n.startsWith(".replace-backup-") })
    }

    @Test fun `duplicate KEEP BOTH writes a numbered copy`() {
        val env = envWith(
            Triple("Pokemon Emerald (USA).zip", "uri:pk", mapOf("Pokemon Emerald.gba" to ByteArray(10) { 1 })),
        )
        val gbaDir = env.roms.mkdir(env.roms.rootNode, "gba")
        env.roms.createFile(gbaDir, "Pokemon Emerald.gba")
        val h = harness(env)

        h.engine.scan()
        h.engine.prepareImport()
        val conflict = h.engine.uiState.value as ImportUiState.ConflictReview
        h.engine.confirmConflicts(
            conflict.conflicts.map { it.copy(resolution = DuplicatePolicy.KEEP_BOTH) },
        )
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertTrue(romFile(env, "gba", "Pokemon Emerald.gba") != null)
        assertTrue(romFile(env, "gba", "Pokemon Emerald (2).gba") != null)
    }

    @Test fun `a failed item keeps its source and the queue continues`() {
        val env = envWith(
            Triple("broken.zip", "uri:broken", mapOf("real.gba" to ByteArray(50))),
            Triple("good.zip", "uri:good", mapOf("good.gba" to ByteArray(60))),
        )
        val h = harness(env)
        h.engine.scan()
        // Sabotage the first item's plan so its staged file is missing.
        val brokenId = h.engine.queueItems.value.first { it.archiveUri == "uri:broken" }.id
        h.queue.update(brokenId) {
            it.copy(plan = it.plan!!.copy(target = ImportTarget.SingleFile("nope.gba")))
        }

        h.engine.prepareImport()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertEquals(1, results.failed.size)
        assertEquals(ImportFailureReason.EXTRACTION_FAILED, results.failed.single().reason)
        // Failed source kept, good source deleted.
        assertEquals(listOf("uri:good"), env.deleted)
        assertTrue(env.zips.containsKey("uri:broken"))
        // The good game still landed.
        assertTrue(romFile(env, "gba", "good.gba") != null)
        // Retry is possible.
        h.engine.retryFailed()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
    }

    @Test fun `cancel after current stops the run with the second item waiting`() {
        val env = envWith(
            Triple("one.zip", "uri:one", mapOf("one.gba" to ByteArray(10))),
            Triple("two.zip", "uri:two", mapOf("two.gba" to ByteArray(10))),
        )
        val h = harness(env)
        h.env.deleteHook = { h.engine.cancelAfterCurrent() }

        h.engine.scan()
        h.engine.prepareImport()
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertTrue(results.cancelled)
        assertEquals(1, results.succeeded)
        val second = h.engine.queueItems.value.first { it.archiveUri == "uri:two" }
        assertEquals(ImportStage.WAITING, second.stage)
    }

    @Test fun `storage preflight refuses to start an impossible run`() {
        val env = envWith(
            Triple("big.zip", "uri:big", mapOf("big.gba" to ByteArray(10_000))),
        ).also { it.freeBytes = 0L }
        val h = harness(env)

        h.engine.scan()
        h.engine.prepareImport()
        val ready = h.engine.uiState.value as ImportUiState.Ready
        assertFalse(ready.storageOk)

        h.engine.startImport()
        // Still on Ready: nothing ran.
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        assertTrue(h.engine.queueItems.value.all { it.stage == ImportStage.WAITING })
    }

    @Test fun `queue and classifications survive a restart`() {
        val env = envWith(
            Triple("mystery.zip", "uri:mz", mapOf("mystery.gba" to ByteArray(100))),
        )
        val first = harness(env) { it.autoIdentify = false }
        first.engine.scan()
        val itemId = (first.engine.uiState.value as ImportUiState.Classifying)
            .needsReview.single().id
        first.engine.setPlatform(itemId, PlatformId.GBA)

        // "Restart": a fresh engine over the same files.
        val prefs = MapKeyValueStore()
        val queue = ImportQueue(first.queueFile).also { it.load() }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val engine2 = ImportEngine(
            scope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            env = env,
            queue = queue,
            inspector = ArchiveInspector(env.archiveOpener()),
            detector = PlatformDetector(),
            extractor = ArchiveExtractor(env.archiveOpener()),
            mapping = PlatformMapping(prefs),
            settings = ImporterSettings(prefs),
            history = ImportHistoryRepository(first.historyFile).also { it.load() },
            clock = { 0L },
        )
        engine2.backToClassifying()
        val classifying = engine2.uiState.value as ImportUiState.Classifying
        assertTrue(classifying.needsReview.isEmpty())
        assertEquals(PlatformId.GBA, classifying.autoIdentified.single().platform)
        assertEquals(itemId, classifying.autoIdentified.single().id)
    }

    @Test fun `revoked downloads grant surfaces as a grant-again error`() {
        val env = envWith(
            Triple("one.zip", "uri:one", mapOf("one.gba" to ByteArray(10))),
        )
        val h = harness(env)
        val brokenEnv = object : ImporterEnvironment by h.env {
            override fun downloadsListing(): DownloadsListing =
                throw SecurityException("revoked")
        }
        val engine = ImportEngine(
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            env = brokenEnv,
            queue = h.queue,
            inspector = ArchiveInspector(h.env.archiveOpener()),
            detector = PlatformDetector(),
            extractor = ArchiveExtractor(h.env.archiveOpener()),
            mapping = PlatformMapping(MapKeyValueStore()),
            settings = ImporterSettings(MapKeyValueStore()),
            history = h.history,
            clock = { 0L },
        )
        engine.scan()
        val error = engine.uiState.value as ImportUiState.Error
        assertEquals(GrantKind.DOWNLOADS, error.grant)
    }

    @Test fun `skip during classification drops the item from the review list`() {
        val env = envWith(
            Triple("mystery.zip", "uri:mz", mapOf("mystery.gba" to ByteArray(100))),
        )
        val h = harness(env) { it.autoIdentify = false }

        h.engine.scan()
        var classifying = h.engine.uiState.value as ImportUiState.Classifying
        val itemId = classifying.needsReview.single().id
        h.engine.skipItem(itemId)

        classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertTrue(classifying.needsReview.isEmpty())
        assertTrue(classifying.autoIdentified.isEmpty())
        assertEquals(ImportStage.SKIPPED, h.engine.queueItems.value.single().stage)
    }

    @Test fun `skipFailedItem refreshes results and dismisses when none are left`() {
        val env = envWith(
            Triple("broken.zip", "uri:broken", mapOf("real.gba" to ByteArray(50))),
            Triple("broken2.zip", "uri:broken2", mapOf("real2.gba" to ByteArray(50))),
        )
        val h = harness(env)
        h.engine.scan()
        for (item in h.engine.queueItems.value) {
            h.queue.update(item.id) {
                it.copy(plan = it.plan!!.copy(target = ImportTarget.SingleFile("nope.gba")))
            }
        }

        h.engine.prepareImport()
        h.engine.startImport()
        var results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(2, results.failed.size)

        // Skipping one failure refreshes the results with the other intact.
        h.engine.skipFailedItem(results.failed.first().itemId)
        results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.failed.size)

        // Skipping the last failure dismisses to idle (nothing remains).
        h.engine.skipFailedItem(results.failed.single().itemId)
        assertTrue(h.engine.uiState.value is ImportUiState.Idle)
        assertTrue(h.engine.queueItems.value.all { it.stage == ImportStage.SKIPPED })
    }

    @Test fun `reviewFailures rebuilds results from failures after a restart`() {
        val env = envWith(
            Triple("broken.zip", "uri:broken", mapOf("real.gba" to ByteArray(50))),
        )
        val h = harness(env)
        h.engine.scan()
        val brokenId = h.engine.queueItems.value.single().id
        h.queue.update(brokenId) {
            it.copy(plan = it.plan!!.copy(target = ImportTarget.SingleFile("nope.gba")))
        }

        h.engine.prepareImport()
        h.engine.startImport()
        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.failed.size)

        // "Restart": a fresh engine over the same persisted queue, now idle.
        val queue = ImportQueue(h.queueFile).also { it.load() }
        val engine2 = ImportEngine(
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            env = env,
            queue = queue,
            inspector = ArchiveInspector(env.archiveOpener()),
            detector = PlatformDetector(),
            extractor = ArchiveExtractor(env.archiveOpener()),
            mapping = PlatformMapping(MapKeyValueStore()),
            settings = ImporterSettings(MapKeyValueStore()),
            history = ImportHistoryRepository(h.historyFile).also { it.load() },
            clock = { 0L },
        )
        engine2.reviewFailures()
        val rebuilt = engine2.uiState.value as ImportUiState.Results
        assertEquals(1, rebuilt.failed.size)
        assertEquals(brokenId, rebuilt.failed.single().itemId)
        assertEquals(ImportFailureReason.EXTRACTION_FAILED, rebuilt.failed.single().reason)
    }

    @Test fun `single-file import writes once with no staging dir and no temp leftovers`() {
        val romBytes = ByteArray(1024) { 42 }
        val env = envWith(
            Triple("Pokemon Emerald (USA).zip", "uri:pk", mapOf("Pokemon Emerald.gba" to romBytes)),
        )
        val h = harness(env)

        h.engine.scan()
        h.engine.prepareImport()
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertEquals(listOf("uri:pk"), env.deleted)

        // Destination has the game with intact bytes.
        val dest = romFile(env, "gba", "Pokemon Emerald.gba")!!
        assertEquals(1024L, env.roms.length(dest))
        assertTrue(env.roms.openInput(dest).readBytes().all { it == 42.toByte() })

        // Single write: no staging dir was ever created, no temp remains.
        val gbaDir = env.roms.find(env.roms.rootNode, "gba")!!
        assertEquals(listOf("Pokemon Emerald.gba"), env.roms.children(gbaDir).map { it.first })
    }

    @Test fun `failed single-file write cleans the temp and restores the REPLACE backup`() {
        val env = envWith(
            Triple("Pokemon Emerald (USA).zip", "uri:pk", mapOf("Pokemon Emerald.gba" to ByteArray(10) { 1 })),
        )
        val gbaDir = env.roms.mkdir(env.roms.rootNode, "gba")
        val owned = env.roms.createFile(gbaDir, "Pokemon Emerald.gba")
        env.roms.openOutput(owned).use { it.write(ByteArray(10) { 9 }) }
        // Fail the temp -> final rename; the backup park still succeeds.
        env.roms.renameGate = { node, _ -> !node.name.startsWith(".importing-") }
        val h = harness(env)

        h.engine.scan()
        h.engine.prepareImport()
        val conflict = h.engine.uiState.value as ImportUiState.ConflictReview
        h.engine.confirmConflicts(
            conflict.conflicts.map { it.copy(resolution = DuplicatePolicy.REPLACE) },
        )
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.failed.size)
        assertEquals(ImportFailureReason.WRITE_FAILED, results.failed.single().reason)
        // Original game intact, no temp, no backup leftovers, source kept.
        assertTrue(env.roms.openInput(romFile(env, "gba", "Pokemon Emerald.gba")!!).readBytes().all { it == 9.toByte() })
        assertEquals(listOf("Pokemon Emerald.gba"), env.roms.children(gbaDir).map { it.first })
        assertTrue(env.deleted.isEmpty())
        assertTrue(env.zips.containsKey("uri:pk"))
    }

    @Test fun `zero-byte payload fails verification and keeps everything safe`() {
        val env = envWith(
            Triple("empty.zip", "uri:empty", mapOf("empty.gba" to ByteArray(0))),
        )
        val h = harness(env)

        h.engine.scan()
        h.engine.prepareImport()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.failed.size)
        assertEquals(ImportFailureReason.VERIFICATION_FAILED, results.failed.single().reason)
        // Source kept; the bad write was rolled back; no temp leftovers.
        assertTrue(env.deleted.isEmpty())
        assertTrue(env.zips.containsKey("uri:empty"))
        val gbaDir = env.roms.find(env.roms.rootNode, "gba")!!
        assertTrue(env.roms.children(gbaDir).isEmpty())
    }

    @Test fun `crash recovery deletes stale single-file temps on the next run`() {
        val env = envWith(
            Triple("Pokemon Emerald (USA).zip", "uri:pk", mapOf("Pokemon Emerald.gba" to ByteArray(10) { 1 })),
        )
        val gbaDir = env.roms.mkdir(env.roms.rootNode, "gba")
        val stale = env.roms.createFile(gbaDir, ".importing-deadbeef")
        env.roms.openOutput(stale).use { it.write(ByteArray(10) { 5 }) }
        val h = harness(env)

        h.engine.scan()
        h.engine.prepareImport()
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        // The stale temp is gone; only the imported game remains.
        assertEquals(listOf("Pokemon Emerald.gba"), env.roms.children(gbaDir).map { it.first })
    }

    // ------------------------------------------------------------------
    // PS1 pipeline (CHD normalization)
    // ------------------------------------------------------------------

    /** Fake PSX device surface: temp under a real dir, fake chdman. */
    private class FakePsxSupport(
        val cacheBase: File = Files.createTempDirectory("psx-eng-test").toFile(),
        var converter: ChdConverter? = null,
    ) : PsxImportSupport {
        val tempDirs = mutableListOf<File>()
        override fun newTempDir(itemId: String): File =
            File(cacheBase, "psx-$itemId").also {
                it.deleteRecursively()
                it.mkdirs()
                tempDirs += it
            }

        override fun converter(): ChdConverter? = converter
        override fun tempFreeBytes(): Long = Long.MAX_VALUE
        override fun cleanStaleTempDirs() {}
    }

    private class FakeChdConverter : ChdConverter {
        override val available: Boolean = true
        override fun createcd(cueFile: File, outChd: File, onProgress: (Float) -> Unit): Boolean {
            onProgress(1f)
            outChd.writeBytes(ByteArray(64) { 7 })
            return true
        }

        override fun verify(chdFile: File): Boolean = chdFile.isFile && chdFile.length() > 0
        override fun cancel() {}
    }

    private fun psxCueZip(name: String, cueName: String, cueText: String, bins: Map<String, ByteArray>) =
        Triple(name, "uri:$name", mapOf(cueName to cueText.toByteArray()) + bins)

    @Test fun `psx broken cue fails typed and leaves psx untouched`() {
        val env = envWith(
            psxCueZip(
                "Game.zip", "Game.cue",
                "FILE \"Game (Track 01).bin\" BINARY\nFILE \"Game (Track 02).bin\" BINARY\n",
                mapOf("Game (Track 01).bin" to ByteArray(16)),
            ),
        )
        val psx = FakePsxSupport(converter = FakeChdConverter())
        val h = harness(env, psxSupport = psx)

        h.engine.scan()
        val item = h.engine.queueItems.value.single()
        h.engine.setPlatform(item.id, PlatformId.PSX)
        h.engine.prepareImport()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.failed.size)
        assertEquals(ImportFailureReason.PSX_CUE_TRACKS_MISSING, results.failed.single().reason)
        // Atomicity: psx/ holds nothing, the temp dir is gone, the
        // source archive is kept for a fixed re-download.
        val psxDir = env.roms.find(env.roms.rootNode, "psx")!!
        assertTrue(env.roms.children(psxDir).isEmpty())
        assertTrue(psx.tempDirs.all { !it.exists() })
        assertTrue(env.deleted.isEmpty())
        assertTrue(env.zips.containsKey("uri:Game.zip"))
    }

    @Test fun `psx single-disc cue normalizes to one chd in psx`() {
        val env = envWith(
            psxCueZip(
                "Crash Bandicoot.zip", "Crash Bandicoot.cue",
                "FILE \"Crash Bandicoot (Track 01).bin\" BINARY\n" +
                    "  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n",
                mapOf("Crash Bandicoot (Track 01).bin" to ByteArray(32)),
            ),
        )
        val psx = FakePsxSupport(converter = FakeChdConverter())
        val h = harness(env, psxSupport = psx)

        h.engine.scan()
        val item = h.engine.queueItems.value.single()
        h.engine.setPlatform(item.id, PlatformId.PSX)
        h.engine.prepareImport()
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertTrue(results.failed.isEmpty())
        // Exactly one artifact: no CUE/BIN debris in the live folder.
        val psxDir = env.roms.find(env.roms.rootNode, "psx")!!
        assertEquals(listOf("Crash Bandicoot.chd"), env.roms.children(psxDir).map { it.first })
        assertTrue(psx.tempDirs.all { !it.exists() })
        assertEquals(listOf("uri:Crash Bandicoot.zip"), env.deleted)
    }

    // ------------------------------------------------------------------
    // Loose files (u68): scan/classify/import without extraction
    // ------------------------------------------------------------------

    /** Fails the test if the archive extractor runs at all. */
    private fun noExtractSpy(env: FakeEnv): ArchiveExtractor =
        object : ArchiveExtractor(env.archiveOpener()) {
            override fun extract(
                fs: ThemeFs,
                ref: ArchiveRef,
                stagingDir: FsNode,
                plan: ImportPlan,
                directFileName: String?,
                onProgress: (Long, Long) -> Unit,
            ): ExtractReport = throw AssertionError("extractor invoked for loose item ${ref.name}")
        }

    @Test fun `loose gba builds confirmed item with SingleFile plan`() {
        val env = envWithLoose("Pokemon Emerald.gba" to ByteArray(512) { 1 })
        val h = harness(env, extractor = noExtractSpy(env))

        h.engine.scan()
        val item = h.engine.queueItems.value.single()
        assertTrue(item.isLooseFile)
        assertEquals(ArchiveKind.LOOSE_FILE, item.archiveKind)
        assertEquals(PlatformId.GBA, item.detection.platform)
        assertEquals(Confidence.CONFIRMED, item.detection.confidence)
        assertTrue(item.detection.recognizedAsGame)
        assertEquals(ImportTarget.SingleFile("Pokemon Emerald.gba"), item.plan!!.target)
        assertEquals(1, item.plan!!.payloadFileCount)
        assertEquals(512L, item.plan!!.payloadBytes)

        val classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(1, classifying.autoIdentified.size)
        assertTrue(classifying.needsReview.isEmpty())
    }

    @Test fun `loose gba imports straight into gba without extraction`() {
        val romBytes = ByteArray(2048) { 42 }
        val env = envWithLoose("Pokemon Emerald.gba" to romBytes)
        val h = harness(env, extractor = noExtractSpy(env))

        h.engine.scan()
        h.engine.prepareImport()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertTrue(results.failed.isEmpty())
        assertEquals(1, results.deletedSources)

        // Destination has the game with intact bytes.
        val dest = romFile(env, "gba", "Pokemon Emerald.gba")!!
        assertEquals(2048L, env.roms.length(dest))
        assertTrue(env.roms.openInput(dest).readBytes().all { it == 42.toByte() })

        // No dot-prefixed temp leftovers.
        val gbaDir = env.roms.find(env.roms.rootNode, "gba")!!
        assertTrue(env.roms.children(gbaDir).none { (n, _) -> n.startsWith(".") })

        // Source deleted only after the verified write.
        assertEquals(listOf("uri:Pokemon Emerald.gba"), env.deleted)
        assertEquals(ImportStage.COMPLETE, h.engine.queueItems.value.single().stage)
    }

    @Test fun `loose bin is recognized but needs review`() {
        val env = envWithLoose("game.bin" to ByteArray(64))
        val h = harness(env)

        h.engine.scan()
        val item = h.engine.queueItems.value.single()
        assertTrue(item.isLooseFile)
        assertTrue(item.detection.recognizedAsGame)
        assertEquals(null, item.detection.platform)

        val classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(1, classifying.needsReview.size)
        assertTrue(classifying.autoIdentified.isEmpty())
    }

    @Test fun `cue claims its bins and lone bin stays independent`() {
        val cue = "FILE \"Game (Track 1).bin\" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n"
        val env = envWithLoose(
            "Game.cue" to cue.toByteArray(),
            "Game (Track 1).bin" to ByteArray(32),
            "lone.bin" to ByteArray(16),
        )
        val h = harness(env)

        h.engine.scan()
        val items = h.engine.queueItems.value
        assertEquals(3, items.size)
        val cueItem = items.first { it.archiveName == "Game.cue" }
        val track = items.first { it.archiveName == "Game (Track 1).bin" }
        val lone = items.first { it.archiveName == "lone.bin" }
        assertTrue(track.isClaimed)
        assertEquals(cueItem.id, track.claimedByItemId)
        assertEquals(null, lone.claimedByItemId)
        assertFalse(track.needsReview)
        assertFalse(track.importable)

        // The classify screen shows only the descriptor and the lone bin.
        val classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(2, classifying.needsReview.size)
        assertTrue(
            classifying.needsReview.map { it.archiveName }
                .containsAll(listOf("Game.cue", "lone.bin")),
        )
    }

    @Test fun `m3u claims its discs`() {
        val env = envWithLoose(
            "Game.m3u" to "#EXTM3U\nDisc 1.chd\nDisc 2.chd\n".toByteArray(),
            "Disc 1.chd" to ByteArray(48),
            "Disc 2.chd" to ByteArray(48),
            "Other.chd" to ByteArray(48),
        )
        val h = harness(env)

        h.engine.scan()
        val items = h.engine.queueItems.value
        assertEquals(4, items.size)
        val m3uItem = items.first { it.archiveName == "Game.m3u" }
        assertEquals(m3uItem.id, items.first { it.archiveName == "Disc 1.chd" }.claimedByItemId)
        assertEquals(m3uItem.id, items.first { it.archiveName == "Disc 2.chd" }.claimedByItemId)
        assertEquals(null, items.first { it.archiveName == "Other.chd" }.claimedByItemId)

        val classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(2, classifying.needsReview.size)
    }

    @Test fun `removing a descriptor frees its claimed files`() {
        val cue = "FILE \"Game (Track 1).bin\" BINARY\n"
        val env = envWithLoose(
            "Game.cue" to cue.toByteArray(),
            "Game (Track 1).bin" to ByteArray(32),
        )
        val h = harness(env)

        h.engine.scan()
        val cueId = h.engine.queueItems.value.first { it.archiveName == "Game.cue" }.id
        h.engine.removeItem(cueId)

        val items = h.engine.queueItems.value
        assertEquals(1, items.size)
        assertEquals(null, items.single().claimedByItemId)
        val classifying = h.engine.uiState.value as ImportUiState.Classifying
        assertEquals(1, classifying.needsReview.size)
    }

    @Test fun `loose psx bin installs as-is`() {
        val env = envWithLoose("Crash.bin" to ByteArray(128) { 9 })
        val psx = FakePsxSupport(converter = FakeChdConverter())
        val h = harness(env, psxSupport = psx, extractor = noExtractSpy(env))

        h.engine.scan()
        val item = h.engine.queueItems.value.single()
        h.engine.setPlatform(item.id, PlatformId.PSX)
        h.engine.prepareImport()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertTrue(results.failed.isEmpty())
        val psxDir = env.roms.find(env.roms.rootNode, "psx")!!
        assertEquals(listOf("Crash.bin"), env.roms.children(psxDir).map { it.first })
        assertEquals(128L, env.roms.length(romFile(env, "psx", "Crash.bin")!!))
        assertTrue(psx.tempDirs.all { !it.exists() })
        assertEquals(listOf("uri:Crash.bin"), env.deleted)
    }

    @Test fun `loose psx cue converts to chd and deletes claimed sources`() {
        val cue = "FILE \"Game (Track 1).bin\" BINARY\n  TRACK 01 MODE1/2352\n    INDEX 01 00:00:00\n"
        val env = envWithLoose(
            "Game.cue" to cue.toByteArray(),
            "Game (Track 1).bin" to ByteArray(64),
        )
        val psx = FakePsxSupport(converter = FakeChdConverter())
        val h = harness(env, psxSupport = psx, extractor = noExtractSpy(env))

        h.engine.scan()
        val cueItem = h.engine.queueItems.value.first { it.archiveName == "Game.cue" }
        h.engine.setPlatform(cueItem.id, PlatformId.PSX)
        h.engine.prepareImport()
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.succeeded)
        assertTrue(results.failed.isEmpty())
        assertEquals(2, results.deletedSources)
        val psxDir = env.roms.find(env.roms.rootNode, "psx")!!
        assertEquals(listOf("Game.chd"), env.roms.children(psxDir).map { it.first })
        assertTrue(psx.tempDirs.all { !it.exists() })
        // Both the descriptor and the claimed track left Downloads.
        assertEquals(setOf("uri:Game.cue", "uri:Game (Track 1).bin"), env.deleted.toSet())
        // The claimed track is consumed, not left waiting.
        assertEquals(ImportStage.COMPLETE, h.engine.queueItems.value.first { it.archiveName == "Game (Track 1).bin" }.stage)
    }

    @Test fun `loose psx broken cue fails typed, sources kept, temp cleaned`() {
        val env = envWithLoose(
            "Game.cue" to "FILE \"Missing.bin\" BINARY\n".toByteArray(),
        )
        val psx = FakePsxSupport(converter = FakeChdConverter())
        val h = harness(env, psxSupport = psx, extractor = noExtractSpy(env))

        h.engine.scan()
        val item = h.engine.queueItems.value.single()
        h.engine.setPlatform(item.id, PlatformId.PSX)
        h.engine.prepareImport()
        assertTrue(h.engine.uiState.value is ImportUiState.Ready)
        h.engine.startImport()

        val results = h.engine.uiState.value as ImportUiState.Results
        assertEquals(1, results.failed.size)
        assertEquals(ImportFailureReason.PSX_CUE_TRACKS_MISSING, results.failed.single().reason)
        // Atomicity: psx/ holds nothing, the temp dir is gone, the
        // source stays in Downloads.
        val psxDir = env.roms.find(env.roms.rootNode, "psx")!!
        assertTrue(env.roms.children(psxDir).isEmpty())
        assertTrue(psx.tempDirs.all { !it.exists() })
        assertTrue(env.deleted.isEmpty())
        assertEquals(1, env.downloads.size)
    }
}
