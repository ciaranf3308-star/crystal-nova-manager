package io.crystalnova.manager.updater

import io.crystalnova.manager.BuildConfig
import io.crystalnova.manager.data.AppUpdateChannel
import io.crystalnova.manager.data.DevUpdateChecker
import io.crystalnova.manager.data.DevUpdateManifest
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.data.SelfUpdateChecker
import io.crystalnova.manager.data.SelfUpdateInfo
import io.crystalnova.manager.data.StorageException
import io.crystalnova.manager.data.ThemeStorage
import io.crystalnova.manager.storage.SafThemeStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Orchestrates check → download → validate → install → verify.
 *
 * Nothing destructive happens before validation succeeds: the ZIP is
 * downloaded to app-private cache, extracted to a private temp dir,
 * validated, and only then handed to [ThemeStorage.installValidatedTheme],
 * which itself stages into `.new` before touching the live theme.
 * A failed network check never modifies the installed theme.
 */
class UpdateManager(
    private val storage: ThemeStorage,
    private val github: GitHubRepository,
    private val workDir: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val appVersionCode: Int = BuildConfig.VERSION_CODE,
    private val prefs: KeyValueStore? = null,
    private val selfUpdate: SelfUpdateChecker = SelfUpdateChecker(),
    private val devUpdate: DevUpdateChecker = DevUpdateChecker(),
) {
    private val _state = MutableStateFlow<ManagerState>(ManagerState.NeedsFolder())
    val state: StateFlow<ManagerState> = _state

    /**
     * Self-update state for the manager app itself. A separate flow on
     * purpose: the theme state machine above is untouched by app checks,
     * downloads, or failures.
     */
    private val _appUpdate = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle())
    val appUpdate: StateFlow<AppUpdateState> = _appUpdate

    private var job: Job? = null
    private var appUpdateJob: Job? = null

    /** The release the app-update flow is currently working with. */
    private var pendingSelfUpdate: SelfUpdateInfo? = null

    /**
     * The dev manifest the app-update flow is currently working with.
     * Non-null exactly when the pending update came from the DEV /
     * CANDIDATE channel, in which case the download is checksum-verified
     * against it before the installer sees the APK.
     */
    private var pendingDevManifest: DevUpdateManifest? = null

    /** Reads the persisted app update channel; unset means DEV. */
    private fun appUpdateChannel(): AppUpdateChannel =
        prefs?.let(AppUpdateChannel::load) ?: AppUpdateChannel.DEV

    init {
        refresh()
        checkAppUpdate()
    }

    fun onEvent(event: ManagerEvent) {
        when (event) {
            ManagerEvent.CheckNow -> checkForUpdates()
            ManagerEvent.StartUpdate -> startUpdate()
            ManagerEvent.StartRollback -> startRollback()
            ManagerEvent.OpenPegasus -> { /* handled by the activity */ }
            ManagerEvent.Dismiss -> _state.value = readyState()
        }
    }

    /** Re-reads folder access (e.g. after the SAF picker returns). */
    fun refresh(folderNotice: String? = null) {
        job?.cancel()
        if (!storage.hasFolderAccess()) {
            _state.value = ManagerState.NeedsFolder(folderNotice)
            return
        }
        // U1.1 regression: a nested install left by the U1 folder-pick
        // wording is repaired automatically before anything else runs.
        var notice: String? = null
        try {
            val s = storage as? SafThemeStorage
            if (s != null && s.hasNestedInstall() && s.fixNestedInstall()) {
                notice = "REPAIRED THEME LOCATION"
            }
        } catch (_: Exception) {
        }
        _state.value = readyState(checking = true, notice = notice)
        checkForUpdates(preserveNotice = notice)
    }

    private fun currentReady(): ManagerState.Ready =
        (_state.value as? ManagerState.Ready) ?: readyState()

    /**
     * Builds the Ready state from storage. The installed display prefers
     * the SHA recorded at install time (authoritative) over the marker's
     * commit (content baseline). A theme directory without a marker is a
     * valid legacy install, shown as such — never an error.
     */
    private fun readyState(
        checking: Boolean = false,
        notice: String? = null,
        updateAvailable: Boolean? = null,
        latest: VersionDisplay? = null,
    ): ManagerState.Ready {
        val prev = _state.value as? ManagerState.Ready
        val installedVersion = storage.readInstalledVersion()
        val recordedSha = (storage as? SafThemeStorage)?.installedSha()
            ?.takeIf { it.isNotBlank() }
        val installed = installedVersion?.let { v ->
            VersionDisplay(v.version, recordedSha?.take(7) ?: v.shortCommit)
        } ?: if (storage.hasThemeDir()) VersionDisplay.legacy() else null
        val backupVersion = storage.readBackupVersion()
        val backup = backupVersion?.let { v ->
            // A backup predates our SHA recording; its marker is authoritative.
            VersionDisplay.of(v)
        } ?: if (storage.hasBackup()) VersionDisplay.legacy() else null
        return ManagerState.Ready(
            installed = installed,
            latest = latest ?: prev?.latest,
            updateAvailable = updateAvailable ?: prev?.updateAvailable ?: false,
            backup = backup,
            checking = checking,
            notice = notice,
            destination = (storage as? SafThemeStorage)?.installDestinationLabel(),
        )
    }

    fun checkForUpdates(preserveNotice: String? = null) {
        if (!storage.hasFolderAccess()) {
            _state.value = ManagerState.NeedsFolder()
            return
        }
        _state.value = currentReady().copy(checking = true, notice = preserveNotice)
        job?.cancel()
        job = scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    val remote = github.fetchLatest()
                    val installed = storage.readInstalledVersion()
                    val installedSha = (storage as? SafThemeStorage)?.installedSha()
                    remote to UpdateDecider.decide(
                        installed = installed,
                        installedSha = installedSha,
                        remoteSha = remote.sha,
                        remoteVersion = remote.version,
                    )
                }
            }
            result
                .onSuccess { (remote, decision) ->
                    _state.value = when (decision) {
                        is UpdateDecider.Decision.UpToDate -> readyState(
                            updateAvailable = false,
                            latest = remote.version?.let(VersionDisplay::of)
                                ?: VersionDisplay("?", remote.sha.take(7)),
                        )
                        is UpdateDecider.Decision.UpdateAvailable -> readyState(
                            updateAvailable = true,
                            latest = remote.version?.let(VersionDisplay::of)
                                ?: VersionDisplay("?", remote.sha.take(7)),
                        )
                        is UpdateDecider.Decision.Unknown -> readyState(notice = decision.reason)
                    }
                }
                .onFailure {
                    // Offline / GitHub unreachable: still open, show installed, offer retry.
                    _state.value = readyState(notice = "COULD NOT CHECK FOR UPDATES")
                }
        }
    }

    fun startUpdate() {
        val ready = _state.value as? ManagerState.Ready ?: return
        if (!ready.updateAvailable || ready.checking) return
        job?.cancel()
        job = scope.launch {
            try {
                // CHECKING — re-fetch latest so we install exactly what we checked.
                _state.value = ManagerState.Updating(Stage.CHECKING)
                val remote = withContext(ioDispatcher) { github.fetchLatest() }

                // DOWNLOADING — into app-private cache, real progress when available.
                _state.value = ManagerState.Updating(Stage.DOWNLOADING)
                val zipFile = File(workDir, "theme-${remote.sha.take(12)}.zip")
                withContext(ioDispatcher) {
                    github.downloadZip(zipFile) { done, total ->
                        _state.value = ManagerState.Updating(Stage.DOWNLOADING, done, total)
                    }
                }
                if (!zipFile.isFile || zipFile.length() == 0L) {
                    throw IOException("Downloaded file is missing or empty")
                }

                // VALIDATING — extract to a private temp dir, reject bad packages.
                _state.value = ManagerState.Updating(Stage.VALIDATING)
                val extractDir = File(workDir, "extract-${remote.sha.take(12)}")
                val themeRoot = withContext(ioDispatcher) {
                    ZipValidator.extract(zipFile, extractDir)
                }

                // INSTALLING — the safe .new/.backup dance.
                _state.value = ManagerState.Updating(Stage.INSTALLING)
                val report = withContext(ioDispatcher) {
                    storage.installValidatedTheme(themeRoot)
                }

                // VERIFYING — confirm the live theme now carries the new version.
                _state.value = ManagerState.Updating(Stage.VERIFYING)
                val installed = withContext(ioDispatcher) { storage.readInstalledVersion() }
                (storage as? SafThemeStorage)?.let { s ->
                    installed?.let { s.recordInstalled(it, remote.sha) }
                }

                _state.value = ManagerState.Updating(Stage.COMPLETE)
                _state.value = ManagerState.UpdateDone(
                    version = installed?.let(VersionDisplay::of) ?: VersionDisplay.of(
                        // Fall back to what we know from the remote marker,
                        // then to the validated theme source itself.
                        remote.version ?: readVersionFromThemeRoot(themeRoot)
                            ?: throw IOException("Installed theme has no version marker"),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidPackageException) {
                _state.value = ManagerState.UpdateFailed("INVALID THEME PACKAGE", restored = true)
            } catch (e: StorageException) {
                // The storage layer reports honestly whether the previous
                // theme survived; never second-guess it by sniffing text.
                _state.value = ManagerState.UpdateFailed(
                    message = e.message ?: "INSTALL FAILED",
                    restored = e.restored,
                )
            } catch (e: SecurityException) {
                _state.value = ManagerState.UpdateFailed("STORAGE ACCESS LOST", restored = true)
            } catch (e: IOException) {
                val stage = (_state.value as? ManagerState.Updating)?.stage
                val detail = e.message?.take(120) ?: "unknown I/O error"
                _state.value = ManagerState.UpdateFailed(
                    "FAILED AT ${stage ?: "UNKNOWN"}: $detail",
                    restored = true,
                )
            } catch (e: Exception) {
                _state.value = ManagerState.UpdateFailed(
                    "INSTALL FAILED — ${(e.message ?: "unknown error").uppercase()}",
                    restored = true,
                )
            } finally {
                withContext(ioDispatcher) {
                    workDir.listFiles()
                        ?.filter { it.name.startsWith("extract-") || it.name.endsWith(".zip") }
                        ?.forEach { it.deleteRecursively() }
                }
            }
        }
    }

    fun startRollback() {
        if (!storage.hasBackup()) return
        job?.cancel()
        job = scope.launch {
            _state.value = ManagerState.RollingBack(Stage.VALIDATING)
            try {
                _state.value = ManagerState.RollingBack(Stage.INSTALLING)
                val report = withContext(ioDispatcher) { storage.rollback() }
                _state.value = ManagerState.RollingBack(Stage.VERIFYING)
                _state.value = ManagerState.RollbackDone(
                    version = report.restoredVersion?.let(VersionDisplay::of),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                _state.value = ManagerState.RollbackFailed(e.message ?: "ROLLBACK FAILED")
            } catch (e: SecurityException) {
                _state.value = ManagerState.RollbackFailed("STORAGE ACCESS LOST")
            } catch (e: Exception) {
                _state.value = ManagerState.RollbackFailed(
                    "ROLLBACK FAILED — ${(e.message ?: "unknown error").uppercase()}",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Self-update: the manager app checking its own GitHub releases.
    // Runs on its own flow and its own job; theme state is never touched.
    // ------------------------------------------------------------------

    /**
     * Checks the manager's own releases for a newer build. On the DEV /
     * CANDIDATE channel this reads the rolling dev-latest manifest and
     * compares versionCode; on STABLE it uses the existing
     * releases/latest logic, byte-for-byte unchanged. A failed check
     * (offline, API hiccup) is silent apart from the tap-to-retry hint —
     * the theme check already reports connectivity problems.
     */
    fun checkAppUpdate() {
        if (_appUpdate.value is AppUpdateState.Downloading ||
            _appUpdate.value is AppUpdateState.Installing
        ) {
            return
        }
        appUpdateJob?.cancel()
        _appUpdate.value = AppUpdateState.Checking
        appUpdateJob = scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    when (appUpdateChannel()) {
                        AppUpdateChannel.DEV -> {
                            val manifest = devUpdate.check(appVersionCode)
                            if (manifest == null) {
                                pendingDevManifest = null
                                null
                            } else {
                                pendingDevManifest = manifest
                                // Surfaced through the exact same
                                // AppUpdateState flow as stable updates —
                                // the UPDATE APP button needs no changes.
                                // The build code is part of the display
                                // version: every DEV u-build shares the
                                // versionName, so the code is the only
                                // thing that tells them apart.
                                SelfUpdateInfo(
                                    version = "${manifest.versionName} (${manifest.versionCode})",
                                    tag = "dev-latest",
                                    apkUrl = manifest.apkUrl,
                                )
                            }
                        }
                        AppUpdateChannel.STABLE -> {
                            pendingDevManifest = null
                            selfUpdate.check(appVersion)
                        }
                    }
                }
            }
            result
                .onSuccess { info ->
                    _appUpdate.value = if (info == null) {
                        AppUpdateState.Idle()
                    } else {
                        AppUpdateState.Available(info)
                    }
                }
                .onFailure {
                    _appUpdate.value = AppUpdateState.Idle(lastCheckFailed = true)
                }
        }
    }

    /**
     * Downloads the available app update into app-private cache. A
     * completed download for the same version is reused; stale versions
     * are deleted so the installer can never pick up an old APK.
     * DEV-channel downloads are SHA-256-verified against the manifest
     * before the state flips to Downloaded.
     */
    fun downloadAppUpdate() {
        val available = _appUpdate.value as? AppUpdateState.Available ?: return
        appUpdateJob?.cancel()
        appUpdateJob = scope.launch {
            _appUpdate.value = AppUpdateState.Downloading()
            val info: SelfUpdateInfo = available.info
            pendingSelfUpdate = info
            val devManifest = pendingDevManifest
            // The cache key MUST include the DEV versionCode, not just
            // the versionName: several DEV builds share one versionName
            // ("1.2.3-u2") while the versionCode moves. Keying by name
            // alone reuses a stale APK forever — the device "installs"
            // the update but stays on the old build, and the same
            // update is offered again. Stable releases bump the
            // versionName per release, so the name stays a valid key.
            val destName = if (devManifest != null)
                "manager-update-dev-${devManifest.versionCode}.apk"
            else
                "manager-update-${info.version}.apk"
            val dest = File(workDir, destName)
            withContext(ioDispatcher) {
                workDir.listFiles()
                    ?.filter { it.name.startsWith("manager-update-") && it != dest }
                    ?.forEach { it.delete() }
            }
            if (dest.isFile && dest.length() > 0) {
                _appUpdate.value = AppUpdateState.Downloaded(dest)
                return@launch
            }
            val result = withContext(ioDispatcher) {
                runCatching {
                    if (devManifest != null) {
                        devUpdate.download(devManifest, dest) { done, total ->
                            _appUpdate.value = AppUpdateState.Downloading(done, total)
                        }
                    } else {
                        selfUpdate.download(info, dest) { done, total ->
                            _appUpdate.value = AppUpdateState.Downloading(done, total)
                        }
                    }
                }
            }
            result
                .onSuccess {
                    _appUpdate.value = AppUpdateState.Downloaded(dest)
                }
                .onFailure {
                    withContext(ioDispatcher) { dest.delete() }
                    // Surface the root cause (HTTP status, checksum
                    // mismatch, timeout, redirect rejection): a bare
                    // "failed" tells the user nothing and makes
                    // device-specific download issues undebuggable.
                    val cause = it.message?.takeIf { m -> m.isNotBlank() } ?: "UNKNOWN ERROR"
                    _appUpdate.value = AppUpdateState.Failed("DOWNLOAD FAILED: $cause")
                }
        }
    }

    /** The APK was handed to Android's system installer. */
    fun noteAppInstallStarted() {
        appUpdateJob?.cancel()
        _appUpdate.value = AppUpdateState.Installing
    }

    /**
     * The system installer gives no callback, and a completed install
     * kills this process — so if the activity resumes while still in
     * [AppUpdateState.Installing], the user backed out of the system
     * prompt and the install never happened. Drop back to
     * [AppUpdateState.Downloaded] when the APK is still cached (one tap
     * retries) or [AppUpdateState.Idle] otherwise, instead of stranding
     * the UI on "INSTALLING — FOLLOW THE SYSTEM PROMPT" forever.
     */
    fun noteAppInstallAborted() {
        if (_appUpdate.value !is AppUpdateState.Installing) return
        val cached = workDir.listFiles()
            ?.filter { it.name.startsWith("manager-update-") && it.isFile && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
        _appUpdate.value =
            if (cached != null) AppUpdateState.Downloaded(cached)
            else AppUpdateState.Idle()
    }

    /**
     * The system needs the one-time "install unknown apps" grant first.
     * Returns to [AppUpdateState.Available] with guidance; the downloaded
     * APK (if any) is kept, so the next tap skips straight to install.
     */
    fun noteAppNeedsInstallPermission(notice: String) {
        val info = pendingSelfUpdate ?: return
        appUpdateJob?.cancel()
        _appUpdate.value = AppUpdateState.Available(info, notice)
    }

    fun noteAppUpdateFailed(message: String) {
        _appUpdate.value = AppUpdateState.Failed(message)
    }

    /**
     * Reads the version from a validated theme source directory's
     * crystal-version.json. Used as a fallback when the installed theme
     * version can't be read and the remote version fetch failed.
     */
    private fun readVersionFromThemeRoot(themeRoot: File): VersionInfo? {
        return try {
            val versionFile = File(themeRoot, "crystal-version.json")
            if (!versionFile.isFile) {
                null
            } else {
                VersionInfo.parse(versionFile.readText(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
    }
}
