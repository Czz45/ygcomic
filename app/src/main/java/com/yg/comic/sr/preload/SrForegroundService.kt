package com.yg.comic.sr.preload

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
import com.yg.comic.MainActivity
import com.yg.comic.R

/**
 * 后台预超分的前台服务：常驻低优先级通知 + PARTIAL_WAKE_LOCK。
 * 作用只有一个——让系统在用户切走 App 后别把进程冻结掉（真机实测冻结会让单页从 3 秒变 7 分钟）。
 * 不碰超分逻辑本身，只是保活 + 显示进度。
 */
class SrForegroundService : Service() {
    companion object {
        private const val TAG = "SrForegroundService"
        private const val CHANNEL_ID = "sr_preload"
        private const val NOTIF_ID = 20231124
        private const val ACTION_START = "start"
        private const val ACTION_PROGRESS = "progress"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"

        fun start(context: Context) = dispatch(context, ACTION_START, 0, 0)

        fun progress(context: Context, done: Int, total: Int) = dispatch(context, ACTION_PROGRESS, done, total)

        fun stop(context: Context) {
            context.stopService(Intent(context, SrForegroundService::class.java))
        }

        private fun dispatch(context: Context, action: String, done: Int, total: Int) {
            val i = Intent(context, SrForegroundService::class.java).setAction(action)
                .putExtra(EXTRA_DONE, done).putExtra(EXTRA_TOTAL, total)
            // Android 8+ 后台起服务必须用 startForegroundService，随后立即 startForeground。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // 30 分钟兜底超时，正常跑完在 onDestroy 主动 release，双保险不泄漏。
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YGComic::sr_preload")
            .apply { acquire(30 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PROGRESS -> showNotification(
                intent.getIntExtra(EXTRA_DONE, 0),
                intent.getIntExtra(EXTRA_TOTAL, 0)
            )
            else -> showNotification(0, 0) // ACTION_START 或空 intent
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "后台预超分", NotificationManager.IMPORTANCE_LOW)
                ch.description = "漫画后台预超分进度"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun showNotification(done: Int, total: Int) {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags)
        val text = if (total > 0) "超分中 $done/$total 页" else "准备中…"
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("YGComic 后台超分")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Android 10+ 起前台服务必须声明类型；dataSync 对应"后台数据同步"，与 Manifest 声明一致。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
