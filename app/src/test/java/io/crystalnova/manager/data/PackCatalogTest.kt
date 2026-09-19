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
  "previewUrl": "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/packs/crystal-dawn-preview.png",
  "previewSha256": "$SHA_B",
  "systems": ["Super Nintendo", "PlayStation 2"],
  "iisuMinVersion": "0.0.7.4",
  "zipUrl": "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/packs/crystal-dawn.zip",
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
}
