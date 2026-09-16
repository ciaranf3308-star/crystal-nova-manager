package io.crystalnova.manager.updater

import io.crystalnova.manager.data.VersionInfo
import io.crystalnova.manager.data.compareVersions

/** Update progress stages shown in the UI. Never faked — each maps to real work. */
enum class Stage {
    CHECKING,
    DOWNLOADING,
    VALIDATING,
    INSTALLING,
    VERIFYING,
    COMPLETE,
}

data class VersionDisplay(val version: String, val shortCommit: String) {
    companion object {
        fun of(v: VersionInfo) = VersionDisplay(v.version, v.shortCommit)
        fun unknown() = VersionDisplay("?", "???????")

        /**
         * A theme directory exists but carries no crystal-version.json —
         * a manual pre-2.0 install. Valid, updatable, and (as a backup)
         * restorable; it simply has no machine-readable version.
         */
        fun legacy() = VersionDisplay("PRE-2.0", "LEGACY")
    }

    val isLegacy: Boolean get() = version == "PRE-2.0" && shortCommit == "LEGACY"
}

sealed interface ManagerState {
    /**
     * No themes/ folder chosen yet — first-run onboarding. Carries an
     * optional guidance message, e.g. when the user picked the theme
     * folder itself and needs to pick the themes/ parent instead.
     */
    data class NeedsFolder(val message: String? = null) : ManagerState

    data class Ready(
        val installed: VersionDisplay?,
        val latest: VersionDisplay?,
        val updateAvailable: Boolean,
        val backup: VersionDisplay?,
        val checking: Boolean = false,
        /** Non-destructive notice, e.g. COULD NOT CHECK FOR UPDATES. */
        val notice: String? = null,
        /** Resolved live-theme destination, shown before install. */
        val destination: String? = null,
    ) : ManagerState

    data class Updating(
        val stage: Stage,
        val downloadedBytes: Long = 0,
        val totalBytes: Long? = null,
    ) : ManagerState {
        /** Real percentage only when the server reported a length. */
        val progress: Float? =
            if (totalBytes != null && totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    data class UpdateFailed(val message: String, val restored: Boolean) : ManagerState
    data class UpdateDone(val version: VersionDisplay) : ManagerState
    data class RollingBack(val stage: Stage) : ManagerState
    data class RollbackDone(val version: VersionDisplay?) : ManagerState
    data class RollbackFailed(val message: String) : ManagerState
}

sealed interface ManagerEvent {
    data object CheckNow : ManagerEvent
    data object StartUpdate : ManagerEvent
    data object StartRollback : ManagerEvent
    data object OpenPegasus : ManagerEvent
    data object Dismiss : ManagerEvent
}

/**
 * Pure update-availability decision. Tested directly; the manager only
 * feeds it values.
 */
object UpdateDecider {
    sealed interface Decision {
        data object UpToDate : Decision
        data object UpdateAvailable : Decision
        /** e.g. GitHub unreachable — the app must still open. */
        data class Unknown(val reason: String) : Decision
    }

    fun decide(
        installed: VersionInfo?,
        installedSha: String?,
        remoteSha: String?,
        remoteVersion: VersionInfo?,
    ): Decision {
        if (remoteSha.isNullOrBlank()) return Decision.Unknown("COULD NOT CHECK FOR UPDATES")
        if (installed == null) return Decision.UpdateAvailable
        if (remoteVersion != null && compareVersions(remoteVersion.version, installed.version) != 0) {
            return Decision.UpdateAvailable
        }
        val knownInstalled = (installedSha ?: installed.commit).trim()
        if (knownInstalled.isEmpty()) return Decision.UpdateAvailable
        // Tolerate short-vs-full SHAs with prefix matching either way.
        val match = remoteSha.startsWith(knownInstalled) || knownInstalled.startsWith(remoteSha)
        return if (match) Decision.UpToDate else Decision.UpdateAvailable
    }
}

/**
 * Self-update state for the manager app itself. Lives in its own flow,
 * completely separate from the theme state machine — the theme updater
 * never sees this, and a failed app check never disturbs theme state.
 */
sealed interface AppUpdateState {
    /** Nothing pending. [lastCheckFailed] offers a tap-to-retry affordance. */
    data class Idle(val lastCheckFailed: Boolean = false) : AppUpdateState

    data object Checking : AppUpdateState

    data class Available(
        val info: io.crystalnova.manager.data.SelfUpdateInfo,
        /** Non-destructive notice, e.g. install-permission guidance. */
        val notice: String? = null,
    ) : AppUpdateState

    data class Downloading(
        val downloadedBytes: Long = 0,
        val totalBytes: Long? = null,
    ) : AppUpdateState {
        /** Real percentage only when the server reported a length. */
        val progress: Float? =
            if (totalBytes != null && totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    data class Downloaded(val file: java.io.File) : AppUpdateState

    /** The APK was handed to Android's system installer; it owns the UI now. */
    data object Installing : AppUpdateState

    data class Failed(val message: String) : AppUpdateState
}
