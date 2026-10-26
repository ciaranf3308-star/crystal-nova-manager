package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.FsNode
import io.crystalnova.manager.storage.ThemeFs
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Health dashboard helpers (u88) — pure JVM, unit-tested without Android.
 *
 * The dashboard shows health at a glance; every number here is either
 * measured or explicitly unavailable. Nothing is invented: a tile that
 * cannot be measured renders its honest fallback, never a fabricated
 * number. Android-dependent collection (StatFs, ActivityManager,
 * BatteryManager, /proc/stat) lives in [HealthDashboardLoad] and is
 * intentionally NOT in this file, so these helpers stay testable on
 * the plain JVM.
 */

/** ROM-root grant state, as observed by the dashboard loader. */
enum class RomGrantState {
    /** A readable ROM root was resolved. */
    GRANTED,

    /** No ROM root was ever picked. */
    NOT_GRANTED,

    /** A root was picked but can no longer be read (grant lost). */
    LOST,
}

/** Free/total snapshot for one storage volume. Nulls mean unmeasured. */
data class VolumeStat(
    /** Honest label for the figure: "SD CARD", "SHARED STORAGE", etc. */
    val label: String,
    val freeBytes: Long?,
    val totalBytes: Long?,
)

/** Everything the health dashboard renders. Nulls = honestly unavailable. */
data class DashboardData(
    /** Total game files from [scanGameLibrary]; null when unscannable. */
    val gamesTotal: Int?,
    /** Console folders from [scanGameLibrary]; 0 when unscannable. */
    val systemsCount: Int,
    /** Sum of [ThemeFs.length] over the scanned tree; null when unmeasured. */
    val libraryBytes: Long?,
    val internalFree: Long?,
    val internalTotal: Long?,
    val sd: VolumeStat,
    val ramFree: Long?,
    val ramTotal: Long?,
    /** e.g. "Snapdragon 8 Gen 2" or the hardware fallback; "—" when unknown. */
    val chipModel: String,
    val chipCores: Int,
    /** Sampled /proc/stat load percent; null when unsampled. */
    val chipLoadPct: Int?,
    /** Battery percent 0–100; null when unknown. */
    val batteryPct: Int?,
    /** Battery temperature in °C; null when unknown. */
    val batteryTempC: Float?,
    /** Live queue items waiting (not complete/skipped/claimed). */
    val downloadsPending: Int,
    /** Live queue items in FAILED stage. */
    val failedImports: Int,
    val romGrant: RomGrantState,
    /** Theme catalog state for the theme tile. */
    val themeOutOfDate: Boolean,
    val themeCatalogReady: Boolean,
    val playSummary: PlaySummary,
    /** Empty = ALL OK. Each entry is one attention reason line. */
    val attentionReasons: List<String>,
)

/** Human-readable byte count: exact bytes below 1 KB, else 1 decimal. */
fun formatStatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes < 1024L * 1024 * 1024 * 1024 ->
            "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        else -> "%.1f TB".format(bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0))
    }
}

/** Free-space percent, or null when the total is unknown/zero. */
fun percentFree(freeBytes: Long?, totalBytes: Long?): Int? {
    if (freeBytes == null || totalBytes == null || totalBytes <= 0) return null
    return ((freeBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/** Duration from seconds: "2H 14M", "45M", or "30S". */
fun formatPlayDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "0S"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}H ${minutes}M"
        minutes > 0 -> "${minutes}M"
        else -> "${seconds}S"
    }
}

/**
 * Inputs for the overall-status derivation. Downloads pending is
 * DELIBERATELY not an input: pending downloads are information, never
 * an attention trigger on their own.
 */
data class AttentionInput(
    val internalFree: Long?,
    val internalTotal: Long?,
    val sdFree: Long?,
    val sdTotal: Long?,
    /** False when the SD figure is a fallback/shared volume. */
    val sdMeasured: Boolean,
    val romGrant: RomGrantState,
    val themeOutOfDate: Boolean,
    val failedImports: Int,
)

/**
 * Overall-status reasons, in display order. Empty = ALL OK.
 *
 * Thresholds (documented contract, do not change silently):
 * - free space below 10% OR below 2 GiB on either measured volume,
 * - ROM-root grant missing (never picked) or lost (unreadable),
 * - theme catalog says an update is available,
 * - one or more failed imports.
 */
fun deriveAttentionReasons(input: AttentionInput): List<String> {
    val reasons = mutableListOf<String>()
    val lowBytes = 2L * 1024 * 1024 * 1024
    val internalPct = percentFree(input.internalFree, input.internalTotal)
    if ((internalPct != null && internalPct < 10) ||
        (input.internalFree != null && input.internalFree < lowBytes)
    ) {
        reasons += "INTERNAL STORAGE LOW"
    }
    if (input.sdMeasured) {
        val sdPct = percentFree(input.sdFree, input.sdTotal)
        if ((sdPct != null && sdPct < 10) ||
            (input.sdFree != null && input.sdFree < lowBytes)
        ) {
            reasons += "SD CARD LOW"
        }
    }
    when (input.romGrant) {
        RomGrantState.NOT_GRANTED -> reasons += "ROM FOLDER NOT GRANTED"
        RomGrantState.LOST -> reasons += "ROM FOLDER ACCESS LOST"
        RomGrantState.GRANTED -> { /* fine */ }
    }
    if (input.themeOutOfDate) reasons += "THEME UPDATE AVAILABLE"
    if (input.failedImports > 0) reasons += "${input.failedImports} FAILED IMPORTS"
    return reasons
}

/** Totals merged across every readable gamelist.xml. */
data class GamelistPlayAggregate(
    val totalPlaycount: Int,
    /** Sum of <playtime> values (seconds, ES-DE convention). */
    val totalPlaytimeSeconds: Long,
    val gamesWithPlay: Int,
    /** Epoch day of the earliest <lastplayed>, or null when none parsed. */
    val earliestDay: Long?,
    /** Epoch day of the latest <lastplayed>, or null when none parsed. */
    val latestDay: Long?,
)

/** What the AVG DAILY PLAY tile shows. */
sealed interface PlaySummary {
    /** Average play time per day over the recorded window. */
    data class DailyTime(val avgSecondsPerDay: Long, val windowDays: Long) : PlaySummary

    /** Average sessions per day over the recorded window. */
    data class DailySessions(val avgPerDay: Double, val windowDays: Long) : PlaySummary

    /** Total play time exists but no usable dates, so no daily average. */
    data class TotalTime(val totalSeconds: Long) : PlaySummary

    /** No play data anywhere. */
    data object NoData : PlaySummary
}

/**
 * Parses one gamelist.xml document, reading ONLY <playcount>,
 * <lastplayed> and <playtime> per <game> entry. Returns null when the
 * document is malformed (the caller skips that file); a well-formed
 * document with no play tags yields a zero aggregate, not null.
 *
 * Unit assumption (documented, ES-DE v3.4.0+ play time tracking):
 * <playtime> values are seconds. <lastplayed> is ES-DE's
 * "yyyyMMdd'T'HHmmss" timestamp; ISO date-times and plain dates are
 * also accepted, anything else is ignored for that entry.
 */
fun parseGamelistPlay(xmlText: String): GamelistPlayAggregate? {
    return try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences = false
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder()
            .parse(xmlText.byteInputStream(Charsets.UTF_8))
        val games = doc.getElementsByTagName("game")
        var totalCount = 0
        var totalTime = 0L
        var gamesWithPlay = 0
        var earliest: Long? = null
        var latest: Long? = null
        for (i in 0 until games.length) {
            val el = games.item(i) as? Element ?: continue
            val playcount = el.childTagText("playcount")?.toIntOrNull()
            val playtime = el.childTagText("playtime")?.toLongOrNull()
            val day = el.childTagText("lastplayed")?.let { parseEsdeDay(it) }
            if (playcount != null) totalCount += playcount
            if (playtime != null && playtime > 0) totalTime += playtime
            if (playcount != null || playtime != null) gamesWithPlay++
            if (day != null) {
                earliest = if (earliest == null) day else minOf(earliest, day)
                latest = if (latest == null) day else maxOf(latest, day)
            }
        }
        GamelistPlayAggregate(totalCount, totalTime, gamesWithPlay, earliest, latest)
    } catch (_: Exception) {
        null
    }
}

private fun Element.childTagText(tag: String): String? {
    val node = getElementsByTagName(tag).item(0) ?: return null
    return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

/** ES-DE lastplayed → epoch day, or null when unparseable. */
fun parseEsdeDay(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val esdeFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    return try {
        LocalDateTime.parse(t, esdeFormat).toLocalDate().toEpochDay()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toLocalDate().toEpochDay()
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(t, DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * Turns merged gamelist totals into the tile's honest summary.
 * The window is inclusive (latest − earliest + 1 days, minimum 1).
 */
fun summarizePlay(agg: GamelistPlayAggregate): PlaySummary {
    val windowDays = if (agg.earliestDay != null && agg.latestDay != null) {
        (agg.latestDay - agg.earliestDay + 1).coerceAtLeast(1)
    } else {
        null
    }
    if (agg.totalPlaytimeSeconds > 0 && windowDays != null) {
        return PlaySummary.DailyTime(agg.totalPlaytimeSeconds / windowDays, windowDays)
    }
    if (agg.totalPlaycount > 0 && windowDays != null) {
        return PlaySummary.DailySessions(agg.totalPlaycount.toDouble() / windowDays, windowDays)
    }
    if (agg.totalPlaytimeSeconds > 0) {
        return PlaySummary.TotalTime(agg.totalPlaytimeSeconds)
    }
    return PlaySummary.NoData
}

/**
 * Sums [ThemeFs.length] over the same tree [scanGameLibrary] counts:
 * hidden entries (name starts with '.') skipped, every directory
 * descended (including `.m3u` directories, per the u80 verbatim rule).
 * Best-effort: unreadable nodes are skipped, never fatal.
 */
fun measureLibrarySize(fs: ThemeFs, root: FsNode): Long {
    var total = 0L
    val stack = ArrayDeque<FsNode>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val children = try {
            fs.children(dir)
        } catch (_: Exception) {
            continue
        }
        for ((name, child) in children) {
            if (name.startsWith('.')) continue
            try {
                if (fs.isDirectory(child)) {
                    stack.addLast(child)
                } else {
                    total += fs.length(child)
                }
            } catch (_: Exception) {
                // Best-effort: skip unreadable nodes.
            }
        }
    }
    return total
}
