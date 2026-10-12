package com.panda.pkgbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service (tipo dataSync) que hospeda el [DownloadEngine]:
 * notificación con progreso + wake lock parcial para que la descarga
 * sobreviva con la pantalla apagada.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.panda.pkgbrowser.action.START"
        const val ACTION_PAUSE = "com.panda.pkgbrowser.action.PAUSE"
        const val ACTION_CANCEL = "com.panda.pkgbrowser.action.CANCEL"
        const val EXTRA_ID = "download_id"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "pkgbrowser_downloads"

        fun startDownload(ctx: Context, id: Long) = sendAction(ctx, ACTION_START, id)
        fun pauseDownload(ctx: Context, id: Long) = sendAction(ctx, ACTION_PAUSE, id)
        fun cancelDownload(ctx: Context, id: Long) = sendAction(ctx, ACTION_CANCEL, id)

        private fun sendAction(ctx: Context, action: String, id: Long) {
            val intent = Intent(ctx, DownloadService::class.java)
                .setAction(action)
                .putExtra(EXTRA_ID, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private val engineListener = object : DownloadEngine.Listener {
        override fun onProgress(id: Long, downloaded: Long, total: Long) {
            updateNotification()
        }

        override fun onStateChanged(id: Long, state: DownloadState) {
            updateNotification()
            if (DownloadEngine.activeCount() == 0) stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PkgBrowser:Download").apply {
            acquire(30 * 60 * 1000L)
        }
        DownloadEngine.addListener(engineListener)
        startForegroundCompat(buildNotification("PkgBrowser listo", null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> if (id >= 0) {
                DownloadEngine.start(this, id)
                updateNotification()
            }
            ACTION_PAUSE -> if (id >= 0) DownloadEngine.pause(this, id)
            ACTION_CANCEL -> if (id >= 0) DownloadEngine.cancel(this, id)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DownloadEngine.removeListener(engineListener)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Descargas",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, progress: Int?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PkgBrowser")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
        if (progress != null) {
            b.setProgress(100, progress, false)
        }
        return b.build()
    }

    private fun updateNotification() {
        val db = DownloadDb(this)
        try {
            val active = db.listItems().firstOrNull {
                it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
            }
            val notif = if (active != null) {
                buildNotification(
                    "${active.fileName} · ${active.progressPercent}%",
                    active.progressPercent
                )
            } else {
                buildNotification("Sin descargas activas", null)
            }
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, notif)
        } finally {
            db.close()
        }
    }
}
