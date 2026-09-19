package io.crystalnova.manager.scraper.store

import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.ScrapedGame
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs
import org.json.JSONObject

/**
 * SAF storage for the persistent sibling directory `crystal-nova-data/`.
 *
 * The U1 theme updater never touches this tree; the scraper never touches
 * the theme directories. All writes are atomic (temp file + rename) and
 * obey the replacement rules:
 * - USER assets are never overwritten automatically.
 * - REAL assets are never replaced by GENERATED ones.
 * - REAL assets MAY replace GENERATED fallbacks.
 *
 * [rootSubdir] is the subdirectory of the SAF tree that roots all data
 * (`crystal-nova-data` beside the theme for the legacy install). Pass
 * null to root the data tree directly at the SAF tree root — used when a
 * dedicated media folder is picked, where the layout is exactly
 * `games/<platform>/<gameId>/`, `cache/`, `index.json`, manifests.
 */
class ScraperStorage(
    private val fs: ThemeFs,
    private val rootSubdir: String? = DATA_DIR_NAME,
) {

    companion object {
        const val DATA_DIR_NAME = "crystal-nova-data"
        const val GAMES_DIR = "games"
        const val CACHE_DIR = "cache"
        const val INDEX_NAME = "index.json"
        const val MANIFEST_NAME = "manifest.json"
        /**
         * Crystal-owned writability probe file. Created, written, read
         * back and deleted against the data root before every scrape —
         * never a user asset, never left behind.
         */
        const val PROBE_FILE_NAME = ".crystal-write-probe"
        private val PROBE_BYTES = "crystal-nova-write-probe-v1".toByteArray()

        /**
         * Pure helper: removes index.json entries whose (platform, gameId)
         * is not in [scannedKeys] (e.g. renamed ROMs whose old slug lingers
         * as a ghost entry). Returns the pruned JSON text plus the number
         * of entries removed, or null when [indexText] is not a usable
         * index. Asset directories are untouched — only the derived index
         * is pruned; the user deletes asset dirs separately.
         */
        fun pruneOrphanedEntries(
            indexText: String,
            scannedKeys: Set<Pair<String, String>>,
        ): Pair<String, Int>? {
            val root = try {
                JSONObject(indexText)
            } catch (_: Exception) { return null }
            val games = root.optJSONObject("games") ?: return null
            var removed = 0
            for (key in games.keys().asSequence().toList()) {
                val o = games.optJSONObject(key) ?: continue
                val platform = o.optString("platform", "")
                    .ifEmpty { key.substringBefore('/') }
                val gameId = o.optString("gameId", "")
                    .ifEmpty { key.substringAfter('/') }
                if ((platform to gameId) !in scannedKeys) {
                    games.remove(key)
                    removed++
                }
            }
            return root.toString() to removed
        }
    }

    /** Result of an asset save attempt. */
    sealed interface SaveResult {
        data object Saved : SaveResult
        /** Kept the existing asset per replacement rules. */
        data class Kept(val reason: String) : SaveResult
        data class Failed(val reason: String) : SaveResult
    }

    private fun dataRoot(): FsNode? {
        // Null only when no folder was ever picked. A revoked grant throws
        // SecurityException out of fs.root()/find()/mkdir() and must
        // propagate — degrading to null here would masquerade revocation
        // as an empty library downstream.
        val root = fs.root() ?: return null
        // A null rootSubdir roots the data tree directly at the SAF tree
        // root (dedicated media folder): games/, cache/, index.json.
        val sub = rootSubdir ?: return root
        return fs.find(root, sub) ?: fs.mkdir(root, sub)
    }

    /**
     * A revoked SAF grant must propagate to the caller's reselection
     * handling — never be absorbed into a null/false/empty result. Call
     * at the top of every generic catch in this class.
     */
    private fun rethrowIfRevoked(e: Exception) {
        if (e is SecurityException) throw e
    }

    private fun ensureDir(parent: FsNode, name: String): FsNode? =
        try {
            fs.find(parent, name) ?: fs.mkdir(parent, name)
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            null
        }

    fun gameDir(platform: String, gameId: String): FsNode? {
        val root = dataRoot() ?: return null
        val games = ensureDir(root, GAMES_DIR) ?: return null
        val plat = ensureDir(games, platform) ?: return null
        return ensureDir(plat, gameId)
    }

    fun cacheDir(): FsNode? {
        val root = dataRoot() ?: return null
        return ensureDir(root, CACHE_DIR)
    }

    /** Relative local path for an asset, e.g. games/gba/slug/front.png */
    fun assetPath(platform: String, gameId: String, slot: AssetSlot): String =
        "$GAMES_DIR/$platform/$gameId/${slot.fileName}"

    fun manifestPath(platform: String, gameId: String): String =
        "$GAMES_DIR/$platform/$gameId/$MANIFEST_NAME"

    /**
     * Saves an asset file honoring replacement rules. [existing] is the
     * current provenance for the slot, or null when empty. [force] is
     * reserved for an explicit user choice (the artwork studio): a user
     * replacing their own USER asset always wins. Imports never pass
     * force, so the USER-wins protection is unchanged for them.
     */
    fun saveAsset(
        platform: String,
        gameId: String,
        slot: AssetSlot,
        provenance: AssetProvenance,
        bytes: ByteArray,
        existing: AssetProvenance?,
        force: Boolean = false,
    ): SaveResult {
        if (existing != null && !force) {
            if (existing.sourceType == SourceType.USER) {
                return SaveResult.Kept("user asset wins")
            }
            if (existing.sourceType == SourceType.REAL &&
                provenance.sourceType == SourceType.GENERATED
            ) {
                return SaveResult.Kept("real asset kept over generated")
            }
        }
        val dir = gameDir(platform, gameId) ?: return SaveResult.Failed("no data dir")
        return try {
            writeAtomically(dir, slot.fileName, bytes)
            SaveResult.Saved
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            SaveResult.Failed(e.message ?: "write failed")
        }
    }

    fun saveManifest(game: ScrapedGame): Boolean {
        val dir = gameDir(game.platform, game.gameId) ?: return false
        return try {
            writeAtomically(dir, MANIFEST_NAME, ScraperJson.manifestToJson(game).toByteArray())
            true
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            false
        }
    }

    fun loadManifest(platform: String, gameId: String): ScrapedGame? {
        val dir = gameDir(platform, gameId) ?: return null
        val node = fs.find(dir, MANIFEST_NAME) ?: return null
        return try {
            ScraperJson.manifestFromJson(fs.openInput(node).bufferedReader().readText())
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            null
        }
    }

    /** Reads a raw file under the data dir by relative path. */
    fun readBytes(relativePath: String): ByteArray? {
        val root = dataRoot() ?: return null
        var node: FsNode = root
        for (part in relativePath.split('/')) {
            node = fs.find(node, part) ?: return null
        }
        return try {
            fs.openInput(node).use { it.readBytes() }
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            null
        }
    }

    fun loadIndexJson(): String? = readBytes(INDEX_NAME)?.toString(Charsets.UTF_8)

    /**
     * Byte length of a stored slot file, or null when absent. Find-only:
     * never creates directories, so the pre-import scan can call it
     * without materializing ghost game dirs.
     */
    fun assetLength(platform: String, gameId: String, slot: AssetSlot): Long? {
        return try {
            val root = fs.root() ?: return null
            val data = if (rootSubdir != null) fs.find(root, rootSubdir) ?: return null else root
            val games = fs.find(data, GAMES_DIR) ?: return null
            val plat = fs.find(games, platform) ?: return null
            val dir = fs.find(plat, gameId) ?: return null
            val node = fs.find(dir, slot.fileName) ?: return null
            fs.length(node)
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            null
        }
    }

    /**
     * Diagnostics-grade presence check for one asset slot file. Never
     * throws — any failure (including a revoked grant) reads as absent;
     * Diagnostics reports media access separately, so this stays a pure
     * file-presence answer for the theme-visibility report.
     */
    fun assetPresent(platform: String, gameId: String, slot: AssetSlot): Boolean {
        return try {
            val dir = gameDir(platform, gameId) ?: return false
            fs.find(dir, slot.fileName) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes one asset slot file. Never throws; returns false when the
     * file wasn't there or couldn't be removed. Used by the artwork
     * studio's per-slot CLEAR — the manifest/index update is the caller's
     * job, mirroring how saveAsset leaves manifest bookkeeping to its
     * caller.
     */
    fun deleteAsset(platform: String, gameId: String, slot: AssetSlot): Boolean {
        return try {
            val dir = gameDir(platform, gameId) ?: return false
            val node = fs.find(dir, slot.fileName) ?: return false
            fs.deleteRecursively(node)
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            false
        }
    }

    /**
     * Raw bytes of index.json, or null when absent/unreadable. Prefer this
     * over [loadIndexJson] when the bytes themselves matter (quarantining a
     * malformed index): a String round-trip through UTF-8 would replace
     * invalid sequences and the backup would no longer be byte-exact.
     */
    fun loadIndexBytes(): ByteArray? = readBytes(INDEX_NAME)

    /**
     * Persistent download cache (`crystal-nova-data/cache/`). Keys are
     * caller-supplied safe file names (e.g. SHA-256 of a URL + ".bin").
     * Writes are atomic; failures are reported, never thrown.
     */
    fun readCache(key: String): ByteArray? {
        val dir = cacheDir() ?: return null
        val node = fs.find(dir, key) ?: return null
        return try {
            fs.openInput(node).use { it.readBytes() }
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            null
        }
    }

    fun writeCache(key: String, bytes: ByteArray): Boolean {
        val dir = cacheDir() ?: return false
        return try {
            writeAtomically(dir, key, bytes)
            true
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            false
        }
    }

    fun saveIndexJson(json: String): Boolean {
        val root = dataRoot() ?: return false
        return try {
            writeAtomically(root, INDEX_NAME, json.toByteArray())
            true
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            false
        }
    }

    /**
     * Phase 1 launcher bridge: writes `config.json` at the data root
     * (contract §3). Filenames are owned by LauncherExport (the contract
     * owner); they are inlined here to keep the storage layer free of a
     * dependency on the launcher bridge module. Additive; never throws.
     */
    fun saveLauncherConfigJson(json: String): Boolean {
        val root = dataRoot() ?: return false
        return try {
            writeAtomically(root, "config.json", json.toByteArray())
            true
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            false
        }
    }

    /**
     * Phase 1 launcher bridge: writes `launcher/profiles.json`
     * (contract §7), creating the `launcher/` dir. The launcher keeps
     * its own cache elsewhere and must ignore everything else here.
     * Additive; never throws.
     */
    fun saveLauncherProfilesJson(json: String): Boolean {
        val root = dataRoot() ?: return false
        val dir = ensureDir(root, "launcher") ?: return false
        return try {
            writeAtomically(dir, "profiles.json", json.toByteArray())
            true
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            false
        }
    }

    /** Result of the pre-scrape writability probe against the data root. */
    sealed interface ProbeResult {
        data object Writable : ProbeResult
        data class Failed(val reason: String) : ProbeResult
    }

    /**
     * Real writability proof against the ACTUAL data root (dedicated
     * media tree root, or legacy themes-root `crystal-nova-data/`):
     * create a small Crystal-owned probe file, write known bytes,
     * read them back, delete the probe. Readable-but-unwritable is
     * NOT writable. Never touches user assets. Never throws — a
     * revoked grant reads as Failed, and callers translate that into
     * their reselection handling.
     */
    fun probeWritable(): ProbeResult {
        val root = try {
            dataRoot()
        } catch (e: Exception) {
            return ProbeResult.Failed("data root unreachable: ${e.message ?: e.javaClass.simpleName}")
        } ?: return ProbeResult.Failed("media data root is not configured")
        return try {
            // A crashed earlier probe must not block this one.
            fs.find(root, PROBE_FILE_NAME)?.let { fs.deleteRecursively(it) }
            val node = fs.createFile(root, PROBE_FILE_NAME)
            try {
                fs.openOutput(node).use { it.write(PROBE_BYTES) }
                val read = fs.openInput(node).use { it.readBytes() }
                if (!read.contentEquals(PROBE_BYTES)) {
                    return ProbeResult.Failed("probe readback mismatch")
                }
            } finally {
                fs.deleteRecursively(node)
            }
            if (fs.find(root, PROBE_FILE_NAME) != null) {
                return ProbeResult.Failed("probe file could not be deleted")
            }
            ProbeResult.Writable
        } catch (e: Exception) {
            ProbeResult.Failed(e.message ?: "probe write failed")
        }
    }

    /**
     * Quarantines [bytes] as a sibling of index.json
     * (`index.json.corrupt-<millis>`) so a malformed index is never lost
     * silently and never parsed again. Returns the backup file name, or
     * null when storage is unavailable.
     */
    fun quarantineIndexBackup(bytes: ByteArray): String? {
        val root = dataRoot() ?: return null
        val name = "$INDEX_NAME.corrupt-${System.currentTimeMillis()}"
        return try {
            writeAtomically(root, name, bytes)
            name
        } catch (e: Exception) {
            rethrowIfRevoked(e)
            null
        }
    }

    /** Lists all stored game manifests: (platform, gameId) pairs. */
    fun listGames(): List<Pair<String, String>> {
        val root = dataRoot() ?: return emptyList()
        val games = fs.find(root, GAMES_DIR) ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for ((platName, platNode) in fs.children(games)) {
            if (!fs.isDirectory(platNode)) continue
            for ((gameName, gameNode) in fs.children(platNode)) {
                if (fs.isDirectory(gameNode) && fs.find(gameNode, MANIFEST_NAME) != null) {
                    out += platName to gameName
                }
            }
        }
        return out
    }

    /**
     * Atomic write within [dir]: create `<name>.tmp`, stream bytes, then
     * swap it over `<name>` with a backup/restore transaction. SAF
     * providers cannot rename over an existing target, so the previous
     * file is first moved aside to `<name>.bak`; if the final rename
     * fails, the backup is restored — the original is never deleted, so
     * a failed write can never lose it.
     */
    @Throws(Exception::class)
    private fun writeAtomically(dir: FsNode, name: String, bytes: ByteArray) {
        val tmpName = "$name.tmp"
        val bakName = "$name.bak"
        fs.find(dir, tmpName)?.let { fs.deleteRecursively(it) }
        fs.find(dir, bakName)?.let { fs.deleteRecursively(it) }
        val tmp = fs.createFile(dir, tmpName)
        try {
            fs.openOutput(tmp).use { it.write(bytes) }
        } catch (e: Exception) {
            fs.deleteRecursively(tmp)
            throw e
        }
        val existing = fs.find(dir, name)
        if (existing != null) {
            // Move the original aside first; from here on it is only ever
            // renamed back, never deleted.
            if (!fs.rename(existing, bakName)) {
                fs.deleteRecursively(tmp)
                throw IllegalStateException("backup failed for $name")
            }
        }
        if (!fs.rename(tmp, name)) {
            // Restore the original; the failed tmp is discarded.
            fs.find(dir, bakName)?.let { fs.rename(it, name) }
            fs.deleteRecursively(tmp)
            throw IllegalStateException("rename failed for $name")
        }
        // The new file is in place; the backup is no longer needed.
        fs.find(dir, bakName)?.let { fs.deleteRecursively(it) }
    }
}
