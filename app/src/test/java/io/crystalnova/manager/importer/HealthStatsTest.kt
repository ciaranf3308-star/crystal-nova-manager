package io.crystalnova.manager.importer

import io.crystalnova.manager.storage.InMemoryThemeFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health dashboard helpers (u88): pure-JVM tests. No Android classes
 * are touched here, so these run on the plain JVM runner.
 */
class HealthStatsTest {

    // ---- formatStatBytes ----

    @Test fun `formatStatBytes covers B KB MB GB TB`() {
        assertEquals("0 B", formatStatBytes(0))
        assertEquals("512 B", formatStatBytes(512))
        assertEquals("1.5 KB", formatStatBytes(1536))
        assertEquals("1.0 MB", formatStatBytes(1024 * 1024))
        assertEquals("2.5 GB", formatStatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("2.0 TB", formatStatBytes(2L * 1024 * 1024 * 1024 * 1024))
        assertEquals("—", formatStatBytes(-1))
    }

    // ---- percentFree ----

    @Test fun `percentFree guards unknown totals`() {
        assertEquals(25, percentFree(25, 100))
        assertEquals(100, percentFree(200, 100))
        assertNull(percentFree(null, 100))
        assertNull(percentFree(10, null))
        assertNull(percentFree(10, 0))
    }

    // ---- formatPlayDuration ----

    @Test fun `formatPlayDuration renders H M S`() {
        assertEquals("0S", formatPlayDuration(0))
        assertEquals("45S", formatPlayDuration(45))
        assertEquals("1M", formatPlayDuration(60))
        assertEquals("2H 14M", formatPlayDuration(2 * 3600 + 14 * 60))
    }

    // ---- deriveAttentionReasons ----

    private fun okInput() = AttentionInput(
        internalFree = 50L * 1024 * 1024 * 1024,
        internalTotal = 100L * 1024 * 1024 * 1024,
        sdFree = 50L * 1024 * 1024 * 1024,
        sdTotal = 100L * 1024 * 1024 * 1024,
        sdMeasured = true,
        romGrant = RomGrantState.GRANTED,
        themeOutOfDate = false,
        failedImports = 0,
    )

    @Test fun `attention is empty when everything is healthy`() {
        assertTrue(deriveAttentionReasons(okInput()).isEmpty())
    }

    @Test fun `attention fires on low internal percent`() {
        val reasons = deriveAttentionReasons(
            okInput().copy(
                internalFree = 5L * 1024 * 1024 * 1024,
                internalTotal = 100L * 1024 * 1024 * 1024,
            ),
        )
        assertEquals(listOf("INTERNAL STORAGE LOW"), reasons)
    }

    @Test fun `attention fires on low internal bytes even above 10 percent`() {
        // 1.5 GiB free of 10 GiB = 15% — the 2 GiB floor still fires.
        val reasons = deriveAttentionReasons(
            okInput().copy(
                internalFree = (1.5 * 1024 * 1024 * 1024).toLong(),
                internalTotal = 10L * 1024 * 1024 * 1024,
            ),
        )
        assertEquals(listOf("INTERNAL STORAGE LOW"), reasons)
    }

    @Test fun `attention ignores an unmeasured SD volume`() {
        // Downloads pending is deliberately not an input at all, and
        // an SD figure that is only a fallback never triggers SD LOW.
        val reasons = deriveAttentionReasons(
            okInput().copy(
                sdFree = 100L * 1024 * 1024,
                sdTotal = 100L * 1024 * 1024 * 1024,
                sdMeasured = false,
            ),
        )
        assertTrue(reasons.isEmpty())
    }

    @Test fun `attention fires on measured SD low`() {
        val reasons = deriveAttentionReasons(
            okInput().copy(
                sdFree = 100L * 1024 * 1024,
                sdTotal = 100L * 1024 * 1024 * 1024,
            ),
        )
        assertEquals(listOf("SD CARD LOW"), reasons)
    }

    @Test fun `attention names grant loss and theme and failures`() {
        val reasons = deriveAttentionReasons(
            okInput().copy(
                romGrant = RomGrantState.LOST,
                themeOutOfDate = true,
                failedImports = 2,
            ),
        )
        assertEquals(
            listOf(
                "ROM FOLDER ACCESS LOST",
                "THEME UPDATE AVAILABLE",
                "2 FAILED IMPORTS",
            ),
            reasons,
        )
    }

    @Test fun `attention names a never-granted ROM folder`() {
        val reasons = deriveAttentionReasons(
            okInput().copy(romGrant = RomGrantState.NOT_GRANTED),
        )
        assertEquals(listOf("ROM FOLDER NOT GRANTED"), reasons)
    }

    // ---- gamelist parsing ----

    private val twoGameXml = """
        <?xml version="1.0"?>
        <gameList>
          <game>
            <path>./game1.chd</path>
            <name>Game One</name>
            <playcount>5</playcount>
            <lastplayed>20240110T203000</lastplayed>
            <playtime>7200</playtime>
          </game>
          <game>
            <path>./game2.chd</path>
            <name>Game Two</name>
            <playcount>3</playcount>
            <lastplayed>20240120T120000</lastplayed>
            <playtime>3600</playtime>
          </game>
        </gameList>
    """.trimIndent()

    @Test fun `gamelist totals playcount playtime and date window`() {
        val agg = parseGamelistPlay(twoGameXml)!!
        assertEquals(8, agg.totalPlaycount)
        assertEquals(10800L, agg.totalPlaytimeSeconds)
        assertEquals(2, agg.gamesWithPlay)
        // Jan 10 -> Jan 20 inclusive = 11 days.
        assertEquals(PlaySummary.DailyTime(10800L / 11, 11), summarizePlay(agg))
    }

    @Test fun `gamelist with counts and dates but no playtime gives sessions per day`() {
        val xml = """
            <gameList>
              <game><playcount>11</playcount><lastplayed>20240201T100000</lastplayed></game>
              <game><playcount>11</playcount><lastplayed>20240211T100000</lastplayed></game>
            </gameList>
        """.trimIndent()
        val agg = parseGamelistPlay(xml)!!
        val summary = summarizePlay(agg) as PlaySummary.DailySessions
        assertEquals(11L, summary.windowDays)
        assertEquals(2.0, summary.avgPerDay, 0.001)
    }

    @Test fun `gamelist with playtime but no dates gives total time only`() {
        val xml = "<gameList><game><playtime>5400</playtime></game></gameList>"
        val agg = parseGamelistPlay(xml)!!
        assertEquals(PlaySummary.TotalTime(5400L), summarizePlay(agg))
    }

    @Test fun `gamelist with no play tags gives no data`() {
        val xml = "<gameList><game><name>Plain</name></game></gameList>"
        val agg = parseGamelistPlay(xml)!!
        assertEquals(0, agg.totalPlaycount)
        assertEquals(0L, agg.totalPlaytimeSeconds)
        assertEquals(PlaySummary.NoData, summarizePlay(agg))
    }

    @Test fun `malformed gamelist is tolerated as null`() {
        assertNull(parseGamelistPlay("<gameList><game><playcount>3"))
        assertNull(parseGamelistPlay("not xml at all"))
    }

    @Test fun `lastplayed accepts ES-DE ISO and date-only formats`() {
        // ES-DE: 20240110T203000.
        assertEquals(
            java.time.LocalDate.of(2024, 1, 10).toEpochDay(),
            parseEsdeDay("20240110T203000"),
        )
        assertEquals(
            java.time.LocalDate.of(2024, 2, 3).toEpochDay(),
            parseEsdeDay("2024-02-03T04:05:06"),
        )
        assertEquals(
            java.time.LocalDate.of(2024, 2, 3).toEpochDay(),
            parseEsdeDay("2024-02-03"),
        )
        assertNull(parseEsdeDay("garbage"))
        assertNull(parseEsdeDay(""))
        assertNull(parseEsdeDay("0"))
    }

    // ---- measureLibrarySize ----

    private fun writeBytes(fs: InMemoryThemeFs, node: io.crystalnova.manager.storage.FsNode, size: Int) {
        fs.openOutput(node).use { it.write(ByteArray(size)) }
    }

    @Test fun `library size mirrors the export walk`() {
        val fs = InMemoryThemeFs()
        val psx = fs.mkdir(fs.rootNode, "psx")
        writeBytes(fs, fs.createFile(psx, "game.chd"), 1000)
        val m3u = fs.mkdir(psx, "Metal Gear Solid.m3u")
        writeBytes(fs, fs.createFile(m3u, "disc1.chd"), 2000)
        val nested = fs.mkdir(psx, "sub")
        writeBytes(fs, fs.createFile(nested, "extra.bin"), 500)
        // Hidden entries are skipped, like the export.
        writeBytes(fs, fs.createFile(fs.rootNode, ".nomedia"), 9999)
        val hidden = fs.mkdir(fs.rootNode, ".hidden")
        writeBytes(fs, fs.createFile(hidden, "secret.bin"), 9999)
        writeBytes(fs, fs.createFile(fs.rootNode, "readme.txt"), 100)
        assertEquals(3600L, measureLibrarySize(fs, fs.rootNode))
    }
}
