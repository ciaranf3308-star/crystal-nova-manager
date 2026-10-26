package io.crystalnova.manager.importer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import io.crystalnova.manager.storage.ThemeFs
import java.io.File
import kotlinx.coroutines.delay

/**
 * Health dashboard collection (u88) — Android-dependent, intentionally
 * separated from [HealthStats] so the pure helpers stay unit-testable
 * on the plain JVM. Callers run this on Dispatchers.IO; the function
 * itself never touches the main thread except for the /proc/stat
 * sample delay, which is a suspend delay, not a block.
 *
 * Honesty contract: every field is measured or left null/unknown.
 * The SD figure is labeled by what it actually is (see
 * [resolveSdVolume]); a guessed volume is never presented as the
 * SD card.
 */
suspend fun loadDashboardData(
    context: Context,
    romsFs: ThemeFs?,
    downloadsPending: Int,
    failedImports: Int,
    themeOutOfDate: Boolean,
    themeCatalogReady: Boolean,
): DashboardData {
    // ---- ROM tree: grant state, library counts/size, gamelists ----
    var romGrant = if (romsFs == null) RomGrantState.NOT_GRANTED else RomGrantState.GRANTED
    var gamesTotal: Int? = null
    var systemsCount = 0
    var libraryBytes: Long? = null
    var playAgg = GamelistPlayAggregate(0, 0L, 0, null, null)
    if (romsFs != null) {
        val root = try {
            romsFs.root()
        } catch (_: SecurityException) {
            romGrant = RomGrantState.LOST
            null
        } catch (_: Exception) {
            romGrant = RomGrantState.LOST
            null
        }
        if (root != null) {
            val scan = try {
                scanGameLibrary(romsFs)
            } catch (_: Exception) {
                null
            }
            if (scan != null) {
                gamesTotal = scan.totalFiles
            }
            // Systems = real top-level folders of the ROM root
            // (loose root files are not a system). Counted directly
            // rather than from the scan, because u80 keys the scan's
            // root bucket by the root folder's real name — it cannot
            // be told apart from a system folder by name alone.
            systemsCount = try {
                romsFs.children(root).count { (name, node) ->
                    !name.startsWith('.') &&
                        runCatching { romsFs.isDirectory(node) }.getOrDefault(false)
                }
            } catch (_: Exception) {
                0
            }
            libraryBytes = try {
                measureLibrarySize(romsFs, root)
            } catch (_: Exception) {
                null
            }
            playAgg = readGamelistPlay(romsFs, root)
        } else if (romGrant == RomGrantState.GRANTED) {
            // root() returned null without throwing: nothing picked.
            romGrant = RomGrantState.NOT_GRANTED
        }
    }

    // ---- Device volumes ----
    val internalStat = try {
        StatFs(context.filesDir.absolutePath).let {
            it.availableBytes to it.totalBytes
        }
    } catch (_: Exception) {
        null to null
    }
    val sdVolume = resolveSdVolume(context)
    val sdStat = try {
        sdVolume?.let { StatFs(it.path).let { s -> s.availableBytes to s.totalBytes } }
    } catch (_: Exception) {
        null
    }
    val sdLabel = when {
        sdVolume != null -> "SD CARD"
        else -> "SHARED STORAGE"
    }
    val (sdFree, sdTotal) = if (sdVolume != null) {
        (sdStat?.first to sdStat?.second)
    } else {
        // Fallback: primary shared storage, labeled honestly.
        try {
            StatFs(Environment.getExternalStorageDirectory().absolutePath)
                .let { it.availableBytes to it.totalBytes }
        } catch (_: Exception) {
            null to null
        }
    }

    // ---- RAM ----
    val memInfo = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    } catch (_: Exception) {
        null
    }

    // ---- Chip ----
    val chipModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL ?: Build.HARDWARE ?: "—"
    } else {
        Build.HARDWARE ?: "—"
    }
    val chipCores = Runtime.getRuntime().availableProcessors()
    val chipLoad = sampleCpuLoadPct()

    // ---- Battery ----
    val batteryPct = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
    } catch (_: Exception) {
        null
    }
    val batteryTemp = try {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it >= 0 }?.let { it / 10f }
    } catch (_: Exception) {
        null
    }

    val attention = deriveAttentionReasons(
        AttentionInput(
            internalFree = internalStat.first,
            internalTotal = internalStat.second,
            sdFree = sdFree,
            sdTotal = sdTotal,
            sdMeasured = sdVolume != null,
            romGrant = romGrant,
            themeOutOfDate = themeOutOfDate,
            failedImports = failedImports,
        ),
    )

    return DashboardData(
        gamesTotal = gamesTotal,
        systemsCount = systemsCount,
        libraryBytes = libraryBytes,
        internalFree = internalStat.first,
        internalTotal = internalStat.second,
        sd = VolumeStat(sdLabel, sdFree, sdTotal),
        ramFree = memInfo?.availMem,
        ramTotal = memInfo?.totalMem?.takeIf { it > 0 },
        chipModel = chipModel,
        chipCores = chipCores,
        chipLoadPct = chipLoad,
        batteryPct = batteryPct,
        batteryTempC = batteryTemp,
        downloadsPending = downloadsPending,
        failedImports = failedImports,
        romGrant = romGrant,
        themeOutOfDate = themeOutOfDate,
        themeCatalogReady = themeCatalogReady,
        playSummary = summarizePlay(playAgg),
        attentionReasons = attention,
    )
}

/**
 * Reads every top-level system folder's gamelist.xml through the SAF
 * ROM filesystem and merges their play totals. Unreadable or
 * malformed files are skipped, never fatal.
 */
private fun readGamelistPlay(fs: ThemeFs, root: io.crystalnova.manager.storage.FsNode): GamelistPlayAggregate {
    var totalCount = 0
    var totalTime = 0L
    var gamesWithPlay = 0
    var earliest: Long? = null
    var latest: Long? = null
    val top = try {
        fs.children(root)
    } catch (_: Exception) {
        return GamelistPlayAggregate(0, 0L, 0, null, null)
    }
    for ((name, child) in top) {
        if (name.startsWith('.')) continue
        val isDir = try {
            fs.isDirectory(child)
        } catch (_: Exception) {
            false
        }
        if (!isDir) continue
        val gamelist = try {
            fs.find(child, "gamelist.xml")
        } catch (_: Exception) {
            null
        } ?: continue
        val text = try {
            fs.openInput(gamelist).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            continue
        }
        val agg = parseGamelistPlay(text) ?: continue
        totalCount += agg.totalPlaycount
        totalTime += agg.totalPlaytimeSeconds
        gamesWithPlay += agg.gamesWithPlay
        if (agg.earliestDay != null) {
            earliest = if (earliest == null) agg.earliestDay else minOf(earliest, agg.earliestDay)
        }
        if (agg.latestDay != null) {
            latest = if (latest == null) agg.latestDay else maxOf(latest, agg.latestDay)
        }
    }
    return GamelistPlayAggregate(totalCount, totalTime, gamesWithPlay, earliest, latest)
}

/**
 * Resolves the removable (SD) volume's app files dir, if any.
 * `getExternalFilesDirs` returns one entry per mounted volume;
 * the primary (emulated) entry is skipped, and the first remaining
 * volume — path segment after /storage/ that is not "emulated" or
 * "self" — is the SD card. Null when there is no removable volume.
 */
private fun resolveSdVolume(context: Context): File? {
    val dirs = try {
        context.getExternalFilesDirs(null)
    } catch (_: Exception) {
        return null
    } ?: return null
    for (dir in dirs) {
        if (dir == null) continue
        val volume = dir.absolutePath.split("/").getOrNull(2) ?: continue
        if (volume != "emulated" && volume != "self") return dir
    }
    return null
}

/**
 * CPU load percent from /proc/stat, sampled twice ~300 ms apart.
 * Null when /proc/stat cannot be read. This is load, never a
 * "choked" judgment — the tile labels it as load.
 */
private suspend fun sampleCpuLoadPct(): Int? {
    fun readCpu(): Pair<Long, Long>? {
        return try {
            val line = File("/proc/stat").bufferedReader().useLines { seq ->
                seq.firstOrNull { it.startsWith("cpu ") }
            } ?: return null
            val parts = line.trim().split(Regex("\\s+")).drop(1)
                .mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) return null
            val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
            val total = parts.sum()
            total to idle
        } catch (_: Exception) {
            null
        }
    }
    val first = readCpu() ?: return null
    delay(300)
    val second = readCpu() ?: return null
    val totalDelta = second.first - first.first
    val idleDelta = second.second - first.second
    if (totalDelta <= 0) return null
    return ((1.0 - idleDelta.toDouble() / totalDelta.toDouble()) * 100)
        .toInt().coerceIn(0, 100)
}
