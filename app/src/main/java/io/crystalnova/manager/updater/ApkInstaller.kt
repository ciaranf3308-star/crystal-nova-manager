package io.crystalnova.manager.updater

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to Android's system package installer.
 *
 * Sideloaded apps cannot install silently — the system always shows its
 * own install prompt, and on Android 8+ the user must first allow
 * "install unknown apps" for this app (a one-time toggle in Settings).
 * The install only succeeds when the APK is signed with the same
 * certificate as the installed app, which is why CI signs every release
 * build with the persistent Crystal Nova keystore.
 */
class ApkInstaller(private val activity: ComponentActivity) {

    sealed interface Result {
        /** The system installer UI is now showing. */
        data object Started : Result

        /** Caller should send the user to the unknown-sources Settings page. */
        data object NeedsPermission : Result

        data class Failed(val message: String) : Result
    }

    fun install(apk: File): Result {
        if (!apk.isFile || apk.length() == 0L) {
            return Result.Failed("UPDATE FILE IS MISSING — PLEASE RETRY")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            return Result.NeedsPermission
        }
        return try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            Result.Started
        } catch (e: Exception) {
            Result.Failed("COULD NOT OPEN INSTALLER")
        }
    }

    companion object {
        /** Fired when [Result.NeedsPermission] is returned. */
        fun unknownSourcesIntent(packageName: String): Intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
    }
}
