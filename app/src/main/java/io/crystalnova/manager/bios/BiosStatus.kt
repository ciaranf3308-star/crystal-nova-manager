package io.crystalnova.manager.bios

/**
 * Firmware requirement of one platform for its *configured* emulator.
 *
 * - REQUIRED: the configured emulator cannot boot games without real
 *   firmware (PS2 / NetherSX2). Only these create setup issues.
 * - OPTIONAL: the configured emulator ships an HLE fallback, so games
 *   boot without firmware (PS1 / RetroArch, Saturn / YabaSanshiro,
 *   Dreamcast / Flycast). Never gates READY.
 * - NOT_REQUIRED: firmware is irrelevant for this platform.
 */
enum class BiosRequirement { REQUIRED, OPTIONAL, NOT_REQUIRED }

/**
 * Firmware state of one platform, from the on-SD inventory.
 *
 * - READY: firmware present and usable, or not needed (HLE fallback /
 *   not required / no games present).
 * - FOUND_UNVERIFIED: a candidate file exists but does not look like a
 *   real BIOS (name/size mismatch) — never presented as READY.
 * - REQUIRED_MISSING: games are present, firmware is required, and no
 *   candidate was found on the SD card.
 * - IMPORT_REQUIRED: a valid-looking BIOS is on the SD card, but the
 *   emulator still needs the user to import it in-app (NetherSX2 keeps
 *   its BIOS in app-private storage, which Crystal cannot write).
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
 * SMALL: only systems where firmware actually matters. Candidate
 * filenames are patterns, not a hash database — a name+size match is
 * "valid-looking" (IMPORT_REQUIRED), never silently auto-trusted
 * beyond that.
 */
data class BiosFirmware(
    val platformSlug: String,
    val label: String,
    val requirement: BiosRequirement,
    /** Case-insensitive regex over the bare file name. */
    val candidateNameRegex: Regex,
    /** Expected byte size of a real BIOS image, 0 when unknown. */
    val expectedSizeBytes: Long,
    /** Package of the configured emulator that consumes the firmware. */
    val emulatorPackage: String?,
)

/**
 * The v24 firmware table. PS2 is the only REQUIRED entry: NetherSX2
 * throws a BIOS-required error without a real imported BIOS. PS1,
 * Saturn and Dreamcast run on their configured emulators' HLE
 * firmware, so they report READY and never block. Everything else is
 * NOT_REQUIRED.
 */
object BiosFirmwareTable {
    const val PS2_BIOS_SIZE = 4_194_304L // 4 MiB — every retail PS2 BIOS

    val PS2 = BiosFirmware(
        platformSlug = "ps2",
        label = "PS2",
        requirement = BiosRequirement.REQUIRED,
        candidateNameRegex = Regex("(?i)^scph[^/]*\\.bin$"),
        expectedSizeBytes = PS2_BIOS_SIZE,
        emulatorPackage = "xyz.aethersx2.android",
    )

    private val optional = listOf(
        BiosFirmware("psx", "PS1", BiosRequirement.OPTIONAL, Regex("(?i)^scph.*\\.bin$"), 524_288L, null),
        BiosFirmware("saturn", "SATURN", BiosRequirement.OPTIONAL, Regex("(?i)^.*\\.bin$"), 0L, null),
        BiosFirmware("dreamcast", "DREAMCAST", BiosRequirement.OPTIONAL, Regex("(?i)^dc_boot\\.bin$"), 2_097_152L, null),
    )

    private val bySlug: Map<String, BiosFirmware> =
        (listOf(PS2) + optional).associateBy { it.platformSlug }

    fun forPlatform(platformSlug: String): BiosFirmware? = bySlug[platformSlug]

    /** Platforms shown on the BIOS status screen, in display order. */
    fun statusScreenPlatforms(): List<BiosFirmware> =
        listOf(PS2) + optional
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
