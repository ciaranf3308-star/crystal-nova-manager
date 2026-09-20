package io.crystalnova.manager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ESDE_SHA_A = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
private const val ESDE_SHA_B = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

private fun esdeEntryJson(
    version: String = "1.0.0",
    versionCode: Int = 1,
    sha: String = ESDE_SHA_A,
) = """
{
  "version": "$version",
  "versionCode": $versionCode,
  "zipUrl": "https://github.com/ciaranf3308-star/crystal-esde-theme/releases/download/stable/crystal-theme-v1.zip",
  "zipSha256": "$sha",
  "zipBytes": 4194304
}
""".trimIndent()

private fun esdeCatalogJson(
    top: String = esdeEntryJson(),
    history: String = "[ ${esdeEntryJson("0.9.0", 0, ESDE_SHA_B)} ]",
    extra: String = "",
) = """
{
  "id": "crystal",
  "version": "1.0.0",
  "versionCode": 1,
  "zipUrl": "https://github.com/ciaranf3308-star/crystal-esde-theme/releases/download/stable/crystal-theme-v1.zip",
  "zipSha256": "$ESDE_SHA_A",
  "zipBytes": 4194304,
  "minManagerVersion": "1.2.4-u48-esdeupdate",
  "minManagerVersionCode": 63,
  "history": $history
  $extra
}
""".trimIndent()

class EsdeThemeCatalogTest {

    @Test
    fun validCatalogParsesFully() {
        val catalog = parseEsdeThemeCatalog(esdeCatalogJson())!!
        assertEquals("crystal", catalog.id)
        assertEquals("1.0.0", catalog.version)
        assertEquals(1, catalog.versionCode)
        assertEquals(4194304L, catalog.zipBytes)
        assertEquals("1.2.4-u48-esdeupdate", catalog.minManagerVersion)
        assertEquals(63, catalog.minManagerVersionCode)
        assertEquals(1, catalog.history.size)
        assertEquals("0.9.0", catalog.history[0].version)
        assertEquals(0, catalog.history[0].versionCode)
    }

    @Test
    fun rollbackTargetIsHistoryHead() {
        val catalog = parseEsdeThemeCatalog(
            esdeCatalogJson(
                history = "[ ${esdeEntryJson("0.9.0", 0, ESDE_SHA_B)}, " +
                    "${esdeEntryJson("0.8.0", -1, ESDE_SHA_B)} ]",
            ),
        )!!
        val rollback = catalog.rollbackTarget()!!
        assertEquals("0.9.0", rollback.version)
        assertEquals(0, rollback.versionCode)
    }

    @Test
    fun emptyHistoryHasNoRollbackTarget() {
        val catalog = parseEsdeThemeCatalog(esdeCatalogJson(history = "[]"))!!
        assertTrue(catalog.history.isEmpty())
        assertNull(catalog.rollbackTarget())
    }

    @Test
    fun missingHistoryDefaultsToEmpty() {
        val json = """
        {
          "id": "crystal",
          "version": "1.0.0",
          "versionCode": 1,
          "zipUrl": "https://github.com/ciaranf3308-star/crystal-esde-theme/releases/download/stable/crystal-theme-v1.zip",
          "zipSha256": "$ESDE_SHA_A"
        }
        """.trimIndent()
        val catalog = parseEsdeThemeCatalog(json)!!
        assertTrue(catalog.history.isEmpty())
        assertNull(catalog.minManagerVersion)
        assertNull(catalog.minManagerVersionCode)
        assertNull(catalog.zipBytes)
    }

    @Test
    fun malformedJsonYieldsNull() {
        assertNull(parseEsdeThemeCatalog("{ not json"))
        assertNull(parseEsdeThemeCatalog(""))
    }

    @Test
    fun missingRequiredFieldsYieldNull() {
        // no versionCode
        assertNull(
            parseEsdeThemeCatalog(
                """
                {
                  "id": "crystal",
                  "version": "1.0.0",
                  "zipUrl": "https://github.com/ciaranf3308-star/x/releases/download/stable/crystal-theme-v1.zip",
                  "zipSha256": "$ESDE_SHA_A"
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun badChecksumYieldsNull() {
        val json = esdeCatalogJson().replace(ESDE_SHA_A, "not-a-checksum")
        assertNull(parseEsdeThemeCatalog(json))
    }

    @Test
    fun badHistoryEntriesAreDropped() {
        // One history entry with a bad checksum is dropped; the good
        // one survives and becomes the rollback target.
        val catalog = parseEsdeThemeCatalog(
            esdeCatalogJson(
                history = "[ ${esdeEntryJson("0.9.0", 0, "bogus")}, " +
                    "${esdeEntryJson("0.8.0", -1, ESDE_SHA_B)} ]",
            ),
        )!!
        assertEquals(1, catalog.history.size)
        assertEquals("0.8.0", catalog.rollbackTarget()!!.version)
    }

    @Test
    fun catalogUrlPointsAtThemeRepoStableRelease() {
        assertNotNull(ESDE_THEME_CATALOG_URL)
        assertTrue(
            ESDE_THEME_CATALOG_URL.startsWith(
                "https://github.com/ciaranf3308-star/crystal-esde-theme/" +
                    "releases/download/stable/catalog.json",
            ),
        )
    }
}
