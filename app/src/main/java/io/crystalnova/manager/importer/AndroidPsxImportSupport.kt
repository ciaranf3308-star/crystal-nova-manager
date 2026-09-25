package io.crystalnova.manager.importer

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Production [PsxImportSupport].
 *
 * Temp lives in the app-private cache dir: chdman needs real POSIX
 * paths (SAF DocumentFile paths are unusable), and partial
 * conversion debris must never sit inside the live ROM folder. The
 * converter is the bundled `libchdman.so` in the native-library dir
 * (extracted thanks to `android:extractNativeLibs="true"`).
 */
class AndroidPsxImportSupport(private val context: Context) : PsxImportSupport {

    private fun cacheDir(): File = context.cacheDir

    override fun newTempDir(itemId: String): File {
        val dir = File(cacheDir(), "psx-$itemId")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    override fun converter(): ChdConverter? {
        val runner = ChdmanRunner(File(context.applicationInfo.nativeLibraryDir))
        return if (runner.available) runner else null
    }

    override fun tempFreeBytes(): Long {
        return try {
            StatFs(cacheDir().path).availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    override fun cleanStaleTempDirs() {
        try {
            cacheDir().listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("psx-") }
                ?.forEach { runCatching { it.deleteRecursively() } }
        } catch (_: Exception) {
        }
    }
}
