package io.crystalnova.manager.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * An archive resolved to a real file for external processes (7zz
 * cannot read SAF `content:` URIs).
 */
data class ExternalArchive(
    val file: File,
    /** True when the caller materialized a temp copy and must delete it after use. */
    val deleteAfterUse: Boolean,
)

/**
 * External 7z backend: the 7zz binary in its own process with its own
 * memory space. Exists because some 7z archives (e.g. LZMA with a
 * 256MB dictionary) cannot be decompressed inside the app's heap —
 * in-app `SevenZFile.getInputStream` allocates the full dictionary.
 * Pure JVM surface — the Android guards (ABI check, native-library
 * dir) live in [AndroidSevenZRunner]; unit tests use a fake.
 */
interface SevenZRunner {
    /** False when no usable backend is present on this device. */
    val available: Boolean

    data class Entry(val path: String, val size: Long, val isDirectory: Boolean)

    /**
     * `7zz l -slt`: lists entries from the archive header only — no
     * dictionary is ever allocated, so this is safe even for
     * pathological archives. Throws on any failure.
     */
    fun list(archive: File): List<Entry>

    /**
     * First [n] bytes of one entry via `7zz x -so`; the dictionary
     * lives in 7zz's process, never the app's. The process is
     * destroyed once [n] bytes are read. Returns null when nothing
     * could be read.
     */
    fun readFirstBytes(archive: File, entryPath: String, n: Int): ByteArray?

    /**
     * `7zz x -o<destDir>`: full extraction into [destDir] (created if
     * needed). Reports 0..1 progress; best effort — never throws for
     * unparsable progress output. Returns true only when 7zz exited 0.
     */
    fun extract(archive: File, destDir: File, onProgress: (Float) -> Unit): Boolean

    /** Destroys an operation currently in flight (cancellation). */
    fun cancel()
}

/**
 * Parses `7zz l -slt` output into entries. Blocks are separated by
 * blank lines; each entry block carries `Path`, `Size` and
 * `Attributes` (leading `D` = directory). The archive header block
 * has no `Attributes` line and is skipped. Never throws — malformed
 * input yields fewer entries, never a crash.
 */
internal fun parseSevenZSlt(output: String): List<SevenZRunner.Entry> {
    val entries = mutableListOf<SevenZRunner.Entry>()
    for (block in output.split(Regex("\\R\\s*\\R"))) {
        var path: String? = null
        var size = 0L
        var attributes: String? = null
        for (line in block.lines()) {
            val sep = line.indexOf(" = ")
            if (sep < 0) continue
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 3)
            when (key) {
                "Path" -> path = value
                "Size" -> size = value.trim().toLongOrNull() ?: 0L
                "Attributes" -> attributes = value.trim()
            }
        }
        if (path.isNullOrEmpty() || attributes == null) continue
        entries += SevenZRunner.Entry(
            path = path,
            size = size.coerceAtLeast(0L),
            isDirectory = attributes.startsWith("D"),
        )
    }
    return entries
}

/**
 * [SevenZRunner] over a real 7zz executable via [ProcessBuilder].
 * Pure JVM (no Android types) so unit tests can drive it with fakes;
 * the CLI contract (`l -slt` format, `-so` byte streaming, `%`
 * progress) was verified against real 7zz output.
 */
open class ProcessSevenZRunner(private val exe: File) : SevenZRunner {

    @Volatile
    private var current: Process? = null

    open override val available: Boolean
        get() = exe.isFile && exe.canExecute()

    private val progressPercent = Regex("""(\d{1,3})\s*%""")

    override fun list(archive: File): List<SevenZRunner.Entry> {
        if (!available) throw IllegalStateException("7zz not available")
        val out = StringBuilder()
        val code = run(
            listOf(exe.absolutePath, "l", "-slt", "--", archive.absolutePath),
        ) { chunk -> out.append(chunk).append('\n') }
        if (code != 0) throw IllegalStateException("7zz list failed (exit $code)")
        val entries = parseSevenZSlt(out.toString())
        if (entries.isEmpty()) throw IllegalStateException("7zz list produced no entries")
        return entries
    }

    override fun readFirstBytes(archive: File, entryPath: String, n: Int): ByteArray? {
        if (!available || n <= 0) return null
        return try {
            // stderr stays separate: with -so, 7zz's banner goes to
            // stderr and stdout carries pure entry bytes. The banner
            // is far below the pipe buffer, so no drain thread is
            // needed — the process is destroyed right after the read.
            val process = ProcessBuilder(
                exe.absolutePath, "x", "-so", "--", archive.absolutePath, entryPath,
            ).redirectErrorStream(false).start()
            current = process
            try {
                val out = ByteArrayOutputStream(minOf(n, 65536).coerceAtLeast(1024))
                val buf = ByteArray(4096)
                var remaining = n
                val input = process.inputStream
                while (remaining > 0) {
                    val r = input.read(buf, 0, minOf(buf.size, remaining))
                    if (r < 0) break
                    out.write(buf, 0, r)
                    remaining -= r
                }
                // Got what we came for — stop 7zz before it
                // decompresses the rest of the (possibly gigantic)
                // entry. The dictionary stays in its process.
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) null else bytes
            } finally {
                runCatching { process.errorStream.close() }
                if (current === process) current = null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun extract(archive: File, destDir: File, onProgress: (Float) -> Unit): Boolean {
        if (!available) return false
        return try {
            destDir.mkdirs()
            // -y: assume Yes on all queries (non-interactive).
            val code = run(
                listOf(
                    exe.absolutePath, "x", "-y",
                    "-o${destDir.absolutePath}", "--", archive.absolutePath,
                ),
            ) { chunk ->
                // Parse generously — never crash on unparsable output.
                val m = runCatching { progressPercent.find(chunk) }.getOrNull()
                if (m != null) {
                    val p = m.groupValues[1].toIntOrNull()
                    if (p != null) onProgress((p.coerceIn(0, 100) / 100f).coerceIn(0f, 1f))
                }
            }
            code == 0
        } catch (_: Exception) {
            false
        }
    }

    override fun cancel() {
        runCatching { current?.destroy() }
        current = null
    }

    /**
     * Runs [args] with merged stdout+stderr, feeding text split on
     * `\r` or `\n` to [onChunk] (7zz emits progress with carriage
     * returns). Returns the exit code.
     */
    private fun run(args: List<String>, onChunk: (String) -> Unit): Int {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        current = process
        try {
            val reader = process.inputStream.bufferedReader()
            val sb = StringBuilder()
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = buf[i]
                    if (c == '\r' || c == '\n') {
                        if (sb.isNotEmpty()) {
                            val chunk = sb.toString()
                            sb.clear()
                            runCatching { onChunk(chunk) }
                        }
                    } else {
                        sb.append(c)
                    }
                }
            }
            if (sb.isNotEmpty()) runCatching { onChunk(sb.toString()) }
            return process.waitFor()
        } finally {
            if (current === process) current = null
        }
    }
}

/**
 * Wiring bundle for the external 7z backend. Null everywhere = the
 * external backend is unavailable; the in-app commons-compress path
 * is the only option (pre-existing behavior, and what unit tests use).
 */
class ExternalSevenZ(
    val runner: SevenZRunner,
    /** Resolves an archive ref to a real file; caller deletes iff [ExternalArchive.deleteAfterUse]. */
    val archiveFile: (ArchiveRef) -> ExternalArchive?,
    /** Scratch dir on the real filesystem for external extraction. */
    val scratchDir: File,
)
