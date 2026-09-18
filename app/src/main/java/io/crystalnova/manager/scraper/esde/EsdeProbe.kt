package io.crystalnova.manager.scraper.esde

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.storage.SafThemeFs
import io.crystalnova.manager.storage.StorageLocations

/**
 * SD media probe (vertical slice): proves the Pegasus theme can render
 * one image straight from the ES-DE export on the SD card —
 * `file:///storage/XXXX-XXXX/Crystal/imports/esde/...` — without
 * copying it anywhere.
 *
 * Flow: the Manager lists `media/<system>/covers/` under the picked
 * (READ-ONLY) ES-DE root via SAF, resolves the canonical
 * `/storage/…` path, and writes the tiny `crystal-esde-probe.json`
 * beside the theme (themes root, same channel as
 * `crystal-media-bridge.json`). The theme reads it and displays the
 * image plus a diagnostic overlay.
 *
 * READ-ONLY CONTRACT: nothing in this file creates, writes, renames
 * or deletes anything beneath the ES-DE tree. The only write is the
 * probe JSON into the Manager-owned themes root.
 */
object EsdeProbe {
    /** Probe file name in the themes root (next to the media bridge). */
    const val PROBE_FILE_NAME = "crystal-esde-probe.json"

    /** Image extensions considered for the probe pick (ES-DE has no videos). */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")

    /**
     * One deterministic test image. [system] is the export's own
     * directory name (e.g. "ps2"); [testAsset] is the absolute
     * filesystem path; [themeUrl] is exactly what the theme hands QML.
     */
    data class Pick(
        val sdMediaRoot: String,
        val system: String,
        val testAsset: String,
        val themeUrl: String,
        val createdSeconds: Long,
    )

    /**
     * Percent-encodes an absolute filesystem path for a `file://` URL,
     * segment by segment (UTF-8). Unreserved characters pass through;
     * everything else (spaces, parentheses, non-ASCII, …) becomes %XX.
     * Real ES-DE cover filenames contain spaces and parentheses, and a
     * raw space is not valid in a URL — the theme decodes this back and
     * requires the decoded path to equal [Pick.testAsset] exactly.
     * Pure — unit-tested on the JVM.
     */
    fun encodePath(path: String): String {
        val hex = "0123456789ABCDEF"
        return path.split("/").joinToString("/") { segment ->
            buildString {
                for (b in segment.toByteArray(Charsets.UTF_8)) {
                    val u = b.toInt() and 0xFF
                    val c = u.toChar()
                    if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                        c == '-' || c == '_' || c == '.' || c == '~'
                    ) {
                        append(c)
                    } else {
                        append('%')
                        append(hex[u shr 4])
                        append(hex[u and 0x0F])
                    }
                }
            }
        }
    }

    /**
     * Pure pick: first system directory (sorted) that has at least one
     * image in its `covers/`, and the first such image (sorted, image
     * extension only). Systems whose `covers/` is missing or empty are
     * skipped. Null when there is nothing usable anywhere — the caller
     * turns that into a clear failure message, never a crash.
     */
    fun chooseTestImage(
        sdMediaRoot: String,
        systemDirs: List<String>,
        coverFiles: (systemDir: String) -> List<String>,
        createdSeconds: Long = System.currentTimeMillis() / 1000,
    ): Pick? {
        val root = sdMediaRoot.trim().trimEnd('/')
        if (root.isEmpty()) return null
        for (system in systemDirs.sorted()) {
            val file = coverFiles(system)
                .filter { name ->
                    val dot = name.lastIndexOf('.')
                    dot > 0 && name.substring(dot + 1).lowercase() in IMAGE_EXTENSIONS
                }
                .sorted()
                .firstOrNull() ?: continue
            val asset = "$root/media/$system/covers/$file"
            return Pick(
                sdMediaRoot = root,
                system = system,
                testAsset = asset,
                themeUrl = "file://" + encodePath(asset),
                createdSeconds = createdSeconds,
            )
        }
        return null
    }

    /** The exact JSON the theme parses (version 1). */
    fun probeJson(pick: Pick): String =
        "{\"version\":1," +
            "\"sdMediaRoot\":\"${jsonEscape(pick.sdMediaRoot)}\"," +
            "\"system\":\"${jsonEscape(pick.system)}\"," +
            "\"testAsset\":\"${jsonEscape(pick.testAsset)}\"," +
            "\"themeUrl\":\"${jsonEscape(pick.themeUrl)}\"," +
            "\"created\":${pick.createdSeconds}}"

    /**
     * The diagnostic block shown in the Manager and mirrored by the
     * theme overlay. The user sends this back after the device test.
     */
    fun diagnosticText(pick: Pick): String = buildString {
        appendLine("External SD media root:")
        appendLine(pick.sdMediaRoot)
        appendLine()
        appendLine("Test asset:")
        appendLine(pick.testAsset)
        appendLine()
        appendLine("Theme URL:")
        appendLine(pick.themeUrl)
        appendLine()
        appendLine("Result:")
        appendLine("pending device validation")
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}

/** Outcome of [EsdeProbeRunner.run]; never throws. */
sealed interface EsdeProbeOutcome {
    data class Success(val pick: EsdeProbe.Pick, val diagnostic: String) : EsdeProbeOutcome
    data class Failure(val reason: String) : EsdeProbeOutcome
}

/**
 * Android runner: SAF-lists the ES-DE export, picks one cover, and
 * writes the probe JSON to the themes root. All SAF access under the
 * ES-DE tree is list/read-only.
 */
class EsdeProbeRunner(
    private val context: Context,
    private val locations: StorageLocations,
    private val themesTreeUri: () -> String?,
) {
    fun run(): EsdeProbeOutcome {
        return try {
            runUnsafe()
        } catch (e: SecurityException) {
            EsdeProbeOutcome.Failure("ES-DE FOLDER ACCESS LOST — PLEASE RESELECT IT")
        } catch (e: Exception) {
            EsdeProbeOutcome.Failure("PROBE FAILED: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun runUnsafe(): EsdeProbeOutcome {
        val uriString = locations.esdeTreeUri()
            ?: return EsdeProbeOutcome.Failure("NO ES-DE FOLDER SELECTED")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        if (root == null || !root.canRead()) {
            return EsdeProbeOutcome.Failure("ES-DE FOLDER NOT READABLE — PLEASE RESELECT IT")
        }
        val sdRoot = locations.canonicalPath(uriString)
            ?: return EsdeProbeOutcome.Failure("COULD NOT RESOLVE THE ES-DE FOLDER PATH")
        // Read-only listing only: never create/write/delete under root.
        val media = root.listFiles().firstOrNull {
            it.isDirectory && it.name == "media"
        } ?: return EsdeProbeOutcome.Failure(
            "NO media/ DIRECTORY UNDER THE PICKED FOLDER — " +
                "PICK THE ES-DE EXPORT ROOT (THE FOLDER HOLDING media/ AND gamelists/)"
        )
        val systems = media.listFiles()
            .filter { it.isDirectory && it.name != null }
            .map { it.name!! }
            .sorted()
        if (systems.isEmpty()) {
            return EsdeProbeOutcome.Failure("NO SYSTEM DIRECTORIES UNDER media/")
        }
        val pick = EsdeProbe.chooseTestImage(
            sdMediaRoot = sdRoot,
            systemDirs = systems,
            coverFiles = { system ->
                media.listFiles()
                    .firstOrNull { it.isDirectory && it.name == system }
                    ?.listFiles()
                    ?.firstOrNull { it.isDirectory && it.name == "covers" }
                    ?.listFiles()
                    ?.filter { !it.isDirectory && it.name != null }
                    ?.map { it.name!! }
                    ?: emptyList()
            },
        ) ?: return EsdeProbeOutcome.Failure(
            "NO COVER IMAGES FOUND IN ANY media/<system>/covers/ — " +
                "THE EXPORT MAY USE A DIFFERENT LAYOUT"
        )
        if (!writeProbeFile(pick)) {
            return EsdeProbeOutcome.Failure(
                "COULD NOT WRITE THE PROBE FILE TO THE THEMES FOLDER"
            )
        }
        return EsdeProbeOutcome.Success(pick, EsdeProbe.diagnosticText(pick))
    }

    /**
     * Writes `crystal-esde-probe.json` into the themes root (Manager
     * territory, beside the media bridge): atomic-ish via tmp + swap,
     * like the bridge writer. False on any failure.
     */
    private fun writeProbeFile(pick: EsdeProbe.Pick): Boolean {
        return try {
            val themes = themesTreeUri() ?: return false
            val fs = SafThemeFs(context) { themes }
            val root = fs.root() ?: return false
            val bytes = EsdeProbe.probeJson(pick).toByteArray()
            val tmpName = "${EsdeProbe.PROBE_FILE_NAME}.tmp"
            fs.find(root, tmpName)?.let { fs.deleteRecursively(it) }
            val tmp = fs.createFile(root, tmpName)
            try {
                fs.openOutput(tmp).use { it.write(bytes) }
            } catch (e: Exception) {
                fs.deleteRecursively(tmp)
                throw e
            }
            fs.find(root, EsdeProbe.PROBE_FILE_NAME)?.let { fs.deleteRecursively(it) }
            fs.rename(tmp, EsdeProbe.PROBE_FILE_NAME)
        } catch (_: Exception) {
            false
        }
    }
}
