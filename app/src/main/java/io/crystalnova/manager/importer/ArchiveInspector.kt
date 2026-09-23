package io.crystalnova.manager.importer

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipInputStream

/** Thrown when an archive cannot be listed (corrupt, truncated, ...). */
class ArchiveReadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Opens raw archive streams. The Android implementation reads through
 * the ContentResolver (SAF document URIs); tests serve bytes from
 * memory or temp files.
 */
interface ArchiveStreamOpener {
    fun openInput(uri: String): InputStream

    /**
     * Seekable channel for 7z (random-access entry table). The
     * returned handle owns every underlying resource — closing it
     * releases all of them.
     */
    fun openChannel(uri: String): ChannelHandle
}

/** A [SeekableByteChannel] plus everything that must close with it. */
class ChannelHandle(
    val channel: SeekableByteChannel,
    private val closeable: Closeable,
) : Closeable {
    override fun close() {
        runCatching { closeable.close() }
    }
}

/**
 * Streaming archive inspection: entry names, sizes, structure, and
 * targeted disc-header bytes — without decompressing any content.
 *
 * ZIP goes through [ZipInputStream]; 7z through [SevenZFile] over a
 * zero-copy seekable channel (the 7z entry table is read from the
 * archive tail; content streams are never opened during inspection).
 */
class ArchiveInspector(private val opener: ArchiveStreamOpener) {

    /** Entry extensions worth capturing header bytes for. */
    private val headerCandidates = setOf("iso", "rvz", "gcm", "bin", "img")

    data class InspectedArchive(
        val inspection: ArchiveInspection,
        /** First [HEADER_WINDOW_BYTES] of header-candidate entries, keyed by entry path. */
        val headers: Map<String, ByteArray>,
    )

    @Throws(ArchiveReadException::class)
    fun inspect(ref: ArchiveRef): InspectedArchive {
        return when (ref.kind) {
            ArchiveKind.ZIP -> inspectZip(ref)
            ArchiveKind.SEVEN_Z -> inspect7z(ref)
            else -> throw ArchiveReadException("Unsupported archive kind: ${ref.kind}")
        }
    }

    private fun inspectZip(ref: ArchiveRef): InspectedArchive {
        val entries = mutableListOf<ArchiveEntryInfo>()
        val headers = mutableMapOf<String, ByteArray>()
        try {
            opener.openInput(ref.uri).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var zipEntry = zip.nextEntry
                    val drain = ByteArray(8192)
                    while (zipEntry != null) {
                        val name = zipEntry.name
                        val isDir = zipEntry.isDirectory || name.endsWith('/')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        // Header capture: read the bounded window inline —
                        // ZipInputStream is sequential, so this is the
                        // only chance. Captured bytes count toward the
                        // drain total below.
                        var drained = 0L
                        if (!isDir && ext in headerCandidates && headers.size < MAX_HEADER_ENTRIES) {
                            val header = readUpTo(zip, HEADER_WINDOW_BYTES)
                            headers[name] = header
                            drained += header.size
                        }
                        // Drain the entry, counting bytes. Two things
                        // ZipInputStream can't tell us otherwise:
                        //  - the true uncompressed size (it reports -1
                        //    until the data descriptor is read), which
                        //    the storage preflight depends on;
                        //  - truncation: a short stream surfaces here as
                        //    EOFException or a short count, never as a
                        //    silently listed entry.
                        while (true) {
                            val read = zip.read(drain)
                            if (read <= 0) break
                            drained += read
                        }
                        val size = zipEntry.size
                        if (!isDir && (size < 0 || drained != size)) {
                            throw ArchiveReadException(
                                "Truncated entry: $name " +
                                    "(declared=$size, read=$drained)",
                            )
                        }
                        entries += ArchiveEntryInfo(
                            path = name,
                            size = size.coerceAtLeast(0),
                            isDirectory = isDir,
                        )
                        zipEntry = zip.nextEntry
                    }
                }
            }
        } catch (e: ArchiveReadException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveReadException("Could not read ZIP: ${e.message}", e)
        }
        return InspectedArchive(buildInspection(ref, entries), headers)
    }

    private fun inspect7z(ref: ArchiveRef): InspectedArchive {
        val entries = mutableListOf<ArchiveEntryInfo>()
        val headers = mutableMapOf<String, ByteArray>()
        try {
            opener.openChannel(ref.uri).use { handle ->
                SevenZFile(handle.channel).use { sevenZ ->
                    var entry = sevenZ.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (!entry.isDirectory && ext in headerCandidates && headers.size < MAX_HEADER_ENTRIES) {
                            sevenZ.getInputStream(entry).use { content ->
                                headers[name] = readUpTo(content, HEADER_WINDOW_BYTES)
                            }
                        }
                        entries += ArchiveEntryInfo(
                            path = name,
                            size = entry.size.coerceAtLeast(0),
                            isDirectory = entry.isDirectory,
                        )
                        entry = sevenZ.nextEntry
                    }
                }
            }
        } catch (e: ArchiveReadException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveReadException("Could not read 7z: ${e.message}", e)
        }
        return InspectedArchive(buildInspection(ref, entries), headers)
    }

    private fun buildInspection(
        ref: ArchiveRef,
        entries: List<ArchiveEntryInfo>,
    ): ArchiveInspection {
        val files = entries.filter { !it.isDirectory }
        return ArchiveInspection(
            kind = ref.kind,
            entries = entries,
            totalUncompressedBytes = files.sumOf { it.size },
            extensions = files.map {
                it.path.substringAfterLast('.', "").lowercase()
            }.toSet(),
            topLevelNames = entries.map { it.path.substringBefore('/') }
                .distinct(),
        )
    }

    /**
     * Computes the placement plan from an inspection: unwraps a single
     * redundant wrapper folder, then decides between a single ROM
     * file placed directly in the platform folder and a game folder
     * preserving internal structure (BIN/CUE units, multi-disc,
     * folder-based formats are never split or flattened).
     *
     * Returns null when the archive holds no files at all.
     */
    fun planPayload(
        inspection: ArchiveInspection,
        archiveName: String,
    ): ImportPlan? {
        val files = inspection.entries.filter { !it.isDirectory }
        if (files.isEmpty()) return null

        // Normalize separators before unwrapping: the extractor sees
        // the same normalized segments via sanitizeEntryPath, so the
        // unwrap depth must be computed on the same alphabet.
        val (paths, depth) = unwrapPayloadPaths(
            files.map { it.path.replace('\\', '/') },
        )
        val payloadBytes = files.sumOf { it.size }
        return if (paths.size == 1 && '/' !in paths.single()) {
            ImportPlan(
                target = ImportTarget.SingleFile(fileName = paths.single()),
                payloadFileCount = 1,
                payloadBytes = payloadBytes,
                unwrapDepth = depth,
            )
        } else {
            ImportPlan(
                target = ImportTarget.GameFolder(
                    folderName = sanitizeFileName(cleanDisplayTitle(archiveName)),
                ),
                payloadFileCount = paths.size,
                payloadBytes = payloadBytes,
                unwrapDepth = depth,
            )
        }
    }

    companion object {
        /**
         * Bounded header window captured per candidate disc image.
         * Covers the GameCube/Wii magic word at disc offset 0x1C with
         * room to spare; still tiny next to a multi-GB image.
         */
        const val HEADER_WINDOW_BYTES = 64
        const val MAX_HEADER_ENTRIES = 8
    }
}

/**
 * Strips redundant single-folder wrappers from entry paths. While
 * every path shares one first segment that is itself a directory (not
 * a root-level file), that segment is dropped from all paths. Returns
 * the stripped paths plus the number of segments removed, so the
 * extractor can reproduce the identical layout later.
 */
fun unwrapPayloadPaths(entryPaths: List<String>): Pair<List<String>, Int> {
    var paths = entryPaths
    var depth = 0
    while (true) {
        val firsts = paths.map { it.substringBefore('/') }.distinct()
        if (firsts.size != 1) break
        val first = firsts.single()
        if (paths.any { it == first }) break
        val stripped = paths.map { it.substringAfter('/') }
        if (stripped.any { it.isEmpty() }) break
        paths = stripped
        depth++
    }
    return paths to depth
}

/**
 * Entry names are attacker-controlled: reject traversal (`..`),
 * absolute paths, and drive letters. Returns the safe relative
 * segments, or null when the entry must be refused. Mirrors the
 * [ZipValidator] containment discipline, adapted to SAF trees where
 * entries are materialized segment-by-segment (no symlinks exist).
 */
fun sanitizeEntryPath(entryPath: String): List<String>? {
    val raw = entryPath.replace('\\', '/')
    if (raw.isEmpty()) return null
    if (raw.startsWith('/') || (raw.length > 1 && raw[1] == ':')) return null
    val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty()) return null
    if (segments.any { it == ".." }) return null
    if (segments.any { it.length > 255 }) return null
    return segments
}

/**
 * Reads up to [n] bytes without `InputStream.readNBytes` (a Java 9
 * API absent below Android 13 without core-library desugaring, which
 * this project does not enable — minSdk is 26).
 */
internal fun readUpTo(input: InputStream, n: Int): ByteArray {    val out = ByteArrayOutputStream(n.coerceAtLeast(0))
    val buf = ByteArray(4096)
    var remaining = n
    while (remaining > 0) {
        val read = input.read(buf, 0, minOf(buf.size, remaining))
        if (read <= 0) break
        out.write(buf, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}
