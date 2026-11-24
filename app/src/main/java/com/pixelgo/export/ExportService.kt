package com.pixelgo.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import com.pixelgo.MainActivity
import com.pixelgo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps PixelGo running (with a progress notification) while [ExportJobs] exports, so a long
 * video export continues when you switch apps or the screen turns off. Nothing here uses the
 * network.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ExportJobs.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        startInForeground()
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelGo:export")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        }
        if (!watching) {
            watching = true
            scope.launch {
                snapshotFlow { ExportJobs.state }
                    .distinctUntilChanged()
                    .collect { s ->
                        if (s == null || s.done) {
                            finish(s)
                        } else {
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification(s))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val n = progressNotification(ExportJobs.state ?: ExportState("", "Exporting…"))
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun finish(s: ExportState?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (s != null) {
            val text = when {
                s.error != null -> "Export stopped: ${s.error}"
                else -> "Saved to ${s.resultPath}"
            }
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (s.error == null) "Export finished" else "Export didn't finish")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, n)
        }
        stopSelf()
    }

    private fun progressNotification(s: ExportState) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(s.title)
        .setContentText("${(s.progress * 100).toInt()}%")
        .setProgress(100, (s.progress * 100).toInt(), false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openAppIntent())
        .addAction(
            0, "Cancel",
            PendingIntent.getService(
                this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Exports", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of video exports"
                }
            )
        }
    }

    /** Android 15+: the system limits how long this kind of service may run. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ExportJobs.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "exports"
        private const val NOTIFICATION_ID = 41
        private const val DONE_NOTIFICATION_ID = 42
        private const val ACTION_CANCEL = "com.pixelgo.export.CANCEL"
    }
}
