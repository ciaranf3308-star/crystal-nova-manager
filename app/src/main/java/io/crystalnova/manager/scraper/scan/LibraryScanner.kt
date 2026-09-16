package io.crystalnova.manager.scraper.scan

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.scraper.match.PlatformTable
import io.crystalnova.manager.scraper.provider.PegasusMetadataReader
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** One discovered ROM file. Identity = platform + relativePath. */
data class RomEntry(
    val platformSlug: String,
    val platformLabel: String,
    /** Path relative to the games root, e.g. "gba/Mario Golf (E).gba". */
    val relativePath: String,
    val fileName: String,
    val size: Long,
    val lastModified: Long,
)

data class DiscoveredSystem(
    val platformSlug: String,
    val label: String,
    val gameCount: Int,
)

/**
 * Walks the user-picked games/ROMs folder (separate SAF grant from the
 * themes tree). Each immediate subdirectory is a system, resolved via
 * [PlatformTable.byFolderName]; unknown folders are still listed but
 * their games get UNKNOWN media treatment downstream.
 *
 * Also collects metadata.pegasus.txt files (read-only) found in the
 * games root and each system dir.
 */
class LibraryScanner(
    private val context: Context,
    private val treeUri: String,
) {
    data class ScanResult(
        val systems: List<DiscoveredSystem>,
        val games: List<RomEntry>,
        /** dir-relative-path ("", "gba", …) -> parsed Pegasus entries */
        val pegasusEntries: Map<String, List<PegasusMetadataReader.Entry>>,
    )

    suspend fun scan(): ScanResult {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: return ScanResult(emptyList(), emptyList(), emptyMap())
        val games = mutableListOf<RomEntry>()
        val systems = mutableListOf<DiscoveredSystem>()
        val pegasus = mutableMapOf<String, List<PegasusMetadataReader.Entry>>()

        readPegasusFile(root)?.let { pegasus[""] = it }
        coroutineContext.ensureActive()

        for (dir in root.listFiles()) {
            coroutineContext.ensureActive()
            if (!dir.isDirectory) continue
            val dirName = dir.name ?: continue
            val platform = PlatformTable.byFolderName(dirName)
            readPegasusFile(dir)?.let { pegasus[dirName] = it }

            val files = mutableListOf<DocumentFile>()
            collectRomFiles(dir, dirName, files, depth = 0)
            coroutineContext.ensureActive()

            // Prefer cue/m3u/gdi over sibling raw track files (per directory).
            // M3U contents are tiny; parse them to find the discs they list.
            val m3uRefsByParent: Map<String, Set<String>> = files
                .filter { it.name?.substringAfterLast('.', "")?.lowercase() == "m3u" }
                .groupBy(
                    { it.parentFile?.uri?.toString() ?: "" },
                    { parseM3uRefs(it) },
                )
                .mapValues { (_, sets) -> sets.flatten().toSet() }
            val namesByParent: Map<String, List<String>> = files.groupBy(
                { it.parentFile?.uri?.toString() ?: "" },
                { it.name ?: "" },
            )
            val skipByParent = skipNames(namesByParent, m3uRefsByParent)

            var count = 0
            for (f in files) {
                coroutineContext.ensureActive()
                val name = f.name ?: continue
                val parentKey = f.parentFile?.uri?.toString() ?: ""
                if (name in (skipByParent[parentKey] ?: emptySet())) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (platform != null && ext !in platform.extensions) continue
                if (platform == null && ext !in GENERIC_ROM_EXTENSIONS) continue
                val relPath = relativePath(root, f) ?: continue
                games += RomEntry(
                    platformSlug = platform?.slug ?: "unknown",
                    platformLabel = platform?.displayName ?: dirName,
                    relativePath = relPath,
                    fileName = name,
                    size = f.length(),
                    lastModified = f.lastModified(),
                )
                count++
            }
            systems += DiscoveredSystem(
                platformSlug = platform?.slug ?: "unknown",
                label = platform?.displayName ?: dirName,
                gameCount = count,
            )
        }
        return ScanResult(systems.sortedBy { it.label }, games, pegasus)
    }

    private fun collectRomFiles(
        dir: DocumentFile,
        dirName: String,
        out: MutableList<DocumentFile>,
        depth: Int,
    ) {
        if (depth > 2) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                if (f.name?.startsWith(".") != true) collectRomFiles(f, dirName, out, depth + 1)
            } else if (f.isFile) {
                out += f
            }
        }
    }

    private fun relativePath(root: DocumentFile, file: DocumentFile): String? {
        // Walk up via parentFile chain; DocumentFile supports getParentFile().
        val parts = mutableListOf<String>()
        var cur: DocumentFile? = file
        while (cur != null && cur.uri != root.uri) {
            parts += cur.name ?: return null
            cur = cur.parentFile
        }
        if (cur == null) return null
        return parts.reversed().joinToString("/")
    }

    private fun readPegasusFile(dir: DocumentFile): List<PegasusMetadataReader.Entry>? {
        val meta = dir.listFiles().firstOrNull {
            it.isFile && it.name.equals("metadata.pegasus.txt", ignoreCase = true)
        } ?: return null
        return try {
            context.contentResolver.openInputStream(meta.uri)?.use { stream ->
                PegasusMetadataReader.parse(stream.bufferedReader().readText())
            }?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** Basenames (lowercased) an M3U playlist references — its multi-disc set. */
    private fun parseM3uRefs(m3u: DocumentFile): Set<String> {
        return try {
            context.contentResolver.openInputStream(m3u.uri)?.bufferedReader()?.useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { it.replace('\\', '/').substringAfterLast('/').lowercase() }
                    .toSet()
            } ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    /** Opens a ROM for header reads. Caller closes the stream. */
    fun openInput(entry: RomEntry): InputStream? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
        var node: DocumentFile = root
        for (part in entry.relativePath.split('/')) {
            node = node.findFile(part) ?: return null
        }
        return try { context.contentResolver.openInputStream(node.uri) } catch (_: Exception) { null }
    }

    companion object {
        /** Extension allowlist for folders with no known platform. */
        val GENERIC_ROM_EXTENSIONS: Set<String> = setOf(
            "nes", "sfc", "smc", "n64", "z64", "gb", "gbc", "gba",
            "nds", "md", "smd", "gen", "sms", "gg", "iso", "cue",
            "chd", "gdi", "zip",
        )

        /** "Game (Track 01)" / "Game - disc 2" style suffixes on track files. */
        private val TRACK_SUFFIX =
            Regex("""[\s._\-(\[]*(track|disc|disk)\s*\d+[\s)\]]*$""", RegexOption.IGNORE_CASE)

        /** Dreamcast GDI track files are conventionally trackNN.bin/raw/img. */
        private val GDI_TRACK_NAME =
            Regex("""^track\d+\.(bin|raw|img)$""", RegexOption.IGNORE_CASE)
        private val GDI_TRACK_EXTS = setOf("bin", "raw", "img")

        /**
         * Pure multi-disc dedup. For each parent directory, returns the
         * file names to skip so a disc set yields one entry:
         * - `game.bin` (or `game (Track 1).bin`) loses to sibling `game.cue`
         * - `track01.bin`/`track02.raw` lose to a sibling `.gdi`
         * - files listed inside an `.m3u` lose to the `.m3u` itself
         *
         * [namesByParent] maps a parent-dir key to the file names in it;
         * [m3uRefsByParent] maps the same key to lowercased basenames the
         * directory's M3U files reference. Returned names keep their
         * original case to match [DocumentFile.name] lookups.
         */
        fun skipNames(
            namesByParent: Map<String, List<String>>,
            m3uRefsByParent: Map<String, Set<String>> = emptyMap(),
        ): Map<String, Set<String>> {
            return namesByParent.mapValues { (parent, names) ->
                val lower = names.map { it.lowercase() }
                val cueBases = lower
                    .filter { it.substringAfterLast('.', "") in setOf("cue", "gdi", "m3u") }
                    .map { it.substringBeforeLast('.') }
                    .toSet()
                val hasGdi = lower.any { it.substringAfterLast('.', "") == "gdi" }
                val m3uRefs = m3uRefsByParent[parent].orEmpty()
                val skip = mutableSetOf<String>()
                for (name in names) {
                    val ln = name.lowercase()
                    val ext = ln.substringAfterLast('.', "")
                    val base = ln.substringBeforeLast('.')
                    when {
                        ext == "bin" &&
                            (base in cueBases || base.replace(TRACK_SUFFIX, "") in cueBases) ->
                            skip += name
                        ext in GDI_TRACK_EXTS && GDI_TRACK_NAME.matches(name) && hasGdi ->
                            skip += name
                        ext != "m3u" && ln in m3uRefs ->
                            skip += name
                    }
                }
                skip
            }
        }
    }
}
