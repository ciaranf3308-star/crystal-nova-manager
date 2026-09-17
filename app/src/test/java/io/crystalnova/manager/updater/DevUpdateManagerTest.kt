package io.crystalnova.manager.updater

import io.crystalnova.manager.data.DevUpdateChecker
import io.crystalnova.manager.data.FakeHttpClient
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.SelfUpdateChecker
import io.crystalnova.manager.data.jsonResponse
import io.crystalnova.manager.data.sha256Hex
import io.crystalnova.manager.storage.InMemoryThemeFs
import io.crystalnova.manager.storage.SafThemeStorage
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
import java.io.File
import java.io.IOException

/**
 * The DEV / CANDIDATE channel rides the existing app-update flow:
 * checkAppUpdate surfaces AppUpdateState.Available from the dev
 * manifest, and downloadAppUpdate lands a checksum-verified APK that
 * the unchanged install handoff picks up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevUpdateManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fs: InMemoryThemeFs
    private lateinit var prefs: MutableMap<String, String>

    private val apkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x09, 0x09)
    private val apkSha: String by lazy {
        val f = File.createTempFile("shacalc", ".bin")
        try {
            f.writeBytes(apkBytes)
            sha256Hex(f)
        } finally {
            f.delete()
        }
    }

    private fun manifestJson(versionCode: Int): String = """{
      "versionName": "1.2.1-u2",
      "versionCode": $versionCode,
      "apkSha256": "$apkSha",
      "apkUrl": "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/dev-latest/crystal-nova-manager-dev.apk",
      "commitSha": "22c6923176a312841a4824db59e1df7d035ac805",
      "builtAt": "2026-09-16T21:00:00Z"
    }"""

    /** Real DevUpdateChecker over fakes: the verify path is production code. */
    private fun devUpdate(
        manifestCode: Int,
        servedBytes: ByteArray = apkBytes,
        failCheck: Boolean = false,
    ): DevUpdateChecker = DevUpdateChecker(
        manifestHttp = FakeHttpClient(
            getHandler = {
                if (failCheck) throw IOException("no route to host")
                jsonResponse(200, manifestJson(manifestCode))
            },
        ),
        assetHttp = FakeHttpClient(
            downloadHandler = { _, dest ->
                dest.parentFile?.mkdirs()
                dest.writeBytes(servedBytes)
            },
        ),
    )

    private fun TestScope.newManager(devUpdate: DevUpdateChecker): Pair<UpdateManager, File> {
        val store = object : io.crystalnova.manager.data.KeyValueStore {
            override fun getString(key: String): String? = prefs[key]
            override fun putString(key: String, value: String?) {
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
            override fun remove(key: String) {
                prefs.remove(key)
            }
        }
        // Stable checker must never fire: any call is a routing bug.
        val neverStable = object : SelfUpdateChecker(
            FakeHttpClient(getHandler = { throw IOException("stable check must not run") }),
            FakeHttpClient(),
        ) {
            override fun check(currentVersion: String) =
                throw IOException("stable check must not run")
        }
        // Theme network always fails — the app flow is the subject.
        val github = GitHubRepository(FakeHttpClient(
            getHandler = { throw IOException("no route to host") },
        ))
        val workDir = tmp.newFolder("work").apply { mkdirs() }
        // No channel key in prefs: the DEV default is what routes here.
        val manager = UpdateManager(
            storage = SafThemeStorage(fs, store),
            github = github,
            workDir = workDir,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            appVersion = "1.2.1-u2",
            appVersionCode = 7,
            prefs = store,
            selfUpdate = neverStable,
            devUpdate = devUpdate,
        )
        return manager to workDir
    }

    @Before
    fun setUp() {
        fs = InMemoryThemeFs()
        prefs = mutableMapOf(SafThemeStorage.KEY_TREE_URI to "content://fake/tree")
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")
    }

    @Test
    fun `dev update is offered through the normal Available state`() = runTest {
        val (manager, _) = newManager(devUpdate(manifestCode = 8))
        advanceUntilIdle()
        val state = manager.appUpdate.value as AppUpdateState.Available
        assertEquals("1.2.1-u2 (8)", state.info.version)
        assertEquals("dev-latest", state.info.tag)
        assertTrue(state.info.apkUrl.endsWith("crystal-nova-manager-dev.apk"))
    }

    @Test
    fun `no dev update when the manifest versionCode is not newer`() = runTest {
        val (manager, _) = newManager(devUpdate(manifestCode = 7))
        advanceUntilIdle()
        assertEquals(AppUpdateState.Idle(false), manager.appUpdate.value)
    }

    @Test
    fun `dev download lands a checksum-verified APK`() = runTest {
        val (manager, _) = newManager(devUpdate(manifestCode = 8))
        advanceUntilIdle()
        manager.downloadAppUpdate()
        advanceUntilIdle()
        val state = manager.appUpdate.value as AppUpdateState.Downloaded
        assertTrue(state.file.isFile)
        assertArrayEquals(apkBytes, state.file.readBytes())
    }

    @Test
    fun `dev download ignores a stale cached APK from the same versionName`() = runTest {
        val (manager, workDir) = newManager(devUpdate(manifestCode = 8))
        // Leftover from an older build sharing this manifest's
        // versionName ("1.2.1-u2") — the old name-only cache key would
        // have reused it forever, reinstalling the stale build.
        val stale = File(workDir, "manager-update-1.2.1-u2.apk")
            .apply { writeBytes(byteArrayOf(0x09, 0x09, 0x09)) }
        advanceUntilIdle()
        assertTrue(manager.appUpdate.value is AppUpdateState.Available)
        manager.downloadAppUpdate()
        advanceUntilIdle()
        val state = manager.appUpdate.value as AppUpdateState.Downloaded
        assertEquals("manager-update-dev-8.apk", state.file.name)
        assertArrayEquals(apkBytes, state.file.readBytes())
        assertFalse("stale cache entry was not pruned", stale.exists())
    }

    @Test
    fun `dev download fails when the checksum mismatches`() = runTest {
        val (manager, _) = newManager(
            devUpdate(manifestCode = 8, servedBytes = byteArrayOf(0x50, 0x4B)),
        )
        advanceUntilIdle()
        manager.downloadAppUpdate()
        advanceUntilIdle()
        val state = manager.appUpdate.value as AppUpdateState.Failed
        assertEquals("DOWNLOAD FAILED: Dev APK checksum mismatch", state.message)
    }

    @Test
    fun `failed dev check offers retry without touching theme state`() = runTest {
        val (manager, _) = newManager(devUpdate(manifestCode = 8, failCheck = true))
        advanceUntilIdle()
        assertEquals(AppUpdateState.Idle(lastCheckFailed = true), manager.appUpdate.value)
        val theme = manager.state.value as ManagerState.Ready
        assertEquals("COULD NOT CHECK FOR UPDATES", theme.notice)
    }
}
