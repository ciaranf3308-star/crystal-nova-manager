package io.crystalnova.manager.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Self-updater tests: release parsing, version gating, and the narrowly
 * scoped release-CDN exception in the APK downloader.
 */
class SelfUpdateTest {

    private val apkName = "crystal-nova-manager-u1.2.apk"

    private fun releaseJson(tag: String, apkName: String? = this.apkName): String {
        val assets = if (apkName == null) {
            "[]"
        } else {
            """[{"name":"$apkName","browser_download_url":"https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/$tag/$apkName"}]"""
        }
        return """{"tag_name":"$tag","assets":$assets}"""
    }

    private fun checker(
        tag: String = "v1.0.2-u1",
        apkName: String? = this.apkName,
        code: Int = 200,
    ): Pair<SelfUpdateChecker, FakeHttpClient> {
        val http = FakeHttpClient(
            getHandler = { jsonResponse(code, releaseJson(tag, apkName)) },
        )
        return SelfUpdateChecker(http) to http
    }

    @Test
    fun `check queries the manager releases endpoint`() {
        val (c, http) = checker()
        c.check("1.0.1-u1")
        assertEquals(listOf(GitHubEndpoints.managerLatestReleaseApi()), http.requested)
    }

    @Test
    fun `check returns info when the release is newer`() {
        val (c, _) = checker(tag = "v1.0.2-u1")
        val info = c.check("1.0.1-u1")
        assertNotNull(info)
        assertEquals("1.0.2-u1", info!!.version)
        assertEquals("v1.0.2-u1", info.tag)
        assertTrue(info.apkUrl.endsWith(apkName))
    }

    @Test
    fun `check returns null when current`() {
        val (c, _) = checker(tag = "v1.0.2-u1")
        assertNull(c.check("1.0.2-u1"))
    }

    @Test
    fun `check returns null when the release is older`() {
        val (c, _) = checker(tag = "v1.0.1-u1")
        assertNull(c.check("1.0.2-u1"))
    }

    @Test
    fun `check handles a tag without v prefix`() {
        val (c, _) = checker(tag = "1.0.3-u1")
        val info = c.check("1.0.2-u1")
        assertEquals("1.0.3-u1", info!!.version)
    }

    @Test
    fun `check throws when the release has no APK asset`() {
        val (c, _) = checker(apkName = null)
        try {
            c.check("1.0.1-u1")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("no APK asset"))
        }
    }

    @Test
    fun `check throws on API error`() {
        val (c, _) = checker(code = 403)
        try {
            c.check("1.0.1-u1")
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `check throws on malformed release JSON`() {
        val http = FakeHttpClient(getHandler = { jsonResponse(200, "not json") })
        val c = SelfUpdateChecker(http)
        try {
            c.check("1.0.1-u1")
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    // --- ReleaseAssetHttpClient hop validation -------------------------

    private val startUrl =
        "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/v1.0.2-u1/$apkName"
    private val cdnUrl =
        "https://objects.githubusercontent.com/github-production-release-asset-abc123/file.apk?token=xyz"
    private val assetClient = ReleaseAssetHttpClient()

    @Test
    fun `download hop allows the trusted start URL`() {
        // The start URL is the exact browser_download_url from the release
        // JSON, pinned to the manager repo by the endpoint boundary.
        assetClient.checkDownloadHop(startUrl, startUrl, cdnHopUsed = false)
    }

    @Test
    fun `download hop allows the single release-CDN redirect`() {
        assetClient.checkDownloadHop(cdnUrl, startUrl, cdnHopUsed = false)
    }

    @Test
    fun `download hop rejects a second CDN redirect`() {
        try {
            assetClient.checkDownloadHop(cdnUrl, startUrl, cdnHopUsed = true)
            fail("expected SecurityException")
        } catch (_: SecurityException) {
        }
    }

    @Test
    fun `download hop rejects a non-CDN host`() {
        try {
            assetClient.checkDownloadHop("https://evil-cdn.example/x.apk", startUrl, false)
            fail("expected SecurityException")
        } catch (_: SecurityException) {
        }
    }

    @Test
    fun `download hop rejects non-https`() {
        try {
            assetClient.checkDownloadHop(
                "http://objects.githubusercontent.com/x.apk",
                startUrl,
                false,
            )
            fail("expected SecurityException")
        } catch (_: SecurityException) {
        }
    }

    @Test
    fun `the general boundary still rejects the release CDN`() {
        // The CDN exception lives ONLY in ReleaseAssetHttpClient. The
        // theme updater's boundary must keep rejecting it.
        try {
            GitHubEndpoints.checkAllowed(cdnUrl)
            fail("expected SecurityException")
        } catch (_: SecurityException) {
        }
    }
}
