package io.crystalnova.manager.diag

import android.content.Context
import android.os.Build
import android.util.Log
import io.crystalnova.manager.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Persists the stack trace of an uncaught crash so it can be read back
 * from the hidden Diagnostics screen after relaunch. The previous
 * default handler still runs afterwards, so the system crash dialog
 * behaves exactly as before — this only leaves a trace behind.
 *
 * The file is overwritten on every crash, so it always holds the most
 * recent one; the header stamps the build it crashed on.
 */
object CrashReporter {
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 16 * 1024

    fun install(context: Context) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
                val header = "$stamp v${BuildConfig.VERSION_NAME} " +
                    "(code ${BuildConfig.VERSION_CODE}) " +
                    "${Build.MANUFACTURER} ${Build.MODEL} " +
                    "API ${Build.VERSION.SDK_INT}\n"
                File(appContext.filesDir, FILE_NAME)
                    .writeText((header + Log.getStackTraceString(throwable)).take(MAX_CHARS))
            } catch (_: Exception) {
                // Never interfere with the crash itself.
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    /** The most recent crash trace, or null when there hasn't been one. */
    fun readTrace(context: Context): String? =
        try {
            File(context.filesDir, FILE_NAME)
                .takeIf { it.isFile && it.length() > 0 }
                ?.readText()
        } catch (_: Exception) {
            null
        }
}
