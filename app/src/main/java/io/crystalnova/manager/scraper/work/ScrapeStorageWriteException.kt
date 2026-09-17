package io.crystalnova.manager.scraper.work

/**
 * A scraper manifest or index.json write failed even though the SAF
 * grant was still valid (a revoked grant surfaces as SecurityException,
 * never as this). v22: a failed write is NOT success — callers must
 * abort the run and surface [message] to the user, never report
 * FINISHED and never count the game as processed.
 */
class ScrapeStorageWriteException(
    message: String,
    /** Platform slug of the game being written, when the failure is per-game. */
    val platform: String? = null,
    /** Game id being written, when the failure is per-game. */
    val gameId: String? = null,
    /** Pipeline stage: "MANIFEST" or "INDEX". */
    val stage: String? = null,
) : Exception(message) {
    companion object {
        const val MANIFEST_WRITE_FAILED =
            "SCRAPE STORAGE WRITE FAILED — MEDIA LOCATION IS NOT WRITABLE"
        const val INDEX_WRITE_FAILED =
            "SCRAPE STORAGE WRITE FAILED — INDEX COULD NOT BE SAVED — MEDIA LOCATION IS NOT WRITABLE"
    }
}
