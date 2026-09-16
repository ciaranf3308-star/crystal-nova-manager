package io.crystalnova.manager.scraper

import android.graphics.Bitmap
import io.crystalnova.manager.data.FakeHttpClient
import io.crystalnova.manager.scraper.generate.ArtRenderer
import io.crystalnova.manager.scraper.generate.BackCoverGenerator
import io.crystalnova.manager.scraper.generate.MediaGenerator
import io.crystalnova.manager.scraper.generate.SpineGenerator
import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.model.MatchConfidence
import io.crystalnova.manager.scraper.model.ScrapedGame
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
import io.crystalnova.manager.scraper.work.ScrapeProgress
import io.crystalnova.manager.storage.InMemoryThemeFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * End-to-end ScrapeJob tests with fakes: no network, no Android
 * framework (rendering goes through a fake [ArtRenderer]).
 */
class ScrapeJobTest {

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

    private fun fakeRenderer(failBack: Boolean = false) = object : ArtRenderer {
        override fun renderSpine(spec: SpineGenerator.Spec) = pngBytes()
        override fun renderBack(spec: BackCoverGenerator.Spec, screenshot: Bitmap?): ByteArray {
            if (failBack) throw IOException("render failed")
            return pngBytes()
        }
        override fun renderMedia(spec: MediaGenerator.Spec, logo: Bitmap?) = pngBytes()
    }

    private fun job(
        storage: ScraperStorage,
        http: FakeHttpClient,
        renderer: ArtRenderer = fakeRenderer(),
        providers: List<ArtworkProvider> = listOf(frontOnlyProvider()),
        meta: MetadataProvider = noMetaProvider(),
        tmp: File,
    ) = ScrapeJob(
        storage = storage,
        cache = MediaCache(storage, File(tmp, "cache-stage").apply { mkdirs() }, http),
        artworkProviders = providers,
        metadataProvider = meta,
        openRomInput = { null },
        artRenderer = renderer,
    )

    @Test fun `front found and back missing from provider generates back fallback`() = runBlocking {
        val tmp = createTempDir("scrape")
        val storage = ScraperStorage(InMemoryThemeFs())
        val result = job(storage, fakeHttp(setOf("https://example.com/front.png")), tmp = tmp)
            .run(listOf(entry()), onProgress = {})
        assertEquals(1, result.succeeded)
        val game = storage.loadManifest("gba", "mario-golf-e") ?: storage.loadManifest("gba", "mario-golf")
        assertNotNull(game)
        assertEquals(SourceType.REAL, game!!.assets[AssetSlot.BOX_FRONT]?.sourceType)
        assertEquals(SourceType.GENERATED, game.assets[AssetSlot.BOX_BACK]?.sourceType)
        assertEquals("crystal-back-v1", game.assets[AssetSlot.BOX_BACK]?.generatedBy)
        assertEquals(Completeness.COMPLETE_CASE_AND_MEDIA, game.completeness)
    }

    @Test fun `generated back failure is partial, not a failed scrape`() = runBlocking {
        val tmp = createTempDir("scrape")
        val storage = ScraperStorage(InMemoryThemeFs())
        val result = job(
            storage,
            fakeHttp(setOf("https://example.com/front.png")),
            renderer = fakeRenderer(failBack = true),
            tmp = tmp,
        ).run(listOf(entry()), onProgress = {})
        assertEquals(0, result.succeeded)
        assertEquals(1, result.partial)
        assertEquals(0, result.failed)
        val game = storage.loadManifest("gba", "mario-golf")!!
        assertEquals(Completeness.PARTIAL_CASE, game.completeness)
        assertTrue(game.assets.containsKey(AssetSlot.BOX_FRONT))
        assertFalse(game.assets.containsKey(AssetSlot.BOX_BACK))
    }

    @Test fun `network failure preserves existing real assets`() = runBlocking {
        val tmp = createTempDir("scrape")
        val fs = InMemoryThemeFs()
        val storage = ScraperStorage(fs)
        val gameId = "mario-golf"
        // Pre-seed: REAL front from an earlier successful scrape.
        val frontProv = AssetProvenance(SourceType.REAL, "libretro", localPath = "games/gba/$gameId/front.png")
        storage.saveAsset("gba", gameId, AssetSlot.BOX_FRONT, frontProv, pngBytes(), null)
        storage.saveManifest(
            ScrapedGame("gba", gameId, "gba/Mario Golf (E).gba", 1024, 2048, "Mario Golf",
                assets = mapOf(AssetSlot.BOX_FRONT to frontProv)),
        )
        // Network is down for everything.
        val result = job(storage, fakeHttp(), tmp = tmp).run(listOf(entry()), onProgress = {})
        assertEquals(0, result.failed)
        val game = storage.loadManifest("gba", gameId)!!
        // The authentic front survives; generated fallbacks still fill the rest offline.
        assertEquals(SourceType.REAL, game.assets[AssetSlot.BOX_FRONT]?.sourceType)
        assertArrayEquals(pngBytes(), storage.readBytes("games/gba/$gameId/front.png"))
    }

    @Test fun `user front art is never overwritten by provider download`() = runBlocking {
        val tmp = createTempDir("scrape")
        val storage = ScraperStorage(InMemoryThemeFs())
        val gameId = "mario-golf"
        val userProv = AssetProvenance(SourceType.USER, "user", localPath = "games/gba/$gameId/front.png")
        val userBytes = pngMagic + ByteArray(64) { 0x7F }
        storage.saveAsset("gba", gameId, AssetSlot.BOX_FRONT, userProv, userBytes, null)
        storage.saveManifest(
            ScrapedGame("gba", gameId, "gba/Mario Golf (E).gba", 1024, 2048, "Mario Golf",
                assets = mapOf(AssetSlot.BOX_FRONT to userProv)),
        )
        job(storage, fakeHttp(setOf("https://example.com/front.png")), tmp = tmp)
            .run(listOf(entry()), onProgress = {})
        val game = storage.loadManifest("gba", gameId)!!
        assertEquals(SourceType.USER, game.assets[AssetSlot.BOX_FRONT]?.sourceType)
        assertArrayEquals(userBytes, storage.readBytes("games/gba/$gameId/front.png"))
    }

    @Test fun `existing metadata survives a rescan that returns fewer fields`() = runBlocking {
        val tmp = createTempDir("scrape")
        val storage = ScraperStorage(InMemoryThemeFs())
        val gameId = "mario-golf"
        storage.saveManifest(
            ScrapedGame(
                "gba", gameId, "gba/Mario Golf (E).gba", 1024, 2048, "Mario Golf: Advance Tour",
                description = "Original description", developer = "Camelot",
                assets = mapOf(AssetSlot.BOX_SPINE to AssetProvenance(SourceType.GENERATED, "crystal", generatedBy = "crystal-spine-v1", localPath = "games/gba/$gameId/spine.png")),
            ),
        )
        // This rescan's metadata source knows nothing: fields must be kept, not nulled.
        job(storage, fakeHttp(), tmp = tmp).run(listOf(entry()), onProgress = {})
        val game = storage.loadManifest("gba", gameId)!!
        assertEquals("Original description", game.description)
        assertEquals("Camelot", game.developer)
        assertEquals("Mario Golf: Advance Tour", game.title)
    }

    @Test fun `weak fuzzy provider match is never auto-accepted`() = runBlocking {
        val tmp = createTempDir("scrape")
        val storage = ScraperStorage(InMemoryThemeFs())
        // The candidate downloads fine, but its name has nothing to do with the title.
        val weakProvider = object : ArtworkProvider {
            override val id = "weak"
            override val displayName = "Weak"
            override val requiresApiKey = false
            override suspend fun artworkFor(query: ScrapeQuery) =
                mapOf(AssetSlot.BOX_FRONT to listOf(ArtworkCandidate("https://example.com/tennis.png")))
        }
        val result = job(
            storage,
            fakeHttp(setOf("https://example.com/tennis.png")),
            providers = listOf(weakProvider),
            tmp = tmp,
        ).run(listOf(entry()), onProgress = {})
        assertEquals(1, result.succeeded)
        val game = storage.loadManifest("gba", "mario-golf")!!
        // The bytes are kept with full provenance, but no match is claimed:
        // the game stays available for a manual match.
        assertEquals(SourceType.REAL, game.assets[AssetSlot.BOX_FRONT]?.sourceType)
        assertEquals("https://example.com/tennis.png", game.assets[AssetSlot.BOX_FRONT]?.sourceUrl)
        assertNull(game.provider)
        assertNull(game.providerGameId)
        assertTrue(game.confidence is MatchConfidence.None)
    }

    @Test fun `exact title provider match is claimed`() = runBlocking {
        val tmp = createTempDir("scrape")
        val storage = ScraperStorage(InMemoryThemeFs())
        val exactProvider = object : ArtworkProvider {
            override val id = "exact"
            override val displayName = "Exact"
            override val requiresApiKey = false
            override suspend fun artworkFor(query: ScrapeQuery) =
                mapOf(AssetSlot.BOX_FRONT to listOf(ArtworkCandidate("https://example.com/Mario Golf.png")))
        }
        job(
            storage,
            fakeHttp(setOf("https://example.com/Mario Golf.png")),
            providers = listOf(exactProvider),
            tmp = tmp,
        ).run(listOf(entry()), onProgress = {})
        val game = storage.loadManifest("gba", "mario-golf")!!
        assertEquals("exact", game.provider)
        assertEquals("Mario Golf.png", game.providerGameId)
        assertTrue(game.confidence is MatchConfidence.ExactTitle)
    }

    @Test fun `cancellation emits a final cancelled progress`() = runBlocking {
        val tmp = createTempDir("scrape")
        val storage = ScraperStorage(InMemoryThemeFs())
        val slowProvider = object : ArtworkProvider {
            override val id = "slow"
            override val displayName = "Slow"
            override val requiresApiKey = false
            override suspend fun artworkFor(query: ScrapeQuery): Map<AssetSlot, List<ArtworkCandidate>> {
                delay(200)
                return emptyMap()
            }
        }
        val seen = mutableListOf<ScrapeProgress>()
        val deferred = async {
            job(storage, fakeHttp(), providers = listOf(slowProvider), tmp = tmp)
                .run((1..30).map { entry("Game $it (E).gba") }, onProgress = { seen += it })
        }
        delay(350) // let the run get going
        deferred.cancel()
        try {
            deferred.await()
        } catch (_: CancellationException) {
            // expected
        }
        assertTrue(seen.isNotEmpty())
        assertTrue("last progress must be marked cancelled", seen.last().cancelled)
    }

    @Test fun `cancellation stops the job mid-run`() = runBlocking {
        val tmp = createTempDir("scrape")
        val storage = ScraperStorage(InMemoryThemeFs())
        val slowProvider = object : ArtworkProvider {
            override val id = "slow"
            override val displayName = "Slow"
            override val requiresApiKey = false
            override suspend fun artworkFor(query: ScrapeQuery): Map<AssetSlot, List<ArtworkCandidate>> {
                delay(200)
                return emptyMap()
            }
        }
        val games = (1..30).map { entry("Game $it (E).gba") }
        val deferred = async {
            job(storage, fakeHttp(), providers = listOf(slowProvider), tmp = tmp)
                .run(games, onProgress = {})
        }
        delay(350) // let the first game start
        deferred.cancel()
        try {
            deferred.await()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        val manifests = (1..30).count { storage.loadManifest("gba", "game-$it") != null }
        assertTrue("cancelled job should not finish all games, did $manifests", manifests < 30)
    }
}
