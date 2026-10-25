package com.selfmod.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.selfmod.agent.R

/**
 * Keeps the agent (and the work it kicks off — model loads, container pack
 * installs, long generations) alive when the user switches away. Without it the
 * system reaps the app's background process and a half-done task silently dies.
 *
 * Started with [start] before a long operation and [stop] once it finishes. The
 * type is `dataSync` because the work is network/storage bound, not a media or
 * phone-call foreground service.
 */
class AgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "ACFCN 后台任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "智能体长任务运行时的常驻通知" }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ACFCN 正在运行")
            .setContentText("智能体长任务进行中，切到后台也不会中断…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "acfcn_agent"
        private const val NOTIF_ID = 1001

        /** Best-effort: a missing permission or a dead context must not crash the caller. */
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, AgentService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, AgentService::class.java)) }
        }
    }
}
