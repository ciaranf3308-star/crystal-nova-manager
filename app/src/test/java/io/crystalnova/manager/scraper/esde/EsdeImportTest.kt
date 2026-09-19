package io.crystalnova.manager.scraper.esde

import io.crystalnova.manager.scraper.match.TitleNormalizer
import io.crystalnova.manager.scraper.model.AssetProvenance
import io.crystalnova.manager.scraper.model.AssetSlot
import io.crystalnova.manager.scraper.model.SourceType
import io.crystalnova.manager.scraper.scan.RomEntry
import org.junit.Assert.*
import org.junit.Test

class EsdeImportTest {

    private fun prov(
        type: SourceType,
        provider: String = "libretro",
    ) = AssetProvenance(
        sourceType = type,
        provider = provider,
        localPath = "games/ps2/x/front.png",
    )

    private fun found(slot: AssetSlot, bytes: Long = 500_000) = EsdeImport.FoundAsset(
        slot = slot,
        relativePath = "media/ps2/covers/x.png",
        fileName = "x.png",
        byteLength = bytes,
    )

    private fun game() = EsdeImport.RomGame("ps2", "x", "X", "x.iso")

    // -- basename ------------------------------------------------------

    @Test fun `romBasename strips folders and extension`() {
        assertEquals("Super Mario World (USA)", EsdeImport.romBasename("snes/Super Mario World (USA).sfc"))
        assertEquals("x", EsdeImport.romBasename("x.iso"))
        assertEquals("x", EsdeImport.romBasename("a\\b\\x.chd"))
    }

    @Test fun `esdeSystemDir maps differing slugs`() {
        assertEquals("gc", EsdeImport.esdeSystemDir("gamecube"))
        assertEquals("3ds", EsdeImport.esdeSystemDir("n3ds"))
        assertEquals("ps2", EsdeImport.esdeSystemDir("ps2"))
        assertEquals("gba", EsdeImport.esdeSystemDir("gba"))
    }

    @Test fun `exactly the five approved slots are imported`() {
        assertEquals(
            setOf(
                AssetSlot.BOX_FRONT, AssetSlot.BOX_BACK, AssetSlot.CLEAR_LOGO,
                AssetSlot.PHYSICAL_MEDIA, AssetSlot.SCREENSHOT,
            ),
            EsdeImport.SLOT_DIRS.keys,
        )
    }

    // -- decisions -----------------------------------------------------

    @Test fun `no source and no existing is a quiet skip`() {
        assertEquals(
            EsdeImport.Decision.SKIP_NO_SOURCE,
            EsdeImport.decide(null, null, null),
        )
    }

    @Test fun `vanished source keeps the previous import`() {
        assertEquals(
            EsdeImport.Decision.SKIP_SOURCE_GONE,
            EsdeImport.decide(null, prov(SourceType.REAL, EsdeImport.PROVIDER_ID), 100),
        )
    }

    @Test fun `missing slot copies`() {
        assertEquals(EsdeImport.Decision.COPY_NEW, EsdeImport.decide(found(AssetSlot.BOX_FRONT), null, null))
    }

    @Test fun `user art is never overwritten`() {
        assertEquals(
            EsdeImport.Decision.SKIP_USER,
            EsdeImport.decide(found(AssetSlot.BOX_FRONT), prov(SourceType.USER, "user"), 100),
        )
    }

    @Test fun `real esde art upgrades generated art`() {
        assertEquals(
            EsdeImport.Decision.COPY_UPGRADE,
            EsdeImport.decide(found(AssetSlot.BOX_FRONT), prov(SourceType.GENERATED, "crystal"), 100),
        )
    }

    @Test fun `other real art is never clobbered`() {
        assertEquals(
            EsdeImport.Decision.SKIP_REAL_OTHER,
            EsdeImport.decide(found(AssetSlot.BOX_FRONT), prov(SourceType.REAL, "libretro"), 500_000),
        )
    }

    @Test fun `unchanged previous import is skipped`() {
        assertEquals(
            EsdeImport.Decision.SKIP_UPTODATE,
            EsdeImport.decide(
                found(AssetSlot.BOX_FRONT, 500_000),
                prov(SourceType.REAL, EsdeImport.PROVIDER_ID), 500_000,
            ),
        )
    }

    @Test fun `changed source re-copies`() {
        assertEquals(
            EsdeImport.Decision.COPY_CHANGED,
            EsdeImport.decide(
                found(AssetSlot.BOX_FRONT, 600_000),
                prov(SourceType.REAL, EsdeImport.PROVIDER_ID), 500_000,
            ),
        )
    }

    @Test fun `missing dest file restores the import`() {
        assertEquals(
            EsdeImport.Decision.COPY_CHANGED,
            EsdeImport.decide(
                found(AssetSlot.BOX_FRONT),
                prov(SourceType.REAL, EsdeImport.PROVIDER_ID), null,
            ),
        )
    }

    // -- report --------------------------------------------------------

    private fun plan(vararg plans: EsdeImport.SlotPlan): EsdeImport.ImportPlan {
        val g = game()
        return EsdeImport.ImportPlan(
            games = listOf(g),
            slotPlans = plans.toList(),
            matchedGameIds = setOf("ps2/x"),
            unmatchedGames = emptyList(),
            unmatchedMediaGroups = emptyList(),
        )
    }

    @Test fun `report contains every required section`() {
        val g = game()
        val p = plan(
            EsdeImport.SlotPlan(g, AssetSlot.BOX_FRONT, found(AssetSlot.BOX_FRONT, 1_000), EsdeImport.Decision.COPY_NEW),
            EsdeImport.SlotPlan(g, AssetSlot.BOX_BACK, found(AssetSlot.BOX_BACK, 2_000), EsdeImport.Decision.SKIP_USER),
            EsdeImport.SlotPlan(g, AssetSlot.CLEAR_LOGO, null, EsdeImport.Decision.SKIP_NO_SOURCE),
        )
        val r = p.reportText()
        assertTrue(r.contains("ROMs scanned: 1"))
        assertTrue(r.contains("Games matched: 1"))
        assertTrue(r.contains("Games unmatched: 0"))
        assertTrue(r.contains("Assets found by slot:"))
        assertTrue(r.contains("BOX_FRONT: 1"))
        assertTrue(r.contains("Assets that will be written:"))
        assertTrue(r.contains("Total to write: 1 files"))
        assertTrue(r.contains("Conflicts/skips: 1"))
        assertTrue(r.contains("kept: user art wins: 1"))
        assertTrue(r.contains("Exact bytes to copy: 1000"))
        assertTrue(r.contains("Media size increase:"))
    }

    @Test fun `idempotent re-run writes nothing`() {
        val g = game()
        val p = plan(
            EsdeImport.SlotPlan(
                g, AssetSlot.BOX_FRONT, found(AssetSlot.BOX_FRONT, 500_000),
                EsdeImport.Decision.SKIP_UPTODATE,
            ),
        )
        assertTrue(p.toWrite.isEmpty())
        assertEquals(0L, p.totalBytes)
        assertTrue(p.reportText().contains("already up to date"))
    }

    @Test fun `formatBytes renders human sizes`() {
        assertEquals("0 B", EsdeImport.ImportPlan.formatBytes(0))
        assertEquals("1.5 KB", EsdeImport.ImportPlan.formatBytes(1536))
        assertEquals("2.7 MB", EsdeImport.ImportPlan.formatBytes(2_831_156))
    }

    // -- gamelist fallback ----------------------------------------------

    @Test fun `parseGamelist maps path basenames to explicit media`() {
        val xml = """<?xml version="1.0"?>
<gameList>
  <game>
    <path>./Final Fantasy X.iso</path>
    <name>Final Fantasy X</name>
    <thumbnail>./media/covers/Final Fantasy X.png</thumbnail>
    <image>./media/screenshots/Final Fantasy X.png</image>
  </game>
</gameList>"""
        val m = EsdeImport.parseGamelist(xml)
        val gm = m["final fantasy x"]
        assertNotNull(gm)
        assertEquals("./media/covers/Final Fantasy X.png", gm!!.thumbnail)
        assertEquals("./media/screenshots/Final Fantasy X.png", gm.image)
    }

    @Test fun `parseGamelist returns empty on garbage`() {
        assertTrue(EsdeImport.parseGamelist("not xml at all").isEmpty())
        assertTrue(EsdeImport.parseGamelist("").isEmpty())
    }

    @Test fun `parseGamelist rejects doctypes`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY x "y">]>
<gameList><game><path>./a.iso</path></game><gameList>"""
        assertTrue(EsdeImport.parseGamelist(xml).isEmpty())
    }

    @Test fun `resolveGamelistMediaCandidates covers the known conventions`() {
        val c = EsdeImport.resolveGamelistMediaCandidates("ps2", "./media/ps2/covers/x.png")
        assertTrue(c.contains("media/ps2/covers/x.png"))

        val c2 = EsdeImport.resolveGamelistMediaCandidates("ps2", "covers/x.png")
        assertTrue(c2.contains("media/ps2/covers/x.png"))

        val c3 = EsdeImport.resolveGamelistMediaCandidates("ps2", "../media/covers/x.png")
        assertTrue(c3.contains("gamelists/media/covers/x.png"))

        val c4 = EsdeImport.resolveGamelistMediaCandidates("ps2", "../../../evil.png")
        assertTrue(c4.isEmpty())
    }

    // -- ROM-scan source (pre-scan no longer reads index.json) ----------

    private fun entry(
        platformSlug: String = "ps2",
        fileName: String = "TOCA Race Driver 3.iso",
    ) = RomEntry(
        platformSlug = platformSlug,
        platformLabel = "PlayStation 2",
        relativePath = "$platformSlug/$fileName",
        fileName = fileName,
        size = 1L,
        lastModified = 0L,
    )

    @Test fun `romGameFromEntry keeps platform and strips title from the file name`() {
        val g = EsdeImport.romGameFromEntry(entry())
        assertEquals("ps2", g.platform)
        assertEquals("TOCA Race Driver 3", g.title)
        assertEquals("TOCA Race Driver 3.iso", g.fileName)
    }

    @Test fun `romGameFromEntry gameId follows the scraper index convention`() {
        // ScrapeJob and the orphan prune both key games by
        // slugify(fileName); the import must write the same keys so its
        // manifests/index entries are recognized, not duplicated.
        val g = EsdeImport.romGameFromEntry(entry())
        assertEquals(TitleNormalizer.slugify("TOCA Race Driver 3.iso"), g.gameId)
    }

    @Test fun `romGameFromEntry handles nested relative paths`() {
        val g = EsdeImport.romGameFromEntry(
            entry(platformSlug = "gba", fileName = "Mario Golf (E).gba"),
        )
        assertEquals("gba", g.platform)
        assertEquals("Mario Golf (E)", g.title)
        assertEquals(TitleNormalizer.slugify("Mario Golf (E).gba"), g.gameId)
    }
}
