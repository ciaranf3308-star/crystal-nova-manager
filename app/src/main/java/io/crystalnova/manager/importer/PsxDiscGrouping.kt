package io.crystalnova.manager.importer

/**
 * PS1 source grouping: which extracted files form one game, and which
 * form one physical disc.
 *
 * The critical invariant: ONE CUE = ONE physical disc. Fifteen BIN
 * tracks referenced by a single CUE are fifteen tracks of ONE disc —
 * never fifteen discs. Separate physical discs are detected ONLY from
 * disc markers in the CUE filenames (Disc 1, Disk 2, CD1, (D2), …).
 * `(Track NN)` markers are audio/data tracks inside a disc and are
 * deliberately never treated as disc markers.
 *
 * Pure JVM — unit-tested without Android.
 */

/** One physical PS1 disc's source files, as found in the temp dir. */
data class PsxSourceDisc(
    /** CUE filename, or null for CHD/PBP/lone-image inputs. */
    val cueName: String?,
    /** The disc's primary file: CUE, CHD, PBP, or lone ISO/BIN. */
    val primaryName: String,
    /** 1-based physical disc number, or null when unknowable. */
    val discNumber: Int?,
    /** SBI filename sitting next to the source, or null. */
    val sbiName: String?,
)

/** Grouped PS1 input ready for normalization. */
sealed interface PsxGameInput {
    val baseTitle: String

    /** One or more CUE/BIN discs → each converts to its own CHD. */
    data class CueDiscs(
        override val baseTitle: String,
        val discs: List<PsxSourceDisc>,
    ) : PsxGameInput

    /** Already-converted CHDs → validated and used directly. */
    data class Chds(
        override val baseTitle: String,
        val discs: List<PsxSourceDisc>,
    ) : PsxGameInput

    /** PBP (chdman cannot read it) → installed as-is. */
    data class SinglePbp(
        override val baseTitle: String,
        val fileName: String,
    ) : PsxGameInput

    /** A lone ISO/BIN with no CUE → installed as-is (already one clean ES-DE entry). */
    data class LoneImage(
        override val baseTitle: String,
        val fileName: String,
    ) : PsxGameInput
}

private val discPatterns = listOf(
    // "Disc 1", "Disk_2", "(Disc 1)", "Disc 1 of 2" — never "(Track 01)".
    Regex("""(?i)(?:^|[\s._(\[-])(?:disc|disk)[\s._\-]*(\d+)"""),
    // "CD1", "CD 2", "(CD1)".
    Regex("""(?i)(?:^|[\s._(\[-])cd[\s._\-]*(\d+)"""),
    // "(D1)", "[d2]".
    Regex("""(?i)[(\[]d(\d+)[)\]]"""),
)

/**
 * 1-based physical disc number from a filename, or null.
 * `(Track NN)` and friends never match: they carry no disc marker.
 */
fun discNumberOf(fileName: String): Int? {
    val stem = fileName.substringBeforeLast('.')
    for (pattern in discPatterns) {
        val m = pattern.find(stem) ?: continue
        val n = m.groupValues[1].toIntOrNull() ?: continue
        if (n in 1..99) return n
    }
    return null
}

private val discMarkerStrip = listOf(
    // "(Disc 1)", "[CD 2]", "(D1)" — bracketed markers anywhere.
    Regex("""(?i)\s*[\(\[]\s*(?:disc|disk|cd|d)\s*\d+[^)\]]*[\)\]]"""),
    // Trailing "Disc 1" / "Disk_2" / "CD1" without brackets.
    Regex("""(?i)[\s._\-]+(?:disc|disk)[\s._\-]*\d+\s*$"""),
    Regex("""(?i)[\s._\-]+cd[\s._\-]*\d+\s*$"""),
)

/**
 * Drops disc markers from a title: "Metal Gear Solid (Disc 1)" →
 * "Metal Gear Solid". Track markers are left alone (they never
 * appear in titles at this point anyway).
 */
fun stripDiscMarker(title: String): String {
    var out = title
    for (pattern in discMarkerStrip) out = pattern.replace(out, "")
    return out.replace(Regex("""[\s._\-]+"""), " ").trim()
}

/** Case-insensitive extension check on a bare filename. */
private fun String.extIs(vararg exts: String): Boolean {
    val lower = lowercase()
    return exts.any { lower.endsWith(".$it") }
}

/**
 * Groups extracted filenames into one [PsxGameInput]. Returns null
 * when nothing usable is present (the normalizer reports
 * PSX_NO_VALID_DISCS).
 *
 * [baseTitle] is the sanitized archive display title; disc markers
 * are stripped from it defensively (cleanDisplayTitle usually already
 * removed bracketed tags).
 */
fun groupPsxSources(
    fileNames: List<String>,
    baseTitle: String,
): PsxGameInput? {
    val names = fileNames.filter { !it.startsWith('.') }
    if (names.isEmpty()) return null
    val title = stripDiscMarker(baseTitle).ifEmpty { baseTitle }

    val byLower = names.associateBy { it.lowercase() }
    fun findSbi(stem: String): String? {
        // Exact stem first ("Game (Disc 1).sbi"), then the bare game
        // title ("Game.sbi") — SBI sets are often shipped once per game.
        return byLower["$stem.sbi".lowercase()]
            ?: byLower["$title.sbi".lowercase()]
    }

    val cues = names.filter { it.extIs("cue") }.sorted()
    if (cues.isNotEmpty()) {
        val discs = cues.mapIndexed { index, cue ->
            val stem = cue.substringBeforeLast('.')
            PsxSourceDisc(
                cueName = cue,
                primaryName = cue,
                // A lone CUE is disc 1 even without a marker; with
                // several unmarked CUEs, filename order wins.
                discNumber = discNumberOf(cue) ?: index + 1,
                sbiName = findSbi(stem),
            )
        }.sortedBy { it.discNumber }
        return PsxGameInput.CueDiscs(title, discs)
    }

    val chds = names.filter { it.extIs("chd") }.sorted()
    if (chds.isNotEmpty()) {
        val discs = chds.mapIndexed { index, chd ->
            PsxSourceDisc(
                cueName = null,
                primaryName = chd,
                discNumber = discNumberOf(chd) ?: index + 1,
                sbiName = findSbi(chd.substringBeforeLast('.')),
            )
        }.sortedBy { it.discNumber }
        return PsxGameInput.Chds(title, discs)
    }

    val pbps = names.filter { it.extIs("pbp") }.sorted()
    if (pbps.isNotEmpty()) {
        return PsxGameInput.SinglePbp(title, pbps.first())
    }

    // Lone ISO/BIN with no CUE: already a single ES-DE entry.
    // (A BIN that belongs to a CUE never reaches this branch —
    // cues win above.)
    val images = names.filter { it.extIs("iso", "bin") }.sorted()
    if (images.isNotEmpty()) {
        return PsxGameInput.LoneImage(title, images.first())
    }

    return null
}

/**
 * Simulates ES-DE's PSX frontend entries for a final `psx/` layout —
 * the product requirement is ONE entry per game. Extensions ES-DE
 * indexes for PSX: cue, bin, chd, m3u, iso, pbp. A directory named
 * `*.m3u` counts as ONE entry (ES-DE's "directories interpreted as
 * files"); its children are not separate entries. This mirrors the
 * exporter rule in GameLibraryExport.kt — both must tell the same
 * one-game truth.
 */
fun esdePsxEntries(files: List<String>, dirs: List<String>): List<String> {
    val indexed = setOf("cue", "bin", "chd", "m3u", "iso", "pbp")
    val entries = mutableListOf<String>()
    for (dir in dirs.sorted()) {
        if (dir.substringAfterLast('.', "").lowercase() == "m3u") {
            entries += dir.substringBeforeLast('.')
        }
    }
    for (file in files.sorted()) {
        if (file.substringAfterLast('.', "").lowercase() in indexed) {
            entries += file.substringBeforeLast('.')
        }
    }
    return entries
}
