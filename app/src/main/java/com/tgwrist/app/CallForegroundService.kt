package com.tgwrist.app

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.tgwrist.app.CallForegroundService.Companion.start
import com.tgwrist.app.notification.Push
import com.tgwrist.app.utils.isSamsungDevice

/**
 * 通话期间维持 CPU 唤醒和系统通话特征的前台服务。
 *
 * 关注点：
 *  - 仅负责 WakeLock + 前台通知 + Wear OngoingActivity；
 *    AudioManager.mode 由 [com.tgwrist.app.runtime.TgCallManager] 在 VoIP 真正建立时管理，避免双方各自切换。
 *  - 多次 [start] 不会重启服务，只会更新通知文案。
 *  - 服务存在期间应当总是处于前台；onTimeout / onTaskRemoved 兜底退出。
 */
class CallForegroundService : Service() {

    companion object {
        private const val TAG = "CallForegroundService"

        const val ACTION_START_CALL = "com.tgwrist.app.action.START_CALL"
        const val ACTION_STOP_CALL = "com.tgwrist.app.action.STOP_CALL"
        const val ACTION_UPDATE_CALL = "com.tgwrist.app.action.UPDATE_CALL"
        const val EXTRA_TITLE = "call_title"
        const val EXTRA_TEXT = "call_text"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "tg_wear_call_channel"

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, title: String, text: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_UPDATE_CALL
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP_CALL
            }
            // 停止服务
            context.stopService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> ensureForeground(
                title = intent.getStringExtra(EXTRA_TITLE)
                    ?: getString(R.string.Call_notification_default_title),
                text = intent.getStringExtra(EXTRA_TEXT)
                    ?: getString(R.string.Tap_to_return_to_call)
            )

            ACTION_UPDATE_CALL -> {
                if (isForegroundStarted) {
                    val title = intent.getStringExtra(EXTRA_TITLE)
                        ?: getString(R.string.Call_notification_default_title)
                    val text = intent.getStringExtra(EXTRA_TEXT)
                        ?: getString(R.string.Tap_to_return_to_call)
                    postNotification(title, text)
                } else {
                    // 还没启动就被要求 update —— 等价于 start
                    ensureForeground(
                        title = intent.getStringExtra(EXTRA_TITLE)
                            ?: getString(R.string.Call_notification_default_title),
                        text = intent.getStringExtra(EXTRA_TEXT)
                            ?: getString(R.string.Tap_to_return_to_call)
                    )
                }
            }

            ACTION_STOP_CALL -> {
                stopCallService()
                return START_NOT_STICKY
            }
        }
        // 通话期间被系统杀掉时尝试重启；ACTION_STOP_CALL 已显式 return NOT_STICKY
        return START_STICKY
    }

    private fun ensureForeground(title: String, text: String) {
        if (isForegroundStarted) {
            postNotification(title, text)
            return
        }
        acquireWakeLock()

        val notification = buildNotification(title, text).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Push.setCallForegroundNotificationActive(this, true)
        // 表盘绿标
        setupWearOngoingActivity(title)
        isForegroundStarted = true
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWrist:CallCpuLock")
            .apply {
                setReferenceCounted(false)
                // 2 小时兜底，超过自动释放
                acquire(2L * 60L * 60L * 1000L)
            }
    }

    private fun returnPendingIntent(): PendingIntent {
        val returnIntent = Intent(this, MainActivity::class.java).apply {
            // 与 MainActivity.onNewIntent 中的 openCallPage extra 保持一致
            putExtra("openCallPage", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(returnPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)

    private fun postNotification(title: String, text: String) {
        val notification = buildNotification(title, text).build()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)

        Push.setCallForegroundNotificationActive(this, true)
    }

    private fun setupWearOngoingActivity(title: String) {
        val builder = buildNotification(title, getString(R.string.Tap_to_return_to_call))
        val status = Status.Builder()
            .addTemplate(title)
            .build()

        val ongoingActivity = OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(if (isSamsungDevice()) R.drawable.ic_call_png else R.drawable.ic_call)
            .setStatus(status)
            .setTouchIntent(returnPendingIntent())
            .build()

        ongoingActivity.apply(this)
        // OngoingActivity.apply 改写了 builder，需要重新 notify 一次确保 Wear 端识别
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, builder.build())
    }

    private fun stopCallService() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "wakeLock release failed", t)
            }
        }
        wakeLock = null

        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        Push.setCallForegroundNotificationActive(this, false)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.Call_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.Call_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        // 仅做兜底清理，不再 stopSelf 防止递归
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Throwable) {}
        }
        wakeLock = null
        Push.setCallForegroundNotificationActive(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
