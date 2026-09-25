package io.crystalnova.manager.importer

import java.io.File

/**
 * CHD conversion backend for the PS1 pipeline. The production
 * implementation shells out to the bundled chdman binary; unit tests
 * use a fake. Pure JVM surface — the Android guards (ABI check,
 * native-library dir) live in [ChdmanRunner].
 */
interface ChdConverter {
    /** False when no usable backend is present on this device. */
    val available: Boolean

    /**
     * `chdman createcd -i cue -o out`. Reports 0..1 progress; never
     * throws for unparsable progress lines. Returns true only when
     * chdman exited 0 AND the output exists and is non-empty.
     */
    fun createcd(cueFile: File, outChd: File, onProgress: (Float) -> Unit): Boolean

    /** `chdman verify -i chd`: true when chdman reports the image valid. */
    fun verify(chdFile: File): Boolean

    /** Destroys a conversion currently in flight (import cancellation). */
    fun cancel()
}

/**
 * [ChdConverter] over a real chdman executable via [ProcessBuilder].
 * Pure JVM (no Android types) so the CI integration test can drive
 * the real `tools/chdman-linux-x64/chdman` binary with it.
 */
open class ProcessChdConverter(private val exe: File) : ChdConverter {

    @Volatile
    private var current: Process? = null

    override val available: Boolean
        get() = exe.isFile && exe.canExecute()

    private val progressPercent = Regex("""(\d{1,3})\s*%""")

    override fun createcd(cueFile: File, outChd: File, onProgress: (Float) -> Unit): Boolean {
        if (!available) return false
        return try {
            val code = run(
                listOf(exe.absolutePath, "createcd", "-i", cueFile.absolutePath, "-o", outChd.absolutePath),
            ) { line ->
                // Parse generously — never crash on unparsable lines.
                val m = runCatching { progressPercent.find(line) }.getOrNull()
                if (m != null) {
                    val p = m.groupValues[1].toIntOrNull()
                    if (p != null) onProgress((p.coerceIn(0, 100) / 100f).coerceIn(0f, 1f))
                }
            }
            code == 0 && outChd.isFile && outChd.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    override fun verify(chdFile: File): Boolean {
        if (!available) return false
        return try {
            run(listOf(exe.absolutePath, "verify", "-i", chdFile.absolutePath)) { _ -> } == 0
        } catch (_: Exception) {
            false
        }
    }

    override fun cancel() {
        runCatching { current?.destroy() }
        current = null
    }

    /** Runs [args], feeding merged stdout+stderr lines to [onLine]. Returns the exit code. */
    private fun run(args: List<String>, onLine: (String) -> Unit): Int {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        current = process
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                runCatching { onLine(line) }
            }
            return process.waitFor()
        } finally {
            if (current === process) current = null
        }
    }
}
