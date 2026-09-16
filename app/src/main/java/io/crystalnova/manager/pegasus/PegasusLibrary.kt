package io.crystalnova.manager.pegasus

import android.content.Context
import android.util.Log
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.scraper.match.PlatformTable
import io.crystalnova.manager.scraper.scan.LibraryScanner
import io.crystalnova.manager.storage.LocationKind
import io.crystalnova.manager.storage.SafThemeFs

/**
 * Owns the Pegasus setup flow: the config-root grant ([PegasusConfig]),
 * the per-platform launcher choices ([LauncherProfileStore]), the
 * library scan → canonical-path mapping, metafile generation, and the
 * SAF write.
 *
 * Injection contract:
 * - Reads games from [LibraryScanner]'s canonical/deduplicated entries
 *   (CUE/BIN→CUE, M3U→M3U, GDI tracks, numbered tracks never become
 *   separate games — the scanner owns that dedup).
 * - Paths are canonical filesystem paths (`/storage/…`), built from
 *   [StorageLocations.canonicalPath] of the ROM tree plus the scanner's
 *   relative path. `content://` URIs never reach the metafile.
 * - Writes exactly one Manager-owned file,
 *   `metafiles/crystal-nova.metadata.pegasus.txt`, via tmp-file +
 *   rename. Never writes or overwrites any other metadata file.
 * - Regeneration happens only on explicit INJECT/REFRESH — never
 *   automatically.
 *
 * [gameSource]/[writer] are seams for unit tests; null means the
 * SAF-backed defaults. [logger] is a seam for the same reason:
 * android.util.Log throws under JVM unit tests.
 */
class PegasusLibrary(
    private val context: Context?,
    prefs: KeyValueStore,
    private val locations: io.crystalnova.manager.storage.StorageLocations,
    internal var gameSource: (suspend () -> List<ScannedGame>)? = null,
    internal var writer: ((configTreeUri: String, bytes: ByteArray) -> Boolean)? = null,
    private val logger: (tag: String, msg: String, err: Throwable?) -> Unit =
        { tag, msg, err -> Log.w(tag, msg, err) },
) {
    companion object {
        private const val TAG = "PegasusLibrary"

        /**
         * Scratch names for the tmp-file + rename swap. Deliberately do
         * NOT look like Pegasus metadata files (Pegasus reads *.txt
         * under metafiles/).
         */
        internal const val TMP_NAME = "crystal-nova-manager-write.tmp"
        internal const val BACKUP_NAME = "crystal-nova-manager-backup.tmp"
    }

    val config = PegasusConfig(context, prefs, logger)
    val profiles = LauncherProfileStore(prefs)

    /**
     * Grant-check seams. Production defaults hit [PegasusConfig.hasAccess]
     * and [io.crystalnova.manager.storage.StorageLocations.hasAccess];
     * unit tests override them because DocumentFile/PackageManager are
     * unavailable on the JVM.
     */
    internal var checkConfigAccess: () -> Boolean = config::hasAccess
    internal var checkRomAccess: () -> Boolean = { locations.hasAccess(LocationKind.ROM) }
    internal var describeUri: (String) -> String = { locations.displayPath(it) }

    /** One game with its canonical absolute ROM path. */
    data class ScannedGame(
        val platformSlug: String,
        val platformLabel: String,
        val title: String,
        /** Canonical filesystem path, e.g. `/storage/emulated/0/ROMs/gba/game.gba`. */
        val path: String,
    )

    sealed interface InjectOutcome {
        data class Ok(
            val collections: Int,
            val games: Int,
            /** ROM folders the Manager doesn't recognize — excluded, surfaced. */
            val unknownFolders: List<String>,
        ) : InjectOutcome
        data class Failed(val message: String) : InjectOutcome
    }

    /** Pure path join for tests: ROM tree canonical path + scanner relative path. */
    fun gamePath(romRootCanonical: String, relativePath: String): String =
        "$romRootCanonical/$relativePath"

    /**
     * Scans the ROM library into canonical-path games. Throws
     * SecurityException when the ROM grant was lost mid-scan (the
     * caller turns that into the reselection state).
     */
    suspend fun scanGames(): List<ScannedGame> {
        gameSource?.let { return it() }
        val ctx = context ?: return emptyList()
        val romUri = locations.romTreeUri() ?: return emptyList()
        val romRoot = locations.canonicalPath(romUri) ?: return emptyList()
        return LibraryScanner(ctx, romUri).scan().games.map { e ->
            ScannedGame(
                platformSlug = e.platformSlug,
                platformLabel = e.platformLabel,
                title = MetafileGenerator.displayTitle(e.fileName),
                path = gamePath(romRoot, e.relativePath),
            )
        }
    }

    /**
     * Result of [buildCollections]: one collection per recognized
     * platform that has games AND a configured launcher profile, plus
     * the labels of recognized systems that are populated but have no
     * launcher (injection refuses to emit a partial library for these)
     * and of ROM folders the Manager doesn't recognize (excluded from
     * the metafile, surfaced to the user).
     */
    data class BuiltLibrary(
        val collections: List<MetafileGenerator.Collection>,
        val unconfiguredSystems: List<String>,
        val unknownFolders: List<String>,
    )

    /**
     * Builds one collection per recognized platform that has games AND
     * a configured launcher profile.
     */
    fun buildCollections(games: List<ScannedGame>): BuiltLibrary {
        val bySystem = games.groupBy { it.platformSlug }
        val recognized = PlatformTable.all().map { it.slug }.toSet()
        val unknownFolders = bySystem
            .filterKeys { it !in recognized }
            .values
            .flatten()
            .map { it.platformLabel }
            .distinct()
            .sorted()
        val collections = mutableListOf<MetafileGenerator.Collection>()
        val unconfigured = mutableListOf<String>()
        for (platform in PlatformTable.all()) {
            val sysGames = bySystem[platform.slug] ?: continue
            if (sysGames.isEmpty()) continue
            val profile = profiles.effectiveProfile(platform.slug)
            if (profile == null || !profile.isConfigured()) {
                unconfigured += platform.displayName
                continue
            }
            // Multi-disc grouping + defensive dedup (CUE/BIN, M3U sets,
            // GDI/numbered tracks): games sharing (platform, base title)
            // become one `game:` with a `files:` list, e.g.
            // `Final Fantasy VII (Disc 1).cue` + `(Disc 2).cue`.
            val games = MetafileGenerator.organizeGames(
                sysGames.map { MetafileGenerator.RawEntry(it.title, it.path) },
            )
            collections += MetafileGenerator.Collection(
                name = platform.displayName,
                shortname = MetafileGenerator.shortnameFor(platform.slug),
                launchLines = profile.launchLines(),
                games = games,
            )
        }
        return BuiltLibrary(collections, unconfigured, unknownFolders)
    }

    /**
     * Full explicit-refresh flow: validate grants, scan, generate,
     * write. Never throws for expected failure modes — they come back
     * as [InjectOutcome.Failed] with a UI-ready message.
     */
    suspend fun inject(): InjectOutcome {
        val configUri = config.treeUri()
            ?: return InjectOutcome.Failed("PEGASUS FOLDER NOT SELECTED")
        if (!checkConfigAccess()) {
            return InjectOutcome.Failed("PEGASUS FOLDER ACCESS LOST — PLEASE RESELECT")
        }
        if (!checkRomAccess()) {
            return InjectOutcome.Failed("ROM LIBRARY NOT AVAILABLE")
        }
        val games = try {
            scanGames()
        } catch (e: SecurityException) {
            return InjectOutcome.Failed("ROM LIBRARY ACCESS LOST — PLEASE RESELECT")
        } catch (e: Exception) {
            logger(TAG, "scan failed", e)
            return InjectOutcome.Failed("LIBRARY SCAN FAILED")
        }
        val built = buildCollections(games)
        if (built.unconfiguredSystems.isNotEmpty()) {
            // Never emit a partial library silently: the user configures
            // a launcher for every populated system, then injects again.
            return InjectOutcome.Failed(
                "NO LAUNCHER: ${built.unconfiguredSystems.joinToString(", ").uppercase()} — " +
                    "CONFIGURE LAUNCHERS FIRST",
            )
        }
        val collections = built.collections
        if (collections.isEmpty()) {
            return InjectOutcome.Failed("NOTHING TO INJECT — NO GAMES WITH A CONFIGURED LAUNCHER")
        }
        val text = try {
            MetafileGenerator.generate(collections)
        } catch (e: IllegalArgumentException) {
            logger(TAG, "bad game path in metafile", e)
            return InjectOutcome.Failed("BAD GAME PATH — ${e.message}")
        }
        val ok = writeMetafile(configUri, text.toByteArray())
        return if (ok) {
            InjectOutcome.Ok(
                collections.size,
                collections.sumOf { it.games.size },
                built.unknownFolders,
            )
        } else {
            InjectOutcome.Failed("WRITE FAILED — CHECK THE PEGASUS FOLDER GRANT")
        }
    }

    /**
     * Friendly config-root status for the UI. Never a raw content://
     * URI: `NOT SELECTED`, the parsed display path, or the access-lost
     * reselection prompt.
     */
    fun configDisplayPath(): String {
        val uri = config.treeUri() ?: return "NOT SELECTED"
        return if (checkConfigAccess()) describeUri(uri) else "ACCESS LOST — RESELECT"
    }

    private fun writeMetafile(configTreeUri: String, bytes: ByteArray): Boolean {
        return try {
            (writer ?: ::defaultWrite)(configTreeUri, bytes)
        } catch (e: Exception) {
            logger(TAG, "metafile write failed", e)
            false
        }
    }

    /**
     * SAF write of the single Manager-owned metafile: tmp-file +
     * rename inside `<config root>/metafiles/`. The swap is
     * backup/restore: the previous Manager-owned metafile (if any) is
     * moved aside to a clearly non-metadata backup name first, so a
     * failed rename restores it instead of losing it. A revoked grant
     * surfaces as SecurityException from [SafThemeFs.root] and reads
     * as a failed write, never as a silent skip. No other metadata
     * file is touched.
     */
    private fun defaultWrite(configTreeUri: String, bytes: ByteArray): Boolean {
        return try {
            val fs = SafThemeFs(context!!) { configTreeUri }
            val root = fs.root() ?: return false
            val metafiles = fs.find(root, MetafileGenerator.METAFILES_DIR)
                ?: fs.mkdir(root, MetafileGenerator.METAFILES_DIR)
            val swap = object : MetafileSwapFs {
                override fun find(name: String): Boolean =
                    fs.find(metafiles, name) != null

                override fun create(name: String): Boolean = try {
                    fs.createFile(metafiles, name)
                    true
                } catch (e: Exception) {
                    false
                }

                override fun write(name: String, bytes: ByteArray): Boolean {
                    val node = fs.find(metafiles, name) ?: return false
                    return try {
                        fs.openOutput(node).use { it.write(bytes) }
                        true
                    } catch (e: Exception) {
                        false
                    }
                }

                override fun rename(from: String, to: String): Boolean {
                    val node = fs.find(metafiles, from) ?: return false
                    return fs.rename(node, to)
                }

                override fun delete(name: String) {
                    fs.find(metafiles, name)?.let { fs.deleteRecursively(it) }
                }
            }
            swapMetafile(swap, bytes)
        } catch (e: Exception) {
            logger(TAG, "default metafile write failed", e)
            false
        }
    }
}

/**
 * Minimal filesystem seam for the metafile swap, so the
 * backup/restore ordering is unit-testable without DocumentFile.
 */
internal interface MetafileSwapFs {
    fun find(name: String): Boolean
    fun create(name: String): Boolean
    fun write(name: String, bytes: ByteArray): Boolean
    fun rename(from: String, to: String): Boolean
    fun delete(name: String)
}

/**
 * SAF write of the single Manager-owned metafile: tmp-file + rename
 * inside `<config root>/metafiles/`. The swap is backup/restore: the
 * previous Manager-owned metafile (if any) is moved aside to a
 * clearly non-metadata backup name first, so a failed rename restores
 * it instead of losing it. Never touches any other metadata file.
 * Returns true only when the final metafile holds [bytes].
 */
internal fun swapMetafile(fs: MetafileSwapFs, bytes: ByteArray): Boolean {
    val final = MetafileGenerator.FILE_NAME
    val tmp = PegasusLibrary.TMP_NAME
    val backup = PegasusLibrary.BACKUP_NAME
    fs.delete(tmp)
    if (!fs.create(tmp)) return false
    if (!fs.write(tmp, bytes)) {
        fs.delete(tmp)
        return false
    }
    fs.delete(backup)
    val hadPrevious = fs.find(final)
    if (hadPrevious && !fs.rename(final, backup)) {
        // Could not preserve the previous metafile: do not risk it.
        fs.delete(tmp)
        return false
    }
    if (!fs.rename(tmp, final)) {
        // Restore the previous metafile; never leave the user
        // with neither.
        if (hadPrevious) fs.rename(backup, final)
        fs.delete(tmp)
        return false
    }
    fs.delete(backup)
    return true
}
