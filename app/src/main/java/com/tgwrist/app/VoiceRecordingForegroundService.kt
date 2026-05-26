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
import com.tgwrist.app.runtime.VoiceRecordingState

/**
 * 维持语音消息录制期间的 CPU 唤醒和 microphone 前台服务类型。
 *
 * 关注点：
 *  - 仅负责 WakeLock + 前台通知；实际的 [android.media.MediaRecorder] 由
 *    [com.tgwrist.app.runtime.VoiceRecordingState] 持有，让 UI 可以读取实时振幅；
 *  - 服务一旦启动，用户在 ChatScreen 内左右翻页或离开页面都不会中断录音；
 *  - 用户必须显式取消 / 完成录制 → service 才会停止。
 */
class VoiceRecordingForegroundService : Service() {

    companion object {
        private const val TAG = "VoiceRecordingFG"

        const val ACTION_START = "com.tgwrist.app.action.VOICE_REC_START"
        const val ACTION_STOP = "com.tgwrist.app.action.VOICE_REC_STOP"
        const val ACTION_CANCEL = "com.tgwrist.app.action.VOICE_REC_CANCEL"
        const val NOTIFICATION_ID = 1003
        const val CHANNEL_ID = "tg_wear_voice_record_channel"

        fun start(context: Context) {
            val intent = Intent(context, VoiceRecordingForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceRecordingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            // startService 即可，Service 内部会调 stopForeground+stopSelf
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
            ACTION_START -> ensureForeground()
            ACTION_CANCEL -> {
                VoiceRecordingState.cancelAll()
                stopRecordingService()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopRecordingService()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        if (isForegroundStarted) return
        acquireWakeLock()

        val notification = buildNotification().build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundStarted = true
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWrist:VoiceRecLock")
            .apply {
                setReferenceCounted(false)
                // 1 小时兜底
                acquire(60L * 60L * 1000L)
            }
    }

    private fun returnPendingIntent(): PendingIntent {
        val returnIntent = Intent(this, MainActivity::class.java).apply {
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

    private fun cancelPendingIntent(): PendingIntent {
        val cancelIntent = Intent(this, VoiceRecordingForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        return PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.Voice_record_notification_title))
            .setContentText(getString(R.string.Voice_record_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(returnPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(
                0,
                getString(R.string.Voice_record_cancel),
                cancelPendingIntent()
            )

    private fun stopRecordingService() {
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (t: Throwable) {
                Log.w(TAG, "wakeLock release failed", t)
            }
        }
        wakeLock = null

        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.Voice_record_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.Voice_record_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Throwable) {}
        }
        wakeLock = null
        // service 异常被杀掉时同步取消录音，防止 MediaRecorder 泄漏
        if (VoiceRecordingState.isRecording.value) {
            VoiceRecordingState.cancelAll()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
