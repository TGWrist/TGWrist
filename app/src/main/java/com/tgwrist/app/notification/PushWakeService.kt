package com.tgwrist.app.notification

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tgwrist.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 收到高优先级 FCM 推送时立即启动的前台服务。
 *
 * 目的：在 Wear OS 深度休眠下，不依赖 WorkManager（可能被推迟到下一个维护窗口），
 * 而是在推送到达的当下——此时应用处于临时白名单，允许后台启动前台服务——
 * 立刻持有 WakeLock 并同步处理推送（TDLib 解密 / 拉取 UpdateCall / 弹出来电通知）。
 *
 * 处理完成（或通话结束）后自行停止。
 */
class PushWakeService : Service() {

    companion object {
        private const val TAG = "PushWakeService"
        const val ACTION_PROCESS = "com.tgwrist.app.action.PUSH_PROCESS"
        const val EXTRA_FCM_DATA = "fcm_data"
        const val NOTIFICATION_ID = 1005
        const val CHANNEL_ID = "tg_wear_push_processing"
        private const val WAKELOCK_TIMEOUT_MS = 10L * 60L * 1000L

        /**
         * 尝试启动前台服务。返回 false 表示系统拒绝（调用方应退回 WorkManager）。
         */
        fun tryStart(context: Context, fcmData: String): Boolean {
            val intent = Intent(context, PushWakeService::class.java).apply {
                action = ACTION_PROCESS
                putExtra(EXTRA_FCM_DATA, fcmData)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService refused: ${e.message}")
                false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        acquireWakeLock()

        val fcmData = intent?.getStringExtra(EXTRA_FCM_DATA)
        if (intent?.action != ACTION_PROCESS || fcmData.isNullOrBlank()) {
            maybeStop()
            return START_NOT_STICKY
        }

        inFlight.incrementAndGet()
        scope.launch {
            try {
                Log.d(TAG, "Processing push in foreground service")
                PushProcessor.process(fcmData)
            } catch (e: Exception) {
                Log.e(TAG, "Push processing failed", e)
            } finally {
                inFlight.decrementAndGet()
                maybeStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun maybeStop() {
        if (inFlight.get() <= 0) {
            Log.d(TAG, "No more work, stopping")
            stopSelf()
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.airplane_logo_white)
            .setContentTitle("TG Wrist")
            .setContentText("Syncing…")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGWrist:push").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Push processing",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        scope.cancel()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
