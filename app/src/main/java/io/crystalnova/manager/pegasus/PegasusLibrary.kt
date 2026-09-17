package io.crystalnova.manager.pegasus

import android.content.Context
import android.util.Log
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.scraper.match.PlatformTable
import io.crystalnova.manager.scraper.scan.LibraryScanner
import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.LocationKind
import io.crystalnova.manager.storage.SafThemeFs

/**
 * Owns the Pegasus setup flow: the ROM-root grant (from
 * [io.crystalnova.manager.storage.StorageLocations]), the per-platform
 * launcher choices ([LauncherProfileStore]), the library scan →
 * canonical-path mapping, metafile generation, and the SAF write.
 *
 * Injection contract (v20 — canonical Pegasus per-system layout):
 * - Reads games from [LibraryScanner]'s canonical/deduplicated entries
 *   (CUE/BIN→CUE, M3U→M3U, GDI tracks, numbered tracks never become
 *   separate games — the scanner owns that dedup).
 * - Paths are canonical filesystem paths (`/storage/…`), built from
 *   [io.crystalnova.manager.storage.StorageLocations.canonicalPath]
 *   of the ROM tree plus the scanner's relative path. `content://`
 *   URIs never reach the metafile.
 * - Writes ONE Manager-owned file per populated system folder:
 *   `<system dir>/crystal-nova.metadata.pegasus.txt`, each holding only
 *   that system's `collection:`, `launch:` and `game:` blocks. The name
 *   matches Pegasus's `*.metadata.pegasus.txt` game-dir scanner
 *   pattern; local metadata files are scanned non-recursively at the
 *   top level of each registered game directory, so the file MUST live
 *   inside the exact system folder that holds the games — never at the
 *   ROM root.
 * - The target folder is derived from each game's absolute path (the
 *   physical folder directly under the ROM root), NOT from the
 *   platform slug — folder aliases (ps1→psx, ds→nds) resolve to the
 *   real directory.
 * - Stale Manager-owned globals are deleted at build time: the v19
 *   file at the ROM root top level and the v18 file under the legacy
 *   config tree's `metafiles/`, so no collection can double-register.
 * - The write is tmp-file + rename inside each system folder. After
 *   the write, every file is reopened and read back: the inject fails
 *   unless each readback is non-empty and byte-identical to what was
 *   written.
 * - The ROM-root grant must carry WRITE. A read-only grant can scan
 *   but not receive metafiles; the inject refuses and names the
 *   RE-PICK ROM ROOT action.
 * - Regeneration happens only on explicit BUILD — never automatically.
 * - Best-effort, non-fatal: merging the populated system folders into
 *   the legacy `game_dirs.txt`. That merge only REGISTERS paths — it
 *   does NOT grant Pegasus filesystem permission (see
 *   [GAMEDIRS_PERMISSION_CAVEAT]).
 *
 * [gameSource] and [logger] are seams for unit tests; the folder-aware
 * writer/reader/deleter seams ([systemWriter], [systemReader],
 * [systemDeleter], [romRootDeleter]) and the legacy-tree seams
 * ([legacyRead], [legacyWrite], [legacyDelete], [legacyTreePicked])
 * default to SAF-backed implementations. Null context means the
 * SAF defaults fail closed.
 */
class PegasusLibrary(
    private val context: Context?,
    private val prefs: KeyValueStore,
    private val locations: io.crystalnova.manager.storage.StorageLocations,
    internal var gameSource: (suspend () -> List<ScannedGame>)? = null,
    private val logger: (tag: String, msg: String, err: Throwable?) -> Unit =
        { tag, msg, err -> Log.w(tag, msg, err) },
) {
    companion object {
        private const val TAG = "PegasusLibrary"

        /**
         * Scratch names for the tmp-file + rename swap, written inside
         * each system folder beside the metafile. Deliberately do NOT
         * look like Pegasus metadata files (Pegasus's game-dir scan
         * only recognizes `*.metadata.pegasus.txt` /
         * `*.metadata.txt` etc.), so the swap is invisible to Pegasus.
         */
        internal const val TMP_NAME = "crystal-nova-manager-write.tmp"
        internal const val BACKUP_NAME = "crystal-nova-manager-backup.tmp"

        /** Last verified inject, stored as "metafiles|games". */
        internal const val KEY_LAST_INJECT = "pegasus_last_inject"

        /** "folder|bytes|games" lines for every system metafile written. */
        internal const val KEY_SYSTEM_METAFILES = "pegasus_system_metafiles"

        /** Display string of the last game_dirs.txt merge outcome. */
        internal const val KEY_GAMEDIRS_STATUS = "pegasus_gamedirs_status"
    }

    val profiles = LauncherProfileStore(prefs)

    /**
     * Grant-check seams. Production defaults hit the persisted ROM-root
     * grant in [io.crystalnova.manager.storage.StorageLocations];
     * unit tests override them because ContentResolver/DocumentFile
     * are unavailable on the JVM.
     */
    internal var checkRomAccess: () -> Boolean = { locations.hasAccess(LocationKind.ROM) }
    internal var checkRomWritable: () -> Boolean = { locations.hasWriteAccess(LocationKind.ROM) }
    internal var describeUri: (String) -> String = { locations.displayPath(it) }

    /** Canonical filesystem path of the ROM root, e.g. /storage/emulated/0/ROMs. */
    internal var romRootCanonical: () -> String? = {
        locations.romTreeUri()?.let { locations.canonicalPath(it) }
    }

    /** Writes bytes to `<romRoot>/<folder>/crystal-nova.metadata.pegasus.txt`. */
    internal var systemWriter: ((folder: String, bytes: ByteArray) -> Boolean)? = null

    /** Reads back `<romRoot>/<folder>/crystal-nova.metadata.pegasus.txt`. */
    internal var systemReader: ((folder: String) -> ByteArray?)? = null

    /** Deletes the Manager-owned metafile inside `<romRoot>/<folder>`. */
    internal var systemDeleter: ((folder: String) -> Boolean)? = null

    /** Deletes a Manager-owned file at the ROM root top level (v19 stale global). */
    internal var romRootDeleter: ((name: String) -> Boolean)? = null

    /** Whether the legacy Pegasus config tree was ever picked (for game_dirs.txt). */
    internal var legacyTreePicked: () -> Boolean =
        { prefs.getString(PegasusConfig.KEY_TREE_URI) != null }

    /** Reads a file under the legacy Pegasus config tree, e.g. "game_dirs.txt". */
    internal var legacyRead: ((relativePath: String) -> ByteArray?)? = null

    /** Writes (truncating) a top-level file under the legacy Pegasus config tree. */
    internal var legacyWrite: ((relativePath: String, bytes: ByteArray) -> Boolean)? = null

    /** Deletes a file under the legacy Pegasus config tree (v18 stale global). */
    internal var legacyDelete: ((relativePath: String) -> Boolean)? = null

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
            /** One Manager-owned metadata file per populated system folder. */
            val metafiles: Int,
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
            /** Best-effort game_dirs.txt registration outcome. */
            val gameDirs: GameDirsOutcome,
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
            val organized = MetafileGenerator.organizeGames(
                sysGames.map { MetafileGenerator.RawEntry(it.title, it.path) },
            )
            collections += MetafileGenerator.Collection(
                name = platform.displayName,
                shortname = MetafileGenerator.shortnameFor(platform.slug),
                launchLines = profile.launchLines(),
                games = organized,
            )
        }
        return BuiltLibrary(collections, unconfigured, unknownFolders)
    }

    /**
     * Full explicit-refresh flow: validate grants, scan, generate,
     * write one metadata file per populated system folder. Never throws
     * for expected failure modes — they come back as
     * [InjectOutcome.Failed] with a UI-ready message.
     *
     * v20: each recognized populated system folder gets its own
     * `crystal-nova.metadata.pegasus.txt` holding only that system's
     * collection. BUILD is gated on the ROM-root grant carrying WRITE.
     * Stale Manager-owned globals (v19 ROM-root file, v18 legacy
     * `metafiles/` file) are deleted so collections can't
     * double-register. `game_dirs.txt` is merged best-effort.
     */
    suspend fun inject(): InjectOutcome {
        // v20 gate: the ROM-root grant must carry WRITE — a read-only
        // grant can scan but cannot receive metafiles.
        if (!checkRomWritable()) {
            return InjectOutcome.Failed("ROM ROOT NOT WRITABLE — RE-PICK ROM ROOT TO GRANT WRITE ACCESS")
        }
        if (locations.romTreeUri() == null || !checkRomAccess()) {
            return InjectOutcome.Failed("ROM LIBRARY NOT AVAILABLE")
        }
        val romRoot = romRootCanonical()
            ?: return InjectOutcome.Failed("ROM LIBRARY NOT AVAILABLE")
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

        val writer = systemWriter ?: ::defaultSystemWrite
        val reader = systemReader ?: ::defaultSystemRead

        // One file per populated system folder: group each collection's
        // games by their exact physical folder (relative to the ROM
        // root), emit only that system's collection into it, and verify
        // every file byte-for-byte. Any mismatch fails the whole BUILD.
        val written = mutableListOf<WrittenSystem>()
        for (collection in collections) {
            val byFolder = collection.games.groupBy { game ->
                val path = game.files.firstOrNull()
                    ?: return InjectOutcome.Failed("BAD GAME PATH — ${game.title}")
                systemFolder(romRoot, path)
                    ?: return InjectOutcome.Failed("BAD GAME PATH — $path")
            }
            for ((folder, folderGames) in byFolder) {
                val text = try {
                    MetafileGenerator.generate(listOf(collection.copy(games = folderGames)))
                } catch (e: IllegalArgumentException) {
                    logger(TAG, "bad game path in metafile: $folder", e)
                    return InjectOutcome.Failed("BAD GAME PATH — ${e.message}")
                }
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (!writeSystemFile(writer, folder, bytes)) {
                    return InjectOutcome.Failed(
                        "WRITE FAILED — $folder — CHECK THE ROM ROOT GRANT",
                    )
                }
                if (!verifySystemFile(reader, folder, bytes)) {
                    return InjectOutcome.Failed(
                        "METAFILE VERIFY FAILED — READBACK DID NOT MATCH ($folder)",
                    )
                }
                written += WrittenSystem(folder, bytes.size.toLong(), folderGames.size)
            }
        }

        // Retire stale Manager-owned globals so no collection can be
        // double-registered (v19 ROM-root file, v18 legacy metafiles
        // file). Best-effort — never fails the BUILD.
        deleteStaleGlobals()

        // Forget metafiles for system folders that no longer have games,
        // so removed systems leave no phantom collections.
        removePhantomSystems(written.map { it.folder }.toSet())

        // Best-effort: register the populated system folders in the
        // legacy game_dirs.txt. This only POINTS Pegasus at the folders
        // — it does NOT grant Pegasus filesystem permission.
        val gameDirs = mergeGameDirsTxt(written.map { "$romRoot/${it.folder}" })

        noteSystemMetafiles(written)
        noteLastInject(written.size, written.sumOf { it.games })
        noteGameDirsOutcome(gameDirs)

        return InjectOutcome.Ok(
            collections = collections.size,
            games = written.sumOf { it.games },
            metafiles = written.size,
            unknownFolders = built.unknownFolders,
            skippedNoLauncher = built.unconfiguredSystems,
            gameDirs = gameDirs,
        )
    }

    private data class WrittenSystem(
        val folder: String,
        val bytes: Long,
        val games: Int,
    )

    private fun writeSystemFile(
        writer: (String, ByteArray) -> Boolean,
        folder: String,
        bytes: ByteArray,
    ): Boolean = try {
        writer(folder, bytes)
    } catch (e: Exception) {
        logger(TAG, "system metafile write failed: $folder", e)
        false
    }

    /**
     * Readback verification per system file. Reopens the metafile the
     * writer just wrote and requires nonzero bytes exactly equal to
     * what was written. Never throws.
     */
    private fun verifySystemFile(
        reader: (String) -> ByteArray?,
        folder: String,
        bytes: ByteArray,
    ): Boolean {
        val readBack = try {
            reader(folder)
        } catch (e: Exception) {
            logger(TAG, "system metafile readback failed: $folder", e)
            null
        }
        if (readBack == null || readBack.isEmpty()) {
            logger(TAG, "system metafile readback missing or empty: $folder", null)
            return false
        }
        if (!readBack.contentEquals(bytes)) {
            logger(TAG, "system metafile readback bytes differ: $folder", null)
            return false
        }
        return true
    }

    /**
     * Removes the Manager-owned global metafiles from earlier releases:
     * the v19 file at the ROM root top level and the v18 file under the
     * legacy config tree's `metafiles/`. Best-effort — a missing grant
     * or an already-absent file never fails the BUILD.
     */
    private fun deleteStaleGlobals() {
        val rootDelete = romRootDeleter ?: ::defaultRomRootDelete
        try {
            rootDelete(MetafileGenerator.FILE_NAME)
        } catch (e: Exception) {
            logger(TAG, "stale ROM-root metafile delete failed", e)
        }
        val legacyDel = legacyDelete ?: ::defaultLegacyDelete
        try {
            legacyDel("${MetafileGenerator.METAFILES_DIR}/${MetafileGenerator.FILE_NAME}")
        } catch (e: Exception) {
            logger(TAG, "stale legacy metafile delete failed", e)
        }
    }

    private fun removePhantomSystems(currentFolders: Set<String>) {
        val previous = readPersistedSystemFolders()
        if (previous.isEmpty()) return
        val deleter = systemDeleter ?: ::defaultSystemDelete
        for (folder in previous - currentFolders) {
            try {
                deleter(folder)
            } catch (e: Exception) {
                logger(TAG, "phantom system metafile delete failed: $folder", e)
            }
        }
    }

    /**
     * Merges the populated system-folder paths into the legacy Pegasus
     * `game_dirs.txt` (one absolute path per line). Idempotent,
     * preserves foreign entries/comments/order, never deletes anything.
     *
     * IMPORTANT: writing this file does NOT grant Pegasus filesystem
     * permission. It only tells Pegasus WHERE to look. Pegasus still
     * needs its own all-files access (it self-prompts on first run);
     * without it, games are silently skipped at scan. Never imply
     * otherwise in UI copy — see [GAMEDIRS_PERMISSION_CAVEAT].
     */
    private fun mergeGameDirsTxt(systemPaths: List<String>): GameDirsOutcome {
        if (!try { legacyTreePicked() } catch (_: Exception) { false }) {
            // The legacy tree was never picked — say so honestly instead
            // of claiming registration succeeded.
            return GameDirsOutcome.SkippedNoGrant
        }
        val read = legacyRead ?: ::defaultLegacyRead
        val write = legacyWrite ?: ::defaultLegacyWrite
        return try {
            val existing = read("game_dirs.txt")?.toString(Charsets.UTF_8)
            when (val merged = mergeGameDirs(existing, systemPaths)) {
                is GameDirsMerge.Unchanged -> GameDirsOutcome.Unchanged
                is GameDirsMerge.Changed ->
                    if (write("game_dirs.txt", merged.content.toByteArray(Charsets.UTF_8))) {
                        GameDirsOutcome.Updated(merged.added)
                    } else {
                        GameDirsOutcome.Failed("WRITE FAILED")
                    }
            }
        } catch (e: SecurityException) {
            // The stored grant is gone — surface it, don't claim success.
            GameDirsOutcome.SkippedNoGrant
        } catch (e: Exception) {
            logger(TAG, "game_dirs.txt merge failed", e)
            GameDirsOutcome.Failed("ERROR")
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics — per-system report
    // ------------------------------------------------------------------

    data class SystemMetafileDiag(
        /** System folder relative to the ROM root, e.g. "gba". */
        val folder: String,
        val present: Boolean,
        val bytes: Long?,
        /** Games emitted into this file at the last verified BUILD. */
        val games: Int,
    )

    data class SystemMetafileReport(
        val systems: List<SystemMetafileDiag>,
        /** Aggregate from live presence, e.g. "13 SYSTEM METAFILES · 147 GAMES". */
        val aggregate: String,
        /** The last game_dirs.txt merge outcome, persisted across restarts. */
        val gameDirs: GameDirsOutcome,
    )

    /**
     * Per-system metadata report for Diagnostics: for every system
     * folder written by the last verified BUILD, whether the
     * Manager-owned file is present now, its byte size, and the game
     * count emitted into it — plus the aggregate and the last
     * game_dirs.txt merge outcome. Never throws.
     */
    fun systemMetafileReport(): SystemMetafileReport {
        val reader = systemReader ?: ::defaultSystemRead
        val entries = readPersistedSystemMetafiles()
        val rows = entries.map { (folder, games) ->
            val live = try { reader(folder) } catch (_: Exception) { null }
            SystemMetafileDiag(folder, live != null, live?.size?.toLong(), games)
        }
        val present = rows.filter { it.present }
        return SystemMetafileReport(
            systems = rows,
            aggregate = "${present.size} SYSTEM METAFILES · ${present.sumOf { it.games }} GAMES",
            gameDirs = lastGameDirsOutcome(),
        )
    }

    /** "13 SYSTEM METAFILES · 147 GAMES" from the last verified inject, or "NONE". */
    fun lastInjectSummary(): String {
        val raw = prefs.getString(KEY_LAST_INJECT) ?: return "NONE"
        val parts = raw.split('|')
        val metafiles = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val games = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return "$metafiles SYSTEM METAFILES · $games GAMES"
    }

    /**
     * Friendly ROM-root metafile target for the setup screen row, e.g.
     * "INTERNAL STORAGE /ROMs · WRITABLE · PER-SYSTEM METAFILES".
     * Never a raw content:// URI.
     */
    fun metafileTargetDisplay(): String {
        val uri = locations.romTreeUri() ?: return "NOT SELECTED"
        val path = describeUri(uri)
        return if (checkRomWritable()) {
            "$path · WRITABLE · PER-SYSTEM METAFILES"
        } else {
            "$path · NOT WRITABLE — RE-PICK ROM ROOT · PER-SYSTEM METAFILES"
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private fun noteSystemMetafiles(written: List<WrittenSystem>) {
        try {
            val lines = written.joinToString("\n") { "${it.folder}|${it.bytes}|${it.games}" }
            prefs.putString(KEY_SYSTEM_METAFILES, lines)
        } catch (e: Exception) {
            logger(TAG, "persist system metafiles failed", e)
        }
    }

    private fun readPersistedSystemMetafiles(): List<Pair<String, Int>> =
        prefs.getString(KEY_SYSTEM_METAFILES)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 3) null else parts[0] to (parts[2].toIntOrNull() ?: 0)
            }
            ?.toList()
            ?: emptyList()

    private fun readPersistedSystemFolders(): Set<String> =
        readPersistedSystemMetafiles().map { it.first }.toSet()

    /** Persists the counts of the last VERIFIED inject. */
    private fun noteLastInject(metafiles: Int, games: Int) {
        try {
            prefs.putString(KEY_LAST_INJECT, "$metafiles|$games")
        } catch (e: Exception) {
            logger(TAG, "persist last inject failed", e)
        }
    }

    private fun noteGameDirsOutcome(outcome: GameDirsOutcome) {
        try {
            prefs.putString(KEY_GAMEDIRS_STATUS, outcome.display())
        } catch (e: Exception) {
            logger(TAG, "persist game_dirs status failed", e)
        }
    }

    /**
     * Reads back the persisted game_dirs.txt merge outcome. Must be the
     * exact inverse of [GameDirsOutcome.display]: the display strings are
     * what the Diagnostics panel shows, and a successful merge ("WRITTEN ·
     * N ADDED") must never read back as a failure — nor may a Failed
     * reason accumulate "SKIPPED —" prefixes across repeated BUILDs.
     */
    private fun lastGameDirsOutcome(): GameDirsOutcome {
        val raw = prefs.getString(KEY_GAMEDIRS_STATUS) ?: return GameDirsOutcome.SkippedNoGrant
        if (raw == GameDirsOutcome.Unchanged.display()) return GameDirsOutcome.Unchanged
        if (raw == GameDirsOutcome.SkippedNoGrant.display()) return GameDirsOutcome.SkippedNoGrant
        val updated = Regex("""WRITTEN · (\d+) ADDED""").find(raw)
        if (updated != null) return GameDirsOutcome.Updated(updated.groupValues[1].toInt())
        val failed = Regex("""^SKIPPED — (.*)$""").find(raw)
        if (failed != null) return GameDirsOutcome.Failed(failed.groupValues[1])
        return GameDirsOutcome.Failed(raw)
    }

    // ------------------------------------------------------------------
    // Production SAF defaults (used when tests don't override the seams)
    // ------------------------------------------------------------------

    private fun safFs(treeUri: String?): SafThemeFs? {
        val ctx = context ?: return null
        if (treeUri == null) return null
        return SafThemeFs(ctx) { treeUri }
    }

    private fun defaultSystemWrite(folder: String, bytes: ByteArray): Boolean {
        return try {
            val fs = safFs(locations.romTreeUri()) ?: return false
            val root = fs.root() ?: return false
            // The populated system folder must exist — games were scanned
            // inside it. Never create folders here.
            val dir = fs.find(root, folder)?.takeIf { fs.isDirectory(it) } ?: return false
            swapMetafile(dirSwapFs(fs, dir), bytes)
        } catch (e: Exception) {
            logger(TAG, "system metafile write failed: $folder", e)
            false
        }
    }

    private fun defaultSystemRead(folder: String): ByteArray? {
        return try {
            val fs = safFs(locations.romTreeUri()) ?: return null
            val root = fs.root() ?: return null
            val dir = fs.find(root, folder) ?: return null
            val node = fs.find(dir, MetafileGenerator.FILE_NAME) ?: return null
            fs.openInput(node).use { it.readBytes() }
        } catch (e: Exception) {
            logger(TAG, "system metafile read failed: $folder", e)
            null
        }
    }

    private fun defaultSystemDelete(folder: String): Boolean {
        return try {
            val fs = safFs(locations.romTreeUri()) ?: return false
            val root = fs.root() ?: return false
            val dir = fs.find(root, folder) ?: return true
            val node = fs.find(dir, MetafileGenerator.FILE_NAME) ?: return true
            fs.deleteRecursively(node)
        } catch (e: Exception) {
            logger(TAG, "system metafile delete failed: $folder", e)
            false
        }
    }

    private fun defaultRomRootDelete(name: String): Boolean {
        return try {
            val fs = safFs(locations.romTreeUri()) ?: return false
            val root = fs.root() ?: return false
            val node = fs.find(root, name) ?: return true
            fs.deleteRecursively(node)
        } catch (e: Exception) {
            logger(TAG, "ROM-root file delete failed: $name", e)
            false
        }
    }

    private fun defaultLegacyRead(relativePath: String): ByteArray? {
        val treeUri = prefs.getString(PegasusConfig.KEY_TREE_URI) ?: return null
        val fs = safFs(treeUri) ?: return null
        // Let a lost grant's SecurityException propagate so the caller
        // reports SkippedNoGrant instead of claiming "no file".
        val root = fs.root() ?: return null
        val node = walkTo(fs, root, relativePath) ?: return null
        return try {
            fs.openInput(node).use { it.readBytes() }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            logger(TAG, "legacy read failed: $relativePath", e)
            null
        }
    }

    private fun defaultLegacyWrite(relativePath: String, bytes: ByteArray): Boolean {
        return try {
            val treeUri = prefs.getString(PegasusConfig.KEY_TREE_URI) ?: return false
            val fs = safFs(treeUri) ?: return false
            val root = fs.root() ?: return false
            // game_dirs.txt lives at the legacy root top level; this
            // path deliberately refuses nested writes.
            val name = relativePath.trim('/')
            if (name.isEmpty() || name.contains('/')) return false
            var node = fs.find(root, name)
            if (node == null) {
                fs.createFile(root, name)
                node = fs.find(root, name) ?: return false
            }
            fs.openOutput(node).use { it.write(bytes) }
            true
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            logger(TAG, "legacy write failed: $relativePath", e)
            false
        }
    }

    private fun defaultLegacyDelete(relativePath: String): Boolean {
        return try {
            val treeUri = prefs.getString(PegasusConfig.KEY_TREE_URI) ?: return false
            val fs = safFs(treeUri) ?: return false
            val root = fs.root() ?: return false
            val node = walkTo(fs, root, relativePath) ?: return true
            fs.deleteRecursively(node)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            logger(TAG, "legacy delete failed: $relativePath", e)
            false
        }
    }

    private fun walkTo(fs: SafThemeFs, root: FsNode, relativePath: String): FsNode? {
        var node = root
        for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
            node = fs.find(node, segment) ?: return null
        }
        return node
    }

    /** Adapts a SafThemeFs scoped to one directory to the swap interface. */
    private fun dirSwapFs(fs: SafThemeFs, dir: FsNode): MetafileSwapFs =
        object : MetafileSwapFs {
            override fun find(name: String): Boolean = fs.find(dir, name) != null

            override fun create(name: String): Boolean = try {
                fs.createFile(dir, name)
                true
            } catch (_: Exception) {
                false
            }

            override fun write(name: String, bytes: ByteArray): Boolean = try {
                val node = fs.find(dir, name) ?: return false
                fs.openOutput(node).use { it.write(bytes) }
                true
            } catch (_: Exception) {
                false
            }

            override fun rename(from: String, to: String): Boolean = try {
                val node = fs.find(dir, from) ?: return false
                fs.rename(node, to)
            } catch (_: Exception) {
                false
            }

            override fun delete(name: String) {
                try {
                    fs.find(dir, name)?.let { fs.deleteRecursively(it) }
                } catch (_: Exception) {
                    // Best-effort scratch cleanup inside the swap.
                }
            }
        }
}

/**
 * The system folder directly under the ROM root that physically
 * contains [gamePath] — the EXACT scanned folder, derived from the
 * game's absolute path rather than the platform slug, so aliases
 * (ps1→psx, ds→nds) resolve to the real directory. Returns null when
 * the path is not inside the ROM root.
 */
internal fun systemFolder(romRoot: String, gamePath: String): String? {
    val root = romRoot.trimEnd('/')
    if (!gamePath.startsWith("$root/")) return null
    val rest = gamePath.removePrefix("$root/")
    val slash = rest.indexOf('/')
    return if (slash < 0) null else rest.substring(0, slash)
}

/**
 * Outcome of the best-effort `game_dirs.txt` registration merge.
 *
 * [SkippedNoGrant] is honest, not fatal: the legacy Pegasus tree was
 * never picked (or the grant is gone), so Crystal never claimed to have
 * registered anything. [Failed] likewise never fails the BUILD — the
 * metadata files are the deliverable; `game_dirs.txt` is a convenience.
 * Public because it is part of [PegasusLibrary.InjectOutcome.Ok] and
 * [PegasusLibrary.SystemMetafileReport], which the UI layer consumes.
 */
sealed interface GameDirsOutcome {
    /** The merge added [added] missing system-folder paths. */
    data class Updated(val added: Int) : GameDirsOutcome

    /** Every system path was already listed — nothing changed. */
    object Unchanged : GameDirsOutcome

    /** No legacy-tree grant to write through — surfaced, not fatal. */
    object SkippedNoGrant : GameDirsOutcome

    /** The merge errored — surfaced, not fatal. */
    data class Failed(val reason: String) : GameDirsOutcome

    fun display(): String = when (this) {
        is Updated -> "WRITTEN · $added ADDED"
        Unchanged -> "UP TO DATE · NOTHING TO ADD"
        SkippedNoGrant -> "SKIPPED — NO LEGACY CONFIG GRANT"
        is Failed -> "SKIPPED — $reason"
    }
}

/**
 * Stated explicitly in code so no UI copy can imply otherwise: writing
 * `game_dirs.txt` does NOT grant Pegasus filesystem permission. It only
 * tells Pegasus WHERE to look. Pegasus still needs its own all-files
 * access (it self-prompts on first run); without it, games are silently
 * skipped at scan.
 */
internal const val GAMEDIRS_PERMISSION_CAVEAT =
    "game_dirs.txt only registers paths — it does not grant Pegasus filesystem permission."

internal sealed interface GameDirsMerge {
    object Unchanged : GameDirsMerge
    data class Changed(val content: String, val added: Int) : GameDirsMerge
}

/**
 * Pure merge for Pegasus's legacy `game_dirs.txt`.
 *
 * Verified against current Pegasus source
 * (src/backend/AppSettings.cpp::parse_gamedirs): the file is read from
 * every dir in configDirs() — on Android that includes the legacy
 * `<storage>/pegasus-frontend` tree when it exists. Each line is
 * passed to the callback as-is: lines starting with `#` are skipped,
 * everything else is kept VERBATIM — no whitespace trimming, and blank
 * lines are NOT skipped (they reach the callback as empty strings).
 * Settings::gameDirs() then applies pretty_path() (QDir::cleanPath:
 * duplicate separators collapsed, trailing slash stripped — still no
 * whitespace trim).
 *
 * So: appends the missing Crystal system-folder paths while preserving
 * foreign entries, comments, blank lines, and original order; never
 * deletes, reorders, or duplicates; and never emits blank lines of its
 * own (a blank line would surface as a junk entry in the UI list).
 * Pure Kotlin, JVM-testable.
 */
internal fun mergeGameDirs(
    existing: String?,
    crystalPaths: List<String>,
): GameDirsMerge {
    val known = existing
        ?.lines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?.toSet()
        ?: emptySet()
    val missing = crystalPaths.distinct().filter { it !in known }
    if (missing.isEmpty()) return GameDirsMerge.Unchanged

    val sb = StringBuilder()
    if (existing.isNullOrBlank()) {
        sb.append("# Pegasus game directories — one absolute path per line.\n")
        sb.append("# Crystal Nova Manager appends its system folders below.\n")
        sb.append("# Your own entries are preserved; duplicates are never added.\n")
    } else {
        sb.append(existing.trimEnd('\n', '\r'))
        sb.append('\n')
    }
    for (path in missing) sb.append(path).append('\n')
    return GameDirsMerge.Changed(sb.toString(), missing.size)
}

/**
 * Minimal filesystem seam for the metafile swap, so the
 * backup/restore ordering is unit-testable without DocumentFile. The
 * seam is a single directory — v20 callers hand it one system folder
 * at a time (the ROM-root global of v19 is retired).
 */
internal interface MetafileSwapFs {
    fun find(name: String): Boolean
    fun create(name: String): Boolean
    fun write(name: String, bytes: ByteArray): Boolean
    fun rename(from: String, to: String): Boolean
    fun delete(name: String)
}

/**
 * SAF write of the single Manager-owned metafile: tmp-file + rename in
 * the caller's directory (v20: one system folder at a time). The swap
 * is backup/restore: the previous Manager-owned metafile (if any) is
 * moved aside to a clearly non-metadata backup name first, so a failed
 * rename restores it instead of losing it. Never touches any other
 * metadata file. Returns true only when the final metafile holds
 * [bytes].
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
