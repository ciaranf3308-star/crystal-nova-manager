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
import org.json.JSONObject
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

    @Test fun `multi-disc cue pair collapses to disc one with a skip notice`() = runBlocking {
        val tmp = createTempDir("scrape-dedupe")
        val storage = ScraperStorage(InMemoryThemeFs())
        // Listed disc-2-first on purpose: the kept representative must be
        // order-independent.
        val games = listOf(
            entry("Final Fantasy VII (Disc 2).cue"),
            entry("Final Fantasy VII (Disc 1).cue"),
        )
        val result = job(storage, fakeHttp(), tmp = tmp).run(games, onProgress = {})

        assertEquals(listOf("Final Fantasy VII (Disc 2).cue"), result.skippedDuplicates)
        val kept = storage.loadManifest("gba", "final-fantasy-vii")
        assertNotNull(kept)
        assertTrue(kept!!.romRelativePath.endsWith("(Disc 1).cue"))
    }

    @Test fun `punctuation-variant roms collapse to one game`() = runBlocking {
        val tmp = createTempDir("scrape-dedupe2")
        val storage = ScraperStorage(InMemoryThemeFs())
        val games = listOf(entry("A&B.gba"), entry("A B.gba"))
        val result = job(storage, fakeHttp(), tmp = tmp).run(games, onProgress = {})

        assertEquals(1, result.skippedDuplicates.size)
        assertNotNull(storage.loadManifest("gba", "a-b"))
        // Progress totals are deduplicated, not double-counted.
        assertEquals(1, result.succeeded + result.partial + result.unmatched + result.failed)
    }

    @Test fun `raw bin loses to cue in dedupe`() = runBlocking {
        val tmp = createTempDir("scrape-dedupe-bin")
        val storage = ScraperStorage(InMemoryThemeFs())
        val games = listOf(entry("Game.bin"), entry("Game.cue"))
        val result = job(storage, fakeHttp(), tmp = tmp).run(games, onProgress = {})

        assertEquals(listOf("Game.bin"), result.skippedDuplicates)
        val kept = storage.loadManifest("gba", "game")
        assertNotNull(kept)
        assertTrue(kept!!.romRelativePath.endsWith("Game.cue"))
    }

    @Test fun `dedupe tie-break is the lexical relative path`() = runBlocking {
        val tmp = createTempDir("scrape-dedupe-path")
        val storage = ScraperStorage(InMemoryThemeFs())
        fun pathEntry(rel: String, name: String) = RomEntry(
            platformSlug = "gba",
            platformLabel = "Game Boy Advance",
            relativePath = rel,
            fileName = name,
            size = 1024,
            lastModified = 2048,
        )
        // Same slug, same extension, no disc numbers — listed in reverse
        // lexical order on purpose; the winner must still be deterministic.
        val games = listOf(
            pathEntry("gba/z/Game.gba", "Game.gba"),
            pathEntry("gba/a/Game.gba", "Game.gba"),
        )
        val result = job(storage, fakeHttp(), tmp = tmp).run(games, onProgress = {})

        assertEquals(1, result.skippedDuplicates.size)
        val kept = storage.loadManifest("gba", "game")
        assertNotNull(kept)
        assertTrue(
            "expected the lexically-first path to win, kept ${kept!!.romRelativePath}",
            kept.romRelativePath.endsWith("gba/a/Game.gba"),
        )
    }

    @Test fun `revoked grant at run start aborts without touching the index`() = runBlocking {
        val tmp = createTempDir("scrape-revoked-start")
        val fs = InMemoryThemeFs()
        // Seed a good index through the healthy fs first.
        val healthy = ScraperStorage(fs)
        healthy.saveManifest(
            ScrapedGame(
                platform = "gba",
                gameId = "seed-game",
                romRelativePath = "gba/Seed Game (E).gba",
                title = "Seed Game",
            ),
        )
        assertTrue(healthy.saveIndexJson("""{"version":1,"games":{"gba/seed-game":{}}}"""))

        val revokedFs = object : io.crystalnova.manager.storage.ThemeFs by fs {
            override fun root(): io.crystalnova.manager.storage.FsNode? =
                throw SecurityException("grant revoked")
        }
        try {
            job(ScraperStorage(revokedFs), fakeHttp(), tmp = tmp)
                .run(listOf(entry("Seed Game (E).gba")), onProgress = {})
            fail("expected SecurityException")
        } catch (_: SecurityException) {
            // Expected: the caller translates this into the reselection state.
        }
        // The stored index is untouched — no blank-out on the way out.
        val root = JSONObject(healthy.loadIndexJson()!!)
        assertTrue(root.getJSONObject("games").has("gba/seed-game"))
    }

    @Test fun `revoked folder access aborts the run with folderAccessLost`() = runBlocking {
        val tmp = createTempDir("scrape-revoked")
        val storage = ScraperStorage(InMemoryThemeFs())
        val revokedMeta = object : MetadataProvider {
            override val id = "revoked"
            override val displayName = "Revoked"
            override val requiresApiKey = false
            override suspend fun lookup(query: ScrapeQuery): GameMetadata? =
                throw SecurityException("permission revoked")
        }
        val games = listOf(entry("Game One.gba"), entry("Game Two.gba"))
        val result = job(storage, fakeHttp(), meta = revokedMeta, tmp = tmp)
            .run(games, onProgress = {})

        assertTrue("expected folderAccessLost", result.folderAccessLost)
        assertEquals(0, result.succeeded + result.partial + result.failed)
    }

    @Test fun `malformed index quarantine preserves the exact bytes`() = runBlocking {
        val tmp = createTempDir("scrape-corrupt-bytes")
        val fs = InMemoryThemeFs()
        val storage = ScraperStorage(fs)
        // Invalid UTF-8: a String round-trip would replace 0xFF/0xFE with
        // U+FFFD, so the quarantine must be written from the raw bytes.
        // (Also malformed JSON: org.json leniently accepts an unquoted
        // trailing value, so the garbage sits where a key is required.)
        val raw = byteArrayOf(
            0x7b,
            0xff.toByte(), 0xfe.toByte(),
        )
        val dataDir = fs.mkdir(fs.root()!!, "crystal-nova-data") as InMemoryThemeFs.Node
        fs.openOutput(fs.createFile(dataDir, "index.json")).use { it.write(raw) }

        val result = job(storage, fakeHttp(), tmp = tmp)
            .run(listOf(entry()), onProgress = {})

        assertTrue("expected indexRebuilt", result.indexRebuilt)
        val backupName = dataDir.children.keys.first { it.startsWith("index.json.corrupt-") }
        val backupNode = dataDir.children[backupName]!!
        assertEquals(raw.toList(), fs.openInput(backupNode).use { it.readBytes() }.toList())
    }

    @Test fun `malformed index is quarantined and rebuilt from manifests`() = runBlocking {
        val tmp = createTempDir("scrape-corrupt")
        val fs = InMemoryThemeFs()
        val storage = ScraperStorage(fs)
        storage.saveManifest(
            ScrapedGame(
                platform = "gba",
                gameId = "seed-game",
                romRelativePath = "gba/Seed Game (E).gba",
                title = "Seed Game",
            ),
        )
        storage.saveIndexJson("this is not json {{{")

        val result = job(storage, fakeHttp(), tmp = tmp)
            .run(listOf(entry("Seed Game (E).gba")), onProgress = {})

        assertTrue("expected indexRebuilt", result.indexRebuilt)
        // index.json parses again and still carries the seeded game.
        val root = JSONObject(storage.loadIndexJson()!!)
        assertTrue(root.getJSONObject("games").has("gba/seed-game"))
        // The corrupt file was quarantined beside the index, not deleted.
        val dataDir = fs.rootNode.children["crystal-nova-data"]!!
        assertTrue(
            "expected a quarantine backup",
            dataDir.children.keys.any { it.startsWith("index.json.corrupt-") },
        )
    }

    @Test fun `retry incomplete skips complete non-stale games`() = runBlocking {
        val tmp = createTempDir("scrape-retry")
        val storage = ScraperStorage(InMemoryThemeFs())
        val http = fakeHttp(setOf("https://example.com/front.png"))
        val games = listOf(entry())
        val first = job(storage, http, tmp = tmp).run(games, onProgress = {})
        assertEquals(1, first.succeeded)
        val manifest = storage.loadManifest("gba", "mario-golf")!!
        assertEquals(Completeness.COMPLETE_CASE_AND_MEDIA, manifest.completeness)

        // Second run, incomplete-only, with a provider that counts calls:
        // the complete, non-stale game must be skipped without any
        // provider or network work.
        var providerCalls = 0
        val counting = object : ArtworkProvider by frontOnlyProvider() {
            override suspend fun artworkFor(query: ScrapeQuery): Map<AssetSlot, List<ArtworkCandidate>> {
                providerCalls++
                return frontOnlyProvider().artworkFor(query)
            }
        }
        val second = job(storage, http, providers = listOf(counting), tmp = tmp)
            .run(games, onlyIncomplete = true, onProgress = {})
        assertEquals("complete game must not hit the provider again", 0, providerCalls)
        assertEquals(1, second.succeeded)
        assertEquals(0, second.partial)
        assertEquals(0, second.unmatched)
    }
}
