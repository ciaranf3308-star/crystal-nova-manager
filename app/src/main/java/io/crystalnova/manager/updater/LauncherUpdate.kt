package io.crystalnova.manager.updater

import android.content.pm.PackageManager
import android.os.Build
import io.crystalnova.manager.data.DevManifestHttpClient
import io.crystalnova.manager.data.DevUpdateChecker
import io.crystalnova.manager.data.DevUpdateManifest
import io.crystalnova.manager.data.GitHubEndpoints
import io.crystalnova.manager.data.HttpClient
import io.crystalnova.manager.data.isDevUpdateAvailable
import io.crystalnova.manager.data.parseDevManifest
import java.io.File
import java.io.IOException

/** Package name of the Crystal Launcher app. */
const val LAUNCHER_PACKAGE = "io.crystalnova.launcher"

/**
 * The rolling dev-latest manifest for the Crystal Launcher, published
 * next to its APK on every launcher release. Same flat shape as the
 * manager's own dev manifest (versionName, versionCode, apkSha256,
 * apkUrl, commitSha, builtAt) so the same parser applies.
 */
const val LAUNCHER_MANIFEST_URL =
    "https://github.com/ciaranf3308-star/crystal-launcher/releases/download/dev-latest/manifest.json"

/**
 * Installed launcher versionCode, or null when the launcher isn't
 * installed. Null is not an error — it means the manager should offer
 * a first install instead of an update.
 */
fun installedLauncherVersionCode(pm: PackageManager): Int? =
    try {
        val info = pm.getPackageInfo(LAUNCHER_PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

/**
 * Lets the Manager drive Crystal Launcher installs and updates, so the
 * launcher never needs a manual sideload after its first one. Reuses
 * the manager's own DEV-channel machinery: manifest fetch, versionCode
 * comparison, SHA-256-verified download — only the manifest URL and
 * the installed-version source differ.
 */
open class LauncherUpdateChecker(
    private val manifestHttp: HttpClient = DevManifestHttpClient(),
    private val dev: DevUpdateChecker = DevUpdateChecker(),
) {
    /**
     * Returns the launcher manifest when the launcher should be
     * installed (not present) or updated (older versionCode), or null
     * when the installed launcher is current. Throws [IOException] on
     * network failure or a malformed manifest — the caller treats that
     * as "could not check", never as an update.
     */
    @Throws(IOException::class)
    open fun check(installedVersionCode: Int?): DevUpdateManifest? {
        // Cache-buster: GitHub's CDN aggressively caches the rolling
        // dev-latest manifest. Appending a timestamp forces a fresh fetch.
        val url = "$LAUNCHER_MANIFEST_URL?t=${System.currentTimeMillis()}"
        val resp = manifestHttp.get(url)
        if (resp.code != 200) throw IOException("Launcher manifest returned HTTP ${resp.code}")
        val manifest = parseDevManifest(resp.bodyText())
            ?: throw IOException("Malformed launcher manifest")
        // Pins the APK URL to the crystal-launcher repo, exactly like
        // the manager's own manifest pinning.
        GitHubEndpoints.checkAllowed(manifest.apkUrl)
        return if (installedVersionCode == null || isDevUpdateAvailable(manifest, installedVersionCode)) {
            manifest
        } else {
            null
        }
    }

    /**
     * Downloads the manifest's APK to [dest] and verifies its SHA-256
     * against the manifest before returning. A mismatch deletes the
     * file and throws — the installer never sees an unverified APK.
     */
    @Throws(IOException::class)
    open fun download(
        manifest: DevUpdateManifest,
        dest: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = dev.download(manifest, dest, onProgress)
}
