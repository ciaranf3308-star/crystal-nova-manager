package io.crystalnova.manager.scraper.esde

import io.crystalnova.manager.scraper.provider.PegasusMetadataReader
import org.junit.Assert.*
import org.junit.Test

class MediaHealthCheckTest {

    private fun entry(
        title: String = "Sonic",
        files: List<String> = listOf("/storage/roms/genesis/Sonic the Hedgehog (USA).md"),
        collection: String? = "Sega Genesis",
        shortname: String? = "genesis",
    ) = PegasusMetadataReader.Entry(
        title = title,
        files = files,
        developer = null,
        publisher = null,
        genre = null,
        players = null,
        description = null,
        release = null,
        collection = collection,
        shortname = shortname,
        launch = null,
    )

    private fun indexJson(vararg entries: String): String =
        """{"version":1,"games":{${entries.joinToString(",")}}}"""

    private fun idxEntry(
        key: String,
        platform: String,
        gameId: String,
        title: String,
        assets: List<String>,
    ) = """"$key":{"platform":"$platform","gameId":"$gameId","title":"$title","assets":[${assets.joinToString(",") { "\"$it\"" }}]}"""

    // -- platformSlug --------------------------------------------------------

    @Test fun `platformSlug passes known slugs through`() {
        assertEquals("ps2", MediaHealthCheck.platformSlug("ps2"))
        assertEquals("gamecube", MediaHealthCheck.platformSlug("gamecube"))
    }

    @Test fun `platformSlug resolves theme aliases`() {
        assertEquals("gamecube", MediaHealthCheck.platformSlug("gc"))
        assertEquals("genesis", MediaHealthCheck.platformSlug("md"))
        assertEquals("genesis", MediaHealthCheck.platformSlug("megadrive"))
        assertEquals("n3ds", MediaHealthCheck.platformSlug("3ds"))
        assertEquals("psx", MediaHealthCheck.platformSlug("ps1"))
        assertEquals("arcade", MediaHealthCheck.platformSlug("mame"))
    }

    @Test fun `platformSlug trims lowercases and rejects unknowns`() {
        assertEquals("gamecube", MediaHealthCheck.platformSlug(" GC "))
        assertEquals("", MediaHealthCheck.platformSlug("atarilynx"))
        assertEquals("", MediaHealthCheck.platformSlug(null))
        assertEquals("", MediaHealthCheck.platformSlug("  "))
    }

    // -- themeKey ------------------------------------------------------------

    @Test fun `themeKey mirrors filename-derived lookup`() {
        // "Sonic the Hedgehog (USA).md" -> slug "sonic-the-hedgehog"
        assertEquals(
            "genesis/sonic-the-hedgehog",
            MediaHealthCheck.themeKey("genesis", listOf("/a/b/Sonic the Hedgehog (USA).md"), "Sonic"),
        )
    }

    @Test fun `themeKey falls back to title when no file`() {
        assertEquals(
            "ps2/okami",
            MediaHealthCheck.themeKey("ps2", emptyList(), "Okami"),
        )
    }

    @Test fun `themeKey is empty when the shortname is unmapped`() {
        assertEquals("", MediaHealthCheck.themeKey("atarilynx", listOf("/x/y.bin"), "T"))
    }

    @Test fun `themeKey uses the first file for multi-file games`() {
        assertEquals(
            "psx/final-fantasy-vii",
            MediaHealthCheck.themeKey(
                "psx",
                listOf("/r/Final Fantasy VII (Disc 1).cue", "/r/Final Fantasy VII (Disc 2).cue"),
                "Final Fantasy VII",
            ),
        )
    }

    // -- parseIndexMaps ------------------------------------------------------

    @Test fun `parseIndexMaps builds byId and byTitle like the theme`() {
        val maps = MediaHealthCheck.parseIndexMaps(
            indexJson(
                idxEntry("genesis/sonic-the-hedgehog", "genesis", "sonic-the-hedgehog", "Sonic the Hedgehog", listOf("front", "logo")),
            ),
        )!!
        val e = maps.byId["genesis/sonic-the-hedgehog"]
        assertNotNull(e)
        assertEquals(setOf("front", "logo"), e!!.assets)
        // byTitle uses slugify(title): "Sonic the Hedgehog" -> "sonic-the-hedgehog"
        assertSame(e, maps.byTitle["genesis/sonic-the-hedgehog"])
    }

    @Test fun `parseIndexMaps rejects wrong version and bad entries`() {
        assertNull(MediaHealthCheck.parseIndexMaps("""{"version":2,"games":{}}"""))
        val maps = MediaHealthCheck.parseIndexMaps(
            indexJson(
                idxEntry("x/bad one", "ps2", "bad one!", "Bad", listOf("front")),
                idxEntry("ps2/good", "ps2", "good", "Good", listOf("front")),
            ),
        )!!
        assertEquals(1, maps.byId.size)
        assertNotNull(maps.byId["ps2/good"])
    }

    // -- classify ------------------------------------------------------------

    private val assetOk: (String, String, String) -> Long? = { _, _, _ -> 500_000L }

    @Test fun `classify OK when index hits and files exist`() {
        val maps = MediaHealthCheck.parseIndexMaps(
            indexJson(idxEntry("genesis/sonic-the-hedgehog", "genesis", "sonic-the-hedgehog", "Sonic the Hedgehog", listOf("front", "logo"))),
        )!!
        val f = MediaHealthCheck.classify("genesis/crystal-nova.metadata.pegasus.txt", entry(), maps.byId, maps.byTitle, assetOk)
        assertEquals(MediaHealthCheck.GameHealth.OK, f.health)
        assertEquals("genesis/sonic-the-hedgehog", f.themeKey)
        assertTrue(f.missingSlots.isEmpty())
    }

    @Test fun `classify SHORTNAME_UNMAPPED kills the whole collection`() {
        val f = MediaHealthCheck.classify(
            "lynx/crystal-nova.metadata.pegasus.txt",
            entry(shortname = "atarilynx", collection = "Atari Lynx"),
            emptyMap(), emptyMap(), assetOk,
        )
        assertEquals(MediaHealthCheck.GameHealth.SHORTNAME_UNMAPPED, f.health)
        assertEquals("", f.themeKey)
    }

    @Test fun `classify NO_INDEX_ENTRY notes title fallback`() {
        val maps = MediaHealthCheck.parseIndexMaps(
            indexJson(idxEntry("genesis/other-game", "genesis", "other-game", "Sonic the Hedgehog", listOf("front"))),
        )!!
        // Filename slug misses, but the title slug hits the same platform entry.
        val f = MediaHealthCheck.classify(
            "genesis/crystal-nova.metadata.pegasus.txt",
            entry(files = listOf("/r/Totally Different Name.md"), title = "Sonic the Hedgehog"),
            maps.byId, maps.byTitle, assetOk,
        )
        assertEquals(MediaHealthCheck.GameHealth.NO_INDEX_ENTRY, f.health)
        assertTrue(f.titleFallbackHit)
    }

    @Test fun `classify ASSET_FILE_MISSING lists the absent slots`() {
        val maps = MediaHealthCheck.parseIndexMaps(
            indexJson(idxEntry("genesis/sonic-the-hedgehog", "genesis", "sonic-the-hedgehog", "Sonic", listOf("front", "logo", "screenshot"))),
        )!!
        val f = MediaHealthCheck.classify(
            "genesis/crystal-nova.metadata.pegasus.txt", entry(), maps.byId, maps.byTitle,
        ) { _, _, stem -> if (stem == "logo") null else 100L }
        assertEquals(MediaHealthCheck.GameHealth.ASSET_FILE_MISSING, f.health)
        assertEquals(listOf("logo"), f.missingSlots)
    }

    @Test fun `classify treats zero-byte files as missing`() {
        val maps = MediaHealthCheck.parseIndexMaps(
            indexJson(idxEntry("genesis/sonic-the-hedgehog", "genesis", "sonic-the-hedgehog", "Sonic", listOf("front"))),
        )!!
        val f = MediaHealthCheck.classify(
            "genesis/crystal-nova.metadata.pegasus.txt", entry(), maps.byId, maps.byTitle,
        ) { _, _, _ -> 0L }
        assertEquals(MediaHealthCheck.GameHealth.ASSET_FILE_MISSING, f.health)
    }

    // -- shortname round-trip ------------------------------------------------

    @Test fun `shortname round-trip catches the lynx atarilynx bug`() {
        val mismatches = MediaHealthCheck.checkShortnameRoundTrip()
        val lynx = mismatches.find { it.slug == "lynx" }
        assertNotNull("expected lynx mismatch", lynx)
        assertEquals("atarilynx", lynx!!.writtenShortname)
        assertEquals("", lynx.themeReadsAs)
    }

    @Test fun `shortname round-trip passes aliased systems`() {
        val mismatches = MediaHealthCheck.checkShortnameRoundTrip().associateBy { it.slug }
        assertNull(mismatches["gamecube"]) // gc -> gamecube
        assertNull(mismatches["n3ds"]) // 3ds -> n3ds
        assertNull(mismatches["genesis"])
    }

    // -- duplicate collections -----------------------------------------------

    @Test fun `duplicate collections are detected with all paths`() {
        val dups = MediaHealthCheck.findDuplicateCollections(
            mapOf(
                "Sega Genesis" to setOf("genesis/a.txt", "genesis2/b.txt", "roms/c.txt"),
                "SNES" to setOf("snes/a.txt"),
            ),
        )
        assertEquals(1, dups.size)
        assertEquals("Sega Genesis", dups[0].collection)
        assertEquals(listOf("genesis/a.txt", "genesis2/b.txt", "roms/c.txt"), dups[0].metafiles)
    }
}
