package io.crystalnova.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.crystalnova.manager.scraper.esde.MediaHealthCheck

/**
 * Media Health Check report (u34): shows exactly why games render
 * blank in Pegasus, per the theme's own resolution chain.
 *
 * Read-only diagnostic — nothing on this screen writes anything.
 */
@Composable
fun MediaHealthReportScreen(
    report: MediaHealthCheck.HealthReport?,
    running: Boolean,
    status: String?,
    onRunAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        routeKey = "settings-esde-health-report",
        title = "MEDIA HEALTH CHECK",
        onBack = onBack,
        modifier = modifier,
        fallbackFocusKey = "esde-health-report-rerun",
    ) {
        ControllerList(
            state = listState,
            dispatcher = dispatcher,
            initialFocus = ::isInitialFocus,
        ) {
            if (running) {
                section {
                    StatusLine("CHECKING… ${status ?: ""}")
                    DimLine("READ-ONLY — NOTHING IS WRITTEN.")
                }
                return@ControllerList
            }
            if (report == null) {
                section {
                    DimLine("NO REPORT YET — RUN THE CHECK FROM ES-DE MEDIA IMPORT.")
                }
                return@ControllerList
            }

            val problems = report.problemFindings()
            val okCount = report.gamesChecked - problems.size
            section {
                StatusLine(
                    "METADATA FILES: ${report.metafilesScanned}\n" +
                        "GAMES CHECKED: ${report.gamesChecked}\n" +
                        "OK: $okCount\n" +
                        "BLANK IN PEGASUS: ${problems.size}",
                )
            }

            section {
                DimLine("PER CONSOLE (AS THE THEME SEES THEM)")
                for (s in report.platformSummaries()) {
                    StatusLine(
                        s.line(),
                        color = if (s.blank > 0) Crystal.Bad else Crystal.Ink,
                    )
                }
            }

            if (report.errors.isNotEmpty()) {
                section {
                    StatusLine("ERRORS", color = Crystal.Bad)
                    for (e in report.errors) {
                        StatusLine("! $e", color = Crystal.Bad)
                    }
                }
            }

            if (report.duplicates.isNotEmpty()) {
                section {
                    StatusLine("DUPLICATE COLLECTIONS (${report.duplicates.size})", color = Crystal.Bad)
                    DimLine("SAME COLLECTION NAME IN 2+ METADATA FILES — PEGASUS SHOWS EACH ONE.")
                    for (d in report.duplicates) {
                        StatusLine("“${d.collection}” — ${d.metafiles.size} FILES:", color = Crystal.Bad)
                        for (p in d.metafiles) {
                            DimLine("  $p")
                        }
                    }
                }
            }

            if (report.shortnameMismatches.isNotEmpty()) {
                section {
                    StatusLine("BROKEN SHORTNAMES (${report.shortnameMismatches.size})", color = Crystal.Bad)
                    DimLine("THE MANAGER WRITES THESE, BUT THE THEME CANNOT MAP THEM BACK — EVERY GAME OF THE SYSTEM GOES BLANK.")
                    for (m in report.shortnameMismatches) {
                        StatusLine(
                            "${m.slug}: WRITES “${m.writtenShortname}”, THEME READS “${m.themeReadsAs.ifEmpty { "(NOTHING)" }}”",
                            color = Crystal.Bad,
                        )
                    }
                }
            }

            if (problems.isNotEmpty()) {
                section {
                    StatusLine("BLANK GAMES (${problems.size})", color = Crystal.Bad)
                    for (f in problems) {
                        StatusLine("“${f.title}” [${f.metafile}]")
                        DimLine("  ${f.describe()}")
                    }
                }
            } else if (report.gamesChecked > 0) {
                section {
                    StatusLine("EVERY CHECKED GAME RESOLVES — NOTHING BLANK.")
                }
            }

            if (report.indexDrift.isNotEmpty()) {
                section {
                    StatusLine("INDEX CLAIMS, DISK DISAGREES (${report.indexDrift.size})", color = Crystal.Bad)
                    DimLine("INDEX.JSON LISTS THESE SLOTS BUT THE PNGS ARE MISSING OR EMPTY.")
                    for (d in report.indexDrift) {
                        StatusLine("“${d.title}” [${d.key}]")
                        DimLine("  MISSING: ${d.missingSlots.joinToString(", ")}")
                    }
                }
            }

            control(
                key = "esde-health-report-rerun",
                testTag = "esde-health-report-rerun",
                label = "RUN CHECK AGAIN",
                onClick = onRunAgain,
            )
        }
    }
}

private fun MediaHealthCheck.PlatformSummary.line(): String {
    val parts = mutableListOf<String>()
    if (shortnameUnmapped > 0) parts.add("$shortnameUnmapped BAD SHORTNAME")
    if (noIndexEntry > 0) parts.add("$noIndexEntry NO INDEX")
    if (assetFileMissing > 0) parts.add("$assetFileMissing MISSING FILES")
    val detail = if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    return "$platform — $games GAMES: $ok OK, $blank BLANK$detail"
}

private fun MediaHealthCheck.GameFinding.describe(): String = when (health) {
    MediaHealthCheck.GameHealth.OK -> "OK"
    MediaHealthCheck.GameHealth.SHORTNAME_UNMAPPED ->
        "SHORTNAME “${shortname}” NOT RECOGNIZED BY THE THEME — WHOLE COLLECTION BLANK"
    MediaHealthCheck.GameHealth.NO_INDEX_ENTRY ->
        "NO INDEX ENTRY FOR KEY “${themeKey}”" +
            if (titleFallbackHit) " — TITLE WOULD MATCH, FILENAME DIFFERS FROM THE SCANNED ROM" else ""
    MediaHealthCheck.GameHealth.ASSET_FILE_MISSING ->
        "INDEX CLAIMS THESE BUT THE PNGS ARE MISSING OR EMPTY: ${missingSlots.joinToString(", ")}"
}
