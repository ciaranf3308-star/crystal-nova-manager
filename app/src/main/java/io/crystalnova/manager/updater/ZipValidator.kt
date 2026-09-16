package io.crystalnova.manager.updater

import java.io.File
import java.util.zip.ZipFile

class InvalidPackageException(message: String) : Exception(message)

/**
 * Extracts and validates a downloaded theme ZIP.
 *
 * Security: every entry is checked against path traversal (`..`),
 * absolute paths, and the canonical-path containment rule — any entry
 * escaping the private staging directory rejects the whole package.
 * Nothing is installed before [extract] returns a validated root.
 */
object ZipValidator {
    val REQUIRED_FILES = listOf("theme.cfg", "theme.qml", "crystal-version.json")
    val REQUIRED_DIRS = listOf("components", "screens", "assets", "fonts")

    /**
     * Extracts [zip] into a fresh [destDir] and returns the repo root.
     * GitHub ZIPs nest everything under `<repo>-<ref>/`; a flat layout is
     * accepted too. Throws [InvalidPackageException] on any problem.
     */
    @Throws(InvalidPackageException::class)
    fun extract(zip: File, destDir: File): File {
        if (!zip.isFile || zip.length() == 0L) {
            throw InvalidPackageException("Downloaded file is missing or empty")
        }
        destDir.deleteRecursively()
        destDir.mkdirs()
        val destCanon = destDir.canonicalPath + File.separator
        var entries = 0
        try {
            ZipFile(zip).use { zf ->
                val list = zf.entries().toList()
                if (list.isEmpty()) throw InvalidPackageException("Archive is empty")
                for (entry in list) {
                    val name = entry.name
                    if (name.isEmpty()) continue
                    if (".." in name || name.startsWith("/") || name.startsWith("\\") ||
                        (name.length > 1 && name[1] == ':')
                    ) {
                        throw InvalidPackageException("Blocked unsafe entry: $name")
                    }
                    val out = File(destDir, name)
                    val outCanon = out.canonicalPath
                    if (outCanon != destDir.canonicalPath && !outCanon.startsWith(destCanon)) {
                        throw InvalidPackageException("Entry escapes staging directory: $name")
                    }
                    entries++
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        } catch (e: InvalidPackageException) {
            throw e
        } catch (e: Exception) {
            throw InvalidPackageException("Could not read archive: ${e.message}")
        }
        if (entries == 0) throw InvalidPackageException("Archive is empty")
        return locateRoot(destDir)
    }

    private fun locateRoot(destDir: File): File {
        if (isValidRoot(destDir)) return destDir
        destDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { if (isValidRoot(it)) return it }
        throw InvalidPackageException(
            "Not a Crystal theme package (missing ${REQUIRED_FILES.joinToString("/")})",
        )
    }

    fun isValidRoot(dir: File): Boolean =
        dir.isDirectory &&
            REQUIRED_FILES.all { File(dir, it).isFile } &&
            REQUIRED_DIRS.all { File(dir, it).isDirectory }
}
