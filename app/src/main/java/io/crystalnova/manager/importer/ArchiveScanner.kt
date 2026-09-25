package io.crystalnova.manager.importer

/**
 * Downloads scanner for the Game Importer.
 *
 * Lists the top level of the granted Downloads tree and picks out
 * supported archives AND loose game files. Supported archives: ZIP
 * and 7z. A loose (non-archive) file is picked up when its lowercase
 * extension is in [DetectionTables.looseFileExtension] — everything
 * the detector can confirm directly, plus the ambiguous disc
 * containers, plus `.m3u`. RAR files are reported as
 * [ArchiveKind.RAR_UNSUPPORTED] — honestly surfaced in the UI ("not
 * supported") rather than silently skipped; everything else
 * (`.sbi`, docs, images, …) is ignored as before.
 *
 * The [DownloadsListing] interface keeps this JVM-testable; the
 * production implementation lives in [ImporterEnvironment]. A future
 * `ACTION_OPEN_DOCUMENT` multi-select fallback can feed archives
 * through the same interface.
 */
data class DownloadFile(
    val name: String,
    val size: Long,
    val uri: String,
    val isDirectory: Boolean,
    /** Subfolder path relative to the listing root, e.g. "GameImport". Empty for top-level files. */
    val relativePath: String = "",
)

/** Lists the top level of the Downloads tree. */
interface DownloadsListing {
    fun listFiles(): List<DownloadFile>
}

/** One archive discovered in Downloads. */
data class ArchiveRef(
    val name: String,
    val uri: String,
    val size: Long,
    val kind: ArchiveKind,
    /** Subfolder path relative to the listing root, e.g. "GameImport". Empty for top-level files. */
    val relativePath: String = "",
)

class ArchiveScanner(private val listing: DownloadsListing) {

    data class ScanOutcome(
        /** ZIP + 7z archives, largest first (big disc images first). */
        val archives: List<ArchiveRef>,
        /** Loose game files (non-archive), largest first. */
        val looseFiles: List<ArchiveRef>,
        /** RAR files: reported, never attempted. */
        val unsupported: List<ArchiveRef>,
    )

    fun scan(): ScanOutcome {
        val files = try {
            listing.listFiles()
        } catch (_: SecurityException) {
            throw SecurityException("DOWNLOADS ACCESS LOST")
        }
        val archives = mutableListOf<ArchiveRef>()
        val looseFiles = mutableListOf<ArchiveRef>()
        val unsupported = mutableListOf<ArchiveRef>()
        for (file in files) {
            if (file.isDirectory) continue
            val kind = archiveKindOf(file.name)
            val ref = ArchiveRef(file.name, file.uri, file.size, kind, file.relativePath)
            when (kind) {
                ArchiveKind.ZIP, ArchiveKind.SEVEN_Z -> archives += ref
                ArchiveKind.RAR_UNSUPPORTED -> unsupported += ref
                ArchiveKind.UNKNOWN ->
                    if (file.name.substringAfterLast('.', "")
                            .lowercase() in DetectionTables.looseFileExtension
                    ) {
                        looseFiles += ref.copy(kind = ArchiveKind.LOOSE_FILE)
                    }
                ArchiveKind.LOOSE_FILE -> {
                    // Never produced by archiveKindOf(); unreachable.
                }
            }
        }
        archives.sortByDescending { it.size }
        looseFiles.sortByDescending { it.size }
        return ScanOutcome(archives, looseFiles, unsupported)
    }
}
