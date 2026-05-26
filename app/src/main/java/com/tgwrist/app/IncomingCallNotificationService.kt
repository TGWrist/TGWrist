package com.tgwrist.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.tgwrist.app.notification.Push
import com.tgwrist.app.utils.isSamsungDevice

/**
 * 后台收到来电时使用的轻量前台服务。
 *
 * 使用 foregroundServiceType=shortService，该类型不需要运行时权限，
 * 可在 FCM 高优先级通知的豁免窗口内合法启动，targetSdk=36 也不会崩溃。
 * 仅负责展示"有人在呼叫你"通知，引导用户打开 App。
 *
 * 当用户真正接听（[CallForegroundService] 启动）后，本服务会被停止。
 */
class IncomingCallNotificationService : Service() {

    companion object {
        private const val TAG = "IncomingCallNotifSvc"

        const val ACTION_START = "com.tgwrist.app.action.INCOMING_CALL_START"
        const val ACTION_STOP  = "com.tgwrist.app.action.INCOMING_CALL_STOP"
        const val EXTRA_TITLE  = "incoming_call_title"
        const val EXTRA_TEXT   = "incoming_call_text"

        const val NOTIFICATION_ID = 1003
        const val CHANNEL_ID      = "tg_wear_incoming_call_channel"

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, IncomingCallNotificationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, IncomingCallNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE)
                    ?: getString(R.string.Call_notification_default_title)
                val text = intent.getStringExtra(EXTRA_TEXT)
                    ?: getString(R.string.Call_incoming_open_app)
                ensureForeground(title, text)
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground(title: String, text: String) {
        if (isForegroundStarted) return

        // 创建基础的 Builder
        val builder = buildNotification(title, text)

        // 在 build() 之前，一次性注入 Wear OS 绿标配置
        val status = Status.Builder()
            .addTemplate(title)
            .build()
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(if (isSamsungDevice()) R.drawable.ic_call_png else R.drawable.ic_call)
            .setStatus(status)
            .setTouchIntent(openAppPendingIntent())
            .build()
            .apply(this) // 将配置写入 builder

        // 构建通知，并打上持续震动/响铃的标签 (FLAG_INSISTENT)
        val notification = builder.build().apply {
            this.flags = this.flags or Notification.FLAG_INSISTENT
        }

        // 一次性启动前台服务，同步出现绿标
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Push.setIncomingCallServiceActive(this, true)
        isForegroundStarted = true
        Log.d(TAG, "Incoming call notification foreground started with insistent alerts")
    }

    private fun buildNotification(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(openAppPendingIntent(), true)

    private fun openAppPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            // 与 MainActivity.onNewIntent 中的 openCallPage extra 保持一致
            putExtra("openCallPage", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        return PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.Incoming_call_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.Incoming_call_notification_channel_description)
            enableVibration(true)
            setShowBadge(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        Push.setIncomingCallServiceActive(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
