package io.crystalnova.manager.scraper.work

import io.crystalnova.manager.scraper.generate.ArtRenderer
import io.crystalnova.manager.scraper.generate.AndroidArtRenderer
import io.crystalnova.manager.scraper.generate.ArtworkPalette
import io.crystalnova.manager.scraper.generate.BackCoverGenerator
import io.crystalnova.manager.scraper.generate.Bitmaps
import io.crystalnova.manager.scraper.generate.MediaGenerator
import io.crystalnova.manager.scraper.generate.SpineGenerator
import io.crystalnova.manager.scraper.match.GameMatcher
import io.crystalnova.manager.scraper.match.PlatformTable
import io.crystalnova.manager.scraper.match.RegionDetector
import io.crystalnova.manager.scraper.match.TitleNormalizer
import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.Completeness
import io.crystalnova.manager.scraper.model.MatchConfidence
import io.crystalnova.manager.scraper.model.MediaKind
import io.crystalnova.manager.scraper.model.Region
import io.crystalnova.manager.scraper.model.ScrapedGame
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.scraper.provider.ArtworkCandidate
import io.crystalnova.manager.scraper.provider.ArtworkProvider
import io.crystalnova.manager.scraper.provider.MetadataProvider
import io.crystalnova.manager.scraper.provider.ScrapeQuery
import io.crystalnova.manager.scraper.scan.RomEntry
import io.crystalnova.manager.scraper.store.MediaCache
import io.crystalnova.manager.scraper.store.ScraperJson
import io.crystalnova.manager.scraper.store.ScraperStorage
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Runs the scrape pipeline over ROM entries:
 * identity -> metadata -> region -> artwork -> generated fallbacks ->
 * manifest + index. Cancellable between games; network failures are
 * recorded per game, never thrown.
 */
class ScrapeJob(
    private val storage: ScraperStorage,
    private val cache: MediaCache,
    private val artworkProviders: List<ArtworkProvider>,
    private val metadataProvider: MetadataProvider,
    private val openRomInput: (RomEntry) -> InputStream?,
    private val regionDetector: RegionDetector = RegionDetector(),
    private val artRenderer: ArtRenderer = AndroidArtRenderer,
) {
    data class JobResult(
        val succeeded: Int,
        val partial: Int,
        val failed: Int,
        val unmatched: Int,
        val cancelled: Boolean,
    )

    suspend fun run(
        games: List<RomEntry>,
        onlyPlatform: String? = null,
        onlyIncomplete: Boolean = false,
        onProgress: (ScrapeProgress) -> Unit,
    ): JobResult {
        val targets = games.filter { onlyPlatform == null || it.platformSlug == onlyPlatform }
        val index = loadIndex().toMutableMap()
        var succeeded = 0
        var partial = 0
        var failed = 0
        var unmatched = 0
        var done = 0
        var cancelled = false

        fun emit(current: GameScrapeStatus?) = onProgress(
            ScrapeProgress(targets.size, done, succeeded, partial, failed, unmatched, current, cancelled),
        )

        try {
            for (entry in targets) {
                coroutineContext.ensureActive()
                val status = GameScrapeStatus(title = entry.fileName, system = entry.platformLabel, stage = "MATCHING")
                emit(status)
                try {
                    val outcome = scrapeOne(entry, onlyIncomplete, status, ::emit)
                    when (outcome) {
                        Completeness.NO_MATCH -> unmatched++
                        Completeness.COMPLETE_CASE, Completeness.COMPLETE_CASE_AND_MEDIA -> succeeded++
                        Completeness.METADATA_ONLY -> unmatched++
                        else -> partial++
                    }
                    val game = storage.loadManifest(entry.platformSlug, TitleNormalizer.slugify(entry.fileName))
                    if (game != null) {
                        val key = "${game.platform}/${game.gameId}"
                        index[key] = ScraperJson.indexEntryToJson(game)
                        saveIndex(index)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        cancelled = true
                        break
                    }
                    failed++
                }
                done++
                emit(null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled outside the per-game guard (ensureActive, index
            // load/save): emit the honest final state before rethrowing so
            // structured concurrency still observes the cancellation.
            cancelled = true
            emit(null)
            throw e
        }
        emit(null)
        return JobResult(succeeded, partial, failed, unmatched, cancelled)
    }

    /**
     * Scrapes one game. Returns its completeness. USER assets from a
     * previous manifest are always preserved.
     */
    private suspend fun scrapeOne(
        entry: RomEntry,
        onlyIncomplete: Boolean,
        status: GameScrapeStatus,
        emit: (GameScrapeStatus?) -> Unit,
    ): Completeness {
        coroutineContext.ensureActive()
        val gameId = TitleNormalizer.slugify(entry.fileName)
        val existing = storage.loadManifest(entry.platformSlug, gameId)
        val stale = existing != null &&
            (existing.fileSize != entry.size || existing.lastModified != entry.lastModified)
        if (onlyIncomplete && existing != null && !stale &&
            (existing.completeness == Completeness.COMPLETE_CASE ||
                existing.completeness == Completeness.COMPLETE_CASE_AND_MEDIA)
        ) {
            return existing.completeness
        }

        // --- metadata (Pegasus files first, filename fallback) ---
        val baseTitle = TitleNormalizer.normalize(entry.fileName)
            .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        val metaQuery = ScrapeQuery(entry.platformSlug, baseTitle, entry.relativePath, Region.UNKNOWN)
        val meta = try { metadataProvider.lookup(metaQuery) } catch (_: Exception) { null }
        val title = meta?.title?.ifBlank { null } ?: existing?.title ?: baseTitle

        // --- region: cheap cartridge headers, then filename tags ---
        val region = detectRegion(entry)

        val query = ScrapeQuery(entry.platformSlug, title, entry.fileName, region)
        val assets = existing?.assets?.toMutableMap() ?: mutableMapOf()

        var live = status
        fun updateSlot(slot: AssetSlot, state: SlotState) {
            live = live.copy(
                front = if (slot == AssetSlot.BOX_FRONT) state else live.front,
                spine = if (slot == AssetSlot.BOX_SPINE) state else live.spine,
                back = if (slot == AssetSlot.BOX_BACK) state else live.back,
                media = if (slot == AssetSlot.PHYSICAL_MEDIA) state else live.media,
                stage = slot.name,
            )
            emit(live)
        }

        fun putAsset(slot: AssetSlot, provenance: AssetProvenance, bytes: ByteArray, state: SlotState) {
            when (storage.saveAsset(entry.platformSlug, gameId, slot, provenance, bytes, assets[slot])) {
                is ScraperStorage.SaveResult.Saved -> {
                    assets[slot] = provenance
                    updateSlot(slot, state)
                }
                is ScraperStorage.SaveResult.Kept -> updateSlot(slot, SlotState.SKIPPED)
                is ScraperStorage.SaveResult.Failed -> updateSlot(slot, SlotState.FAILED)
            }
        }

        // --- artwork providers (Libretro: front/screenshot/logo) ---
        var providerId: String? = existing?.provider
        var providerGameId: String? = existing?.providerGameId
        var confidence: MatchConfidence = existing?.confidence ?: MatchConfidence.None
        var frontFile: java.io.File? = null
        var logoFile: java.io.File? = null
        var screenshotFile: java.io.File? = null

        for (provider in artworkProviders) {
            coroutineContext.ensureActive()
            val candidates: Map<AssetSlot, List<ArtworkCandidate>> = try {
                provider.artworkFor(query)
            } catch (_: Exception) { emptyMap() }
            for ((slot, urls) in candidates) {
                if (slot !in setOf(AssetSlot.BOX_FRONT, AssetSlot.SCREENSHOT, AssetSlot.CLEAR_LOGO)) continue
                if (assets[slot]?.sourceType == SourceType.REAL) continue
                updateSlot(slot, SlotState.WORKING)
                val hit = firstDownload(urls)
                if (hit != null) {
                    val bytes = hit.first.readBytes()
                    val prov = AssetProvenance(
                        sourceType = SourceType.REAL,
                        provider = provider.id,
                        providerGameId = provider.id + ":" + hit.second.url.substringAfterLast('/'),
                        region = region.takeIf { it != Region.UNKNOWN },
                        sourceUrl = hit.second.url,
                        localPath = storage.assetPath(entry.platformSlug, gameId, slot),
                    )
                    putAsset(slot, prov, bytes, SlotState.REAL)
                    if (prov.sourceType == SourceType.REAL) {
                        val matchConf = providerConfidence(query.title, hit.second.url)
                        if (matchConf != MatchConfidence.None) {
                            providerId = provider.id
                            providerGameId = hit.second.url.substringAfterLast('/')
                            confidence = matchConf
                        }
                        // Weak fuzzy (None): the bytes are kept with full
                        // provenance in the asset record, but the match is
                        // NEVER auto-accepted — no providerId/providerGameId/
                        // confidence claim. providerId stays null so the loop
                        // continues and a stronger provider can still match;
                        // a game with no accepted match surfaces as manual.
                    }
                    when (slot) {
                        AssetSlot.BOX_FRONT -> frontFile = hit.first
                        AssetSlot.CLEAR_LOGO -> logoFile = hit.first
                        AssetSlot.SCREENSHOT -> screenshotFile = hit.first
                        else -> {}
                    }
                } else {
                    updateSlot(slot, SlotState.FAILED)
                }
            }
            if (providerId != null) break
        }

        // --- generated fallbacks (never overwrite REAL/USER) ---
        val platform = PlatformTable.bySlug(entry.platformSlug)
        val platformLabel = platform?.displayName ?: entry.platformLabel
        val frontBitmap = frontFile?.let { Bitmaps.decodeCapped(it, 256) }
        val sampled = frontBitmap?.let {
            ArtworkPalette.sampleFromPixels(Bitmaps.pixels(it), it.width, it.height)
        }

        // Spine.
        if (!assets.containsKey(AssetSlot.BOX_SPINE)) {
            updateSlot(AssetSlot.BOX_SPINE, SlotState.WORKING)
            try {
                val spec = SpineGenerator.layout(title, entry.platformSlug, platformLabel, region, sampled)
                val bytes = artRenderer.renderSpine(spec)
                putAsset(
                    AssetSlot.BOX_SPINE,
                    AssetProvenance(SourceType.GENERATED, "crystal",
                        localPath = storage.assetPath(entry.platformSlug, gameId, AssetSlot.BOX_SPINE),
                        generatedBy = SpineGenerator.GENERATOR_ID),
                    bytes, SlotState.GENERATED,
                )
            } catch (_: Exception) {
                updateSlot(AssetSlot.BOX_SPINE, SlotState.FAILED)
            }
        }
        // Back.
        if (!assets.containsKey(AssetSlot.BOX_BACK)) {
            updateSlot(AssetSlot.BOX_BACK, SlotState.WORKING)
            try {
                val frontAspect = frontBitmap?.let { it.width.toFloat() / it.height }
                val spec = BackCoverGenerator.layout(
                    title = title,
                    description = meta?.description,
                    developer = meta?.developer,
                    publisher = meta?.publisher,
                    releaseYear = meta?.releaseYear,
                    genre = meta?.genre,
                    players = meta?.players,
                    platformLabel = platformLabel,
                    platformSlug = entry.platformSlug,
                    hasScreenshot = screenshotFile != null,
                    frontAspect = frontAspect,
                    sampled = sampled,
                )
                val shotBitmap = screenshotFile?.let { Bitmaps.decodeCapped(it, 512) }
                val bytes = artRenderer.renderBack(spec, shotBitmap)
                shotBitmap?.recycle()
                putAsset(
                    AssetSlot.BOX_BACK,
                    AssetProvenance(SourceType.GENERATED, "crystal",
                        localPath = storage.assetPath(entry.platformSlug, gameId, AssetSlot.BOX_BACK),
                        generatedBy = BackCoverGenerator.GENERATOR_ID),
                    bytes, SlotState.GENERATED,
                )
            } catch (_: Exception) {
                updateSlot(AssetSlot.BOX_BACK, SlotState.FAILED)
            }
        }
        // Physical media.
        if (!assets.containsKey(AssetSlot.PHYSICAL_MEDIA)) {
            updateSlot(AssetSlot.PHYSICAL_MEDIA, SlotState.WORKING)
            try {
                val kind = platform?.mediaKind ?: MediaKind.UNKNOWN
                val logoBitmap = logoFile?.let { Bitmaps.decodeCapped(it, 256) }
                val spec = MediaGenerator.layout(
                    kind = kind, title = title,
                    platformSlug = entry.platformSlug, platformLabel = platformLabel,
                    hasLogo = logoBitmap != null, sampled = sampled,
                )
                val bytes = artRenderer.renderMedia(spec, logoBitmap)
                logoBitmap?.recycle()
                putAsset(
                    AssetSlot.PHYSICAL_MEDIA,
                    AssetProvenance(SourceType.GENERATED, "crystal",
                        localPath = storage.assetPath(entry.platformSlug, gameId, AssetSlot.PHYSICAL_MEDIA),
                        generatedBy = MediaGenerator.GENERATOR_ID),
                    bytes, SlotState.GENERATED,
                )
            } catch (_: Exception) {
                updateSlot(AssetSlot.PHYSICAL_MEDIA, SlotState.FAILED)
            }
        }
        frontBitmap?.recycle()

        val game = ScrapedGame(
            platform = entry.platformSlug,
            gameId = gameId,
            romRelativePath = entry.relativePath,
            fileSize = entry.size,
            lastModified = entry.lastModified,
            title = title,
            description = meta?.description ?: existing?.description,
            developer = meta?.developer ?: existing?.developer,
            publisher = meta?.publisher ?: existing?.publisher,
            releaseYear = meta?.releaseYear ?: existing?.releaseYear,
            genre = meta?.genre ?: existing?.genre,
            players = meta?.players ?: existing?.players,
            region = region,
            provider = providerId,
            providerGameId = providerGameId,
            confidence = confidence,
            assets = assets,
        )
        storage.saveManifest(game)
        return game.completeness
    }

    private fun detectRegion(entry: RomEntry): Region {
        val headerPlatforms = setOf("snes", "n64", "gb", "gbc", "gba", "genesis", "nds", "n3ds")
        if (entry.platformSlug in headerPlatforms) {
            try {
                val reader = RegionDetector.HeaderReader { offset, length ->
                    openRomInput(entry)?.use { stream ->
                        var toSkip = offset.toLong()
                        while (toSkip > 0) {
                            val s = stream.skip(toSkip)
                            if (s <= 0) return@use null
                            toSkip -= s
                        }
                        val buf = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = stream.read(buf, read, length - read)
                            if (n <= 0) break
                            read += n
                        }
                        if (read == 0) null else buf.copyOf(read)
                    }
                }
                val fromHeader = regionDetector.fromHeader(entry.platformSlug, reader)
                if (fromHeader != Region.UNKNOWN) return fromHeader
            } catch (_: Exception) { /* fall through to filename tags */ }
        }
        return regionDetector.fromFileName(entry.fileName)
    }

    /** First candidate URL that downloads and looks like an image. */
    private fun firstDownload(candidates: List<ArtworkCandidate>): Pair<java.io.File, ArtworkCandidate>? {
        for (c in candidates) {
            val file = try { cache.fetch(c.url) } catch (_: Exception) { null } ?: continue
            if (looksLikeImage(file)) return file to c
        }
        return null
    }

    private fun looksLikeImage(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        val head = ByteArray(8)
        try {
            file.inputStream().use { it.read(head) }
        } catch (_: Exception) { return false }
        val png = head[0] == 0x89.toByte() && head[1] == 0x50.toByte()
        val jpg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        val webp = head[0] == 0x52.toByte() && head[1] == 0x49.toByte() // "RIFF"
        val gif = head[0] == 0x47.toByte() && head[1] == 0x49.toByte() // "GI"
        return png || jpg || webp || gif
    }

    private fun providerConfidence(title: String, url: String): MatchConfidence {
        val name = url.substringAfterLast('/').substringBeforeLast('.')
        return GameMatcher.matchConfidence(title, name)
    }
    private fun loadIndex(): Map<String, JSONObject> {
        val text = storage.loadIndexJson() ?: return emptyMap()
        return try {
            val root = JSONObject(text)
            val games = root.optJSONObject("games") ?: return emptyMap()
            games.keys().asSequence().associateWith { games.getJSONObject(it) }
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveIndex(index: Map<String, JSONObject>) {
        val root = JSONObject()
        root.put("version", 1)
        val games = JSONObject()
        for ((k, v) in index) games.put(k, v)
        root.put("games", games)
        storage.saveIndexJson(root.toString())
    }
}
