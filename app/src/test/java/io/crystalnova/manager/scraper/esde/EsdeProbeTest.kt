package io.crystalnova.manager.scraper.esde

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the SD media probe's deterministic pick, the
 * probe JSON the theme parses, and the diagnostic text the user sends
 * back. The SAF listing itself runs on device; [EsdeProbe.chooseTestImage]
 * takes plain lists so the decision logic is fully testable here.
 */
class EsdeProbeTest {

    private val root = "/storage/1234-ABCD/Crystal/imports/esde"

    @Test
    fun `picks first sorted system and first sorted cover image`() {
        val pick = EsdeProbe.chooseTestImage(
            sdMediaRoot = root,
            systemDirs = listOf("ps2", "gba", "n64"),
            coverFiles = { system ->
                when (system) {
                    "gba" -> listOf("z.png", "a.png")
                    else -> listOf("only.png")
                }
            },
            createdSeconds = 1_700_000_000L,
        )!!
        assertEquals("gba", pick.system)
        assertEquals("$root/media/gba/covers/a.png", pick.testAsset)
        assertEquals("file://$root/media/gba/covers/a.png", pick.themeUrl)
        assertEquals(root, pick.sdMediaRoot)
        assertEquals(1_700_000_000L, pick.createdSeconds)
    }

    @Test
    fun `ignores non-image files and accepts uppercase extensions`() {
        val pick = EsdeProbe.chooseTestImage(
            sdMediaRoot = root,
            systemDirs = listOf("psx"),
            coverFiles = { listOf("notes.txt", "B.JPG", "a.video.mp4") },
        )!!
        assertEquals("$root/media/psx/covers/B.JPG", pick.testAsset)
    }

    @Test
    fun `null when no system directories`() {
        assertNull(
            EsdeProbe.chooseTestImage(root, emptyList(), { listOf("a.png") }),
        )
    }

    @Test
    fun `null when covers has no images`() {
        assertNull(
            EsdeProbe.chooseTestImage(
                root,
                listOf("ps2"),
                { listOf("readme.txt", "thumb.db") },
            ),
        )
    }

    @Test
    fun `skips systems whose covers are missing or empty`() {
        val pick = EsdeProbe.chooseTestImage(
            sdMediaRoot = root,
            systemDirs = listOf("ps2", "gba"),
            coverFiles = { system ->
                when (system) {
                    "gba" -> emptyList() // first alphabetically, but no covers
                    else -> listOf("x.png")
                }
            },
        )!!
        assertEquals("ps2", pick.system)
        assertEquals("$root/media/ps2/covers/x.png", pick.testAsset)
    }

    @Test
    fun `null when root is blank`() {
        assertNull(
            EsdeProbe.chooseTestImage("  ", listOf("ps2"), { listOf("a.png") }),
        )
    }

    @Test
    fun `probeJson is versioned and theme-parseable`() {
        val pick = EsdeProbe.Pick(
            sdMediaRoot = root,
            system = "ps2",
            testAsset = "$root/media/ps2/covers/game.png",
            themeUrl = "file://$root/media/ps2/covers/game.png",
            createdSeconds = 42L,
        )
        val doc = JSONObject(EsdeProbe.probeJson(pick))
        assertEquals(1, doc.getInt("version"))
        assertEquals(root, doc.getString("sdMediaRoot"))
        assertEquals("ps2", doc.getString("system"))
        assertEquals("$root/media/ps2/covers/game.png", doc.getString("testAsset"))
        assertEquals("file://$root/media/ps2/covers/game.png", doc.getString("themeUrl"))
        assertEquals(42L, doc.getLong("created"))
    }

    @Test
    fun `diagnosticText carries the four required lines`() {
        val pick = EsdeProbe.Pick(
            sdMediaRoot = root,
            system = "ps2",
            testAsset = "$root/media/ps2/covers/game.png",
            themeUrl = "file://$root/media/ps2/covers/game.png",
            createdSeconds = 42L,
        )
        val text = EsdeProbe.diagnosticText(pick)
        assertTrue(text.contains("External SD media root:"))
        assertTrue(text.contains(root))
        assertTrue(text.contains("Test asset:"))
        assertTrue(text.contains("$root/media/ps2/covers/game.png"))
        assertTrue(text.contains("Theme URL:"))
        assertTrue(text.contains("file://$root/media/ps2/covers/game.png"))
        assertTrue(text.contains("Result:"))
        assertTrue(text.contains("pending device validation"))
    }

    @Test
    fun `trailing slash on the root is trimmed`() {
        val pick = EsdeProbe.chooseTestImage(
            sdMediaRoot = "$root/",
            systemDirs = listOf("gba"),
            coverFiles = { listOf("a.png") },
        )!!
        assertEquals("$root/media/gba/covers/a.png", pick.testAsset)
    }

    @Test
    fun `encodePath leaves unreserved characters alone`() {
        assertEquals(
            "/storage/1234-ABCD/Crystal/imports/esde/media/ps2/covers/a-b_c~d.png",
            EsdeProbe.encodePath("/storage/1234-ABCD/Crystal/imports/esde/media/ps2/covers/a-b_c~d.png"),
        )
    }

    @Test
    fun `encodePath percent-encodes spaces and parentheses`() {
        assertEquals(
            "/storage/1234-ABCD/x/Sonic%20the%20Hedgehog%20%28USA%29.png",
            EsdeProbe.encodePath("/storage/1234-ABCD/x/Sonic the Hedgehog (USA).png"),
        )
    }

    @Test
    fun `encodePath encodes non-ASCII as UTF-8 bytes`() {
        assertEquals("/x/%C3%A9.png", EsdeProbe.encodePath("/x/é.png"))
    }

    @Test
    fun `themeUrl is the encoded file URL while testAsset stays raw`() {
        val pick = EsdeProbe.chooseTestImage(
            sdMediaRoot = root,
            systemDirs = listOf("ps2"),
            coverFiles = { listOf("Sonic the Hedgehog (USA).png") },
        )!!
        assertEquals(
            "$root/media/ps2/covers/Sonic the Hedgehog (USA).png",
            pick.testAsset,
        )
        assertEquals(
            "file://$root/media/ps2/covers/Sonic%20the%20Hedgehog%20%28USA%29.png",
            pick.themeUrl,
        )
    }
}
