package io.crystalnova.manager.scraper.store

import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.ScrapedGame
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs

/**
 * SAF storage for the persistent sibling directory `crystal-nova-data/`.
 *
 * The U1 theme updater never touches this tree; the scraper never touches
 * the theme directories. All writes are atomic (temp file + rename) and
 * obey the replacement rules:
 * - USER assets are never overwritten automatically.
 * - REAL assets are never replaced by GENERATED ones.
 * - REAL assets MAY replace GENERATED fallbacks.
 */
class ScraperStorage(private val fs: ThemeFs) {

    companion object {
        const val DATA_DIR_NAME = "crystal-nova-data"
        const val GAMES_DIR = "games"
        const val CACHE_DIR = "cache"
        const val INDEX_NAME = "index.json"
        const val MANIFEST_NAME = "manifest.json"
    }

    /** Result of an asset save attempt. */
    sealed interface SaveResult {
        data object Saved : SaveResult
        /** Kept the existing asset per replacement rules. */
        data class Kept(val reason: String) : SaveResult
        data class Failed(val reason: String) : SaveResult
    }

    private fun dataRoot(): FsNode? {
        val root = try { fs.root() } catch (_: SecurityException) { return null } ?: return null
        return fs.find(root, DATA_DIR_NAME) ?: fs.mkdir(root, DATA_DIR_NAME)
    }

    private fun ensureDir(parent: FsNode, name: String): FsNode? =
        try { fs.find(parent, name) ?: fs.mkdir(parent, name) } catch (_: Exception) { null }

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
     * current provenance for the slot, or null when empty.
     */
    fun saveAsset(
        platform: String,
        gameId: String,
        slot: AssetSlot,
        provenance: AssetProvenance,
        bytes: ByteArray,
        existing: AssetProvenance?,
    ): SaveResult {
        if (existing != null) {
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
            SaveResult.Failed(e.message ?: "write failed")
        }
    }

    fun saveManifest(game: ScrapedGame): Boolean {
        val dir = gameDir(game.platform, game.gameId) ?: return false
        return try {
            writeAtomically(dir, MANIFEST_NAME, ScraperJson.manifestToJson(game).toByteArray())
            true
        } catch (_: Exception) { false }
    }

    fun loadManifest(platform: String, gameId: String): ScrapedGame? {
        val dir = gameDir(platform, gameId) ?: return null
        val node = fs.find(dir, MANIFEST_NAME) ?: return null
        return try {
            ScraperJson.manifestFromJson(fs.openInput(node).bufferedReader().readText())
        } catch (_: Exception) { null }
    }

    /** Reads a raw file under the data dir by relative path. */
    fun readBytes(relativePath: String): ByteArray? {
        val root = dataRoot() ?: return null
        var node: FsNode = root
        for (part in relativePath.split('/')) {
            node = fs.find(node, part) ?: return null
        }
        return try { fs.openInput(node).use { it.readBytes() } } catch (_: Exception) { null }
    }

    fun loadIndexJson(): String? = readBytes(INDEX_NAME)?.toString(Charsets.UTF_8)

    /**
     * Persistent download cache (`crystal-nova-data/cache/`). Keys are
     * caller-supplied safe file names (e.g. SHA-256 of a URL + ".bin").
     * Writes are atomic; failures are reported, never thrown.
     */
    fun readCache(key: String): ByteArray? {
        val dir = cacheDir() ?: return null
        val node = fs.find(dir, key) ?: return null
        return try { fs.openInput(node).use { it.readBytes() } } catch (_: Exception) { null }
    }

    fun writeCache(key: String, bytes: ByteArray): Boolean {
        val dir = cacheDir() ?: return false
        return try { writeAtomically(dir, key, bytes); true } catch (_: Exception) { false }
    }

    fun saveIndexJson(json: String): Boolean {
        val root = dataRoot() ?: return false
        return try { writeAtomically(root, INDEX_NAME, json.toByteArray()); true }
        catch (_: Exception) { false }
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
