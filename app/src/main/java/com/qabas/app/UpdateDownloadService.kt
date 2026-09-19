package com.qabas.app

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * خدمة أمامية لتنزيل التحديثات: تُبقي التنزيل حيّاً في الخلفية
 * (إشعار دائم + WakeLock) حتى يكتمل أو يُلغيه المستخدم فقط.
 */
class UpdateDownloadService : Service() {

    companion object {
        const val ACTION_START = "com.qabas.app.UPDATE_START"
        const val ACTION_CANCEL = "com.qabas.app.UPDATE_CANCEL"
        const val EXTRA_INFO = "info_json"
        const val EXTRA_FORCE_FULL = "force_full"
        private const val CHANNEL_ID = "qabas_updates"
        private const val NOTIF_ID = 2001
        private const val WAKE_TAG = "Qabas:UpdateDownload"

        fun start(context: Context, info: UpdateManager.UpdateInfo, forceFull: Boolean = false) {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INFO, Json.encodeToString(UpdateManager.UpdateInfo.serializer(), info))
                putExtra(EXTRA_FORCE_FULL, forceFull)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, UpdateDownloadService::class.java).apply { action = ACTION_CANCEL }
            )
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeKey: String? = null
    private var lastPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                activeKey?.let { UpdateManager.cancelDownload(it) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val json = intent.getStringExtra(EXTRA_INFO) ?: return START_NOT_STICKY
                val forceFull = intent.getBooleanExtra(EXTRA_FORCE_FULL, false)
                val info = runCatching {
                    Json.decodeFromString(UpdateManager.UpdateInfo.serializer(), json)
                }.getOrNull() ?: return START_NOT_STICKY
                startDownload(info, forceFull)
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(info: UpdateManager.UpdateInfo, forceFull: Boolean) {
        activeKey = "${info.versionName}|${info.versionCode}"
        lastPercent = -1
        ensureChannel()
        acquireWakeLock()
        startForegroundWithType(NOTIF_ID, buildNotification("بدء تنزيل التحديث…", null))
        scope.launch(Dispatchers.IO) {
            UpdateManager.downloadAndInstall(
                this@UpdateDownloadService, info,
                onProgress = { p -> updateNotification(p) },
                onDone = { ok, msg -> finishDownload(ok, msg) },
                forceFull = forceFull
            )
        }
    }

    private fun updateNotification(p: UpdateManager.DownloadProgress) {
        if (p.percent == lastPercent) return
        lastPercent = p.percent
        val label = when (p.phase) {
            UpdateManager.Phase.DOWNLOADING_DELTA -> "تنزيل تحديث صغير ${p.percent}٪"
            UpdateManager.Phase.APPLYING_PATCH -> "تطبيق التحديث الصغير…"
            UpdateManager.Phase.DOWNLOADING_FULL -> "تنزيل التحديث الكامل ${p.percent}٪"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(label, p.percent))
    }

    private fun finishDownload(ok: Boolean, msg: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val done = buildDoneNotification(if (ok) "اكتمل التنزيل" else "تعذّر التنزيل", msg)
        nm?.notify(NOTIF_ID, done)
        stopForeground(STOP_FOREGROUND_DETACH)
        releaseWakeLock()
        activeKey = null
        stopSelf()
    }

    private fun buildNotification(text: String, percent: Int?): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, UpdateDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("تحديث قبس")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء", cancelIntent)
        if (percent != null) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun buildDoneNotification(title: String, text: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "تحديثات قبس", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "تقدّم تنزيل تحديثات التطبيق"
                }
            )
        }
    }

    private fun startForegroundWithType(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                id, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(id, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        activeKey?.let { UpdateManager.cancelDownload(it) }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
