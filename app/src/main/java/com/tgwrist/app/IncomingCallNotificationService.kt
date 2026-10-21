package com.tgwrist.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
 *
 * 实现说明（两个通知）：
 *  - [NOTIFICATION_ID]：前台服务自身的静默通知，附带 Wear OngoingActivity（绿标）。
 *    Wear OS 会把带 OngoingActivity 的前台服务通知当作 "Ongoing Activity" 处理
 *    并放进 NowBar，这类通知的 fullScreenIntent 会被忽略——所以这里不能挂来电提醒。
 *  - [ALERT_NOTIFICATION_ID]：真正的来电提醒，独立发出：fullScreenIntent + 铃声 +
 *    FLAG_INSISTENT。它不绑定前台服务，也不带 OngoingActivity，因而会按普通
 *    高优先级通知处理，能够点亮屏幕并拉起通话页。
 */
class IncomingCallNotificationService : Service() {

    companion object {
        private const val TAG = "IncomingCallNotifSvc"

        const val ACTION_START = "com.tgwrist.app.action.INCOMING_CALL_START"
        const val ACTION_STOP  = "com.tgwrist.app.action.INCOMING_CALL_STOP"
        const val EXTRA_TITLE  = "incoming_call_title"
        const val EXTRA_TEXT   = "incoming_call_text"

        /** 前台服务通知（静默，带绿标） */
        const val NOTIFICATION_ID = 1003
        /** 来电提醒通知（全屏 + 铃声） */
        const val ALERT_NOTIFICATION_ID = 1004

        /** 旧渠道 id，保留以便清理；新版本不再使用 */
        const val CHANNEL_ID        = "tg_wear_incoming_call_channel"
        const val FGS_CHANNEL_ID    = "tg_wear_incoming_call_fgs"
        const val RING_CHANNEL_ID   = "tg_wear_incoming_call_ring"

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
    private var ringPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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

        // 1) 前台服务通知：静默 + Wear 绿标（OngoingActivity）。不带 fullScreenIntent。
        val fgsBuilder = NotificationCompat.Builder(this, FGS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val status = Status.Builder()
            .addTemplate(title)
            .build()
        OngoingActivity.Builder(this, NOTIFICATION_ID, fgsBuilder)
            .setStaticIcon(if (isSamsungDevice()) R.drawable.ic_call_png else R.drawable.ic_call)
            .setStatus(status)
            .setTouchIntent(openAppPendingIntent())
            .build()
            .apply(this)

        val fgsNotification = fgsBuilder.build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                fgsNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, fgsNotification)
        }

        // 2) 来电提醒：独立通知，fullScreenIntent + 铃声 + FLAG_INSISTENT。
        val alertNotification = NotificationCompat.Builder(this, RING_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(openAppPendingIntent(), true)
            // 铃声 / 震动由本服务自己播放（见 startRinging），不依赖 Wear 的通知提醒管线
            // （该管线在"未佩戴"时会静默所有通知）。
            .setSilent(true)
            .build()

        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(ALERT_NOTIFICATION_ID, alertNotification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
        }

        startRinging()

        Push.setIncomingCallServiceActive(this, true)
        isForegroundStarted = true
        Log.d(TAG, "Incoming call foreground started; alert notification posted separately")
    }

    /**
     * 自己播放铃声 + 循环震动。
     * 遵守系统响铃模式：静音 → 不响不震；仅震动 → 只震动；正常 → 铃声 + 震动。
     */
    private fun startRinging() {
        val audioManager = getSystemService(AudioManager::class.java)
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                if (uri != null) {
                    ringPlayer = MediaPlayer().apply {
                        // USAGE_ALARM 而不是 RINGTONE：Samsung Wear 在"未佩戴"状态下通过
                        // PLAY_AUDIO/VIBRATE app-op 静音 RINGTONE/NOTIFICATION 用途的音频
                        // （日志：event:muted source:opPlayAudio），但闹钟用途不受限制。
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        setDataSource(this@IncomingCallNotificationService, uri)
                        isLooping = true
                        setOnErrorListener { _, what, extra ->
                            Log.w(TAG, "Ringtone error $what/$extra")
                            true
                        }
                        prepare()
                        start()
                    }
                    Log.d(TAG, "Ringtone started")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start ringtone", e)
                ringPlayer?.release()
                ringPlayer = null
            }
        }

        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
            try {
                vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Vibrator::class.java)
                }
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 1200), 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    vibrator?.vibrate(
                        effect,
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(effect)
                }
                Log.d(TAG, "Vibration started")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start vibration", e)
            }
        }
    }

    private fun stopRinging() {
        try {
            ringPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        ringPlayer = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

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

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // 前台服务渠道：低优先级、无声
        val fgsChannel = NotificationChannel(
            FGS_CHANNEL_ID,
            getString(R.string.Incoming_call_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.Incoming_call_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(fgsChannel)

        // 来电铃声渠道：高优先级 + 系统铃声（USAGE_NOTIFICATION_RINGTONE）+ 震动
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val ringChannel = NotificationChannel(
            RING_CHANNEL_ID,
            getString(R.string.Incoming_call_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.Incoming_call_notification_channel_description)
            setSound(ringtoneUri, ringAttrs)
            enableVibration(true)
            setShowBadge(true)
            setBypassDnd(false)
        }
        manager.createNotificationChannel(ringChannel)

        // 清理旧渠道，避免设置页里出现重复项
        try {
            manager.deleteNotificationChannel(CHANNEL_ID)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopRinging()
        try {
            getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        Push.setIncomingCallServiceActive(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
