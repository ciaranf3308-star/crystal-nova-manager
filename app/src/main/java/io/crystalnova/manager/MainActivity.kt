package io.crystalnova.manager

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.crystalnova.manager.data.GitHubRepository
import io.crystalnova.manager.data.KeyValueStore
import io.crystalnova.manager.storage.SafThemeFs
import io.crystalnova.manager.storage.SafThemeStorage
import io.crystalnova.manager.ui.ThemeUpdateScreen
import io.crystalnova.manager.updater.ManagerEvent
import io.crystalnova.manager.updater.ManagerState
import io.crystalnova.manager.updater.UpdateManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import java.io.File

private class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Pegasus Frontend's Android package, verified against the official
         * source: src/app/platform/android/AndroidManifest.xml in
         * mmatyas/pegasus-frontend declares package="org.pegasus_frontend.android".
         * We never force-stop it and never assume the launch intent exists —
         * [isPegasusInstalled] checks at runtime and the UI falls back to
         * "RESTART PEGASUS TO APPLY" instructions.
         */
        const val PEGASUS_PACKAGE = "org.pegasus_frontend.android"
    }

    private lateinit var manager: UpdateManager
    private lateinit var storage: SafThemeStorage
    private val scope = MainScope()

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    storage.treeUri = uri.toString()
                } catch (_: SecurityException) {
                    // Picker granted nothing usable; stay on the onboarding screen.
                }
            }
            manager.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = SharedPrefsStore(getSharedPreferences("crystal-nova-manager", MODE_PRIVATE))
        val fs = SafThemeFs(this) { prefs.getString(SafThemeStorage.KEY_TREE_URI) }
        storage = SafThemeStorage(fs, prefs)
        manager = UpdateManager(
            storage = storage,
            github = GitHubRepository(),
            workDir = File(cacheDir, "updater").apply { mkdirs() },
            scope = scope,
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        setContent {
            val state by manager.state.collectAsState()
            ThemeUpdateScreen(
                state = state,
                onEvent = { event ->
                    if (event === ManagerEvent.OpenPegasus) openPegasus()
                    else manager.onEvent(event)
                },
                pegasusLaunchable = isPegasusInstalled(),
                onPickFolder = { folderPicker.launch(null) },
                onExit = { finish() },
            )
        }
    }

    private fun handleBack() {
        when (manager.state.value) {
            is ManagerState.Ready,
            is ManagerState.NeedsFolder,
            -> finish()
            else -> manager.onEvent(ManagerEvent.Dismiss)
        }
    }

    private fun isPegasusInstalled(): Boolean =
        packageManager.getLaunchIntentForPackage(PEGASUS_PACKAGE) != null

    private fun openPegasus() {
        packageManager.getLaunchIntentForPackage(PEGASUS_PACKAGE)?.let(::startActivity)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
