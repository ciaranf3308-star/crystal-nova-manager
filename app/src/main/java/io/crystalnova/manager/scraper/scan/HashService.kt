package io.crystalnova.manager.scraper.scan

import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * On-demand ROM hashing. NEVER run during the initial library scan —
 * multi-GB disc images would make startup I/O-bound and hot. Hashes are
 * computed only when a provider supports hash matching, ambiguity needs
 * resolving, or the user explicitly asks. Chunked and cancellable.
 */
object HashService {
    suspend fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        input.use { stream ->
            while (true) {
                coroutineContext.ensureActive()
                val n = stream.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun crc32Hex(input: InputStream): String {
        val crc = java.util.zip.CRC32()
        val buf = ByteArray(256 * 1024)
        input.use { stream ->
            while (true) {
                coroutineContext.ensureActive()
                val n = stream.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
            }
        }
        return "%08x".format(crc.value)
    }
}
