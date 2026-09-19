package io.crystalnova.manager.scraper.esde

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.scraper.provider.PegasusMetadataReader
import io.crystalnova.manager.scraper.store.ScraperStorage
import io.crystalnova.manager.storage.LocationKind
import io.crystalnova.manager.storage.StorageLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes [MediaHealthCheck] against the real SAF trees.
 * Read-only: walks every `*.metadata.pegasus.txt` on the card, loads
 * index.json, and verifies each claimed asset file exists on disk.
 * Never writes anything. Must run off the UI thread.
 */
class MediaHealthCheckRunner(
    private val context: Context,
    private val locations: StorageLocations,
    private val storage: ScraperStorage,
) {
    private val reader = PegasusMetadataReader

    suspend fun run(onProgress: (String) -> Unit = {}): MediaHealthCheck.HealthReport =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()

            // -- ROM tree: find every *.metadata.pegasus.txt -----------------
            onProgress("FINDING METADATA FILES…")
            val romUri = locations.romTreeUri()
            if (romUri == null || !locations.hasAccess(LocationKind.ROM)) {
                errors.add("ROM LIBRARY NOT AVAILABLE — PICK YOUR ROMS FOLDER IN SETTINGS → ROM LIBRARY FIRST.")
                return@withContext MediaHealthCheck.HealthReport(
                    metafilesScanned = 0,
                    metafilePaths = emptyList(),
                    gamesChecked = 0,
                    findings = emptyList(),
                    duplicates = emptyList(),
                    shortnameMismatches = MediaHealthCheck.checkShortnameRoundTrip(),
                    indexDrift = emptyList(),
                    errors = errors,
                )
            }
            val metafiles = findMetafiles(romUri)
            onProgress("READING ${metafiles.size} METADATA FILES…")

            // -- Parse each metafile ----------------------------------------
            // metafile display path -> parsed entries
            val parsed = LinkedHashMap<String, List<PegasusMetadataReader.Entry>>()
            // collection name -> metafile display paths declaring it
            val collectionFiles = LinkedHashMap<String, MutableSet<String>>()
            for ((displayPath, file) in metafiles) {
                val text = try {
                    context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    errors.add("$displayPath: UNREADABLE (${e.message ?: e.javaClass.simpleName})")
                    null
                } ?: continue
                val entries = try {
                    reader.parse(text)
                } catch (e: Exception) {
                    errors.add("$displayPath: PARSE FAILED (${e.message ?: e.javaClass.simpleName})")
                    continue
                }
                parsed[displayPath] = entries
                for (e in entries) {
                    val c = e.collection?.takeIf { it.isNotBlank() } ?: continue
                    collectionFiles.getOrPut(c) { LinkedHashSet() }.add(displayPath)
                }
            }

            // -- index.json ---------------------------------------------------
            onProgress("READING INDEX…")
            val indexText = try {
                storage.loadIndexJson()
            } catch (e: Exception) {
                errors.add("INDEX.JSON UNREADABLE (${e.message ?: e.javaClass.simpleName})")
                null
            }
            val maps = indexText?.let { MediaHealthCheck.parseIndexMaps(it) }
            if (indexText != null && maps == null) {
                errors.add("INDEX.JSON PRESENT BUT UNPARSEABLE OR WRONG VERSION — THEME WOULD SHOW NO ART AT ALL.")
            }
            val byId = maps?.byId ?: emptyMap()
            val byTitle = maps?.byTitle ?: emptyMap()

            // -- Classify every game the way the theme resolves it -----------
            val findings = mutableListOf<MediaHealthCheck.GameFinding>()
            val checkedKeys = HashSet<String>()
            var done = 0
            val total = parsed.values.sumOf { it.size }
            for ((displayPath, entries) in parsed) {
                for (entry in entries) {
                    done++
                    if (done % 25 == 0 || done == total) {
                        onProgress("CHECKING GAME $done/$total…")
                    }
                    val finding = MediaHealthCheck.classify(
                        metafile = displayPath,
                        entry = entry,
                        byId = byId,
                        byTitle = byTitle,
                        assetBytes = { platform, gameId, stem ->
                            // Stems the theme has no slot for can never be
                            // requested, so they can never blank a game.
                            val slot = MediaHealthCheck.SLOT_BY_STEM[stem] ?: return@classify 1L
                            try {
                                storage.assetLength(platform, gameId, slot)
                            } catch (_: Exception) {
                                null
                            }
                        },
                    )
                    findings.add(finding)
                    if (finding.themeKey.isNotEmpty()) checkedKeys.add(finding.themeKey)
                }
            }

            // -- Index -> disk drift for entries no metafile covered ----------
            onProgress("VERIFYING INDEX FILES…")
            val drift = mutableListOf<MediaHealthCheck.IndexDrift>()
            if (maps != null) {
                for ((key, ie) in byId) {
                    if (key in checkedKeys) continue
                    val missing = ie.assets.sorted().filter { stem ->
                        // Unknown stems are never requested by the theme.
                        val slot = MediaHealthCheck.SLOT_BY_STEM[stem] ?: return@filter false
                        val len = try {
                            storage.assetLength(ie.platform, ie.gameId, slot)
                        } catch (_: Exception) {
                            null
                        }
                        len == null || len <= 0L
                    }
                    if (missing.isNotEmpty()) {
                        drift.add(MediaHealthCheck.IndexDrift(key, ie.title, missing))
                    }
                }
                drift.sortBy { it.key }
            }

            MediaHealthCheck.HealthReport(
                metafilesScanned = parsed.size,
                metafilePaths = parsed.keys.sorted(),
                gamesChecked = findings.size,
                findings = findings.sortedWith(
                    compareBy(
                        { it.health != MediaHealthCheck.GameHealth.OK },
                        { it.metafile },
                        { it.title.lowercase() },
                    ),
                ),
                duplicates = MediaHealthCheck.findDuplicateCollections(collectionFiles),
                shortnameMismatches = MediaHealthCheck.checkShortnameRoundTrip(),
                indexDrift = drift,
                errors = errors,
            )
        }

    /**
     * Every `*.metadata.pegasus.txt` anywhere under the ROM tree,
     * returned as display-path -> DocumentFile. Recursive: stale files
     * can hide in subfolders or at the root.
     */
    private fun findMetafiles(treeUri: String): List<Pair<String, DocumentFile>> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
        val out = mutableListOf<Pair<String, DocumentFile>>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                continue
            }
            for (child in children) {
                try {
                    if (child.isDirectory) {
                        stack.add(child)
                    } else if ((child.name ?: "").endsWith(".metadata.pegasus.txt")) {
                        out.add(relativeDisplayPath(root, child) to child)
                    }
                } catch (_: Exception) {
                    // Skip unreadable nodes; the walk must not die on one bad file.
                }
            }
        }
        return out.sortedBy { it.first.lowercase() }
    }

    /** "GC/crystal-nova.metadata.pegasus.txt" style path relative to the tree root. */
    private fun relativeDisplayPath(root: DocumentFile, file: DocumentFile): String {
        val parts = ArrayDeque<String>()
        var cur: DocumentFile? = file
        while (cur != null && cur.uri != root.uri) {
            cur.name?.let { parts.addFirst(it) }
            cur = try {
                cur.parentFile
            } catch (_: Exception) {
                null
            }
        }
        return if (parts.isEmpty()) file.name ?: "?" else parts.joinToString("/")
    }
}
