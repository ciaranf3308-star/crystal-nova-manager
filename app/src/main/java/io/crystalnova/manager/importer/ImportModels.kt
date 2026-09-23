package io.crystalnova.manager.importer

import java.util.Locale

/**
 * Game Importer: core domain models.
 *
 * Everything here is pure JVM (no Android types) so the detection,
 * queue, and engine logic stays unit-testable on the JVM. Android
 * specifics (SAF streams, notifications) enter through
 * [ImporterEnvironment].
 */

/**
 * The 18 console identities the Nova library configures. Genesis and
 * Mega Drive are distinct identities (the user names both) that share
 * one default ROM folder — see [PlatformMapping].
 */
enum class PlatformId {
    NES,
    SNES,
    N64,
    GB,
    GBC,
    GBA,
    NDS,
    N3DS,
    GAMECUBE,
    WII,
    WIIU,
    DREAMCAST,
    GENESIS,
    MEGADRIVE,
    PSX,
    PS2,
    PSP,
    XBOX,
}

/** Human-facing names for the classification grid and review screens. */
data class PlatformLabels(
    val long: String,
    val short: String,
)

fun PlatformId.labels(): PlatformLabels = when (this) {
    PlatformId.NES -> PlatformLabels("Nintendo Entertainment System", "NES")
    PlatformId.SNES -> PlatformLabels("Super Nintendo", "SNES")
    PlatformId.N64 -> PlatformLabels("Nintendo 64", "N64")
    PlatformId.GB -> PlatformLabels("Game Boy", "GB")
    PlatformId.GBC -> PlatformLabels("Game Boy Color", "GBC")
    PlatformId.GBA -> PlatformLabels("Game Boy Advance", "GBA")
    PlatformId.NDS -> PlatformLabels("Nintendo DS", "NDS")
    PlatformId.N3DS -> PlatformLabels("Nintendo 3DS", "3DS")
    PlatformId.GAMECUBE -> PlatformLabels("GameCube", "GC")
    PlatformId.WII -> PlatformLabels("Wii", "WII")
    PlatformId.WIIU -> PlatformLabels("Wii U", "WIIU")
    PlatformId.DREAMCAST -> PlatformLabels("Dreamcast", "DC")
    PlatformId.GENESIS -> PlatformLabels("Genesis", "GEN")
    PlatformId.MEGADRIVE -> PlatformLabels("Mega Drive", "MD")
    PlatformId.PSX -> PlatformLabels("PlayStation", "PS1")
    PlatformId.PS2 -> PlatformLabels("PlayStation 2", "PS2")
    PlatformId.PSP -> PlatformLabels("PSP", "PSP")
    PlatformId.XBOX -> PlatformLabels("Xbox", "XBOX")
}

/** How sure the detector (or the user) is about an archive's platform. */
enum class Confidence {
    /** Deterministic signal: ROM extension, disc-header magic, serial range. */
    CONFIRMED,

    /** Filename alias or weak structural hint — shown for manual review. */
    LIKELY,

    /** No usable signal. */
    UNKNOWN,
}

/** Result of classifying one archive. */
data class Detection(
    val platform: PlatformId?,
    val confidence: Confidence,
    /** Human-readable evidence, e.g. "game.gba", "SLUS-20554". */
    val signals: List<String> = emptyList(),
    /** False when the archive looks like docs/tools rather than a game. */
    val recognizedAsGame: Boolean = true,
    /** True when the platform came from the user's tap, not the detector. */
    val manual: Boolean = false,
)

/** Archive container kinds the scanner understands. */
enum class ArchiveKind {
    ZIP,
    SEVEN_Z,

    /**
     * Honest unsupported marker: RAR is reported, never silently
     * skipped, and never attempted. The UI explains why.
     */
    RAR_UNSUPPORTED,
    UNKNOWN,
}

fun archiveKindOf(fileName: String): ArchiveKind {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".zip") -> ArchiveKind.ZIP
        lower.endsWith(".7z") -> ArchiveKind.SEVEN_Z
        lower.endsWith(".rar") -> ArchiveKind.RAR_UNSUPPORTED
        else -> ArchiveKind.UNKNOWN
    }
}

/** One entry inside an archive, from streaming inspection (no decompression). */
data class ArchiveEntryInfo(
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
)

/** What the inspector learned without decompressing content. */
data class ArchiveInspection(
    val kind: ArchiveKind,
    val entries: List<ArchiveEntryInfo>,
    /** Sum of uncompressed entry sizes (0 when the format hides it). */
    val totalUncompressedBytes: Long,
    /** Distinct lowercase extensions present, e.g. {"gba"}. */
    val extensions: Set<String>,
    /** Top-level names (files and dirs) — used for wrapper detection. */
    val topLevelNames: List<String>,
)

/** Where the extracted payload will land inside the platform folder. */
sealed interface ImportTarget {
    /** One ROM file placed directly, e.g. `gba/Pokemon Emerald.gba`. */
    data class SingleFile(val fileName: String) : ImportTarget

    /**
     * A game folder preserving internal structure, e.g.
     * `psx/Metal Gear Solid/game.bin + game.cue`. The folder name is
     * derived from the display title; ROM filenames inside are kept
     * byte-identical.
     */
    data class GameFolder(val folderName: String) : ImportTarget
}

/**
 * The placement plan for one archive, computed at scan time from the
 * inspection. Persisted with the queue item so a restart never
 * re-derives it.
 */
data class ImportPlan(
    val target: ImportTarget,
    /** Game files under the payload root (dirs excluded). */
    val payloadFileCount: Int,
    /** Uncompressed payload bytes (estimate from the archive headers). */
    val payloadBytes: Long,
    /**
     * Leading path segments stripped as a redundant wrapper folder
     * (see [unwrapPayloadPaths]). The extractor applies the same
     * number so scan-time and extract-time layouts agree exactly.
     */
    val unwrapDepth: Int = 0,
)

/** Machine stages for one archive import. */
enum class ImportStage {
    /** Classified, waiting for the import run. */
    WAITING,

    /** Mid-run: re-checking the archive still exists. */
    INSPECTING,

    /** Mid-run: decompressing into the staging dir. */
    EXTRACTING,

    /** Mid-run: moving the verified payload into the platform folder. */
    COPYING,

    /** Mid-run: existence/count/size checks on the destination. */
    VERIFYING,

    /** Mid-run: removing the staging dir. */
    CLEANING,

    COMPLETE,
    FAILED,

    /** Duplicate of an existing game, or user-skipped. Source kept. */
    SKIPPED,
}

/** Typed failure reasons — the UI shows these, never raw stack traces. */
enum class ImportFailureReason {
    ARCHIVE_CORRUPT,
    UNSUPPORTED_FORMAT,
    UNKNOWN_PLATFORM,
    NOT_RECOGNIZED_AS_GAME,
    DESTINATION_UNAVAILABLE,
    NOT_ENOUGH_STORAGE,
    PERMISSION_DENIED,
    EXTRACTION_FAILED,
    WRITE_FAILED,
    VERIFICATION_FAILED,
    DUPLICATE_SKIPPED,
    SOURCE_MISSING,
    CANCELLED,
    INTERNAL_ERROR,
}

fun ImportFailureReason.message(): String = when (this) {
    ImportFailureReason.ARCHIVE_CORRUPT -> "ARCHIVE COULD NOT BE READ"
    ImportFailureReason.UNSUPPORTED_FORMAT -> "ARCHIVE FORMAT NOT SUPPORTED"
    ImportFailureReason.UNKNOWN_PLATFORM -> "PLATFORM UNKNOWN"
    ImportFailureReason.NOT_RECOGNIZED_AS_GAME -> "NOT RECOGNIZED AS A GAME"
    ImportFailureReason.DESTINATION_UNAVAILABLE -> "ROM STORAGE UNAVAILABLE"
    ImportFailureReason.NOT_ENOUGH_STORAGE -> "NOT ENOUGH STORAGE"
    ImportFailureReason.PERMISSION_DENIED -> "STORAGE PERMISSION DENIED"
    ImportFailureReason.EXTRACTION_FAILED -> "EXTRACTION FAILED"
    ImportFailureReason.WRITE_FAILED -> "COULD NOT WRITE TO THE ROM FOLDER"
    ImportFailureReason.VERIFICATION_FAILED -> "DESTINATION VERIFICATION FAILED"
    ImportFailureReason.DUPLICATE_SKIPPED -> "ALREADY IN LIBRARY — SKIPPED"
    ImportFailureReason.SOURCE_MISSING -> "ARCHIVE NO LONGER IN DOWNLOADS"
    ImportFailureReason.CANCELLED -> "CANCELLED"
    ImportFailureReason.INTERNAL_ERROR -> "UNEXPECTED ERROR"
}

/** What to do when the destination already holds the game. */
enum class DuplicatePolicy {
    SKIP,
    REPLACE,
    KEEP_BOTH,
}

/**
 * One archive in the import queue. Persisted to app-private JSON on
 * every state transition (see [ImportQueue]) so classification and
 * queue state survive app restarts.
 */
data class ArchiveItem(
    /** Stable id (random UUID at scan time). */
    val id: String,
    /** SAF document URI string of the source archive in Downloads. */
    val archiveUri: String,
    /** Original filename, e.g. "Metal Gear Solid 2 Substance.7z". */
    val archiveName: String,
    /** Cleaned display name, e.g. "Metal Gear Solid 2 Substance". */
    val displayTitle: String,
    val archiveKind: ArchiveKind,
    val archiveBytes: Long,
    val detection: Detection,
    /** Null until the user (or auto-identify) assigns a platform. */
    val platform: PlatformId?,
    val plan: ImportPlan?,
    val stage: ImportStage = ImportStage.WAITING,
    val failure: ImportFailureReason? = null,
    val failureDetail: String? = null,
    /** Inspection itself failed (corrupt/unsupported) — never imported. */
    val inspectFailed: Boolean = false,
    /** Per-item duplicate resolution chosen in the conflict screen. */
    val duplicatePolicy: DuplicatePolicy? = null,
    /** Bytes actually extracted (filled during EXTRACTING). */
    val extractedBytes: Long = 0L,
    /**
     * The source archive vanished from Downloads after it was queued
     * (transient across scans, persisted so a restart keeps the flag).
     */
    val sourceMissing: Boolean = false,
    val addedAt: Long = 0L,
) {
    /** True when the item still needs a human decision. */
    val needsReview: Boolean
        get() = stage == ImportStage.WAITING &&
            !inspectFailed &&
            !sourceMissing &&
            detection.recognizedAsGame &&
            platform == null

    /** True when the item can enter the import run. */
    val importable: Boolean
        get() = !inspectFailed &&
            !sourceMissing &&
            detection.recognizedAsGame &&
            platform != null &&
            plan != null &&
            stage != ImportStage.COMPLETE &&
            stage != ImportStage.SKIPPED
}

/** Cleans "Pokemon - Emerald Version (USA) (Rev 1).zip" down to
 * "Pokemon Emerald Version" for display. The source filename is never
 * renamed on disk — this is display-only.
 */
fun cleanDisplayTitle(fileName: String): String {
    var name = fileName.substringBeforeLast('.')
    // Drop bracketed tags: (USA), (Rev 1), [GBA], [!], etc.
    name = name.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "")
    // "Pokemon - Emerald Version" -> "Pokemon Emerald Version".
    name = name.replace(Regex("\\s+-\\s+"), " ")
    name = name.replace('_', ' ')
    return name.replace(Regex("\\s+"), " ").trim().ifEmpty { fileName }
}

/**
 * Filesystem-safe folder/file name derived from a title. ROM filenames
 * themselves are kept intact — this is only for generated game-folder
 * names and staging dirs.
 */
fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
    return cleaned.ifEmpty { "game" }.take(120)
}

/** "4.3 GB" style formatting for the UI. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) {
        "$bytes B"
    } else {
        // One decimal, trimming ".0". Locale.US: deterministic on
        // every device (some locales use a decimal comma).
        val text = "%.1f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        "$text ${units[unit]}"
    }
}

/** Human-facing per-item import outcome for the history record. */
enum class ImportOutcome { SUCCESS, FAILED, SKIPPED }

/** One row of the lightweight import history shown on results. */
data class ImportHistoryEntry(
    val id: String,
    val title: String,
    val platform: PlatformId?,
    val archiveName: String,
    val outcome: ImportOutcome,
    val reason: ImportFailureReason?,
    val finishedAt: Long,
)

/** Controller-oriented display strings for failure reasons. */
fun ImportFailureReason.label(): String = when (this) {
    ImportFailureReason.ARCHIVE_CORRUPT -> "ARCHIVE COULD NOT BE READ"
    ImportFailureReason.UNSUPPORTED_FORMAT -> "UNSUPPORTED ARCHIVE"
    ImportFailureReason.UNKNOWN_PLATFORM -> "UNKNOWN PLATFORM"
    ImportFailureReason.NOT_RECOGNIZED_AS_GAME -> "NOT A GAME"
    ImportFailureReason.DESTINATION_UNAVAILABLE -> "ROM FOLDER UNAVAILABLE"
    ImportFailureReason.NOT_ENOUGH_STORAGE -> "NOT ENOUGH STORAGE"
    ImportFailureReason.PERMISSION_DENIED -> "ACCESS LOST"
    ImportFailureReason.EXTRACTION_FAILED -> "EXTRACTION FAILED"
    ImportFailureReason.WRITE_FAILED -> "WRITE FAILED"
    ImportFailureReason.VERIFICATION_FAILED -> "VERIFY FAILED"
    ImportFailureReason.DUPLICATE_SKIPPED -> "SKIPPED (DUPLICATE)"
    ImportFailureReason.SOURCE_MISSING -> "ARCHIVE GONE"
    ImportFailureReason.CANCELLED -> "CANCELLED"
    ImportFailureReason.INTERNAL_ERROR -> "UNEXPECTED ERROR"
}

/** Controller-oriented display strings for import stages. */
fun ImportStage.label(): String = when (this) {
    ImportStage.WAITING -> "QUEUED"
    ImportStage.INSPECTING -> "CHECKING ARCHIVE"
    ImportStage.EXTRACTING -> "EXTRACTING"
    ImportStage.COPYING -> "WRITING GAME"
    ImportStage.VERIFYING -> "VERIFYING"
    ImportStage.CLEANING -> "TIDYING UP"
    ImportStage.COMPLETE -> "DONE"
    ImportStage.FAILED -> "FAILED"
    ImportStage.SKIPPED -> "SKIPPED"
}
