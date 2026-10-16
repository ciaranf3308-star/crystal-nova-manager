package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * Streaming archive extraction into a [ThemeFs] directory (a staging
 * dir, or — for single-file imports — the destination ROM folder
 * itself under a temp name; see [directFileName]).
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
 *
 * Open so unit tests can spy on (never-)invocation.
 *
 * 7z has an external fallback ([externalSevenZ]): when the in-app
 * SevenZFile path throws anything (e.g. an OOM from a 256MB LZMA
 * dictionary), the separate 7zz process extracts to a scratch dir and
 * the payload files are copied into the [ThemeFs] with the exact same
 * mapping (unwrap depth, single-write mode, zip-slip sanitization).
 */
open class ArchiveExtractor(
    private val opener: ArchiveStreamOpener,
    private val externalSevenZ: ExternalSevenZ? = null,
) {

    data class ExtractReport(
        val fileCount: Int,
        val bytesWritten: Long,
    )

    @Throws(ArchiveReadException::class)
    open fun extract(
        fs: ThemeFs,
        ref: ArchiveRef,
        stagingDir: FsNode,
        plan: ImportPlan,
        /**
         * Single-write mode for [ImportTarget.SingleFile]: only the
         * entry matching the plan's payload file is written, renamed
         * to this name. Every other entry is skipped, so a disc
         * archive's stray docs never land in the ROM folder. Null =
         * classic mode: every payload entry is written under its
         * planned path. When no entry matches, the usual "no
         * extractable files" error fires.
         *
         * Kept ahead of [onProgress] so existing trailing-lambda call
         * sites keep binding to the progress callback.
         */
        directFileName: String? = null,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ): ExtractReport {
        return when (ref.kind) {
            ArchiveKind.ZIP -> extractZip(fs, ref, stagingDir, plan, onProgress, directFileName)
            ArchiveKind.SEVEN_Z -> extract7z(fs, ref, stagingDir, plan, onProgress, directFileName)
            else -> throw ArchiveReadException("Unsupported archive kind: ${ref.kind}")
        }
    }

    private fun extractZip(
        fs: ThemeFs,
        ref: ArchiveRef,
        stagingDir: FsNode,
        plan: ImportPlan,
        onProgress: (Long, Long) -> Unit,
        directFileName: String?,
    ): ExtractReport {
        // Single-write mode: only this payload entry is kept.
        val directName = directFileName
            ?.takeIf { plan.target is ImportTarget.SingleFile }
        val directTarget = directName
            ?.let { listOf((plan.target as ImportTarget.SingleFile).fileName) }
        var files = 0
        var bytes = 0L
        try {
            opener.openInput(ref.uri).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !entry.name.endsWith('/')) {
                            val target = payloadSegments(entry.name, plan)
                            val outSegments = when {
                                target == null -> null
                                directTarget != null && target != directTarget -> null
                                directName != null -> listOf(directName)
                                else -> target
                            }
                            if (outSegments != null) {
                                val total = plan.payloadBytes.coerceAtLeast(1)
                                bytes += writeEntry(fs, stagingDir, outSegments, zip) { chunkBytes ->
                                    // Per-chunk progress: a single giant entry
                                    // (e.g. a PS2 ISO) would otherwise report
                                    // nothing for its entire multi-minute write.
                                    onProgress(bytes + chunkBytes, total)
                                }
                                files++
                                if (files > MAX_ENTRIES) {
                                    throw ArchiveReadException("Archive has too many entries")
                                }
                                // No post-entry onProgress here: the final
                                // per-chunk callback already reported this
                                // exact byte count — emitting it again would
                                // produce a duplicate consecutive value.
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

    /**
     * 7z extraction with external fallback: the in-app SevenZFile path
     * first; on any throwable the separate 7zz process tries.
     * Cancellation is never swallowed (and kills the 7zz process).
     * Only when both fail does the original in-app error propagate.
     */
    private fun extract7z(
        fs: ThemeFs,
        ref: ArchiveRef,
        stagingDir: FsNode,
        plan: ImportPlan,
        onProgress: (Long, Long) -> Unit,
        directFileName: String?,
    ): ExtractReport {
        val inAppError: Throwable = try {
            return extract7zInApp(fs, ref, stagingDir, plan, onProgress, directFileName)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            t
        }
        val ext = externalSevenZ
        if (ext != null && ext.runner.available) {
            try {
                return extract7zExternal(ext, fs, ref, stagingDir, plan, onProgress, directFileName)
            } catch (e: CancellationException) {
                runCatching { ext.runner.cancel() }
                throw e
            } catch (_: Throwable) {
                // Fall through to the original in-app error below.
            }
        }
        throw if (inAppError is ArchiveReadException) inAppError
        else ArchiveReadException("Extraction failed: ${inAppError.message}", inAppError)
    }

    /**
     * External 7z extraction: 7zz unpacks into a scratch dir on the
     * real filesystem (its process, its memory), then payload files
     * are copied into the [ThemeFs] with the same mapping the in-app
     * path applies — unwrap depth, single-write mode, zip-slip
     * sanitization. Scratch is always cleaned up.
     */
    private fun extract7zExternal(
        ext: ExternalSevenZ,
        fs: ThemeFs,
        ref: ArchiveRef,
        stagingDir: FsNode,
        plan: ImportPlan,
        onProgress: (Long, Long) -> Unit,
        directFileName: String?,
    ): ExtractReport {
        val archive = ext.archiveFile(ref)
            ?: throw ArchiveReadException("Extraction failed: cannot materialize archive")
        val scratch = File(ext.scratchDir, "x-${System.nanoTime()}").apply { mkdirs() }
        try {
            val total = plan.payloadBytes.coerceAtLeast(1)
            val ok = ext.runner.extract(archive.file, scratch) { frac ->
                onProgress((frac.coerceIn(0f, 1f) * total).toLong(), total)
            }
            if (!ok) throw ArchiveReadException("Extraction failed: 7zz reported an error")
            // Single-write mode: only this payload entry is kept.
            val directName = directFileName
                ?.takeIf { plan.target is ImportTarget.SingleFile }
            val directTarget = directName
                ?.let { listOf((plan.target as ImportTarget.SingleFile).fileName) }
            var files = 0
            var bytes = 0L
            // Depth-first walk in deterministic name order.
            val stack = ArrayDeque<File>()
            stack.add(scratch)
            while (stack.isNotEmpty()) {
                val dir = stack.removeFirst()
                val kids = dir.listFiles() ?: continue
                for (kid in kids.sortedBy { it.name }) {
                    if (kid.isDirectory) {
                        stack.add(kid)
                        continue
                    }
                    val rel = kid.relativeTo(scratch).path.replace(File.separatorChar, '/')
                    val target = payloadSegments(rel, plan)
                    val outSegments = when {
                        target == null -> null
                        directTarget != null && target != directTarget -> null
                        directName != null -> listOf(directName)
                        else -> target
                    }
                    if (outSegments != null) {
                        FileInputStream(kid).use { input ->
                            bytes += writeEntry(fs, stagingDir, outSegments, input) { chunkBytes ->
                                onProgress(bytes + chunkBytes, total)
                            }
                        }
                        files++
                        if (files > MAX_ENTRIES) {
                            throw ArchiveReadException("Archive has too many entries")
                        }
                    }
                }
            }
            if (files == 0) throw ArchiveReadException("Archive contained no extractable files")
            return ExtractReport(files, bytes)
        } finally {
            runCatching { scratch.deleteRecursively() }
            if (archive.deleteAfterUse) runCatching { archive.file.delete() }
        }
    }

    private fun extract7zInApp(
        fs: ThemeFs,
        ref: ArchiveRef,
        stagingDir: FsNode,
        plan: ImportPlan,
        onProgress: (Long, Long) -> Unit,
        directFileName: String?,
    ): ExtractReport {
        // Single-write mode: only this payload entry is kept.
        val directName = directFileName
            ?.takeIf { plan.target is ImportTarget.SingleFile }
        val directTarget = directName
            ?.let { listOf((plan.target as ImportTarget.SingleFile).fileName) }
        var files = 0
        var bytes = 0L
        try {
            opener.openChannel(ref.uri).use { handle ->
                SevenZFile(handle.channel).use { sevenZ ->
                    var entry = sevenZ.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val target = payloadSegments(entry.name, plan)
                            val outSegments = when {
                                target == null -> null
                                directTarget != null && target != directTarget -> null
                                directName != null -> listOf(directName)
                                else -> target
                            }
                            if (outSegments != null) {
                                val total = plan.payloadBytes.coerceAtLeast(1)
                                sevenZ.getInputStream(entry).use { content ->
                                    bytes += writeEntry(fs, stagingDir, outSegments, content) { chunkBytes ->
                                        // Per-chunk progress: a single giant entry
                                        // would otherwise report nothing until done.
                                        onProgress(bytes + chunkBytes, total)
                                    }
                                }
                                files++
                                if (files > MAX_ENTRIES) {
                                    throw ArchiveReadException("Archive has too many entries")
                                }
                                // No post-entry onProgress here: the final
                                // per-chunk callback already reported this
                                // exact byte count — emitting it again would
                                // produce a duplicate consecutive value.
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
