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
 *   After the write, the metafile is reopened and read back: the
 *   inject fails unless the readback is non-empty and byte-identical
 *   to what was written.
 * - The config root must be one Pegasus actually reads
 *   ([ConfigValidity.VALID]); a wrong or lost root refuses the inject
 *   and points the user at FIX PEGASUS FOLDER.
 * - Regeneration happens only on explicit INJECT/REFRESH — never
 *   automatically.
 *
 * [gameSource]/[writer]/[metafileReader] are seams for unit tests; null
 * means the SAF-backed defaults. [logger] is a seam for the same
 * reason: android.util.Log throws under JVM unit tests.
 */
class PegasusLibrary(
    private val context: Context?,
    private val prefs: KeyValueStore,
    private val locations: io.crystalnova.manager.storage.StorageLocations,
    internal var gameSource: (suspend () -> List<ScannedGame>)? = null,
    internal var writer: ((configTreeUri: String, bytes: ByteArray) -> Boolean)? = null,
    /**
     * Readback seam mirroring [writer]: reopens the Manager-owned
     * metafile and returns its bytes, or null when unreadable.
     * Defaults to a SAF read of the same metafile the writer wrote.
     */
    internal var metafileReader: ((configTreeUri: String) -> ByteArray?)? = null,
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

        /** Last verified inject, stored as "systems|games". */
        internal const val KEY_LAST_INJECT = "pegasus_last_inject"
    }

    val config = PegasusConfig(context, prefs, logger)
    val profiles = LauncherProfileStore(prefs)

    /**
     * Grant-check seams. Production defaults hit
     * [PegasusConfig.validity] and
     * [io.crystalnova.manager.storage.StorageLocations.hasAccess];
     * unit tests override them because DocumentFile/PackageManager are
     * unavailable on the JVM.
     */
    internal var checkConfigValidity: () -> ConfigValidity = config::validity
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
            /**
             * Populated systems skipped because no launcher is
             * configured for them. Injection is partial by design:
             * every configured system is injected and these are
             * reported, so the user can configure launchers
             * progressively and re-inject. The operation fails only
             * when zero collections can be built.
             */
            val skippedNoLauncher: List<String> = emptyList(),
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
     * launcher (injection skips these and reports them — progressive
     * system setup) and of ROM folders the Manager doesn't recognize
     * (excluded from the metafile, surfaced to the user).
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
        // v18: the config root must be a real Pegasus config root, not
        // just a readable folder. An invalid root refuses the inject —
        // the UI points the user at FIX PEGASUS FOLDER.
        when (checkConfigValidity()) {
            ConfigValidity.VALID -> Unit
            ConfigValidity.NOT_SELECTED ->
                return InjectOutcome.Failed("PEGASUS FOLDER NOT SELECTED")
            ConfigValidity.WRONG_FOLDER ->
                return InjectOutcome.Failed("PEGASUS FOLDER INVALID — USE FIX PEGASUS FOLDER")
            ConfigValidity.ACCESS_LOST ->
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
        val collections = built.collections
        if (collections.isEmpty()) {
            // Fail only when ZERO populated systems can be injected:
            // either nothing is populated at all, or everything
            // populated lacks a configured launcher.
            return InjectOutcome.Failed(
                if (built.unconfiguredSystems.isNotEmpty()) {
                    "NO LAUNCHER: ${built.unconfiguredSystems.joinToString(", ").uppercase()} — " +
                        "CONFIGURE LAUNCHERS FIRST"
                } else {
                    "NOTHING TO INJECT — NO GAMES WITH A CONFIGURED LAUNCHER"
                },
            )
        }
        // Partial injection: every populated system with a configured
        // launcher is emitted; populated systems without one are
        // skipped and reported (progressive setup — configure more
        // launchers and re-inject).
        val text = try {
            MetafileGenerator.generate(collections)
        } catch (e: IllegalArgumentException) {
            logger(TAG, "bad game path in metafile", e)
            return InjectOutcome.Failed("BAD GAME PATH — ${e.message}")
        }
        val bytes = text.toByteArray()
        val ok = writeMetafile(configUri, bytes)
        if (!ok) {
            return InjectOutcome.Failed("WRITE FAILED — CHECK THE PEGASUS FOLDER GRANT")
        }
        // v18: verify the write, not just the rename. Reopen the
        // Manager-owned metafile and require it to be non-empty AND
        // byte-identical to what was written.
        if (!verifyMetafile(configUri, bytes)) {
            return InjectOutcome.Failed("METAFILE VERIFY FAILED — READBACK DID NOT MATCH")
        }
        val systems = collections.size
        val gameCount = collections.sumOf { it.games.size }
        noteLastInject(systems, gameCount)
        return InjectOutcome.Ok(
            systems,
            gameCount,
            built.unknownFolders,
            built.unconfiguredSystems,
        )
    }

    /**
     * v18: readback verification. Reopens the metafile the writer just
     * wrote and requires nonzero bytes that are exactly equal to what
     * was written. Never throws.
     */
    private fun verifyMetafile(configTreeUri: String, bytes: ByteArray): Boolean {
        val readBack = try {
            (metafileReader ?: ::defaultRead)(configTreeUri)
        } catch (e: Exception) {
            logger(TAG, "metafile readback failed", e)
            null
        }
        if (readBack.isNullOrEmpty()) {
            logger(TAG, "metafile readback missing or empty", null)
            return false
        }
        if (!readBack.contentEquals(bytes)) {
            logger(TAG, "metafile readback bytes differ from what was written", null)
            return false
        }
        return true
    }

    /** Result of [metafileStatus]. */
    data class MetafileStatus(val present: Boolean, val bytes: Long?)

    /**
     * Whether the Manager-owned metafile is present and readable, and
     * its byte size (null when unreadable). Backed by the
     * [metafileReader] seam, so diagnostics and verification agree.
     * Never throws.
     */
    fun metafileStatus(): MetafileStatus {
        val configUri = config.treeUri() ?: return MetafileStatus(false, null)
        val bytes = try {
            (metafileReader ?: ::defaultRead)(configUri)
        } catch (_: Exception) {
            null
        }
        return MetafileStatus(bytes != null, bytes?.size?.toLong())
    }

    /** "n SYSTEMS · m GAMES" from the last verified inject, or "NONE". */
    fun lastInjectSummary(): String {
        val raw = prefs.getString(KEY_LAST_INJECT) ?: return "NONE"
        val parts = raw.split('|')
        val systems = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val games = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return "$systems SYSTEMS · $games GAMES"
    }

    /** Persists the counts of the last VERIFIED inject. */
    private fun noteLastInject(systems: Int, games: Int) {
        try {
            prefs.putString(KEY_LAST_INJECT, "$systems|$games")
        } catch (e: Exception) {
            logger(TAG, "persist last inject failed", e)
        }
    }

    /**
     * Friendly config-root validity for the dashboard PEGASUS CONFIG
     * row. Never a raw content:// URI; the real display path lives in
     * Diagnostics.
     */
    fun configDisplayPath(): String = when (checkConfigValidity()) {
        ConfigValidity.VALID -> "CONFIG READY"
        ConfigValidity.WRONG_FOLDER -> "WRONG FOLDER"
        ConfigValidity.ACCESS_LOST -> "ACCESS LOST — RESELECT"
        ConfigValidity.NOT_SELECTED -> "NOT SELECTED"
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

    /**
     * SAF read of the Manager-owned metafile — the default behind
     * [metafileReader], i.e. the same `<config root>/metafiles/`
     * file the writer wrote. Used for write verification and the
     * diagnostics metafile status. Null on any failure; never throws.
     */
    private fun defaultRead(configTreeUri: String): ByteArray? {
        return try {
            val fs = SafThemeFs(context!!) { configTreeUri }
            val root = fs.root() ?: return null
            val metafiles = fs.find(root, MetafileGenerator.METAFILES_DIR) ?: return null
            val node = fs.find(metafiles, MetafileGenerator.FILE_NAME) ?: return null
            fs.openInput(node).use { it.readBytes() }
        } catch (e: Exception) {
            logger(TAG, "default metafile read failed", e)
            null
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
