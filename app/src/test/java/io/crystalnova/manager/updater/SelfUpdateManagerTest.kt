package io.crystalnova.manager.updater

import io.crystalnova.manager.data.FakeHttpClient
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.data.SelfUpdateChecker
import io.crystalnova.manager.data.SelfUpdateInfo
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
 * The self-updater runs on its own flow: app checks, downloads, and
 * failures never disturb the theme state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfUpdateManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fs: InMemoryThemeFs
    private lateinit var prefs: MutableMap<String, String>

    private val newerRelease = SelfUpdateInfo(
        version = "1.0.2-u1",
        tag = "v1.0.2-u1",
        apkUrl = "https://github.com/ciaranf3308-star/crystal-nova-manager/releases/download/v1.0.2-u1/crystal-nova-manager-u1.2.apk",
    )

    private class FakeSelfUpdate(
        private val info: SelfUpdateInfo? = null,
        private val failCheck: Boolean = false,
    ) : SelfUpdateChecker(FakeHttpClient(), FakeHttpClient()) {
        override fun check(currentVersion: String): SelfUpdateInfo? {
            if (failCheck) throw IOException("no route to host")
            return info
        }

        override fun download(
            info: SelfUpdateInfo,
            dest: File,
            onProgress: (Long, Long?) -> Unit,
        ) {
            dest.parentFile?.mkdirs()
            // Minimal ZIP magic so the APK sanity check passes.
            dest.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            onProgress(dest.length(), dest.length())
        }
    }

    @Before
    fun setUp() {
        fs = InMemoryThemeFs()
        // These tests pin the STABLE channel: they verify the
        // releases/latest flow, whose default is DEV since the
        // DEV / CANDIDATE channel shipped.
        prefs = mutableMapOf(
            SafThemeStorage.KEY_TREE_URI to "content://fake/tree",
            AppUpdateChannel.KEY to AppUpdateChannel.STABLE.name,
        )
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")
    }

    private fun TestScope.newManager(
        appVersion: String = "1.0.1-u1",
        selfUpdate: SelfUpdateChecker = FakeSelfUpdate(newerRelease),
    ): Pair<UpdateManager, File> {
        val store = object : io.crystalnova.manager.data.KeyValueStore {
            override fun getString(key: String): String? = prefs[key]
            override fun putString(key: String, value: String?) {
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
            override fun remove(key: String) {
                prefs.remove(key)
            }
        }
        // Theme network always fails here — the point is that the theme
        // state machine behaves independently of the app check.
        val github = GitHubRepository(FakeHttpClient(
            getHandler = { throw IOException("no route to host") },
        ))
        val workDir = tmp.newFolder("work").apply { mkdirs() }
        val manager = UpdateManager(
            storage = SafThemeStorage(fs, store),
            github = github,
            workDir = workDir,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            appVersion = appVersion,
            prefs = store,
            selfUpdate = selfUpdate,
        )
        return manager to workDir
    }

    @Test
    fun `app update is offered when the release is newer`() = runTest {
        val (manager, _) = newManager()
        advanceUntilIdle()
        val state = manager.appUpdate.value as AppUpdateState.Available
        assertEquals("1.0.2-u1", state.info.version)
        assertNull(state.notice)
    }

    @Test
    fun `no app update when current`() = runTest {
        val (manager, _) = newManager(
            appVersion = "1.0.2-u1",
            selfUpdate = FakeSelfUpdate(info = null),
        )
        advanceUntilIdle()
        assertEquals(AppUpdateState.Idle(false), manager.appUpdate.value)
    }

    @Test
    fun `failed app check offers retry without touching theme state`() = runTest {
        val (manager, _) = newManager(selfUpdate = FakeSelfUpdate(failCheck = true))
        advanceUntilIdle()
        assertEquals(AppUpdateState.Idle(lastCheckFailed = true), manager.appUpdate.value)
        // The theme machine still reached Ready on its own terms.
        val theme = manager.state.value as ManagerState.Ready
        assertEquals("COULD NOT CHECK FOR UPDATES", theme.notice)
    }

    @Test
    fun `download lands a Downloaded state with the versioned APK`() = runTest {
        val (manager, _) = newManager()
        advanceUntilIdle()
        manager.downloadAppUpdate()
        advanceUntilIdle()
        val state = manager.appUpdate.value as AppUpdateState.Downloaded
        assertTrue(state.file.isFile)
        assertTrue(state.file.name.contains("1.0.2-u1"))
    }

    @Test
    fun `download deletes a stale versioned APK`() = runTest {
        val (manager, workDir) = newManager()
        advanceUntilIdle()
        val stale = File(workDir, "manager-update-1.0.1-u1.apk")
        stale.writeBytes(byteArrayOf(0x50, 0x4B))
        manager.downloadAppUpdate()
        advanceUntilIdle()
        assertFalse("stale APK must be deleted", stale.exists())
        val downloaded = manager.appUpdate.value as AppUpdateState.Downloaded
        assertTrue(downloaded.file.isFile)
    }

    @Test
    fun `install-permission notice returns to Available with guidance`() = runTest {
        val (manager, _) = newManager()
        advanceUntilIdle()
        manager.downloadAppUpdate()
        advanceUntilIdle()
        assertTrue(manager.appUpdate.value is AppUpdateState.Downloaded)
        manager.noteAppNeedsInstallPermission("ALLOW X, THEN TAP UPDATE APP AGAIN")
        val state = manager.appUpdate.value as AppUpdateState.Available
        assertEquals("1.0.2-u1", state.info.version)
        assertEquals("ALLOW X, THEN TAP UPDATE APP AGAIN", state.notice)
    }
}
