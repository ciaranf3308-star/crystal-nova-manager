package io.crystalnova.manager.updater

import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.data.DevUpdateManifest
import io.crystalnova.manager.data.FakeHttpClient
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.data.SelfUpdateChecker
import io.crystalnova.manager.data.jsonResponse
import io.crystalnova.manager.storage.InMemoryThemeFs
import io.crystalnova.manager.storage.SafThemeStorage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The manager drives Crystal Launcher installs/updates through its own
 * DEV-channel machinery. These tests pin the launcher manifest URL to a
 * fake HTTP client so no test touches the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherUpdateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fs: InMemoryThemeFs
    private lateinit var prefs: MutableMap<String, String>

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

    private fun fakeChecker(
        manifest: DevUpdateManifest?,
        downloadBytes: ByteArray = "apk".toByteArray(),
    ) = object : LauncherUpdateChecker() {
        override fun check(installedVersionCode: Int?): DevUpdateManifest? = manifest
        override fun download(
            manifest: DevUpdateManifest,
            dest: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            dest.writeBytes(downloadBytes)
            onProgress(downloadBytes.size.toLong(), downloadBytes.size.toLong())
        }
    }

    private fun manifest(versionCode: Int = 6) = DevUpdateManifest(
        versionName = "0.3.0-beta4",
        versionCode = versionCode,
        apkSha256 = "b".repeat(64),
        apkUrl = "https://github.com/ciaranf3308-star/crystal-launcher/releases/download/dev-latest/crystal-nova-launcher-0.3.0-beta4.apk",
        commitSha = "c".repeat(40),
        builtAt = "2026-09-19T21:47:15Z",
    )

    private fun TestScope.newManager(checker: LauncherUpdateChecker): UpdateManager {
        val store = object : KeyValueStore {
            override fun getString(key: String): String? = prefs[key]
            override fun putString(key: String, value: String?) {
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
            override fun remove(key: String) { prefs.remove(key) }
        }
        val noAppUpdate = object : SelfUpdateChecker(FakeHttpClient(), FakeHttpClient()) {
            override fun check(currentVersion: String) = null
        }
        // Theme flow needs a seeded theme dir; the launcher flow never
        // touches it.
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")
        return UpdateManager(
            storage = SafThemeStorage(fs, store),
            github = GitHubRepository(FakeHttpClient()),
            workDir = tmp.newFolder("work").apply { mkdirs() },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            prefs = store,
            selfUpdate = noAppUpdate,
            launcherUpdateChecker = checker,
        )
    }

    @Before
    fun setUp() {
        fs = InMemoryThemeFs()
        prefs = mutableMapOf(
            SafThemeStorage.KEY_TREE_URI to "content://fake/tree",
            AppUpdateChannel.KEY to AppUpdateChannel.STABLE.name,
        )
    }

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
            downloadHandler = { _, dest -> dest.writeBytes(bytes) },
        )
        val checker = LauncherUpdateChecker(dev = io.crystalnova.manager.data.DevUpdateChecker(assetHttp = http))
        val dest = tmp.newFile("launcher.apk")
        checker.download(manifest().copy(apkSha256 = sha), dest) { _, _ -> }
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test
    fun `sha mismatch deletes the apk and throws`() {
        val http = FakeHttpClient(
            downloadHandler = { _, dest -> dest.writeBytes("tampered".toByteArray()) },
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

    // UpdateManager launcher flow -------------------------------------------------

    @Test
    fun `manager check surfaces launcher update as available`() = runTest {
        val manager = newManager(fakeChecker(manifest()))
        advanceUntilIdle()
        manager.checkLauncherUpdate(4)
        advanceUntilIdle()
        val state = manager.launcherUpdate.value
        assertTrue("expected Available, was $state", state is AppUpdateState.Available)
        assertTrue(manager.launcherInstalled)
        val info = (state as AppUpdateState.Available).info
        assertTrue(info.version.contains("0.3.0-beta4"))
    }

    @Test
    fun `manager check with absent launcher offers install`() = runTest {
        val manager = newManager(fakeChecker(manifest()))
        advanceUntilIdle()
        manager.checkLauncherUpdate(null)
        advanceUntilIdle()
        val state = manager.launcherUpdate.value
        assertTrue("expected Available, was $state", state is AppUpdateState.Available)
        assertFalse(manager.launcherInstalled)
    }

    @Test
    fun `manager check with current launcher stays idle`() = runTest {
        val manager = newManager(fakeChecker(null))
        advanceUntilIdle()
        manager.checkLauncherUpdate(6)
        advanceUntilIdle()
        assertTrue(manager.launcherUpdate.value is AppUpdateState.Idle)
        assertTrue(manager.launcherInstalled)
    }

    @Test
    fun `manager download caches the launcher apk`() = runTest {
        val manager = newManager(fakeChecker(manifest()))
        advanceUntilIdle()
        manager.checkLauncherUpdate(4)
        advanceUntilIdle()
        manager.downloadLauncherUpdate()
        advanceUntilIdle()
        val state = manager.launcherUpdate.value
        assertTrue("expected Downloaded, was $state", state is AppUpdateState.Downloaded)
        val file = (state as AppUpdateState.Downloaded).file
        assertTrue(file.name.startsWith("launcher-update-"))
        assertTrue(file.length() > 0)
    }

    @Test
    fun `backing out of the installer recovers to downloaded`() = runTest {
        val manager = newManager(fakeChecker(manifest()))
        advanceUntilIdle()
        manager.checkLauncherUpdate(4)
        advanceUntilIdle()
        manager.downloadLauncherUpdate()
        advanceUntilIdle()
        manager.noteLauncherInstallStarted()
        assertTrue(manager.launcherUpdate.value is AppUpdateState.Installing)
        manager.noteLauncherInstallAborted()
        val state = manager.launcherUpdate.value
        assertTrue("expected Downloaded, was $state", state is AppUpdateState.Downloaded)
    }

    @Test
    fun `manager and launcher apk caches never share a filename`() = runTest {
        val manager = newManager(fakeChecker(manifest()))
        advanceUntilIdle()
        manager.checkLauncherUpdate(4)
        advanceUntilIdle()
        manager.downloadLauncherUpdate()
        advanceUntilIdle()
        val file = (manager.launcherUpdate.value as AppUpdateState.Downloaded).file
        assertFalse("launcher apk must not collide with the manager apk cache",
            file.name.startsWith("manager-update-") || file.name == "update.apk")
    }
}
