package com.hermeswebui.android.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R

/**
 * `dataSync` foreground service whose only job is keeping the process, and so the WebView renderer
 * running the official Webapp, foreground-ranked while the activity is hidden. It holds no socket
 * and no state: `WebappGatewayCoordinator` starts it and stops it.
 */
class HermesKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                ongoingNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (error: IllegalStateException) {
            // Android 15+ refuses here once the dataSync budget is spent.
            Log.w(TAG, "Keep-alive could not enter the foreground", error)
            stopSelf()
        } catch (error: SecurityException) {
            Log.w(TAG, "Keep-alive could not enter the foreground", error)
            stopSelf()
        }
        // A restarted service without the activity's WebView would keep nothing alive.
        return START_NOT_STICKY
    }

    /** Android 15+: the per-app dataSync budget ran out. Stop now and say so once, quietly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        postPausedNotice()
        stopSelf()
    }

    @Deprecated("Android 14 variant; Android 15+ calls onTimeout(startId, fgsType).")
    override fun onTimeout(startId: Int) {
        onTimeout(startId, 0)
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hermes is staying connected")
            .setContentText("Your open Hermes session keeps running in the background")
            .setContentIntent(openAppIntent(this))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setColor(ContextCompat.getColor(this, R.color.brand_sky))
            .build()

    @SuppressLint("MissingPermission")
    private fun postPausedNotice() {
        if (!canPostNotifications(this)) return
        val notice = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Background connection paused")
            .setContentText("Android limits how long apps stay connected. Open Hermes to resume.")
            .setContentIntent(openAppIntent(this))
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setColor(ContextCompat.getColor(this, R.color.brand_sky))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(PAUSED_NOTIFICATION_ID, notice) }
    }

    companion object {
        const val CHANNEL_ID = "hermes_webapp_keepalive"
        const val CHANNEL_GROUP_ID = "hermes_webapp"
        const val NOTIFICATION_ID = 20_021
        const val PAUSED_NOTIFICATION_ID = 20_022
        const val ACTION_OPEN_APP = "com.hermeswebui.android.action.OPEN_WEBAPP"
        private const val TAG = "HermesKeepAliveService"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Hermes keeps the official Webapp connected in the background"
                group = CHANNEL_GROUP_ID
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        /** Brings the existing task forward. It never carries a URL: a reload would drop the live socket. */
        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_APP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        /** Returns false when Android refused the start (for example a start from the background). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, HermesKeepAliveService::class.java))
            true
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Keep-alive start refused", error)
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "Keep-alive start refused", error)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesKeepAliveService::class.java))
        }
    }
}
