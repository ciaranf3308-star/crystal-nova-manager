package io.crystalnova.manager.bios

/**
 * Confidence tier of a main PS2 BIOS candidate.
 *
 * - STRONG: the classic retail dump shape — an `SCPH`-named `.bin`
 *   at a sane BIOS size. This is what PCSX2/NetherSX2 expect.
 * - CANDIDATE: plausible but not the classic shape — a `.rom0`
 *   dump or a non-SCPH `.bin` at a sane BIOS size. Honest, not
 *   verified: NetherSX2 validates the user's pick at import.
 */
enum class Ps2CandidateConfidence { STRONG, CANDIDATE }

/** One main PS2 BIOS candidate: a file NetherSX2 could import. */
data class Ps2BiosCandidate(
    val file: BiosFile,
    val confidence: Ps2CandidateConfidence,
)

/**
 * Full classification of a scanned file list for PS2 firmware.
 *
 * - [candidates]: main BIOS candidates, strongest first. Empty when
 *   nothing looks like an importable BIOS image.
 * - [ancillary]: PS2 dump artifacts (`.nvm`, `.rom1`, `.rom2`,
 *   `erom…`) that support "PS2 firmware exists" but are never the
 *   main importable BIOS.
 * - [misSized]: BIOS-named files (`SCPH` `.bin`, `.rom0`) whose size
 *   is outside the sane range — present, not sane, never trusted.
 */
data class Ps2Detection(
    val candidates: List<Ps2BiosCandidate> = emptyList(),
    val ancillary: List<BiosFile> = emptyList(),
    val misSized: List<BiosFile> = emptyList(),
)

/**
 * PS2 BIOS candidate classifier — pure, JVM-testable, no Android.
 *
 * Hardware truth (2026-09-17, Nova): a working EmuDeck BIOS tree
 * copied from a ROG Ally holds ~20 PS2 dump-related files under all
 * kinds of names. Requiring `SCPH*.bin` at exactly 4 MiB reported
 * that folder MISSING — a false negative on a setup that runs PS2
 * games fine. This classifier is deliberately generous about
 * *finding* candidates and strict about *trusting* them:
 *
 * - main candidates: `SCPH*.bin`, `*.rom0`, or any `*.bin` whose size
 *   is in the sane PCSX2 range (4–8 MiB; retail dumps are 4 MiB).
 *   Strongest first; NetherSX2 validates the user's pick at import.
 * - ancillary: `.nvm` / `.rom1` / `.rom2` / `erom…` files. These name
 *   specific dump artifacts, so they are supporting evidence only —
 *   never a main candidate, even at a BIOS-like size.
 * - mis-sized: BIOS-named files outside the sane range.
 *
 * Crystal never distributes, downloads, or links firmware, and READY
 * still requires the user's NetherSX2 import attestation — a
 * candidate is never silently auto-trusted.
 */
object Ps2BiosClassifier {
    /** Retail PS2 BIOS dumps are 4 MiB — the floor of the sane range. */
    const val MIN_BIOS_SIZE_BYTES = 4_194_304L

    /** Some dumps pad out; anything past 8 MiB is not a PS2 BIOS. */
    const val MAX_BIOS_SIZE_BYTES = 8_388_608L

    private val SCPH_BIN = Regex("(?i)^scph[^/]*\\.bin$")
    private val ROM0 = Regex("(?i)^[^/]*\\.rom0$")
    private val ANY_BIN = Regex("(?i)^[^/]*\\.bin$")
    private val ANCILLARY = Regex("(?i)^([^/]*\\.(nvm|rom1|rom2)|erom[^/]*)$")

    private fun saneSize(sizeBytes: Long): Boolean =
        sizeBytes in MIN_BIOS_SIZE_BYTES..MAX_BIOS_SIZE_BYTES

    fun classify(files: List<BiosFile>): Ps2Detection {
        val candidates = mutableListOf<Ps2BiosCandidate>()
        val ancillary = mutableListOf<BiosFile>()
        val misSized = mutableListOf<BiosFile>()
        for (f in files) {
            val name = f.name
            when {
                // Dump artifacts first: an nvm/rom1/rom2/erom file
                // names a specific artifact, so it is supporting
                // evidence only — never the importable BIOS.
                ANCILLARY.matches(name) -> ancillary.add(f)
                // Main candidates: a BIOS-sized dump is a plausible
                // import whatever it is called. SCPH shape is strong;
                // anything else plausible is a candidate for
                // NetherSX2 to validate.
                (ANY_BIN.matches(name) || ROM0.matches(name)) && saneSize(f.sizeBytes) ->
                    candidates.add(
                        Ps2BiosCandidate(
                            f,
                            if (SCPH_BIN.matches(name)) Ps2CandidateConfidence.STRONG
                            else Ps2CandidateConfidence.CANDIDATE,
                        ),
                    )
                // BIOS-named but insanely sized: found, not sane.
                SCPH_BIN.matches(name) || ROM0.matches(name) -> misSized.add(f)
            }
        }
        // Strongest first; stable within a tier (scan order kept).
        candidates.sortBy { it.confidence.ordinal }
        return Ps2Detection(candidates, ancillary, misSized)
    }
}
