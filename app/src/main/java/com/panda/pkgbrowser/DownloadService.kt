package com.panda.pkgbrowser

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Foreground service (tipo dataSync) que hospeda el [DownloadEngine] y —
 * desde v1.1 — el puente persistente hacia la PS4.
 *
 * Bug v1.0: el puente (TinyHttpServer) vivía en un hilo suelto de la
 * actividad y se detenía en el `finally` a los segundos del 200 OK del RPI;
 * la PS4 se quedaba sin servidor a mitad de la descarga del PKG
 * ("No se ha podido descargar"). Ahora el servidor lo retiene este servicio
 * ([startBridge]/[stopBridge]) con notificación persistente y acción
 * ⏹ Detener, y solo se detiene cuando el usuario lo pide.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.panda.pkgbrowser.action.START"
        const val ACTION_PAUSE = "com.panda.pkgbrowser.action.PAUSE"
        const val ACTION_CANCEL = "com.panda.pkgbrowser.action.CANCEL"
        const val ACTION_START_BRIDGE = "com.panda.pkgbrowser.action.START_BRIDGE"
        const val ACTION_STOP_BRIDGE = "com.panda.pkgbrowser.action.STOP_BRIDGE"
        const val EXTRA_ID = "download_id"
        const val EXTRA_FILE_PATH = "bridge_file_path"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "pkgbrowser_downloads"
        private const val BRIDGE_PORT = 8080

        /** Estado observable para la UI (la actividad lo lee cada segundo). */
        @Volatile
        var bridgeActive: Boolean = false
            private set

        @Volatile
        var bridgeFileName: String? = null
            private set

        fun startDownload(ctx: Context, id: Long) = sendAction(ctx, ACTION_START, id)
        fun pauseDownload(ctx: Context, id: Long) = sendAction(ctx, ACTION_PAUSE, id)
        fun cancelDownload(ctx: Context, id: Long) = sendAction(ctx, ACTION_CANCEL, id)

        /** Pide al servicio que levante el puente sirviendo [file]. */
        fun startBridge(ctx: Context, file: File) {
            val intent = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_START_BRIDGE)
                .putExtra(EXTRA_FILE_PATH, file.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** Pide al servicio que detenga el puente. */
        fun stopBridge(ctx: Context) {
            val intent = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_STOP_BRIDGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

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
    private var bridgeServer: TinyHttpServer? = null

    private val engineListener = object : DownloadEngine.Listener {
        override fun onProgress(id: Long, downloaded: Long, total: Long) {
            updateNotification()
        }

        override fun onStateChanged(id: Long, state: DownloadState) {
            updateNotification()
            // No matar el servicio si el puente sigue activo: la PS4 lo necesita
            // durante toda su descarga, aunque aquí ya no haya descargas.
            if (DownloadEngine.activeCount() == 0 && bridgeServer == null) stopSelf()
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
            ACTION_START_BRIDGE -> {
                val path = intent?.getStringExtra(EXTRA_FILE_PATH)
                if (path != null) startBridgeInternal(File(path)) else updateNotification()
            }
            ACTION_STOP_BRIDGE -> stopBridgeInternal()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bridgeServer?.stop()
        bridgeServer = null
        bridgeActive = false
        bridgeFileName = null
        DownloadEngine.removeListener(engineListener)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Levanta (o reemplaza) el puente sirviendo [file] en :8080/pkg. */
    private fun startBridgeInternal(file: File) {
        try {
            bridgeServer?.stop()
            val server = TinyHttpServer(BRIDGE_PORT)
            server.fileToServe = file
            server.start()
            bridgeServer = server
            bridgeActive = true
            bridgeFileName = file.name
            ensureWakeLock()
        } catch (_: Exception) {
            bridgeServer?.stop()
            bridgeServer = null
            bridgeActive = false
            bridgeFileName = null
        }
        updateNotification()
    }

    /** Detiene el puente. Si no queda trabajo pendiente, deja morir el servicio. */
    private fun stopBridgeInternal() {
        bridgeServer?.stop()
        bridgeServer = null
        bridgeActive = false
        bridgeFileName = null
        updateNotification()
        if (DownloadEngine.activeCount() == 0) stopSelf()
    }

    /** El puente puede tardar horas (PKG de 17 GB): wake lock largo. */
    private fun ensureWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PkgBrowser:Bridge").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
    }

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

    /** Notificación persistente del puente, con acción ⏹ Detener. */
    private fun buildBridgeNotification(): Notification {
        val stopIntent = Intent(this, DownloadService::class.java)
            .setAction(ACTION_STOP_BRIDGE)
        val pi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌉 Puente activo")
            .setContentText(bridgeFileName ?: "")
            .setSubText("Detenlo cuando la PS4 termine")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "⏹ Detener", pi)
            .build()
    }

    private fun updateNotification() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = if (bridgeServer != null) {
            // El puente manda: debe seguir visible durante toda la descarga de la PS4.
            buildBridgeNotification()
        } else {
            val db = DownloadDb(this)
            try {
                val active = db.listItems().firstOrNull {
                    it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
                }
                if (active != null) {
                    buildNotification(
                        "${active.fileName} · ${active.progressPercent}%",
                        active.progressPercent
                    )
                } else {
                    buildNotification("Sin descargas activas", null)
                }
            } finally {
                db.close()
            }
        }
        mgr.notify(NOTIF_ID, notif)
    }
}
