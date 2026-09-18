package io.crystalnova.manager.updater

import io.crystalnova.manager.data.FakeHttpClient
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.data.jsonResponse
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fs: InMemoryThemeFs
    private lateinit var prefs: MutableMap<String, String>
    private lateinit var storage: SafThemeStorage
    private lateinit var work: java.io.File

    private val remoteSha = "abc1234def5678abc1234def5678abc1234def56"
    private val remoteVersionJson =
        """{"version":"2.1.0","commit":"$remoteSha","channel":"stable"}"""

    private fun themeZipBytes(version: String, commit: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            val files = mapOf(
                "theme.cfg" to "[theme]",
                "theme.qml" to "import QtQuick",
                "crystal-version.json" to
                    """{"version":"$version","commit":"$commit","channel":"stable"}""",
                "components/a.qml" to "x",
                "screens/b.qml" to "x",
                "assets/c.png" to "png",
                "fonts/f.otf" to "font",
            )
            files.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry("crystal-nova-pegasus-theme-main/$name"))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun github(
        sha: String = remoteSha,
        versionJson: String? = remoteVersionJson,
        zipBytes: ByteArray? = null,
        failNetwork: Boolean = false,
    ): GitHubRepository {
        val http = FakeHttpClient(
            getHandler = { url ->
                if (failNetwork) throw IOException("no route to host")
                when {
                    "commits" in url -> jsonResponse(200, """{"sha":"$sha"}""")
                    "crystal-version.json" in url ->
                        if (versionJson == null) jsonResponse(404, "x")
                        else jsonResponse(200, versionJson)
                    else -> throw IOException("unexpected $url")
                }
            },
            downloadHandler = { _, dest ->
                if (failNetwork) throw IOException("no route to host")
                dest.writeBytes(zipBytes ?: ByteArray(0))
            },
        )
        return GitHubRepository(http)
    }

    private fun TestScope.newManager(gh: GitHubRepository): UpdateManager {
        val store = object : io.crystalnova.manager.data.KeyValueStore {
            override fun getString(key: String): String? = prefs[key]
            override fun putString(key: String, value: String?) {
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
            override fun remove(key: String) {
                prefs.remove(key)
            }
        }
        storage = SafThemeStorage(fs, store)
        // Theme tests never touch the network for the manager app itself:
        // a fake self-update checker keeps the app flow deterministic.
        val noAppUpdate = object : io.crystalnova.manager.data.SelfUpdateChecker(
            io.crystalnova.manager.data.FakeHttpClient(),
            io.crystalnova.manager.data.FakeHttpClient(),
        ) {
            override fun check(currentVersion: String) = null
        }
        return UpdateManager(
            storage = storage,
            github = gh,
            workDir = tmp.newFolder("work").apply { mkdirs() }.also { work = it },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            prefs = store,
            selfUpdate = noAppUpdate,
        )
    }

    @Before
    fun setUp() {
        fs = InMemoryThemeFs()
        // Theme tests never touch the network for the manager app
        // itself; pin STABLE so the default DEV channel (shipped with
        // the DEV / CANDIDATE channel) can't fire a real check here.
        prefs = mutableMapOf(
            SafThemeStorage.KEY_TREE_URI to "content://fake/tree",
            AppUpdateChannel.KEY to AppUpdateChannel.STABLE.name,
        )
        fs.seedTheme(SafThemeStorage.THEME_DIR_NAME, "2.0.0", "8a86a06")
    }

    @Test
    fun `check reports update available when remote is newer`() = runTest {
        val manager = newManager(github())
        advanceUntilIdle()
        val state = manager.state.value as ManagerState.Ready
        assertTrue(state.updateAvailable)
        assertEquals("2.1.0", state.latest!!.version)
        assertEquals("2.0.0", state.installed!!.version)
    }

    @Test
    fun `check reports up to date when SHAs match`() = runTest {
        val gh = github(
            sha = "8a86a06",
            versionJson = """{"version":"2.0.0","commit":"8a86a06","channel":"stable"}""",
        )
        val manager = newManager(gh)
        advanceUntilIdle()
        val state = manager.state.value as ManagerState.Ready
        assertFalse(state.updateAvailable)
        assertNull(state.notice)
    }

    @Test
    fun `network failure keeps the app open with installed version and retry`() = runTest {
        val manager = newManager(github(failNetwork = true))
        advanceUntilIdle()
        val state = manager.state.value as ManagerState.Ready
        assertEquals("COULD NOT CHECK FOR UPDATES", state.notice)
        assertEquals("2.0.0", state.installed!!.version)
        // Installed theme untouched.
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
        assertFalse(storage.hasBackup())
    }

    @Test
    fun `full update flow installs the new theme and keeps a backup`() = runTest {
        val manager = newManager(github(zipBytes = themeZipBytes("2.1.0", remoteSha)))
        advanceUntilIdle()

        manager.onEvent(ManagerEvent.StartUpdate)
        advanceUntilIdle()

        val done = manager.state.value as ManagerState.UpdateDone
        assertEquals("2.1.0", done.version.version)
        assertEquals("2.1.0", storage.readInstalledVersion()!!.version)
        assertEquals("2.0.0", storage.readBackupVersion()!!.version)
        // Installed SHA recorded for future comparisons.
        assertEquals(remoteSha, storage.installedSha())
    }

    @Test
    fun `invalid zip fails the update and the old theme stays live`() = runTest {
        val badZip = ByteArrayOutputStream().also {
            ZipOutputStream(it).use { zos ->
                zos.putNextEntry(ZipEntry("crystal-nova-pegasus-theme-main/theme.qml"))
                zos.write("x".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        val manager = newManager(github(zipBytes = badZip))
        advanceUntilIdle()

        manager.onEvent(ManagerEvent.StartUpdate)
        advanceUntilIdle()

        val failed = manager.state.value as ManagerState.UpdateFailed
        assertEquals("INVALID THEME PACKAGE", failed.message)
        assertTrue(failed.restored)
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
        assertFalse(storage.hasBackup())
    }

    @Test
    fun `failed download fails the update without touching the theme`() = runTest {
        val http = FakeHttpClient(
            getHandler = { url ->
                when {
                    "commits" in url -> jsonResponse(200, """{"sha":"$remoteSha"}""")
                    else -> jsonResponse(200, remoteVersionJson)
                }
            },
            downloadHandler = { _, _ -> throw IOException("connection reset") },
        )
        val manager = newManager(GitHubRepository(http))
        advanceUntilIdle()

        manager.onEvent(ManagerEvent.StartUpdate)
        advanceUntilIdle()

        val failed = manager.state.value as ManagerState.UpdateFailed
        assertEquals("FAILED AT DOWNLOADING: connection reset", failed.message)
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
    }

    @Test
    fun `rollback after update restores the previous version`() = runTest {
        val manager = newManager(github(zipBytes = themeZipBytes("2.1.0", remoteSha)))
        advanceUntilIdle()
        manager.onEvent(ManagerEvent.StartUpdate)
        advanceUntilIdle()
        assertEquals("2.1.0", storage.readInstalledVersion()!!.version)

        manager.onEvent(ManagerEvent.StartRollback)
        advanceUntilIdle()

        val done = manager.state.value as ManagerState.RollbackDone
        assertEquals("2.0.0", done.version!!.version)
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
    }

    @Test
    fun `legacy install without a marker is offered the update, not an error`() = runTest {
        // The Nova's manual Phase 1.7 install: real theme, no marker.
        fs = InMemoryThemeFs()
        fs.seedLegacyTheme(SafThemeStorage.THEME_DIR_NAME)
        val manager = newManager(github(zipBytes = themeZipBytes("2.0.0", remoteSha)))
        advanceUntilIdle()

        val state = manager.state.value as ManagerState.Ready
        assertTrue(state.updateAvailable)
        assertTrue(state.installed!!.isLegacy)
        assertNull(state.notice)

        manager.onEvent(ManagerEvent.StartUpdate)
        advanceUntilIdle()

        // Bootstrapped: the new theme carries the marker, the legacy
        // install is kept as the one backup.
        val done = manager.state.value as ManagerState.UpdateDone
        assertEquals("2.0.0", done.version.version)
        assertEquals("2.0.0", storage.readInstalledVersion()!!.version)
        assertTrue(storage.hasBackup())
        assertNull(storage.readBackupVersion())
    }

    @Test
    fun `legacy install stays usable offline with retry`() = runTest {
        fs = InMemoryThemeFs()
        fs.seedLegacyTheme(SafThemeStorage.THEME_DIR_NAME)
        val manager = newManager(github(failNetwork = true))
        advanceUntilIdle()

        val state = manager.state.value as ManagerState.Ready
        assertEquals("COULD NOT CHECK FOR UPDATES", state.notice)
        assertTrue(state.installed!!.isLegacy)
        assertFalse(state.updateAvailable)
    }

    @Test
    fun `needs folder state when no SAF permission was ever granted`() = runTest {
        prefs.remove(SafThemeStorage.KEY_TREE_URI)
        fs.rootAvailable = false
        val manager = newManager(github())
        advanceUntilIdle()
        assertTrue(manager.state.value is ManagerState.NeedsFolder)
    }

    @Test
    fun `aborted install with a cached APK returns to Downloaded`() = runTest {
        val manager = newManager(github())
        val apk = java.io.File(work, "manager-update-1.2.3-u2.apk")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        manager.noteAppInstallStarted()
        assertTrue(manager.appUpdate.value is AppUpdateState.Installing)
        manager.noteAppInstallAborted()
        val state = manager.appUpdate.value
        assertTrue(state is AppUpdateState.Downloaded)
        assertEquals(apk, (state as AppUpdateState.Downloaded).file)
    }

    @Test
    fun `aborted install without a cached APK returns to Idle`() = runTest {
        val manager = newManager(github())
        manager.noteAppInstallStarted()
        manager.noteAppInstallAborted()
        assertTrue(manager.appUpdate.value is AppUpdateState.Idle)
    }

    @Test
    fun `aborted install is a no-op when not installing`() = runTest {
        val manager = newManager(github())
        // init kicks off a check, so the state is Checking — the point
        // is that noteAppInstallAborted leaves any non-Installing state
        // untouched.
        val before = manager.appUpdate.value
        assertFalse(before is AppUpdateState.Installing)
        manager.noteAppInstallAborted()
        assertEquals(before, manager.appUpdate.value)
    }
}
