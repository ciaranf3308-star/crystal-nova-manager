package io.crystalnova.manager.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.crystalnova.manager.CrystalManagerApp
import io.crystalnova.manager.MainActivity
import io.crystalnova.manager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground wrapper for the game import run. Deliberately thin:
 * the [ImportEngine] owns every decision and the queue; this service
 * only keeps the process alive, shows the ongoing notification
 * (current game, N of M, stage), and forwards the cancel action.
 *
 * Started from the review screen's IMPORT ALL; stops itself when the
 * engine reaches Results / Error / Idle.
 */
class ImportService : Service() {

    companion object {
        const val ACTION_START = "io.crystalnova.manager.importer.action.START"
        const val ACTION_CANCEL = "io.crystalnova.manager.importer.action.CANCEL"

        private const val NOTIFICATION_ID = 41
        private const val CHANNEL_ID = "game_importer"

        fun start(context: Context) {
            val intent = Intent(context, ImportService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as CrystalManagerApp).importerGraph.engine
        if (intent?.action == ACTION_CANCEL) {
            engine.cancelAfterCurrent()
            return START_NOT_STICKY
        }

        createChannel()
        startForegroundNow(buildNotification(null, 0, 0))
        // The activity is the single start owner: it calls
        // engine.startImport() immediately before starting this
        // service, so the first uiState emission the collector below
        // sees is already Importing. Never start a run from here —
        // startImport() is idempotent but a second owner invites
        // races.

        if (!collecting) {
            collecting = true
            serviceScope.launch {
                engine.uiState.collect { state ->
                    when (state) {
                        is ImportUiState.Importing -> {
                            val current = state.current
                            startForegroundNow(
                                buildNotification(
                                    current,
                                    state.index,
                                    state.total,
                                ),
                            )
                        }
                        is ImportUiState.Results,
                        is ImportUiState.Error,
                        is ImportUiState.Idle,
                        -> stopSelf()
                        else -> Unit
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNow(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Game imports",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while game archives are imported"
            },
        )
    }

    private fun buildNotification(
        current: ImportingGame?,
        index: Int,
        total: Int,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "CANCEL AFTER CURRENT",
                cancel,
            )
        if (current == null || total <= 0) {
            builder.setContentTitle("Preparing game import…")
        } else {
            val count = "${(index + 1).coerceAtMost(total)} of $total"
            builder.setContentTitle("${current.title} — $count")
            val detail = current.detail ?: current.stage.label()
            builder.setContentText(detail)
            val progress = current.progress
            if (progress != null) {
                builder.setProgress(1000, (progress * 1000).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            builder.setSubText(current.platform.name)
        }
        return builder.build()
    }
}
