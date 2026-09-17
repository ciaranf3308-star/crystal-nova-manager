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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /**
     * The whole pipeline does blocking I/O (LibraryScanner access,
     * HttpURLConnection downloads, SAF reads/writes, image processing,
     * manifest/index writes). It must never run on the caller's
     * dispatcher (MainScope on device): injectable seam, Dispatchers.IO
     * in production.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class JobResult(
        val succeeded: Int,
        val partial: Int,
        val failed: Int,
        val unmatched: Int,
        val cancelled: Boolean,
        /** True when index.json was malformed and rebuilt from manifests. */
        val indexRebuilt: Boolean = false,
        /** File names dropped by the duplicate-ROM pre-pass. */
        val skippedDuplicates: List<String> = emptyList(),
        /**
         * True when a SAF SecurityException aborted the run: the games
         * folder grant was revoked mid-scrape. The caller should send the
         * user back to the folder picker.
         */
        val folderAccessLost: Boolean = false,
    )

    suspend fun run(
        games: List<RomEntry>,
        onlyPlatform: String? = null,
        onlyIncomplete: Boolean = false,
        onProgress: (ScrapeProgress) -> Unit,
    ): JobResult = withContext(ioDispatcher) {
        val (deduped, skippedDuplicates) = dedupeEntries(
            games.filter { onlyPlatform == null || it.platformSlug == onlyPlatform },
        )
        val targets = deduped
        val index = mutableMapOf<String, JSONObject>()
        val indexRebuilt: Boolean
        // NOTE: loadIndex()/rebuildIndexFromManifests() may throw
        // SecurityException on a revoked grant. That propagates out of
        // run() before the try/finally below, so the finally never
        // persists an index that was never loaded (an empty map would
        // blank the last good index). The caller translates it into the
        // "folder access lost" reselection state.
        when (val indexLoad = loadIndex()) {
            is IndexLoad.Ok -> {
                index.putAll(indexLoad.entries)
                indexRebuilt = false
            }
            is IndexLoad.Corrupt -> {
                // A malformed index.json is quarantined, never trusted:
                // rebuild the in-memory index from manifests so the next
                // write cannot prune entries the run didn't cover.
                index.putAll(rebuildIndexFromManifests())
                indexRebuilt = true
            }
        }
        var succeeded = 0
        var partial = 0
        var failed = 0
        var unmatched = 0
        var done = 0
        var cancelled = false
        var folderAccessLost = false
        /**
         * Non-cancellation failure already in flight when the finally
         * runs (a manifest [ScrapeStorageWriteException]): an index-save
         * failure on top of it must not replace it — the first failure
         * names the game and stage.
         */
        var runFailure: Throwable? = null

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
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        // Structured concurrency: a cancelled scrape must
                        // propagate, never be downgraded to a per-game
                        // failure. The outer catch marks it cancelled, the
                        // finally still saves the index, and the caller
                        // observes the cancellation.
                        throw e
                    }
                    if (e is ScrapeStorageWriteException) {
                        // v22: a manifest write failed — storage is broken.
                        // Never a per-game "failed" count, never FINISHED:
                        // abort the run and let the caller surface it.
                        throw e
                    }
                    if (isRevocation(e)) {
                        folderAccessLost = true
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
            runFailure = e
            emit(null)
            throw e
        } catch (e: ScrapeStorageWriteException) {
            // Manifest write failure from the per-game guard: abort the
            // run. Recorded so the finally cannot replace it with an
            // index-save failure.
            runFailure = e
            throw e
        } finally {
            // One index write per run — including on cancellation.
            // Manifests are the per-game source of truth, so a kill loses
            // nothing but this derived file, which rebuilds from manifests
            // next pass.
            // (Previously this rewrote the whole file over SAF after every
            // game: O(N^2) bytes for large libraries.)
            //
            // v22: a failed index write is never silent and never becomes
            // "0 games". The on-disk index is untouched (atomic
            // backup/restore in the write path), so the previous good
            // index stays safe; the run aborts instead of reporting
            // FINISHED. On cancellation the write is best-effort and must
            // never mask the cancellation.
            if (cancelled) {
                try {
                    saveIndex(index)
                } catch (_: Exception) {
                    // The run is cancelled, not finished: manifests already
                    // persisted per game are usable, and the index rebuilds
                    // from them next pass.
                }
            } else {
                try {
                    saveIndex(index)
                } catch (e: ScrapeStorageWriteException) {
                    if (runFailure == null) throw e
                    // A manifest failure is already in flight with the
                    // game and stage attached — keep it.
                }
            }
        }
        emit(null)
        JobResult(
            succeeded, partial, failed, unmatched, cancelled,
            indexRebuilt, skippedDuplicates, folderAccessLost,
        )
    }

    /**
     * True when [e] (or any cause in its chain) is a SAF SecurityException:
     * the persisted games-folder grant was revoked. Callers translate this
     * into the "folder access lost" reselection state instead of a generic
     * failure, because no retry can succeed until the user re-picks.
     */
    private fun isRevocation(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SecurityException) return true
            t = t.cause
        }
        return false
    }

    /**
     * Collapses entries that slug to the same (platform, gameId): keeps one
     * representative per game — preferring .m3u/.cue/.gdi over playable
     * images over raw .bin/.img/.raw track dumps, lowest disc number on
     * ties, lexical relative path as the final deterministic tie-break —
     * and reports the dropped file names. Without this, multi-disc pairs
     * (two .cue files, no .m3u) and punctuation variants ("A&B" vs "A B")
     * silently overwrite each other's manifest/assets, last writer wins.
     */
    private fun dedupeEntries(games: List<RomEntry>): Pair<List<RomEntry>, List<String>> {
        val skipped = mutableListOf<String>()
        val kept = games.groupBy { it.platformSlug to TitleNormalizer.slugify(it.fileName) }
            .values
            .map { group ->
                if (group.size == 1) {
                    group[0]
                } else {
                    val best = group.minWithOrNull(dedupeOrder())!!
                    skipped += group.filter { it !== best }.map { it.fileName }
                    best
                }
            }
        return kept to skipped
    }

    private fun dedupeOrder(): Comparator<RomEntry> {
        fun extRank(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
            "m3u" -> 0
            "cue" -> 1
            "gdi" -> 2
            // Raw track dumps are never the playable image: they lose to
            // any sibling image format, including plain extension-less
            // "everything else".
            "bin", "img", "raw" -> 4
            else -> 3
        }
        fun discNumber(name: String): Int =
            Regex("""(?i)[(\[]?\s*disc\s*(\d+)""").find(name)
                ?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        // Final tie-break is the full relative path (not the bare file
        // name): deterministic across runs and directory layouts.
        return compareBy({ extRank(it.fileName) }, { discNumber(it.fileName) }, { it.relativePath })
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
        val meta = try {
            metadataProvider.lookup(metaQuery)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A revoked SAF grant must abort the run, not masquerade as a
            // per-game metadata miss for hundreds of games.
            if (isRevocation(e)) throw e
            null
        }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRevocation(e)) throw e
                emptyMap()
            }
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
        // v22: a failed manifest write is NOT success. If SAF cannot
        // write, the game must not count as processed and the run must
        // not finish "successfully" with 0 persisted — abort loudly so
        // the caller surfaces SCRAPE STORAGE WRITE FAILED.
        if (!storage.saveManifest(game)) {
            throw ScrapeStorageWriteException(
                ScrapeStorageWriteException.MANIFEST_WRITE_FAILED,
                platform = entry.platformSlug,
                gameId = gameId,
                stage = "MANIFEST",
            )
        }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { /* fall through to filename tags */ }
        }
        return regionDetector.fromFileName(entry.fileName)
    }

    /** First candidate URL that downloads and looks like an image. */
    private suspend fun firstDownload(candidates: List<ArtworkCandidate>): Pair<java.io.File, ArtworkCandidate>? {
        for (c in candidates) {
            val file = try {
                cache.fetch(c.url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancel mid-download must abort the job, not skip to
                // the next candidate URL.
                throw e
            } catch (_: Exception) { null } ?: continue
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
    private sealed interface IndexLoad {
        data class Ok(val entries: Map<String, JSONObject>) : IndexLoad
        /** The on-disk index.json was malformed and has been quarantined. */
        data object Corrupt : IndexLoad
    }

    private fun loadIndex(): IndexLoad {
        val bytes = storage.loadIndexBytes() ?: return IndexLoad.Ok(emptyMap())
        val text = bytes.toString(Charsets.UTF_8)
        return try {
            val root = JSONObject(text)
            val games = root.optJSONObject("games") ?: return IndexLoad.Ok(emptyMap())
            IndexLoad.Ok(games.keys().asSequence().associateWith { games.getJSONObject(it) })
        } catch (_: Exception) {
            // Malformed index: quarantine the exact original bytes (never
            // parse them again) and let the caller rebuild from manifests.
            storage.quarantineIndexBackup(bytes)
            IndexLoad.Corrupt
        }
    }

    /** Rebuilds the in-memory index from stored manifests. Never throws. */
    private fun rebuildIndexFromManifests(): MutableMap<String, JSONObject> {
        val out = mutableMapOf<String, JSONObject>()
        // A revoked grant mid-rebuild must abort, not rebuild an empty
        // index that the finally would then persist over the good one.
        val stored = try {
            storage.listGames()
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) { emptyList() }
        for ((platform, gameId) in stored) {
            val game = try {
                storage.loadManifest(platform, gameId)
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) { null } ?: continue
            try {
                out["$platform/$gameId"] = ScraperJson.indexEntryToJson(game)
            } catch (_: Exception) { /* skip one bad manifest */ }
        }
        return out
    }

    /**
     * Serializes and persists the run's index. v22: a failed write
     * throws [ScrapeStorageWriteException] — the run must NOT report
     * FINISHED and must NOT present "0 games". The previous good
     * index.json is untouched: [ScraperStorage.writeAtomically] restores
     * the original on any failure, so this throw can never blank it.
     */
    private fun saveIndex(index: Map<String, JSONObject>) {
        val root = JSONObject()
        root.put("version", 1)
        val games = JSONObject()
        for ((k, v) in index) games.put(k, v)
        root.put("games", games)
        if (!storage.saveIndexJson(root.toString())) {
            throw ScrapeStorageWriteException(
                ScrapeStorageWriteException.INDEX_WRITE_FAILED,
                stage = "INDEX",
            )
        }
    }
}
