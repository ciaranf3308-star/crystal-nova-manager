package io.crystalnova.manager.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.crystalnova.manager.data.EsdeThemeEntry
import io.crystalnova.manager.data.JsonVal
import io.crystalnova.manager.data.parseJson
import io.crystalnova.manager.data.sha256Hex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of one ES-DE theme install. A failure never leaves a
 * half-written theme: on any error the previous theme (if any) is
 * kept and the error is reported honestly.
 */
sealed interface EsdeThemeInstallResult {
    data class Success(val notes: List<String>) : EsdeThemeInstallResult
    data class Failure(val message: String, val notes: List<String>) :
        EsdeThemeInstallResult
}

/**
 * Installs a verified ES-DE "crystal" theme ZIP into the SAF-granted
 * themes folder (u48).
 *
 * The user grants the ES-DE themes folder ONCE with the system folder
 * picker ([android.content.Intent.ACTION_OPEN_DOCUMENT_TREE]); the
 * persistable URI permission is the only storage access the app holds.
 * NO MANAGE_EXTERNAL_STORAGE, no broad storage permission, ever. On
 * every install the persisted URI is validated first — if it no
 * longer resolves (revoked, folder moved) the install refuses and the
 * activity re-prompts.
 *
 * Atomicity: the new theme is staged in `crystal-new/` inside the
 * granted themes folder, verified non-empty, then the old `crystal/`
 * is parked as `crystal-old/`, `crystal-new/` is renamed to
 * `crystal/`, and only then is the old directory deleted. If the
 * rename fails the parked directory is restored. The device holds
 * exactly ONE theme version — no accumulation.
 *
 * Best-effort ThemeSet activation: ES-DE's data dir is the parent of
 * the granted themes folder; `es_settings.xml` there gets
 * `<string name="ThemeSet" value="crystal" />`. This is wrapped in
 * try/catch — when ES-DE is running the write is skipped outright
 * (ES-DE would overwrite it on exit) and the outcome is noted, never
 * silent. When the write is impossible the note says so and points at
 * ES-DE's own Appearance settings.
 */
class EsdeThemeInstaller(
    context: Context,
    private val cacheDir: File,
) {
    // Only the application context is retained — it grants no storage
    // access by itself; every file operation goes through the
    // user-granted SAF tree URI.
    private val appContext: Context = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    companion object {
        const val THEME_DIR_NAME = "crystal"
        const val VERSION_MARKER = "crystal-version.json"
        private const val STAGING_DIR_NAME = "crystal-new"
        private const val PARKED_DIR_NAME = "crystal-old"
        private const val SETTINGS_FILE = "es_settings.xml"
    }

    /**
     * Resolves the persisted themes tree URI. Returns null when the
     * URI was never granted, is unparseable, or the folder no longer
     * resolves readably AND writably — the caller re-prompts.
     */
    fun resolveThemesDir(treeUriString: String?): DocumentFile? {
        if (treeUriString.isNullOrBlank()) return null
        return try {
            val dir = DocumentFile.fromTreeUri(appContext, Uri.parse(treeUriString))
            dir?.takeIf { it.exists() && it.canRead() && it.canWrite() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True when `themes/crystal/` exists on disk under the granted
     * folder. False covers both "never installed" and "grant gone".
     */
    fun themePresentOnDisk(treeUriString: String?): Boolean {
        val themes = resolveThemesDir(treeUriString) ?: return false
        return try {
            themes.findFile(THEME_DIR_NAME)?.exists() == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads `themes/crystal/crystal-version.json` written by a previous
     * successful install. Returns (version, versionCode?) or null when
     * the marker is absent or unreadable.
     */
    fun readInstalledMarker(treeUriString: String?): Pair<String, Int?>? {
        val themes = resolveThemesDir(treeUriString) ?: return null
        return try {
            val themeDir = themes.findFile(THEME_DIR_NAME) ?: return null
            val marker = themeDir.findFile(VERSION_MARKER) ?: return null
            val text = contentResolver.openInputStream(marker.uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return null
            val root = parseJson(text) as? JsonVal.Obj ?: return null
            val version = (root.map["version"] as? JsonVal.Str)
                ?.value?.takeIf { it.isNotBlank() } ?: return null
            val versionCode = (root.map["versionCode"] as? JsonVal.Num)
                ?.raw?.toIntOrNull()
            version to versionCode
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Installs the already-verified [zip] for [entry] into the granted
     * themes folder. The ZIP is re-verified against
     * [EsdeThemeEntry.zipSha256] first (fail closed).
     *
     * @param esdeRunning when true, the `es_settings.xml` ThemeSet
     * write is skipped and noted — ES-DE would overwrite it on exit.
     * @param onStep progress labels for the UI ("VERIFYING…",
     * "INSTALLING…", …).
     */
    suspend fun install(
        zip: File,
        entry: EsdeThemeEntry,
        treeUriString: String,
        esdeRunning: Boolean,
        onStep: (String) -> Unit = {},
    ): EsdeThemeInstallResult = withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()
        try {
            val themes = resolveThemesDir(treeUriString)
                ?: return@withContext EsdeThemeInstallResult.Failure(
                    "THE THEMES FOLDER IS NO LONGER ACCESSIBLE — " +
                        "GRANT IT AGAIN",
                    notes,
                )
            onStep("VERIFYING…")
            if (!zip.isFile || zip.length() == 0L) {
                return@withContext EsdeThemeInstallResult.Failure(
                    "THE DOWNLOADED FILE IS MISSING OR EMPTY",
                    notes,
                )
            }
            if (!sha256Hex(zip).equals(entry.zipSha256, ignoreCase = true)) {
                return@withContext EsdeThemeInstallResult.Failure(
                    "THEME CHECKSUM MISMATCH — REFUSING TO INSTALL",
                    notes,
                )
            }
            onStep("EXTRACTING…")
            val extractDir = File(cacheDir, "esde-extract").apply {
                deleteRecursively()
                mkdirs()
            }
            val themeRoot: File
            try {
                themeRoot = extractZip(extractDir, zip)
            } catch (e: Exception) {
                extractDir.deleteRecursively()
                return@withContext EsdeThemeInstallResult.Failure(
                    "COULD NOT READ THE THEME PACKAGE: " +
                        (e.message ?: "unknown error"),
                    notes,
                )
            }
            onStep("INSTALLING…")
            val installError = swapTheme(themes, themeRoot)
            extractDir.deleteRecursively()
            if (installError != null) {
                return@withContext EsdeThemeInstallResult.Failure(
                    installError,
                    notes,
                )
            }
            writeVersionMarker(themes, entry)
            notes += themeSetNote(themes, treeUriString, esdeRunning)
            EsdeThemeInstallResult.Success(notes)
        } catch (e: SecurityException) {
            EsdeThemeInstallResult.Failure(
                "STORAGE ACCESS WAS REVOKED — GRANT THE THEMES FOLDER AGAIN",
                notes,
            )
        } catch (e: Exception) {
            EsdeThemeInstallResult.Failure(
                "INSTALL FAILED: ${(e.message ?: "unknown error").uppercase()}",
                notes,
            )
        }
    }

    // ------------------------------------------------------------------
    // Extraction (zip-slip protected; ES-DE theme layout)
    // ------------------------------------------------------------------

    /**
     * Extracts [zip] into [destDir] and returns the theme root: the zip
     * either contains the theme files at its root or nests them under
     * a single top-level directory (e.g. `crystal/`). Throws on any
     * unsafe entry or an empty package.
     */
    private fun extractZip(destDir: File, zip: File): File {
        val destCanon = destDir.canonicalPath + File.separator
        var entries = 0
        ZipFile(zip).use { zf ->
            val list = zf.entries().toList()
            if (list.isEmpty()) throw IllegalArgumentException("Archive is empty")
            for (entry in list) {
                val name = entry.name
                if (name.isEmpty()) continue
                if (".." in name || name.startsWith("/") || name.startsWith("\\") ||
                    (name.length > 1 && name[1] == ':')
                ) {
                    throw IllegalArgumentException("Blocked unsafe entry: $name")
                }
                val out = File(destDir, name)
                val outCanon = out.canonicalPath
                if (outCanon != destDir.canonicalPath && !outCanon.startsWith(destCanon)) {
                    throw IllegalArgumentException("Entry escapes staging directory: $name")
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
        if (entries == 0) throw IllegalArgumentException("Archive is empty")
        val root = locateThemeRoot(destDir)
        val files = root.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) throw IllegalArgumentException("Theme package has no files")
        return root
    }

    private fun locateThemeRoot(destDir: File): File {
        // The zip root usually IS the theme (theme.xml at top level).
        if (File(destDir, "theme.xml").isFile) return destDir
        // Otherwise accept a single top-level directory (e.g. crystal/).
        val dirs = destDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (dirs.size == 1) return dirs[0]
        throw IllegalArgumentException(
            "Not an ES-DE theme package (expected theme.xml at the root)",
        )
    }

    // ------------------------------------------------------------------
    // Atomic swap inside the granted themes folder
    // ------------------------------------------------------------------

    /**
     * Stages [themeRoot] as `crystal-new/`, verifies it is non-empty,
     * then atomically swaps it into `crystal/`. Returns an error
     * message on failure, or null on success. The previous theme is
     * never left half-written.
     */
    private fun swapTheme(themes: DocumentFile, themeRoot: File): String? {
        // Clear any stale staging/parked dirs from a crashed run.
        themes.findFile(STAGING_DIR_NAME)?.delete()
        themes.findFile(PARKED_DIR_NAME)?.delete()
        val staging = themes.createDirectory(STAGING_DIR_NAME)
            ?: return "COULD NOT CREATE THE STAGING FOLDER"
        try {
            copyRecursively(themeRoot, staging)
        } catch (e: Exception) {
            staging.delete()
            return "COULD NOT COPY THE NEW THEME: " +
                (e.message ?: "unknown error").uppercase()
        }
        if ((staging.listFiles().isEmpty())) {
            staging.delete()
            return "THE NEW THEME COPIED ZERO FILES — REFUSING TO SWAP"
        }
        val old = themes.findFile(THEME_DIR_NAME)
        if (old != null && !old.renameTo(PARKED_DIR_NAME)) {
            staging.delete()
            return "COULD NOT PARK THE OLD THEME — KEEPING IT"
        }
        if (!staging.renameTo(THEME_DIR_NAME)) {
            // Restore the parked theme: a failed swap must never leave
            // the device themeless.
            val parked = themes.findFile(PARKED_DIR_NAME)
            if (old != null && parked != null) {
                parked.renameTo(THEME_DIR_NAME)
            }
            staging.delete()
            return "COULD NOT ACTIVATE THE NEW THEME — THE OLD THEME IS STILL IN PLACE"
        }
        themes.findFile(PARKED_DIR_NAME)?.delete()
        return null
    }

    private fun copyRecursively(src: File, destDir: DocumentFile) {
        val children = src.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val sub = destDir.createDirectory(child.name)
                    ?: throw IllegalStateException("createDirectory failed: ${child.name}")
                copyRecursively(child, sub)
            } else {
                val mime = mimeFor(child.name)
                val doc = destDir.createFile(mime, child.name)
                    ?: throw IllegalStateException("createFile failed: ${child.name}")
                contentResolver.openOutputStream(doc.uri)?.use { out ->
                    child.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("openOutputStream failed: ${child.name}")
            }
        }
    }

    private fun mimeFor(name: String): String = when (
        name.substringAfterLast('.', "").lowercase()
    ) {
        "xml" -> "text/xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "json" -> "application/json"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun writeVersionMarker(themes: DocumentFile, entry: EsdeThemeEntry) {
        val themeDir = themes.findFile(THEME_DIR_NAME) ?: return
        themeDir.findFile(VERSION_MARKER)?.delete()
        val marker = themeDir.createFile("application/json", VERSION_MARKER) ?: return
        val installedAt = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US,
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        // The version comes from the catalog: escape defensively so a
        // hostile string can never break the marker JSON.
        val safeVersion = entry.version
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val json = "{\"version\":\"$safeVersion\"," +
            "\"versionCode\":${entry.versionCode}," +
            "\"installedAt\":\"$installedAt\"}"
        try {
            contentResolver.openOutputStream(marker.uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            // The marker is bookkeeping, never worth failing the
            // install over — the prefs record is authoritative.
            marker.delete()
        }
    }

    // ------------------------------------------------------------------
    // Best-effort ThemeSet activation
    // ------------------------------------------------------------------

    /**
     * ES-DE's data dir is the parent of the granted themes folder;
     * `es_settings.xml` there holds `<string name="ThemeSet" …/>`.
     * Best-effort: never throws, always returns an honest note.
     */
    private fun themeSetNote(
        themes: DocumentFile,
        treeUriString: String,
        esdeRunning: Boolean,
    ): String {
        if (esdeRunning) {
            return "ES-DE IS RUNNING — ThemeSet NOT WRITTEN (ES-DE WOULD " +
                "OVERWRITE IT ON EXIT). PICK THE \"CRYSTAL\" THEME IN " +
                "ES-DE'S APPEARANCE SETTINGS, OR QUIT AND RESTART ES-DE " +
                "AND USE THE UPDATE AGAIN."
        }
        return try {
            val parentUri = parentTreeUri(treeUriString) ?: return noSettingsNote()
            val dataDir = DocumentFile.fromTreeUri(appContext, parentUri)
                ?: return noSettingsNote()
            val settings = dataDir.findFile(SETTINGS_FILE) ?: return noSettingsNote()
            val text = contentResolver.openInputStream(settings.uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return noSettingsNote()
            val pattern = Regex(
                """<string\s+name="ThemeSet"\s+value="[^"]*"\s*/>""",
            )
            val replacement = """<string name="ThemeSet" value="crystal" />"""
            val updated = if (pattern.containsMatchIn(text)) {
                pattern.replace(text, replacement)
            } else {
                // No ThemeSet entry yet: insert before the final
                // closing tag, whatever the root element is called.
                text.replace(
                    Regex("</[A-Za-z][^>]*>\\s*$"),
                    "$replacement\n$0",
                )
            }
            if (updated == text) return noSettingsNote()
            contentResolver.openOutputStream(settings.uri, "w")?.use { out ->
                out.write(updated.toByteArray(Charsets.UTF_8))
            } ?: return noSettingsNote()
            "ES-DE'S ThemeSet WAS SET TO \"CRYSTAL\"."
        } catch (_: SecurityException) {
            "COULD NOT AUTO-SELECT THE THEME (NO ACCESS OUTSIDE THE " +
                "THEMES FOLDER) — PICK \"CRYSTAL\" IN ES-DE'S APPEARANCE " +
                "SETTINGS."
        } catch (_: Exception) {
            noSettingsNote()
        }
    }

    private fun noSettingsNote(): String =
        "THEME INSTALLED BUT NOT AUTO-SELECTED — PICK \"CRYSTAL\" IN " +
            "ES-DE'S APPEARANCE SETTINGS."

    /**
     * Derives the parent tree URI from a themes tree URI. E.g.
     * `content://…/tree/primary%3AFOUND.000%2Fthemes` →
     * `content://…/tree/primary%3AFOUND.000`. Never assumes the data
     * dir is named ES-DE — any folder name works.
     */
    private fun parentTreeUri(treeUriString: String): Uri? {
        val uri = runCatching { Uri.parse(treeUriString) }.getOrNull()
            ?: return null
        if (uri.scheme.isNullOrBlank() || uri.authority.isNullOrBlank()) return null
        val segments = uri.pathSegments
        // …/tree/<documentId>
        val treeIdx = segments.indexOf("tree")
        if (treeIdx < 0 || treeIdx + 1 >= segments.size) return null
        val docId = segments[treeIdx + 1] // e.g. "primary:FOUND.000/themes"
        val parentId = docId.substringBeforeLast('/', "")
            .takeIf { it.isNotEmpty() && it != docId } ?: return null
        val parentDoc = Uri.encode(parentId, "/:")
        return Uri.parse(
            "${uri.scheme}://${uri.authority}/tree/$parentDoc",
        )
    }

}
