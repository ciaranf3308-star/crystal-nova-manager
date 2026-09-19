package io.crystalnova.manager.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val LIB_SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

private fun libCatalogJson(zipUrl: String, zipSha: String = LIB_SHA) = """
{
  "catalogVersion": 1,
  "packs": [
    { "id": "crystal-dawn", "name": "Crystal Dawn", "version": "1.0",
      "versionCode": 1,
      "zipUrl": "$zipUrl", "zipSha256": "$zipSha",
      "systems": ["Super Nintendo"] }
  ]
}
""".trimIndent()

private class MapPrefs : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PackLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun library(
        http: HttpClient,
        prefs: KeyValueStore? = null,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = PackLibrary(
        http = http,
        workDir = tmp.newFolder("packs"),
        scope = scope,
        prefs = prefs,
    )

    @Test
    fun catalogFetchSuccessYieldsReady() = runTest {
        val http = FakeHttpClient(
            getHandler = { jsonResponse(200, libCatalogJson("https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/packs/d.zip")) },
        )
        val lib = library(http, scope = this)
        advanceUntilIdle()
        val state = lib.catalog.value
        assertTrue(state is PackCatalogState.Ready)
        assertEquals("crystal-dawn", (state as PackCatalogState.Ready).packs.single().id)
    }

    @Test
    fun catalog404IsHonestUnavailable() = runTest {
        val http = FakeHttpClient(getHandler = { jsonResponse(404, "nope") })
        val lib = library(http, scope = this)
        advanceUntilIdle()
        val state = lib.catalog.value
        assertTrue(state is PackCatalogState.Unavailable)
        assertTrue((state as PackCatalogState.Unavailable).message.contains("NOT PUBLISHED YET"))
    }

    @Test
    fun malformedCatalogIsUnavailable() = runTest {
        val http = FakeHttpClient(getHandler = { jsonResponse(200, "{oops") })
        val lib = library(http, scope = this)
        advanceUntilIdle()
        assertTrue(lib.catalog.value is PackCatalogState.Unavailable)
    }

    @Test
    fun networkFailureIsUnavailable() = runTest {
        val http = FakeHttpClient(getHandler = { throw IOException("offline") })
        val lib = library(http, scope = this)
        advanceUntilIdle()
        val state = lib.catalog.value
        assertTrue(state is PackCatalogState.Unavailable)
        assertEquals("OFFLINE", (state as PackCatalogState.Unavailable).message)
    }

    @Test
    fun packUrlEscapingRepoIsRejected() = runTest {
        val http = FakeHttpClient(
            getHandler = { jsonResponse(200, libCatalogJson("https://evil.example/pack.zip")) },
        )
        val lib = library(http, scope = this)
        advanceUntilIdle()
        val state = lib.catalog.value
        assertTrue(state is PackCatalogState.Unavailable)
    }

    @Test
    fun downloadVerifiesChecksum() = runTest {
        val content = "fake zip bytes".toByteArray()
        val sha = sha256Hex(content)
        val http = FakeHttpClient(
            getHandler = {
                jsonResponse(
                    200,
                    libCatalogJson(
                        "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/packs/d.zip",
                        sha,
                    ),
                )
            },
            downloadHandler = { _, dest -> dest.writeBytes(content) },
        )
        val lib = library(http, scope = this)
        advanceUntilIdle()
        val pack = (lib.catalog.value as PackCatalogState.Ready).packs.single()
        lib.downloadPack(pack)
        advanceUntilIdle()
        val dl = lib.download.value
        assertTrue(dl is PackDownloadState.ReadyToInstall)
        assertTrue((dl as PackDownloadState.ReadyToInstall).zip.isFile)
    }

    @Test
    fun checksumMismatchFailsAndDeletes() = runTest {
        val http = FakeHttpClient(
            getHandler = {
                jsonResponse(
                    200,
                    libCatalogJson(
                        "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/packs/d.zip",
                        "0".repeat(64),
                    ),
                )
            },
            downloadHandler = { _, dest -> dest.writeBytes("tampered".toByteArray()) },
        )
        val lib = library(http, scope = this)
        advanceUntilIdle()
        val pack = (lib.catalog.value as PackCatalogState.Ready).packs.single()
        lib.downloadPack(pack)
        advanceUntilIdle()
        val dl = lib.download.value
        assertTrue(dl is PackDownloadState.Failed)
        assertTrue((dl as PackDownloadState.Failed).message.contains("CHECKSUM"))
        // No unverified ZIP may linger in the cache.
        assertTrue(
            tmp.root.walkTopDown().none { it.name.startsWith("pack-") && it.name.endsWith(".zip") },
        )
    }

    @Test
    fun installedPackRoundTripsThroughPrefs() = runTest {
        val prefs = MapPrefs()
        val http = FakeHttpClient(
            getHandler = {
                jsonResponse(
                    200,
                    libCatalogJson("https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/packs/d.zip"),
                )
            },
        )
        val lib = library(http, prefs, scope = this)
        advanceUntilIdle()
        assertNull(lib.installedPack())
        val pack = (lib.catalog.value as PackCatalogState.Ready).packs.single()
        lib.noteInstalled(pack)
        val installed = lib.installedPack()!!
        assertEquals("crystal-dawn", installed.id)
        assertEquals("Crystal Dawn", installed.name)
        assertEquals("1.0", installed.version)
    }

    @Test
    fun noPrefsMeansNoInstalledPack() = runTest {
        val http = FakeHttpClient(
            getHandler = {
                jsonResponse(
                    200,
                    libCatalogJson("https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/packs/d.zip"),
                )
            },
        )
        val lib = library(http, scope = this)
        advanceUntilIdle()
        val pack = (lib.catalog.value as PackCatalogState.Ready).packs.single()
        lib.noteInstalled(pack) // no prefs: must not crash
        assertNull(lib.installedPack())
    }
}
