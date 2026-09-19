package io.crystalnova.manager.updater

import io.crystalnova.manager.data.DevUpdateManifest
import io.crystalnova.manager.data.FakeHttpClient
import io.crystalnova.manager.data.GitHubEndpoints
import io.crystalnova.manager.data.jsonResponse
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * u44: the Crystal Launcher flow is archived — [UpdateManager] no longer
 * wires it. These tests pin the standalone [LauncherUpdateChecker] to a
 * fake HTTP client so the archived checker stays covered without
 * touching the network.
 */
class LauncherUpdateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val launcherManifestJson =
        """{"versionName":"0.3.0-beta4","versionCode":6,"apkSha256":"%s","apkUrl":"https://github.com/ciaranf3308-star/crystal-launcher/releases/download/dev-latest/crystal-nova-launcher-0.3.0-beta4.apk","commitSha":"aba74f4690b23ec20100e18efe3fb4c4fbd8d46c","builtAt":"2026-09-19T21:47:15Z"}"""

    private fun manifestHttp(
        body: String = launcherManifestJson.format("a".repeat(64)),
        code: Int = 200,
        fail: Boolean = false,
    ) = FakeHttpClient(
        getHandler = { url ->
            if (fail) throw IOException("no route to host")
            assertTrue("unexpected url $url", url.startsWith(LAUNCHER_MANIFEST_URL))
            jsonResponse(code, body)
        },
    )

    private fun manifest(versionCode: Int = 6) = DevUpdateManifest(
        versionName = "0.3.0-beta4",
        versionCode = versionCode,
        apkSha256 = "b".repeat(64),
        apkUrl = "https://github.com/ciaranf3308-star/crystal-launcher/releases/download/dev-latest/crystal-nova-launcher-0.3.0-beta4.apk",
        commitSha = "c".repeat(40),
        builtAt = "2026-09-19T21:47:15Z",
    )

    // LauncherUpdateChecker.check ------------------------------------------------

    @Test
    fun `absent launcher offers first install`() {
        val checker = LauncherUpdateChecker(manifestHttp = manifestHttp())
        val found = checker.check(null)
        assertNotNull(found)
        assertEquals(6, found!!.versionCode)
        assertEquals("0.3.0-beta4", found.versionName)
    }

    @Test
    fun `older installed launcher offers update`() {
        val checker = LauncherUpdateChecker(manifestHttp = manifestHttp())
        val found = checker.check(4)
        assertNotNull(found)
        assertEquals(6, found!!.versionCode)
    }

    @Test
    fun `current launcher reports no update`() {
        val checker = LauncherUpdateChecker(manifestHttp = manifestHttp())
        assertNull(checker.check(6))
    }

    @Test
    fun `newer installed launcher reports no update`() {
        val checker = LauncherUpdateChecker(manifestHttp = manifestHttp())
        assertNull(checker.check(99))
    }

    @Test(expected = IOException::class)
    fun `malformed manifest throws`() {
        val checker = LauncherUpdateChecker(manifestHttp = manifestHttp(body = "{nope"))
        checker.check(null)
    }

    @Test(expected = IOException::class)
    fun `http error throws`() {
        val checker = LauncherUpdateChecker(manifestHttp = manifestHttp(code = 404, body = "x"))
        checker.check(null)
    }

    @Test(expected = IOException::class)
    fun `network failure throws`() {
        val checker = LauncherUpdateChecker(manifestHttp = manifestHttp(fail = true))
        checker.check(null)
    }

    @Test
    fun `launcher apk url passes the github allowlist`() {
        GitHubEndpoints.checkAllowed(
            "https://github.com/ciaranf3308-star/crystal-launcher/releases/download/dev-latest/crystal-nova-launcher-0.3.0-beta4.apk",
        )
    }

    @Test(expected = SecurityException::class)
    fun `non github apk url is rejected`() {
        GitHubEndpoints.checkAllowed("https://evil.example.com/launcher.apk")
    }

    // LauncherUpdateChecker.download ---------------------------------------------

    @Test
    fun `download verifies sha and keeps the apk`() {
        val bytes = "fake-apk-bytes".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val http = FakeHttpClient(
            downloadHandler = { _: String, dest: File -> dest.writeBytes(bytes) },
        )
        val checker = LauncherUpdateChecker(dev = io.crystalnova.manager.data.DevUpdateChecker(assetHttp = http))
        val dest = tmp.newFile("launcher.apk")
        checker.download(manifest().copy(apkSha256 = sha), dest) { _, _ -> }
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test
    fun `sha mismatch deletes the apk and throws`() {
        val http = FakeHttpClient(
            downloadHandler = { _: String, dest: File -> dest.writeBytes("tampered".toByteArray()) },
        )
        val checker = LauncherUpdateChecker(dev = io.crystalnova.manager.data.DevUpdateChecker(assetHttp = http))
        val dest = tmp.newFile("launcher.apk")
        try {
            checker.download(manifest(), dest) { _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertFalse("tampered apk must not survive", dest.exists() && dest.length() > 0)
    }
}
