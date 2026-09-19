package io.crystalnova.manager.scraper.esde

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.ScrapedGame
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.scraper.scan.RomEntry
import io.crystalnova.manager.scraper.store.ScraperJson
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.storage.StorageLocations
import java.security.MessageDigest
import org.json.JSONObject

/**
 * SAF-backed ES-DE media import. The export is opened READ-ONLY; all
 * writes go through [ScraperStorage.saveAsset] into the existing media
 * tree, honoring the standing replacement rules (USER wins, REAL from
 * the export may replace GENERATED, other REAL art is never clobbered).
 *
 * Call [prescan] first and show the user [EsdeImport.ImportPlan.reportText].
 * Only then call [execute]. Re-running after an import only processes
 * new/changed/missing assets (idempotent + incremental).
 */
class EsdeImportRunner(
    private val context: Context,
    private val locations: StorageLocations,
    private val storage: ScraperStorage,
) {

    sealed interface PrescanResult {
        data class Ready(val plan: EsdeImport.ImportPlan) : PrescanResult
        data class Failed(val reason: String) : PrescanResult
    }

    data class SystemValidation(
        val platform: String,
        val label: String,
        val ok: Boolean,
        val detail: String,
    )

    data class ImportResult(
        val written: Int,
        val bytes: Long,
        val skipped: Int,
        val failures: List<String>,
        val validations: List<SystemValidation>,
        /** Re-scan after the import: proves idempotency (0 remaining). */
        val rescanRemaining: Int,
    )

    /** Systems the user asked to validate after the import. */
    private val validationSystems = listOf(
        "ps2" to "PS2",
        "psx" to "PS1",
        "n64" to "N64",
        "gamecube" to "GameCube",
        "gba" to "GBA",
    )

    companion object {
        /** Refuse absurdly large files instead of OOMing. */
        const val MAX_FILE_BYTES = 64L * 1024L * 1024L
        /** Cap a gamelist.xml read; larger files are treated as absent. */
        const val MAX_GAMELIST_BYTES = 16L * 1024L * 1024L
    }

    // ------------------------------------------------------------------
    // Pre-scan.
    //
    // Matches the ES-DE export against the authoritative ROM library —
    // the ROM scan's [RomEntry] list, NOT the scraper's index.json
    // (which only gains entries once artwork is scraped and therefore
    // can never be a pre-import prerequisite). The caller
    // (ScraperManager.runEsdeImportPrescan) guarantees [roms] is a
    // fresh-or-cached scan before calling.
    // ------------------------------------------------------------------

    fun prescan(roms: List<RomEntry>): PrescanResult {
        val exportRoot = openExportRoot()
            ?: return PrescanResult.Failed(
                "Cannot read the ES-DE export folder — it is not picked, " +
                    "or the grant was revoked. Settings → ES-DE EXPORT FOLDER → " +
                    "pick the export folder again.",
            )

        val mediaDir = exportRoot.findFile("media")
        if (mediaDir == null || !mediaDir.isDirectory) {
            return PrescanResult.Failed(
                "The picked folder does not look like an ES-DE export: " +
                    "no media/ directory at its root.",
            )
        }

        // One listing pass over the media tree: export-relative lowercase
        // path -> file. ~2k files in a typical export; fine for a manual
        // action.
        val exportIndex = mutableMapOf<String, DocumentFile>()
        indexTree(mediaDir, "media", 0, exportIndex)

        val games = roms.map(EsdeImport::romGameFromEntry)
        if (games.isEmpty()) {
            return PrescanResult.Failed(
                "The ROM library is empty — nothing to match against. " +
                    "Check Settings → ROM LIBRARY: the folder must grant " +
                    "access and contain game files.",
            )
        }

        val slotPlans = mutableListOf<EsdeImport.SlotPlan>()
        val matchedIds = mutableSetOf<String>()
        val gamelistCache = mutableMapOf<String, Map<String, EsdeImport.GamelistMedia>?>()

        for (game in games) {
            val esdeSys = EsdeImport.esdeSystemDir(game.platform)
            val base = EsdeImport.romBasename(game.fileName)
            val foundBySlot = mutableMapOf<AssetSlot, EsdeImport.FoundAsset>()

            // Primary: ES-DE names media after the ROM basename.
            if (base.isNotEmpty()) {
                for ((slot, dirs) in EsdeImport.SLOT_DIRS) {
                    val found = findPrimary(exportIndex, esdeSys, dirs, base, slot)
                    if (found != null) foundBySlot[slot] = found
                }
            }

            // Fallback: gamelist.xml may name media explicitly.
            if (foundBySlot.isEmpty() && base.isNotEmpty()) {
                val mediaByRom = gamelistCache.getOrPut(esdeSys) {
                    loadGamelist(exportIndex, esdeSys)
                } ?: emptyMap()
                val gm = mediaByRom[base.lowercase()]
                if (gm != null) {
                    findGamelistAsset(exportIndex, esdeSys, AssetSlot.BOX_FRONT, gm.thumbnail)
                        ?.let { foundBySlot[AssetSlot.BOX_FRONT] = it }
                    findGamelistAsset(exportIndex, esdeSys, AssetSlot.SCREENSHOT, gm.image)
                        ?.let { foundBySlot[AssetSlot.SCREENSHOT] = it }
                }
            }

            val manifest = try {
                storage.loadManifest(game.platform, game.gameId)
            } catch (_: Exception) { null }

            for ((slot, _) in EsdeImport.SLOT_DIRS) {
                val found = foundBySlot[slot]
                if (found != null) matchedIds.add("${game.platform}/${game.gameId}")
                val existing = manifest?.assets?.get(slot)
                val existingLength = if (existing != null) {
                    try { storage.assetLength(game.platform, game.gameId, slot) }
                    catch (_: Exception) { null }
                } else null
                slotPlans.add(
                    EsdeImport.SlotPlan(
                        game = game,
                        slot = slot,
                        found = found,
                        decision = EsdeImport.decide(found, existing, existingLength),
                    ),
                )
            }
        }

        val matchedSet = matchedIds.toSet()
        val unmatched = games.filter { "${it.platform}/${it.gameId}" !in matchedSet }
        return PrescanResult.Ready(
            EsdeImport.ImportPlan(
                games = games,
                slotPlans = slotPlans,
                matchedGameIds = matchedSet,
                unmatchedGames = unmatched,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Execute.
    // ------------------------------------------------------------------

    fun execute(
        plan: EsdeImport.ImportPlan,
        roms: List<RomEntry>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportResult {
        val exportRoot = openExportRoot()
            ?: return ImportResult(0, 0, 0, listOf("Lost access to the ES-DE export folder."), emptyList(), -1)
        val toWrite = plan.toWrite
        val failures = mutableListOf<String>()
        var written = 0
        var bytes = 0L
        // (platform, gameId) -> manifest being built up.
        val manifests = mutableMapOf<String, io.crystalnova.manager.scraper.model.ScrapedGame>()

        // Index repair: load existing manifests for all matched games.
        // If a previous import copied files but failed to update index.json
        // (e.g. u23), the re-run will SKIP all files (already exist) and
        // the index would never be fixed. Pre-loading ensures the index
        // gets repaired even when nothing is copied.
        for (gameId in plan.matchedGameIds) {
            val parts = gameId.split("/", limit = 2)
            if (parts.size != 2) continue
            try {
                val m = storage.loadManifest(parts[0], parts[1])
                if (m != null) manifests[gameId] = m
            } catch (_: Exception) { }
        }

        toWrite.forEachIndexed { i, sp ->
            try {
                val found = sp.found ?: return@forEachIndexed
                val doc = resolveExportDoc(exportRoot, found.relativePath)
                    ?: throw IllegalStateException("ES-DE file vanished: ${found.relativePath}")
                if (doc.length() > MAX_FILE_BYTES) {
                    throw IllegalStateException("File too large, skipped: ${found.relativePath}")
                }
                val data = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read: ${found.relativePath}")
                val digest = MessageDigest.getInstance("SHA-256")
                val sha = digest.digest(data).joinToString("") { "%02x".format(it) }

                val key = "${sp.game.platform}/${sp.game.gameId}"
                val manifest = manifests.getOrPut(key) {
                    storage.loadManifest(sp.game.platform, sp.game.gameId)
                        // ES-DE import: the game was never scraped, so no
                        // manifest exists. Create a minimal one rather than
                        // failing — the artwork is what matters.
                        ?: ScrapedGame(
                            platform = sp.game.platform,
                            gameId = sp.game.gameId,
                            romRelativePath = "",
                            title = sp.game.title,
                        )
                }
                val provenance = AssetProvenance(
                    sourceType = SourceType.REAL,
                    provider = EsdeImport.PROVIDER_ID,
                    sourceUrl = "esde:${found.relativePath}",
                    localPath = storage.assetPath(sp.game.platform, sp.game.gameId, sp.slot),
                    sha256 = sha,
                )
                when (val r = storage.saveAsset(
                    sp.game.platform, sp.game.gameId, sp.slot, provenance, data,
                    manifest.assets[sp.slot],
                )) {
                    is ScraperStorage.SaveResult.Saved -> {
                        val updated = manifest.assets.toMutableMap()
                        updated[sp.slot] = provenance
                        manifests[key] = manifest.copy(assets = updated)
                        written++
                        bytes += data.size
                    }
                    is ScraperStorage.SaveResult.Kept ->
                        failures.add("${sp.game.title} [${sp.slot.name}]: kept (${r.reason})")
                    is ScraperStorage.SaveResult.Failed ->
                        failures.add("${sp.game.title} [${sp.slot.name}]: ${r.reason}")
                }
            } catch (e: Exception) {
                if (e is SecurityException) throw e
                failures.add("${sp.game.title} [${sp.slot.name}]: ${e.message}")
            }
            onProgress(i + 1, toWrite.size)
        }

        // Persist manifests, then refresh the affected index entries —
        // the same path the scraper uses, so the theme resolves the new
        // art with zero theme changes.
        val indexFailures = persistManifestsAndIndex(manifests)
        failures.addAll(indexFailures)

        val validations = validationSystems.map { (platform, label) ->
            validateSystem(platform, label)
        }

        val rescanRemaining = when (val r = prescan(roms)) {
            is PrescanResult.Ready -> r.plan.toWrite.size
            is PrescanResult.Failed -> -1
        }

        return ImportResult(
            written = written,
            bytes = bytes,
            skipped = plan.skipCount(),
            failures = failures,
            validations = validations,
            rescanRemaining = rescanRemaining,
        )
    }

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    private fun openExportRoot(): DocumentFile? {
        val uriString = locations.esdeTreeUri() ?: return null
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
            if (root != null && root.canRead()) root else null
        } catch (_: Exception) {
            null
        }
    }

    private fun indexTree(
        dir: DocumentFile,
        rel: String,
        depth: Int,
        out: MutableMap<String, DocumentFile>,
    ) {
        if (depth > 5) return
        val children = try { dir.listFiles() } catch (_: Exception) { return }
        for (f in children) {
            val name = f.name ?: continue
            val r = "$rel/$name"
            out[r.lowercase()] = f
            if (f.isDirectory) indexTree(f, r, depth + 1, out)
        }
    }

    private fun findPrimary(
        exportIndex: Map<String, DocumentFile>,
        esdeSys: String,
        dirs: List<String>,
        base: String,
        slot: AssetSlot,
    ): EsdeImport.FoundAsset? {
        val baseLower = base.lowercase()
        for (dir in dirs) {
            for (ext in EsdeImport.IMAGE_EXTENSIONS) {
                val key = "media/$esdeSys/$dir/$baseLower.$ext"
                val doc = exportIndex[key]
                if (doc != null && doc.isFile) {
                    val rel = "media/$esdeSys/$dir/${doc.name}"
                    return EsdeImport.FoundAsset(slot, rel, doc.name ?: "", doc.length())
                }
            }
        }
        return null
    }

    private fun loadGamelist(
        exportIndex: Map<String, DocumentFile>,
        esdeSys: String,
    ): Map<String, EsdeImport.GamelistMedia>? {
        val doc = exportIndex["gamelists/$esdeSys/gamelist.xml"] ?: return null
        if (!doc.isFile || doc.length() > MAX_GAMELIST_BYTES) return null
        val xml = try {
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) { null } ?: return null
        return EsdeImport.parseGamelist(xml)
    }

    private fun findGamelistAsset(
        exportIndex: Map<String, DocumentFile>,
        esdeSys: String,
        slot: AssetSlot,
        ref: String?,
    ): EsdeImport.FoundAsset? {
        if (ref.isNullOrBlank()) return null
        for (candidate in EsdeImport.resolveGamelistMediaCandidates(esdeSys, ref)) {
            val doc = exportIndex[candidate.lowercase()]
            if (doc != null && doc.isFile) {
                return EsdeImport.FoundAsset(slot, candidate, doc.name ?: "", doc.length())
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Shared helpers.
    // ------------------------------------------------------------------

    private fun resolveExportDoc(root: DocumentFile, relativePath: String): DocumentFile? {
        var node = root
        for (part in relativePath.split('/')) {
            node = node.findFile(part) ?: return null
        }
        return if (node.isFile) node else null
    }

    private fun persistManifestsAndIndex(
        manifests: Map<String, io.crystalnova.manager.scraper.model.ScrapedGame>,
    ): List<String> {
        val failures = mutableListOf<String>()
        if (manifests.isEmpty()) return failures
        for ((key, game) in manifests) {
            try {
                if (!storage.saveManifest(game)) failures.add("$key: manifest write failed")
            } catch (e: Exception) {
                if (e is SecurityException) throw e
                failures.add("$key: manifest write failed (${e.message})")
            }
        }
        // Refresh the touched index entries through the same serializer
        // the scraper uses.
        try {
            val text = storage.loadIndexJson() ?: throw IllegalStateException("index.json unreadable")
            val root = JSONObject(text)
            val games = root.optJSONObject("games") ?: JSONObject()
            for ((key, game) in manifests) {
                // Add new entries, not just update existing ones — ES-DE
                // imports create manifests for games the scraper never saw.
                games.put(key, ScraperJson.indexEntryToJson(game))
            }
            root.put("games", games)
            if (!storage.saveIndexJson(root.toString())) {
                failures.add("index.json write failed")
            }
        } catch (e: Exception) {
            if (e is SecurityException) throw e
            failures.add("index.json update failed (${e.message})")
        }
        return failures
    }

    /**
     * Post-import validation for one system: counts games whose cover is
     * REAL esde-import art in the manifest, listed in the index entry,
     * AND present as a file in the media tree — i.e. resolvable through
     * the existing index/manifest path the theme reads.
     */
    private fun validateSystem(platform: String, label: String): SystemValidation {
        return try {
            val text = storage.loadIndexJson()
                ?: return SystemValidation(platform, label, false, "index.json unreadable")
            val games = JSONObject(text).optJSONObject("games") ?: JSONObject()
            var ok = 0
            for (key in games.keys()) {
                val o = games.optJSONObject(key) ?: continue
                if (o.optString("platform", "") != platform) continue
                val assets = o.optJSONArray("assets") ?: continue
                var hasCover = false
                for (i in 0 until assets.length()) {
                    if (assets.optString(i) == "front") { hasCover = true; break }
                }
                if (!hasCover) continue
                val gameId = o.optString("gameId", "")
                val manifest = try { storage.loadManifest(platform, gameId) } catch (_: Exception) { null }
                val prov = manifest?.assets?.get(AssetSlot.BOX_FRONT)
                if (prov != null && prov.sourceType == SourceType.REAL &&
                    prov.provider == EsdeImport.PROVIDER_ID &&
                    storage.assetPresent(platform, gameId, AssetSlot.BOX_FRONT)
                ) {
                    ok++
                }
            }
            SystemValidation(
                platform, label, ok > 0,
                if (ok > 0) "$ok game(s) with imported covers resolve via index/manifest" else "no imported covers yet",
            )
        } catch (e: Exception) {
            if (e is SecurityException) throw e
            SystemValidation(platform, label, false, "validation failed (${e.message})")
        }
    }
}
