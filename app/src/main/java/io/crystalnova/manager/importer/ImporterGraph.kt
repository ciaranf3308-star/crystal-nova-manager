package io.crystalnova.manager.importer

import android.content.Context
import io.crystalnova.manager.data.PrefKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Process-wide importer wiring. Owned by [CrystalManagerApp] and
 * shared by the Activity UI and the [ImportService]: there is exactly
 * one [ImportEngine] per process, so the service and the screens can
 * never run two engines (and two import loops) at once.
 *
 * The [scope] is application-scoped — an import run keeps going when
 * the Activity is backgrounded or recreated. The foreground service
 * keeps the process alive; the engine's persisted queue keeps the
 * run honest across a process restart.
 */
class ImporterGraph(context: Context) {

    private val appContext = context.applicationContext

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val prefs = PrefKeyValueStore(
        appContext.getSharedPreferences("crystal-nova-manager", Context.MODE_PRIVATE),
    )

    val env: ImporterEnvironment = AndroidImporterEnvironment(appContext)

    val queue: ImportQueue =
        ImportQueue(File(appContext.filesDir, "importer/queue.json")).also { it.load() }

    val history: ImportHistoryRepository =
        ImportHistoryRepository(File(appContext.filesDir, "importer/history.json")).also { it.load() }

    val mapping: PlatformMapping = PlatformMapping(prefs)

    val settings: ImporterSettings = ImporterSettings(prefs)

    val engine: ImportEngine = ImportEngine(
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        env = env,
        queue = queue,
        inspector = ArchiveInspector(env.archiveOpener()),
        detector = PlatformDetector(),
        extractor = ArchiveExtractor(env.archiveOpener()),
        mapping = mapping,
        settings = settings,
        history = history,
    )
}
