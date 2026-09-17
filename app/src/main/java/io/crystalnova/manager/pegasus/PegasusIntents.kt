package io.crystalnova.manager.pegasus

import android.content.ComponentName
import android.content.Intent

/**
 * Pegasus launch intents — the v18 reload path.
 *
 * Research (Pegasus master @83fd27f, Qt 5.15.3-lts): NO programmatic
 * refresh exists. The manifest has a single `singleTop`
 * `.MainActivity` with only MAIN/LAUNCHER/LEANBACK_LAUNCHER filters —
 * no custom actions, no receivers, no exported services/providers, and
 * no onNewIntent/rescan API. The only refresh
 * (`Settings::reloadProviders()`) is QML-internal; nothing external
 * can trigger it. Do NOT attempt an intent-extra hack.
 *
 * VERIFIED reload path: launching the explicit component
 * `org.pegasus_frontend.android/.MainActivity` with
 * `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`. CLEAR_TASK
 * finishes the existing singleTop activity, which makes Qt's
 * `QtActivityDelegate.onDestroy()` call `System.exit(0)` — the process
 * dies (Pegasus declares exhaustive `android:configChanges`, so the
 * activity is never retained) — and Android starts the new instance in
 * a FRESH process: `main()` → `Backend::start()` →
 * `onScanRequested(scan_on_launch)`. With the default
 * `scan_on_launch=true` that forces a full rescan, which reads
 * `<config dir>/metafiles/*.metadata.pegasus.txt` — exactly where the
 * Manager writes. A plain launcher intent on the singleTop activity
 * merely resumes the stale task, which is why the post-build notice
 * used to lie.
 *
 * CAVEAT: if the user disabled Pegasus's "Scan for games every time
 * Pegasus starts" setting, a fresh launch takes the GameDataCache path
 * whose fingerprint does NOT cover `configDir/metafiles/`
 * recursively — the new metafile may not invalidate the cache and the
 * stale list could be restored. Pegasus's provider is exported=false,
 * so we cannot read its settings; the notice wording stays honest
 * ("RESTART & RELOAD") instead of pretending detection exists.
 *
 * The flag logic is pure ([PegasusRestartLaunch]) so unit tests assert
 * the flags without Robolectric — android.content.Intent is a stub on
 * the JVM. [PegasusRestartGate] tracks whether a fresh build is
 * pending: OPEN PEGASUS restarts only then, and keeps the plain resume
 * behavior otherwise (never kills the user's session needlessly).
 */
object PegasusIntents {
    /** Manifest package: `org.pegasus_frontend.android`. */
    const val PACKAGE = "org.pegasus_frontend.android"

    /** Full activity class name from the manifest package + `.MainActivity`. */
    const val MAIN_ACTIVITY = "org.pegasus_frontend.android.MainActivity"

    /** The verified restart flags. */
    val RESTART_FLAGS: Int =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    /** Pure restart-launch descriptor; JVM-testable without Android. */
    fun restartLaunch(): PegasusRestartLaunch = PegasusRestartLaunch()

    /** The real restart Intent; call from an Activity only. */
    fun restartIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        val launch = restartLaunch()
        component = ComponentName(launch.packageName, launch.className)
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(launch.flags)
    }
}

/** Pure descriptor for the verified Pegasus restart launch. */
data class PegasusRestartLaunch(
    val packageName: String = PegasusIntents.PACKAGE,
    val className: String = PegasusIntents.MAIN_ACTIVITY,
    val flags: Int = PegasusIntents.RESTART_FLAGS,
)

/**
 * Whether tapping OPEN PEGASUS should restart (a fresh build is
 * pending) or resume. [consumeRestart] returns true exactly once per
 * successful build. JVM-testable; MainActivity owns one instance.
 */
class PegasusRestartGate {
    var pendingBuild: Boolean = false

    /**
     * True when a fresh build was pending; clears the pending state in
     * either case.
     */
    fun consumeRestart(): Boolean {
        val pending = pendingBuild
        pendingBuild = false
        return pending
    }
}
