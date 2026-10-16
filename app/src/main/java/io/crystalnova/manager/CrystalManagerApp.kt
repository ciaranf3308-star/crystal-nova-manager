package io.crystalnova.manager

import android.app.Application
import io.crystalnova.manager.importer.ImporterGraph

/**
 * Application holder for the importer graph. The [ImporterGraph]
 * owns the import engine on a process-wide coroutine scope so an
 * import run (and its foreground service) survives the Activity
 * being backgrounded or recreated.
 */
class CrystalManagerApp : Application() {
    val importerGraph: ImporterGraph by lazy { ImporterGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // u75: one-time 3DS ROM folder correction ("3ds" -> "n3ds").
        // Runs on Dispatchers.IO inside the graph; never throws.
        importerGraph.runStartupMigrations()
    }
}
