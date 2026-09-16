package io.crystalnova.manager.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * DEV / CANDIDATE channel tests: manifest parsing (pure Kotlin, no
 * org.json), the versionCode update decision, channel persistence with
 * its DEV default, and the checker's fetch + checksum-verified
 * download against a fake HTTP layer.
 */
class DevChannelUpdateTest {

    private val apkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x01, 0x02)
    private val apkSha: String = MessageDigest.getInstance("SHA-256")
        .digest(apkBytes).joinToString("") { "%02x".format(it) }

    private fun manifestJson(
        versionName: String = "1.2.1-u2",
        versionCode: String = "8",
        sha: String = apkSha,
        apkUrl: String = "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/crystal-nova-manager-dev.apk",
    ): String = """{
      "versionName": "$versionName",
      "versionCode": $versionCode,
      "apkSha256": "$sha",
      "apkUrl": "$apkUrl",
      "commitSha": "22c6923176a312841a4824db59e1df7d035ac805",
      "builtAt": "2026-09-16T21:00:00Z"
    }"""

    private fun memStore(): KeyValueStore {
        val map = mutableMapOf<String, String>()
        return object : KeyValueStore {
            override fun getString(key: String): String? = map[key]
            override fun putString(key: String, value: String?) {
                if (value == null) map.remove(key) else map[key] = value
            }
            override fun remove(key: String) {
                map.remove(key)
            }
        }
    }

    // --- manifest parsing ------------------------------------------------

    @Test
    fun `parses a valid manifest`() {
        val m = parseDevManifest(manifestJson())
        assertNotNull(m)
        assertEquals("1.2.1-u2", m!!.versionName)
        assertEquals(8, m.versionCode)
        assertEquals(apkSha, m.apkSha256)
        assertTrue(m.apkUrl.endsWith("crystal-nova-manager-dev.apk"))
        assertEquals("22c6923176a312841a4824db59e1df7d035ac805", m.commitSha)
        assertEquals("2026-09-16T21:00:00Z", m.builtAt)
    }

    @Test
    fun `parsing tolerates whitespace and field order`() {
        val m = parseDevManifest(
            """{"builtAt":"t","commitSha":"c","apkUrl":"u","apkSha256":"$apkSha","versionCode":9,"versionName":"v"}""",
        )
        assertNotNull(m)
        assertEquals(9, m!!.versionCode)
        assertEquals("v", m.versionName)
    }

    @Test
    fun `parsing rejects malformed JSON`() {
        assertNull(parseDevManifest("not json"))
        assertNull(parseDevManifest("""{"versionName": "x",}"""))
        assertNull(parseDevManifest("""{"versionName": "x""""))
        assertNull(parseDevManifest(""))
    }

    @Test
    fun `parsing rejects missing fields`() {
        assertNull(parseDevManifest("""{"versionName":"1.0"}"""))
        // versionCode missing
        assertNull(
            parseDevManifest(
                """{"versionName":"v","apkSha256":"$apkSha","apkUrl":"u","commitSha":"c","builtAt":"t"}""",
            ),
        )
    }

    @Test
    fun `parsing rejects blank fields and bad sha`() {
        // blank versionName
        assertNull(parseDevManifest(manifestJson(versionName = " ")))
        // non-numeric versionCode
        assertNull(parseDevManifest(manifestJson(versionCode = "\"eight\"")))
        // sha not 64 hex chars
        assertNull(parseDevManifest(manifestJson(sha = "deadbeef")))
    }

    // --- update decision -------------------------------------------------

    @Test
    fun `update is available when the manifest versionCode is newer`() {
        val m = parseDevManifest(manifestJson(versionCode = "8"))!!
        assertTrue(isDevUpdateAvailable(m, 7))
    }

    @Test
    fun `no update when versionCode is equal or older`() {
        val m = parseDevManifest(manifestJson(versionCode = "8"))!!
        assertFalse(isDevUpdateAvailable(m, 8))
        assertFalse(isDevUpdateAvailable(m, 9))
    }

    // --- channel persistence ---------------------------------------------

    @Test
    fun `channel defaults to DEV when unset`() {
        assertEquals(AppUpdateChannel.DEV, AppUpdateChannel.load(memStore()))
    }

    @Test
    fun `channel defaults to DEV on unrecognized stored value`() {
        val store = memStore()
        store.putString(AppUpdateChannel.KEY, "BETA")
        assertEquals(AppUpdateChannel.DEV, AppUpdateChannel.load(store))
    }

    @Test
    fun `channel round-trips through the key-value store`() {
        val store = memStore()
        AppUpdateChannel.save(store, AppUpdateChannel.STABLE)
        assertEquals(AppUpdateChannel.STABLE, AppUpdateChannel.load(store))
        AppUpdateChannel.save(store, AppUpdateChannel.DEV)
        assertEquals(AppUpdateChannel.DEV, AppUpdateChannel.load(store))
    }

    // --- checker ---------------------------------------------------------

    private fun checker(
        manifest: String = manifestJson(),
        code: Int = 200,
        downloadBytes: ByteArray = apkBytes,
    ): Pair<DevUpdateChecker, FakeHttpClient> {
        val manifestHttp = FakeHttpClient(
            getHandler = { jsonResponse(code, manifest) },
        )
        val assetHttp = FakeHttpClient(
            downloadHandler = { _, dest ->
                dest.parentFile?.mkdirs()
                dest.writeBytes(downloadBytes)
            },
        )
        return DevUpdateChecker(manifestHttp, assetHttp) to manifestHttp
    }

    @Test
    fun `check fetches the dev manifest URL`() {
        val (c, http) = checker()
        c.check(7)
        assertEquals(listOf(DEV_MANIFEST_URL), http.requested)
    }

    @Test
    fun `check returns the manifest when newer`() {
        val (c, _) = checker()
        val m = c.check(7)
        assertNotNull(m)
        assertEquals(8, m!!.versionCode)
        assertEquals("1.2.1-u2", m.versionName)
    }

    @Test
    fun `check returns null when current or older`() {
        val (c, _) = checker()
        assertNull(c.check(8))
        assertNull(c.check(9))
    }

    @Test
    fun `check throws on HTTP error`() {
        val (c, _) = checker(code = 404)
        try {
            c.check(7)
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `check throws on malformed manifest`() {
        val (c, _) = checker(manifest = "not json")
        try {
            c.check(7)
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `check pins the APK URL to the manager repo`() {
        val (c, _) = checker(
            manifest = manifestJson(apkUrl = "https://evil.example/x.apk"),
        )
        try {
            c.check(7)
            fail("expected SecurityException")
        } catch (_: SecurityException) {
        }
    }

    @Test
    fun `download keeps the APK when the checksum matches`() {
        val (c, _) = checker()
        val m = c.check(7)!!
        val dest = File.createTempFile("dev-apk", ".apk")
        try {
            c.download(m, dest) { _, _ -> }
            assertTrue(dest.isFile)
            assertArrayEquals(apkBytes, dest.readBytes())
        } finally {
            dest.delete()
        }
    }

    @Test
    fun `download deletes the APK and throws on checksum mismatch`() {
        val (c, _) = checker(downloadBytes = byteArrayOf(0x50, 0x4B, 0x00))
        val m = c.check(7)!!
        val dest = File.createTempFile("dev-apk", ".apk")
        try {
            c.download(m, dest) { _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("checksum"))
        } finally {
            assertFalse("mismatched APK must be deleted", dest.exists())
        }
    }

    // --- sha256Hex -------------------------------------------------------

    @Test
    fun `sha256Hex matches the known vector`() {
        val f = File.createTempFile("sha", ".bin")
        try {
            f.writeBytes("abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256Hex(f),
            )
        } finally {
            f.delete()
        }
    }
}
