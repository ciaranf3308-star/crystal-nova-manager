package io.crystalnova.manager.updater

import io.crystalnova.manager.data.GitHubRepository
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
) {
    private val _state = MutableStateFlow<ManagerState>(ManagerState.NeedsFolder())
    val state: StateFlow<ManagerState> = _state

    private var job: Job? = null

    init {
        refresh()
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
                        // Fall back to what we know from the remote marker.
                        remote.version ?: throw IOException("Installed theme has no version marker"),
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
                _state.value = ManagerState.UpdateFailed("DOWNLOAD FAILED", restored = true)
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
}
