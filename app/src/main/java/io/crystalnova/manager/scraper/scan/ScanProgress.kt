package io.crystalnova.manager.scraper.scan

import java.util.Locale

/**
 * Truthful library-scan progress. No percentages: the total is the
 * cheap pre-count of top-level system folders, and everything else is
 * a plain counter. Pure Kotlin so the display line is unit-testable.
 */
data class ScanProgress(
    /** Folder currently being walked, e.g. "gba". Empty before the first folder. */
    val currentFolder: String,
    val systemsDone: Int,
    val systemsTotal: Int,
    val gamesFound: Int,
) {
    /**
     * One honest status line, e.g.
     * `SCANNING GBA · 7/18 SYSTEMS · 1,284 GAMES FOUND`.
     */
    fun displayLine(): String =
        "SCANNING ${currentFolder.uppercase()} · $systemsDone/$systemsTotal SYSTEMS · " +
            String.format(Locale.US, "%,d GAMES FOUND", gamesFound)
}
