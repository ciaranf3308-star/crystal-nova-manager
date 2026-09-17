package io.crystalnova.manager.scraper

import android.graphics.Bitmap
import io.crystalnova.manager.data.FakeHttpClient
import io.crystalnova.manager.scraper.generate.ArtRenderer
import io.crystalnova.manager.scraper.generate.BackCoverGenerator
import io.crystalnova.manager.scraper.generate.MediaGenerator
import io.crystalnova.manager.scraper.generate.SpineGenerator
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.scraper.provider.ArtworkCandidate
import io.crystalnova.manager.scraper.provider.ArtworkProvider
import io.crystalnova.manager.scraper.provider.GameMetadata
import io.crystalnova.manager.scraper.provider.MetadataProvider
import io.crystalnova.manager.scraper.provider.ScrapeQuery
import io.crystalnova.manager.scraper.scan.RomEntry
import io.crystalnova.manager.scraper.store.MediaCache
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.scraper.work.ScrapeJob
import io.crystalnova.manager.scraper.work.ScrapeStorageWriteException
import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.InMemoryThemeFs
import io.crystalnova.manager.storage.ThemeFs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * v22 hotfix regression tests: the scraper failed on real hardware
 * (attempt 1: crash/ANR from blocking work on the Activity scope;
 * attempt 2: FINISHED with 0 persisted/indexed data from silent write
 * failures). Each test below pins one fix; write failures are real
 * (failing fake filesystem), never mocked into success.
 */
class ScraperV22HotfixTest {

    private val pngMagic = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun pngBytes() = pngMagic + ByteArray(64) { 0x2A }

    private fun entry(name: String = "Mario Golf (E).gba") = RomEntry(
        platformSlug = "gba",
        platformLabel = "Game Boy Advance",
        relativePath = "gba/$name",
        fileName = name,
        size = 1024,
        lastModified = 2048,
    )

    private fun fakeHttp(okUrls: Set<String> = emptySet()) = FakeHttpClient(
        downloadHandler = { url, dest ->
            if (url in okUrls) dest.writeBytes(pngBytes())
            else throw IOException("network down")
        },
    )

    private fun frontOnlyProvider() = object : ArtworkProvider {
        override val id = "fake"
        override val displayName = "Fake"
        override val requiresApiKey = false
        override suspend fun artworkFor(query: ScrapeQuery) =
            mapOf(AssetSlot.BOX_FRONT to listOf(ArtworkCandidate("https://example.com/front.png")))
    }

    private fun noMetaProvider() = object : MetadataProvider {
        override val id = "none"
        override val displayName = "None"
        override val requiresApiKey = false
        override suspend fun lookup(query: ScrapeQuery): GameMetadata? = null
    }

    private fun fakeRenderer() = object : ArtRenderer {
        override fun renderSpine(spec: SpineGenerator.Spec) = pngBytes()
        override fun renderBack(spec: BackCoverGenerator.Spec, screenshot: Bitmap?) = pngBytes()
        override fun renderMedia(spec: MediaGenerator.Spec, logo: Bitmap?) = pngBytes()
    }

    private fun job(
        storage: ScraperStorage,
        http: FakeHttpClient,
        providers: List<ArtworkProvider> = listOf(frontOnlyProvider()),
        tmp: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = ScrapeJob(
        storage = storage,
        cache = MediaCache(storage, File(tmp, "cache-stage").apply { mkdirs() }, http),
        artworkProviders = providers,
        metadataProvider = noMetaProvider(),
        openRomInput = { null },
        artRenderer = fakeRenderer(),
        ioDispatcher = ioDispatcher,
    )

    /** ThemeFs that throws on file creation for names matching [failOn]. */
    private fun failingFs(
        delegate: InMemoryThemeFs,
        failOn: (String) -> Boolean,
    ): ThemeFs = object : ThemeFs by delegate {
        override fun createFile(parent: FsNode, name: String): FsNode {
            if (failOn(name)) throw IOException("read-only media")
            return delegate.createFile(parent, name)
        }
    }

    private suspend fun <T : Throwable> assertThrows(
        expected: Class<T>,
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (e: Throwable) {
            if (expected.isInstance(e)) {
                @Suppress("UNCHECKED_CAST")
                return e as T
            }
            throw AssertionError("expected ${expected.simpleName} but got ${e.javaClass.simpleName}", e)
        }
        throw AssertionError("expected ${expected.simpleName} but nothing was thrown")
    }

    // --- 1. IO dispatcher seam -------------------------------------------

    @Test fun `scrape pipeline runs on the injected io dispatcher`() = runBlocking {
        val used = AtomicBoolean(false)
        val ioProbe = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                used.set(true)
                block.run()
            }
        }
        val tmp = createTempDir("scrape-v22-io")
        val storage = ScraperStorage(InMemoryThemeFs())
        val result = job(
            storage,
            fakeHttp(setOf("https://example.com/front.png")),
            tmp = tmp,
            ioDispatcher = ioProbe,
        ).run(listOf(entry()), onProgress = {})
        assertTrue(
            "blocking pipeline must execute via the injected io dispatcher, not the caller's",
            used.get(),
        )
        assertEquals(1, result.succeeded)
    }

    // --- 2. Failed manifest write -----------------------------------------

    @Test fun `failed manifest write aborts the run with a storage exception`() = runBlocking {
        val fs = InMemoryThemeFs()
        val storage = ScraperStorage(
            failingFs(fs) { name -> name.startsWith(ScraperStorage.MANIFEST_NAME) },
        )
        val tmp = createTempDir("scrape-v22-manifest")
        val ex = assertThrows(ScrapeStorageWriteException::class.java) {
            job(storage, fakeHttp(setOf("https://example.com/front.png")), tmp = tmp)
                .run(listOf(entry()), onProgress = {})
        }
        assertTrue(
            "user must see the write-failed message, got: ${ex.message}",
            ex.message!!.contains("SCRAPE STORAGE WRITE FAILED"),
        )
        assertEquals("gba", ex.platform)
        assertEquals("mario-golf", ex.gameId)
        assertEquals("MANIFEST", ex.stage)
        // No successful finished state: nothing persisted, nothing counted.
        assertNull(
            "failed manifest write must not leave a readable manifest",
            ScraperStorage(fs).loadManifest("gba", "mario-golf"),
        )
    }

    // --- 3. Failed index write --------------------------------------------

    @Test fun `failed index write aborts the run and keeps the previous good index`() = runBlocking {
        val fs = InMemoryThemeFs()
        val tmp = createTempDir("scrape-v22-index")
        // Seed a good index with the working filesystem first.
        val seed = ScraperStorage(fs)
        job(seed, fakeHttp(setOf("https://example.com/front.png")), tmp = tmp)
            .run(listOf(entry()), onProgress = {})
        val before = seed.loadIndexJson()
        assertNotNull(before)
        assertTrue(before!!.contains("mario-golf"))

        // Now fail only index.json writes; manifests still persist.
        val storage = ScraperStorage(
            failingFs(fs) { name -> name.startsWith(ScraperStorage.INDEX_NAME) },
        )
        val ex = assertThrows(ScrapeStorageWriteException::class.java) {
            job(storage, fakeHttp(setOf("https://example.com/front.png")), tmp = tmp)
                .run(listOf(entry()), onProgress = {})
        }
        assertTrue(
            "index failure must be surfaced explicitly, got: ${ex.message}",
            ex.message!!.contains("INDEX"),
        )
        // The previous good index is untouched: the atomic write restores
        // the original on failure, so this never becomes "0 games".
        assertEquals("failed index write must not clobber the good index", before, seed.loadIndexJson())
    }

    // --- 4. Writable preflight --------------------------------------------

    @Test fun `write probe succeeds on a writable root and leaves no litter`() {
        val fs = InMemoryThemeFs()
        val storage = ScraperStorage(fs)
        assertTrue(storage.probeWritable() is ScraperStorage.ProbeResult.Writable)
        val dataDir = fs.find(fs.root()!!, ScraperStorage.DATA_DIR_NAME)!!
        assertNull(
            "probe must delete its own file",
            fs.find(dataDir, ScraperStorage.PROBE_FILE_NAME),
        )
    }

    @Test fun `write probe fails closed on an unwritable root`() {
        val storage = ScraperStorage(
            failingFs(InMemoryThemeFs()) { _ -> true },
        )
        val probe = storage.probeWritable()
        assertTrue("unwritable root must fail the probe", probe is ScraperStorage.ProbeResult.Failed)
    }

    @Test fun `preflight gate refuses read-only dedicated media grant`() {
        // Even with a writable probe target, a dedicated media tree whose
        // persisted grant lacks WRITE must refuse the scrape.
        val good = ScraperStorage(InMemoryThemeFs())
        val refused = scrapeWritePreflight(
            storage = good,
            hasDedicatedMediaTree = true,
            hasWriteGrant = false,
        )
        assertTrue(refused is ScraperStorage.ProbeResult.Failed)

        val allowed = scrapeWritePreflight(
            storage = good,
            hasDedicatedMediaTree = true,
            hasWriteGrant = true,
        )
        assertTrue(allowed is ScraperStorage.ProbeResult.Writable)
    }

    @Test fun `preflight gate refuses when the probe fails`() {
        val broken = ScraperStorage(
            failingFs(InMemoryThemeFs()) { _ -> true },
        )
        val gate = scrapeWritePreflight(
            storage = broken,
            hasDedicatedMediaTree = false,
            hasWriteGrant = false,
        )
        assertTrue(
            "preflight failure must prevent the scrape from starting",
            gate is ScraperStorage.ProbeResult.Failed,
        )
    }

    // --- 5. Successful write path ------------------------------------------

    @Test fun `successful write path persists manifest and index normally`() = runBlocking {
        val tmp = createTempDir("scrape-v22-ok")
        val storage = ScraperStorage(InMemoryThemeFs())
        val games = listOf(entry("Mario Golf (E).gba"), entry("Metroid Fusion (U).gba"))
        val result = job(storage, fakeHttp(setOf("https://example.com/front.png")), tmp = tmp)
            .run(games, onProgress = {})
        assertEquals(2, result.succeeded)
        assertEquals(0, result.failed)

        val manifest = storage.loadManifest("gba", "mario-golf")
        assertNotNull("manifest must persist on the success path", manifest)
        assertEquals(SourceType.REAL, manifest!!.assets[AssetSlot.BOX_FRONT]?.sourceType)

        val indexText = storage.loadIndexJson()
        assertNotNull("index must persist on the success path", indexText)
        val indexGames = JSONObject(indexText!!).getJSONObject("games")
        assertEquals(2, indexGames.length())
        assertTrue(indexGames.has("gba/mario-golf"))
        assertTrue(indexGames.has("gba/metroid-fusion"))
    }

    // --- 6. Retry-incomplete -------------------------------------------------

    @Test fun `retry incomplete does not redo complete non-stale games`() = runBlocking {
        val tmp = createTempDir("scrape-v22-retry")
        val storage = ScraperStorage(InMemoryThemeFs())
        val http = fakeHttp(setOf("https://example.com/front.png"))
        val games = listOf(entry())
        val first = job(storage, http, tmp = tmp).run(games, onProgress = {})
        assertEquals(1, first.succeeded)
        assertEquals(
            Completeness.COMPLETE_CASE_AND_MEDIA,
            storage.loadManifest("gba", "mario-golf")!!.completeness,
        )

        var providerCalls = 0
        val counting = object : ArtworkProvider by frontOnlyProvider() {
            override suspend fun artworkFor(query: ScrapeQuery): Map<AssetSlot, List<ArtworkCandidate>> {
                providerCalls++
                return frontOnlyProvider().artworkFor(query)
            }
        }
        val second = job(storage, http, providers = listOf(counting), tmp = tmp)
            .run(games, onlyIncomplete = true, onProgress = {})
        assertEquals("complete non-stale game must not hit the provider again", 0, providerCalls)
        assertEquals(1, second.succeeded)
        assertEquals(0, second.failed)
    }
}
