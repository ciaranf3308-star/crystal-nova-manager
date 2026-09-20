package io.crystalnova.manager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SHA_A = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
private const val SHA_B = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

private fun catalogJson(packs: String) = """
{
  "catalogVersion": 1,
  "sourceRepo": "ciaranf3308-star/emulationtesterciaran",
  "sourceSha": "deadbee",
  "packs": [ $packs ]
}
""".trimIndent()

private fun packJson(
    id: String = "crystal-dawn",
    sha: String = SHA_A,
    extra: String = "",
) = """
{
  "id": "$id",
  "name": "Crystal Dawn",
  "version": "1.0",
  "versionCode": 1,
  "description": "First light.",
  "previewUrl": "https://github.com/ciaranf3308-star/crystal-nova-packs/releases/download/stable/crystal-dawn-preview.png",
  "previewSha256": "$SHA_B",
  "systems": ["Super Nintendo", "PlayStation 2"],
  "iisuMinVersion": "0.0.7.4",
  "zipUrl": "https://github.com/ciaranf3308-star/crystal-nova-packs/releases/download/stable/crystal-dawn.zip",
  "zipSha256": "$sha",
  "zipBytes": 123456,
  "assets": [
    { "slot": "icon", "platform": "snes", "path": "crystal-dawn/snes/icon.png",
      "sha256": "$SHA_B", "bytes": 1024, "width": 512, "height": 512, "required": true }
  ]
  $extra
}
""".trimIndent()

class PackCatalogTest {

    @Test
    fun validCatalogParsesFully() {
        val catalog = parsePackCatalog(catalogJson(packJson()))!!
        assertEquals(1, catalog.catalogVersion)
        assertEquals("ciaranf3308-star/emulationtesterciaran", catalog.sourceRepo)
        assertEquals("deadbee", catalog.sourceSha)
        assertEquals(1, catalog.packs.size)
        val pack = catalog.packs[0]
        assertEquals("crystal-dawn", pack.id)
        assertEquals("Crystal Dawn", pack.name)
        assertEquals("1.0", pack.version)
        assertEquals(1, pack.versionCode)
        assertEquals("First light.", pack.description)
        assertEquals(listOf("Super Nintendo", "PlayStation 2"), pack.systems)
        assertEquals("0.0.7.4", pack.iisuMinVersion)
        assertEquals(123456L, pack.zipBytes)
        assertEquals(SHA_A, pack.zipSha256)
        assertNotNull(pack.previewUrl)
        assertEquals(1, pack.assets.size)
        val asset = pack.assets[0]
        assertEquals("icon", asset.slot)
        assertEquals("snes", asset.platform)
        assertEquals("crystal-dawn/snes/icon.png", asset.path)
        assertEquals(1024L, asset.bytes)
        assertEquals(512, asset.width)
        assertEquals(512, asset.height)
        assertTrue(asset.required)
    }

    @Test
    fun malformedJsonYieldsNull() {
        assertNull(parsePackCatalog("{ not json"))
        assertNull(parsePackCatalog(""))
        assertNull(parsePackCatalog("[1,2,3]"))
        assertNull(parsePackCatalog("""{"catalogVersion": 1,"packs": ["""))
    }

    @Test
    fun missingTopLevelShapeYieldsNull() {
        assertNull(parsePackCatalog("""{"packs": []}"""))
        assertNull(parsePackCatalog("""{"catalogVersion": 1}"""))
        assertNull(parsePackCatalog("""{"catalogVersion": "one", "packs": []}"""))
    }

    @Test
    fun emptyPacksArrayIsHonestNotNull() {
        val catalog = parsePackCatalog(catalogJson(""))!!
        assertTrue(catalog.packs.isEmpty())
    }

    @Test
    fun badPackIsDroppedButGoodPackSurvives() {
        val bad = packJson(id = "bad-pack", sha = "not-a-sha")
        val catalog = parsePackCatalog(catalogJson("${packJson(id = "good-pack")}, $bad"))!!
        assertEquals(1, catalog.packs.size)
        assertEquals("good-pack", catalog.packs[0].id)
    }

    @Test
    fun packMissingRequiredFieldIsDropped() {
        val noZip = """
        { "id": "x", "name": "X", "version": "1.0", "versionCode": 1,
          "zipSha256": "$SHA_A" }
        """.trimIndent()
        val catalog = parsePackCatalog(catalogJson(noZip))!!
        assertTrue(catalog.packs.isEmpty())
    }

    @Test
    fun optionalFieldsDefaultCleanly() {
        val minimal = """
        { "id": "min", "name": "Min", "version": "0.1", "versionCode": 2,
          "zipUrl": "https://github.com/x/y/releases/download/dev-latest/packs/m.zip",
          "zipSha256": "$SHA_A" }
        """.trimIndent()
        val pack = parsePackCatalog(catalogJson(minimal))!!.packs.single()
        assertEquals("", pack.description)
        assertNull(pack.previewUrl)
        assertNull(pack.previewSha256)
        assertTrue(pack.systems.isEmpty())
        assertNull(pack.iisuMinVersion)
        assertNull(pack.zipBytes)
        assertTrue(pack.assets.isEmpty())
    }

    @Test
    fun unknownKeysAreTolerated() {
        val catalog = parsePackCatalog(
            catalogJson(packJson(extra = """, "futureField": {"nested": [1,2,true,null]}""")),
        )!!
        assertEquals(1, catalog.packs.size)
    }

    @Test
    fun jsonParserHandlesNestingAndEscapes() {
        val v = parseJson("""{"a": [1, -2.5, true, false, null, "x\"y"], "b": {}}""")
        val obj = v as? JsonVal.Obj
        assertNotNull(obj)
        val arr = obj!!.map["a"] as? JsonVal.Arr
        assertEquals(6, arr!!.items.size)
        assertEquals("x\"y", (arr.items[5] as JsonVal.Str).value)
        assertEquals("-2.5", (arr.items[1] as JsonVal.Num).raw)
    }

    /**
     * u47: the REAL published catalog schema, verbatim from
     * https://github.com/ciaranf3308-star/crystal-nova-packs/releases/download/stable/catalog.json
     * (trimmed to 3 of 60 assets). If the pipeline changes the schema
     * in a way the parser cannot read, THIS test fails in CI — not on
     * the Nova. Update the fixture when the catalog intentionally
     * evolves; never weaken the assertions to chase it.
     */
    @Test
    fun realPublishedCatalogSchemaParses() {
        val catalog = parsePackCatalog(REAL_CATALOG_JSON)
        assertNotNull("published catalog must parse", catalog)
        assertEquals(1, catalog!!.catalogVersion)
        assertEquals("ciaranf3308-star/emulationtesterciaran", catalog.sourceRepo)
        assertEquals(1, catalog.packs.size)
        val pack = catalog.packs[0]
        assertEquals("crystal-nova", pack.id)
        assertEquals("Crystal Nova", pack.name)
        assertEquals("1.0.0", pack.version)
        assertEquals(1, pack.versionCode)
        assertEquals(20, pack.systems.size)
        assertTrue(pack.systems.contains("snes"))
        assertEquals("0.0.7.4", pack.iisuMinVersion)
        assertEquals(
            "https://github.com/ciaranf3308-star/crystal-nova-packs/releases/download/stable/crystal-pack-v1.zip",
            pack.zipUrl,
        )
        assertEquals(
            "c2cba178ca6ddde0c9ab3f86c422063f9af12aa557ca7c206d8c53ec48af86d3",
            pack.zipSha256,
        )
        assertEquals(3661364L, pack.zipBytes)
        assertEquals(
            "https://github.com/ciaranf3308-star/crystal-nova-packs/releases/download/stable/preview.png",
            pack.previewUrl,
        )
        assertEquals(3, pack.assets.size)
        val icon = pack.assets[0]
        assertEquals("icon", icon.slot)
        assertEquals("dreamcast", icon.platform)
        assertEquals("Crystal/dreamcast/icon.png", icon.path)
        assertEquals(540, icon.width)
        assertEquals(540, icon.height)
        assertTrue(icon.required)
        val background = pack.assets[2]
        assertEquals("background", background.slot)
        assertTrue(background.path.endsWith(".webp"))
    }
}

/**
 * Verbatim copy of the live published catalog (assets trimmed to the
 * first 3 of 60). Captured 2026-09-20 from the crystal-nova-packs
 * `stable` release.
 */
private const val REAL_CATALOG_JSON = """{"catalogVersion": 1, "sourceRepo": "ciaranf3308-star/emulationtesterciaran", "sourceSha": "3f5181f69cd09844e7cabffbe2f2e0bb07c00549", "packs": [{"id": "crystal-nova", "name": "Crystal Nova", "kind": "platform-pack", "version": "1.0.0", "versionCode": 1, "description": "Crystal platform pack for iiSU: translucent blue/white system icons, title logos and hero backgrounds.", "previewUrl": "https://github.com/ciaranf3308-star/crystal-nova-packs/releases/download/stable/preview.png", "previewSha256": "664f04167186f68737dcdc6435adf8082620961cf99a6c9193b2b87e07a6f006", "systems": ["dreamcast", "gb", "gba", "gbc", "gc", "genesis", "n3ds", "n64", "nds", "nes", "ps2", "psp", "psx", "snes", "steam", "wii", "wiiu", "windows", "xbox", "xbox360"], "iisuMinVersion": "0.0.7.4", "zipFileName": "crystal-pack-v1.zip", "zipUrl": "https://github.com/ciaranf3308-star/crystal-nova-packs/releases/download/stable/crystal-pack-v1.zip", "zipSha256": "c2cba178ca6ddde0c9ab3f86c422063f9af12aa557ca7c206d8c53ec48af86d3", "zipBytes": 3661364, "assets": [{"slot": "icon", "platform": "dreamcast", "path": "Crystal/dreamcast/icon.png", "sha256": "c04eda970f0662fb861c7a1787ebf6a26be2a0393cfc05b040d7ecf2d0b0e34f", "bytes": 33633, "width": 540, "height": 540, "required": true}, {"slot": "title", "platform": "dreamcast", "path": "Crystal/dreamcast/title.png", "sha256": "327dd4c952bba37d795c4d5b62674860d3f1458607f32eb408c8256a29c29812", "bytes": 76819, "width": 1552, "height": 383, "required": true}, {"slot": "background", "platform": "dreamcast", "path": "Crystal/dreamcast/hero.webp", "sha256": "e09a40eb9d3ee4cb2f56aa7aef24af4d9f2e55a014e1f23ad68483c123e5d7e8", "bytes": 90716, "width": 1672, "height": 941, "required": true}]}]}"""
