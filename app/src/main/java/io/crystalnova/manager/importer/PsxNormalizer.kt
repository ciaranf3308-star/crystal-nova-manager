package io.crystalnova.manager.importer

import java.io.File

/**
 * PS1 post-extraction normalization: turns whatever the archive held
 * (CUE/BIN track sets, lone images, existing CHDs, PBPs) into the
 * canonical library layout —
 *
 * - single disc → `<Base>.chd` (+ `<Base>.sbi` when the source had one)
 * - multi-disc  → `<Base>.m3u/` containing `<Base> (Disc N).chd`
 *   (+ per-disc `.sbi`) and `<Base>.m3u` (ES-DE's "directories
 *   interpreted as files" → exactly one frontend entry)
 * - PBP / lone ISO/BIN → installed as-is (valid inputs chdman cannot
 *   improve; never destroyed)
 *
 * Everything happens inside [tempDir] (the engine's private
 * `cacheDir/psx-<itemId>/`); the live `psx/` folder is untouched until
 * the engine copies the validated result. On any failure the outcome
 * is a typed [ImportFailureReason] — never a stack trace — and the
 * engine deletes the temp dir, so no partial BIN/CUE/CHD debris ever
 * reaches the ROM folder.
 *
 * The CUE parsing / disc grouping itself is pure ([CueParser],
 * [PsxDiscGrouping]); only the chdman calls go through the injected
 * [ChdConverter], so unit tests use a fake and never need the binary.
 */
data class PsxInstallFile(
    /**
     * Destination path relative to `psx/`, e.g.
     * `Crash Bandicoot.chd` or
     * `Metal Gear Solid.m3u/Metal Gear Solid (Disc 1).chd`.
     */
    val destRelPath: String,
    /** Source file inside the temp dir. */
    val source: File,
)

sealed interface PsxNormalizeOutcome {
    data class Success(
        val gameTitle: String,
        val files: List<PsxInstallFile>,
    ) : PsxNormalizeOutcome

    data class Failure(
        val reason: ImportFailureReason,
        val detail: String?,
    ) : PsxNormalizeOutcome
}

object PsxNormalizer {

    private class Cancelled : Exception()

    /**
     * Normalizes the extracted files in [tempDir].
     *
     * @param displayTitle the archive's display title (already cleaned
     * at scan time); disc markers are stripped defensively.
     * @param converter the chdman backend, or null when unavailable —
     * CUE inputs then fail with PSX_CHDMAN_MISSING while already-valid
     * CHD/PBP/lone inputs still install as-is.
     * @param onProgress (label, 0..1 or null) for the NORMALIZING UI.
     */
    fun normalize(
        tempDir: File,
        displayTitle: String,
        converter: ChdConverter?,
        onProgress: (label: String, fraction: Float?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): PsxNormalizeOutcome {
        return try {
            normalizeInner(tempDir, displayTitle, converter, onProgress, isCancelled)
        } catch (_: Cancelled) {
            converter?.cancel()
            PsxNormalizeOutcome.Failure(ImportFailureReason.CANCELLED, "Cancelled during conversion")
        } catch (e: FailureException) {
            PsxNormalizeOutcome.Failure(e.reason, e.detailText)
        }
    }

    private fun normalizeInner(
        tempDir: File,
        displayTitle: String,
        converter: ChdConverter?,
        onProgress: (label: String, fraction: Float?) -> Unit,
        isCancelled: () -> Boolean,
    ): PsxNormalizeOutcome {
        fun checkCancelled() {
            if (isCancelled()) throw Cancelled()
        }

        val allFiles = tempDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(tempDir).path.replace(File.separatorChar, '/') to it }
            .toList()
        val byBaseName = allFiles.groupBy { (rel, _) -> rel.substringAfterLast('/') }

        val baseTitle = stripDiscMarker(sanitizeFileName(displayTitle))
            .ifEmpty { "game" }
        val input = groupPsxSources(byBaseName.keys.toList(), baseTitle)
            ?: return PsxNormalizeOutcome.Failure(
                ImportFailureReason.PSX_NO_VALID_DISCS,
                "No CUE, CHD, PBP, ISO, or BIN found in the archive",
            )

        fun resolve(name: String): File? = byBaseName[name]?.firstOrNull()?.second

        /** Final CHD install entries for one validated/converted disc. */
        data class DoneDisc(val chdFile: File, val chdName: String, val sbiFile: File?, val sbiName: String?)

        fun convertCueDisc(disc: PsxSourceDisc, index: Int, total: Int): DoneDisc? {
            val cueName = disc.cueName
                ?: return null // unreachable: only called for CUE discs
            val cueFile = resolve(cueName)
                ?: return null // unreachable: grouped from these names
            val cueDir = cueFile.parentFile ?: tempDir

            // --- Validate every referenced track exists. ---
            val cueText = cueFile.readText()
            val refs = CueParser.referencedFiles(cueText)
            if (refs.isEmpty()) {
                throw FailureException(
                    ImportFailureReason.PSX_CUE_TRACKS_MISSING,
                    "$cueName has no FILE entries — not a usable disc image",
                )
            }
            val patchedLines = cueText.lines().toMutableList()
            for (ref in refs) {
                val direct = File(cueDir, ref).takeIf { it.isFile }
                val found = direct
                    ?: byBaseName.keys.firstOrNull { it.equals(ref, ignoreCase = true) }
                        ?.let { byBaseName[it]!!.first().second }
                if (found == null) {
                    throw FailureException(
                        ImportFailureReason.PSX_CUE_TRACKS_MISSING,
                        "$cueName references \"$ref\", which is not in the archive",
                    )
                }
                // Case-only mismatch: patch the TEMP cue copy so
                // chdman finds the track. User data is never touched.
                if (direct == null && found.name != ref) {
                    for (i in patchedLines.indices) {
                        if (patchedLines[i].contains(ref)) {
                            patchedLines[i] = patchedLines[i].replace(ref, found.name)
                        }
                    }
                }
            }
            val patchedText = patchedLines.joinToString("\n")
            if (patchedText != cueText) {
                cueFile.writeText(patchedText)
            }

            // --- Convert. ---
            val conv = converter?.takeIf { it.available }
                ?: throw FailureException(
                    ImportFailureReason.PSX_CHDMAN_MISSING,
                    "chdman is not available on this device — cannot convert $cueName",
                )
            checkCancelled()
            val chdName = if (total == 1) "$baseTitle.chd" else "$baseTitle (Disc ${disc.discNumber}).chd"
            val outChd = File(tempDir, chdName)
            if (outChd.exists()) outChd.delete()
            onProgress("CONVERTING DISC ${index + 1}/$total", 0f)
            val ok = conv.createcd(cueFile, outChd) { fraction ->
                if (isCancelled()) {
                    // Destroy the process; createcd returns false.
                    // We must not throw from the callback — the
                    // converter swallows callback exceptions — so the
                    // typed CANCELLED outcome is raised just below.
                    conv.cancel()
                } else {
                    onProgress("CONVERTING DISC ${index + 1}/$total", fraction)
                }
            }
            if (isCancelled()) throw Cancelled()
            if (!ok || !conv.verify(outChd)) {
                runCatching { outChd.delete() }
                throw FailureException(
                    ImportFailureReason.PSX_CHDMAN_FAILED,
                    "chdman failed to convert or verify $cueName",
                )
            }
            val sbiName = disc.sbiName
            val sbiFile = sbiName?.let { resolve(it) }
            return DoneDisc(
                chdFile = outChd,
                chdName = chdName,
                sbiFile = sbiFile,
                sbiName = sbiFile?.let { "${chdName.substringBeforeLast('.')}.sbi" },
            )
        }

        val done = mutableListOf<DoneDisc>()
        when (input) {
            is PsxGameInput.CueDiscs -> {
                onProgress("CHECKING DISCS", null)
                input.discs.forEachIndexed { index, disc ->
                    convertCueDisc(disc, index, input.discs.size)?.let { done += it }
                }
            }
            is PsxGameInput.Chds -> {
                // Already CHD: validate when we can, use directly —
                // never recompress.
                val conv = converter?.takeIf { it.available }
                input.discs.forEachIndexed { index, disc ->
                    checkCancelled()
                    val src = resolve(disc.primaryName)!!
                    onProgress("VERIFYING DISC ${index + 1}/${input.discs.size}", null)
                    if (conv != null && !conv.verify(src)) {
                        throw FailureException(
                            ImportFailureReason.PSX_CHDMAN_FAILED,
                            "${disc.primaryName} failed chdman verification",
                        )
                    }
                    val chdName = if (input.discs.size == 1) "$baseTitle.chd"
                    else "$baseTitle (Disc ${disc.discNumber}).chd"
                    val sbiFile = disc.sbiName?.let { resolve(it) }
                    done += DoneDisc(
                        chdFile = src,
                        chdName = chdName,
                        sbiFile = sbiFile,
                        sbiName = sbiFile?.let { "${chdName.substringBeforeLast('.')}.sbi" },
                    )
                }
            }
            is PsxGameInput.SinglePbp -> {
                // chdman cannot read PBP: preserve the valid input.
                val src = resolve(input.fileName)!!
                done += DoneDisc(src, "$baseTitle.pbp", null, null)
            }
            is PsxGameInput.LoneImage -> {
                // Already one clean ES-DE entry: keep the extension.
                val ext = input.fileName.substringAfterLast('.', "bin")
                val src = resolve(input.fileName)!!
                done += DoneDisc(src, "$baseTitle.$ext", null, null)
            }
        }
        if (done.isEmpty()) {
            return PsxNormalizeOutcome.Failure(
                ImportFailureReason.PSX_NO_VALID_DISCS,
                "No usable PS1 disc images in the archive",
            )
        }

        // --- Assemble the final layout. ---
        val files = mutableListOf<PsxInstallFile>()
        if (done.size == 1) {
            val d = done.single()
            files += PsxInstallFile(d.chdName, d.chdFile)
            if (d.sbiFile != null && d.sbiName != null) {
                files += PsxInstallFile(d.sbiName, d.sbiFile)
            }
        } else {
            // ES-DE "directories interpreted as files": the directory
            // and the playlist share the name `<Base>.m3u` → ONE entry.
            val dirName = "$baseTitle.m3u"
            val m3uName = "$baseTitle.m3u"
            val m3uContent = buildString {
                for (d in done) append(d.chdName).append('\n')
            }
            val m3uFile = File(tempDir, "__crystal.m3u").also { it.writeText(m3uContent) }
            files += PsxInstallFile("$dirName/$m3uName", m3uFile)
            for (d in done) {
                files += PsxInstallFile("$dirName/${d.chdName}", d.chdFile)
                if (d.sbiFile != null && d.sbiName != null) {
                    files += PsxInstallFile("$dirName/${d.sbiName}", d.sbiFile)
                }
            }
        }
        onProgress("READY", 1f)
        return PsxNormalizeOutcome.Success(baseTitle, files)
    }

    private class FailureException(
        val reason: ImportFailureReason,
        detail: String?,
    ) : Exception(detail) {
        val detailText: String? = detail
    }
}
