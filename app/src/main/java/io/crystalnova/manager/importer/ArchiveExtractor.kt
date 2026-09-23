package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.util.zip.ZipInputStream

/**
 * Streaming archive extraction into a [ThemeFs] staging directory.
 *
 * - Entry-by-entry through SAF output streams with a bounded 32 KiB
 *   buffer — a multi-GB disc image never sits in memory whole.
 * - Zip-slip-safe: every entry path goes through [sanitizeEntryPath]
 *   (no `..`, no absolute paths, no drive letters); directories are
 *   materialized segment-by-segment inside the staging dir only.
 * - The scan-time [ImportPlan.unwrapDepth] is applied so the staged
 *   layout is byte-identical to what the plan promised; BIN/CUE and
 *   multi-file units are kept together, never split or flattened.
 * - One archive at a time — the engine never runs two extractions
 *   concurrently (see [ImportEngine]).
 */
class ArchiveExtractor(private val opener: ArchiveStreamOpener) {

    data class ExtractReport(
        val fileCount: Int,
        val bytesWritten: Long,
    )

    @Throws(ArchiveReadException::class)
    fun extract(
        fs: ThemeFs,
        ref: ArchiveRef,
        stagingDir: FsNode,
        plan: ImportPlan,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ): ExtractReport {
        return when (ref.kind) {
            ArchiveKind.ZIP -> extractZip(fs, ref, stagingDir, plan, onProgress)
            ArchiveKind.SEVEN_Z -> extract7z(fs, ref, stagingDir, plan, onProgress)
            else -> throw ArchiveReadException("Unsupported archive kind: ${ref.kind}")
        }
    }

    private fun extractZip(
        fs: ThemeFs,
        ref: ArchiveRef,
        stagingDir: FsNode,
        plan: ImportPlan,
        onProgress: (Long, Long) -> Unit,
    ): ExtractReport {
        var files = 0
        var bytes = 0L
        try {
            opener.openInput(ref.uri).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !entry.name.endsWith('/')) {
                            val target = payloadSegments(entry.name, plan)
                            if (target != null) {
                                val total = plan.payloadBytes.coerceAtLeast(1)
                                bytes += writeEntry(fs, stagingDir, target, zip) { chunkBytes ->
                                    // Per-chunk progress: a single giant entry
                                    // (e.g. a PS2 ISO) would otherwise report
                                    // nothing for its entire multi-minute write.
                                    onProgress(bytes + chunkBytes, total)
                                }
                                files++
                                if (files > MAX_ENTRIES) {
                                    throw ArchiveReadException("Archive has too many entries")
                                }
                                onProgress(bytes, total)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: ArchiveReadException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveReadException("Extraction failed: ${e.message}", e)
        }
        if (files == 0) throw ArchiveReadException("Archive contained no extractable files")
        return ExtractReport(files, bytes)
    }

    private fun extract7z(
        fs: ThemeFs,
        ref: ArchiveRef,
        stagingDir: FsNode,
        plan: ImportPlan,
        onProgress: (Long, Long) -> Unit,
    ): ExtractReport {
        var files = 0
        var bytes = 0L
        try {
            opener.openChannel(ref.uri).use { handle ->
                SevenZFile(handle.channel).use { sevenZ ->
                    var entry = sevenZ.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val target = payloadSegments(entry.name, plan)
                            if (target != null) {
                                val total = plan.payloadBytes.coerceAtLeast(1)
                                sevenZ.getInputStream(entry).use { content ->
                                    bytes += writeEntry(fs, stagingDir, target, content) { chunkBytes ->
                                        // Per-chunk progress: a single giant entry
                                        // would otherwise report nothing until done.
                                        onProgress(bytes + chunkBytes, total)
                                    }
                                }
                                files++
                                if (files > MAX_ENTRIES) {
                                    throw ArchiveReadException("Archive has too many entries")
                                }
                                onProgress(bytes, total)
                            }
                        }
                        entry = sevenZ.nextEntry
                    }
                }
            }
        } catch (e: ArchiveReadException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveReadException("Extraction failed: ${e.message}", e)
        }
        if (files == 0) throw ArchiveReadException("Archive contained no extractable files")
        return ExtractReport(files, bytes)
    }

    /**
     * Maps an archive entry to its payload-relative segments, applying
     * the plan's wrapper unwrap. Null = skip (unsafe path, or a
     * wrapper-dir entry that unwraps to nothing).
     */
    private fun payloadSegments(entryName: String, plan: ImportPlan): List<String>? {
        val segments = sanitizeEntryPath(entryName) ?: return null
        if (plan.unwrapDepth >= segments.size) return null
        return segments.drop(plan.unwrapDepth)
    }

    private fun writeEntry(
        fs: ThemeFs,
        stagingDir: FsNode,
        segments: List<String>,
        content: java.io.InputStream,
        onChunk: (chunkBytesDone: Long) -> Unit = {},
    ): Long {
        val parent = ensureDir(fs, stagingDir, segments.dropLast(1))
        val name = segments.last()
        val existing = fs.find(parent, name)
        val node = when {
            existing == null -> fs.createFile(parent, name)
            fs.isDirectory(existing) ->
                throw ArchiveReadException("Entry collides with a directory: $name")
            else -> existing // Truncate-and-rewrite via openOutput.
        }
        var written = 0L
        fs.openOutput(node).use { out ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = content.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                written += n
                onChunk(written)
            }
        }
        return written
    }

    private fun ensureDir(fs: ThemeFs, root: FsNode, segments: List<String>): FsNode {
        var dir = root
        for (seg in segments) {
            val existing = fs.find(dir, seg)
            dir = when {
                existing == null -> fs.mkdir(dir, seg)
                fs.isDirectory(existing) -> existing
                else -> throw ArchiveReadException("Entry collides with a file: $seg")
            }
        }
        return dir
    }

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_ENTRIES = 50_000
    }
}
