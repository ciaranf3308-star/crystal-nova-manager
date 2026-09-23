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
}
