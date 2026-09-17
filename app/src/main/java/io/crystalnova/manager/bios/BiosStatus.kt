package io.crystalnova.manager.bios

/**
 * Firmware requirement of one platform for its *configured* emulator.
 *
 * - REQUIRED: the configured emulator cannot boot games without real
 *   firmware (PS2 / NetherSX2). Only these create setup issues.
 * - OPTIONAL: reserved for a platform whose configured emulator is
 *   known-good with an HLE fallback. v24 tracks no such platform —
 *   PS2 is the only firmware entry. Never gates READY.
 * - NOT_REQUIRED: firmware is irrelevant for this platform.
 */
enum class BiosRequirement { REQUIRED, OPTIONAL, NOT_REQUIRED }

/**
 * Firmware state of one platform, from the on-SD inventory.
 *
 * - READY: firmware present and usable (PS2: a main BIOS candidate
 *   is on the SD card and the emulator-side import is attested), or
 *   firmware is not needed (no games present for the platform).
 * - FOUND_UNVERIFIED: PS2 dump files exist but no main BIOS
 *   candidate was identified (ancillary artifacts only, or
 *   BIOS-named files with insane sizes) — never presented as READY.
 * - REQUIRED_MISSING: games are present, firmware is required, and
 *   nothing PS2-shaped was found on the SD card.
 * - IMPORT_REQUIRED: at least one main BIOS candidate is on the SD
 *   card (strong SCPH shape or plausible candidate — NetherSX2
 *   validates the user's pick), but the emulator still needs the
 *   user to import it in-app (NetherSX2 keeps its BIOS in
 *   app-private storage, which Crystal cannot write).
 * - NOT_REQUIRED: firmware is irrelevant for this platform.
 */
enum class BiosStatus {
    READY,
    FOUND_UNVERIFIED,
    REQUIRED_MISSING,
    IMPORT_REQUIRED,
    NOT_REQUIRED,
}

/**
 * One firmware entry for a platform Crystal exposes. Deliberately
 * SMALL: only systems where firmware actually matters. PS2 candidate
 * detection lives in [Ps2BiosClassifier] — the single authority for
 * what counts as a plausible BIOS — not in filename patterns here.
 */
data class BiosFirmware(
    val platformSlug: String,
    val label: String,
    val requirement: BiosRequirement,
    /** Package of the configured emulator that consumes the firmware. */
    val emulatorPackage: String?,
)

/**
 * The v24 firmware table. Deliberately PS2-only: NetherSX2 throws a
 * BIOS-required error without a real imported BIOS, and PS2 is the
 * only platform whose firmware can block READY. Every other
 * platform's firmware is out of scope for v24 — Crystal asserts
 * nothing about their HLE fallbacks or BIOS state (in particular,
 * Saturn has no verified launcher configured, so no Saturn firmware
 * claim is made), and nothing optional ever gates.
 */
object BiosFirmwareTable {
    val PS2 = BiosFirmware(
        platformSlug = "ps2",
        label = "PS2",
        requirement = BiosRequirement.REQUIRED,
        emulatorPackage = "xyz.aethersx2.android",
    )

    private val bySlug: Map<String, BiosFirmware> = mapOf(PS2.platformSlug to PS2)

    fun forPlatform(platformSlug: String): BiosFirmware? = bySlug[platformSlug]

    /** Platforms shown on the BIOS status screen, in display order. */
    fun statusScreenPlatforms(): List<BiosFirmware> = listOf(PS2)
}

/**
 * One firmware problem that feeds the HOME readiness issue model.
 * [headline] is the short status fragment shown on HOME, e.g.
 * "PS2 · BIOS REQUIRED".
 */
data class BiosIssue(
    val platformLabel: String,
    val headline: String,
)
