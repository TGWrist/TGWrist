package com.tgwrist.app.notification

import android.os.SystemClock
import android.util.Log
import com.tgwrist.app.TGWrist
import com.tgwrist.app.runtime.CALL_STATE_NONE
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.runtime.UserManager
import com.tgwrist.app.utils.setTdlibParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * FCM 推送的直接处理器（不经过 WorkManager）。
 *
 * 背景：原先 onMessageReceived 只是把任务塞进 WorkManager（expedited，配额用完时退化为
 * 普通任务）。在 Doze / Wear 深度休眠下，普通任务会被推迟到下一个维护窗口，
 * 来电通知因此可能晚 1~2 分钟才弹出，对方早已挂断。
 *
 * 这里把 [NotificationBackgroundWorker.handleProcessNotification] 的逻辑复制了一份，
 * 由 [PushWakeService]（前台服务 + WakeLock）在收到推送的当下立即执行。
 * Worker 仍然保留，作为前台服务无法启动时的兜底。
 */
object PushProcessor {
    private const val TAG = "PushProcessor"

    /** 与 Worker 保持一致：通话保持上限（前台服务没有 10 分钟硬限制，但仍保守处理） */
    private val MAX_CALL_HOLD: Duration = 9.minutes
    private val INCOMING_CALL_GRACE: Duration = 30.seconds

    /**
     * 处理一条推送。返回 true 表示已处理（成功或无需处理），false 表示失败。
     * 内部会在通话期间阻塞，直到通话结束（或超时），以保持 TgClient 存活。
     */
    suspend fun process(fcmData: String): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        if (fcmData.isBlank()) return true

        TgClient.init()
        try {
            val pushReceiverId = suspendTdRequest<TdApi.PushReceiverId>(
                TdApi.GetPushReceiverId(fcmData)
            )
            if (pushReceiverId == null) {
                Log.e(TAG, "GetPushReceiverId failed")
                return false
            }

            val activeUser = UserManager.getActiveUser()
            if (activeUser == null || activeUser.pushReceiverId != pushReceiverId.id) {
                Log.d(TAG, "Push receiver ID ${pushReceiverId.id} doesn't match active user, skip")
                return true
            }

            val authState = suspendTdRequest<TdApi.Object>(TdApi.GetAuthorizationState())
            if (authState is TdApi.AuthorizationStateWaitTdlibParameters) {
                TGWrist.context.setTdlibParameters(activeUser.userId.toString())
            }

            val processResult = suspendTdRequest<TdApi.Object>(
                TdApi.ProcessPushNotification(fcmData)
            )
            return when {
                processResult is TdApi.Ok -> {
                    Log.d(TAG, "Push notification processed successfully")
                    delay(3000.milliseconds)
                    true
                }
                processResult is TdApi.Error && processResult.code == 406 -> {
                    // TDLib 无法本地处理，等待服务器更新（含来电 UpdateCall）
                    delay(20000.milliseconds)
                    true
                }
                else -> {
                    Log.e(TAG, "ProcessPushNotification failed: $processResult")
                    delay(20000.milliseconds)
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            return false
        } finally {
            awaitCallSafely(startedAt)
            try {
                if (!Config.isMainActivityAlive && TgCallManager.callState.value == CALL_STATE_NONE) {
                    TgClient.close()
                }
            } catch (closeError: Exception) {
                Log.e(TAG, "Failed to close TgClient", closeError)
            }
        }
    }

    private suspend fun awaitCallSafely(startedAt: Long) {
        fun freshCallNotifyAt(): Long {
            val ts = Push.lastIncomingCallNotificationAt
            return if (ts >= startedAt) ts else 0L
        }

        val initialCallActive = TgCallManager.callState.value != CALL_STATE_NONE
        val initialNotifyAt = freshCallNotifyAt()
        if (!initialCallActive && initialNotifyAt == 0L) return

        Log.d(TAG, "Holding alive (callActive=$initialCallActive, justNotifiedCall=${initialNotifyAt != 0L})")

        val holdStart = SystemClock.elapsedRealtime()
        val maxHoldMs = MAX_CALL_HOLD.inWholeMilliseconds
        val graceMs = INCOMING_CALL_GRACE.inWholeMilliseconds
        var seenCallActive = initialCallActive

        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - holdStart
            if (elapsed >= maxHoldMs) {
                Log.w(TAG, "Reached max call hold, releasing")
                break
            }
            val callActive = TgCallManager.callState.value != CALL_STATE_NONE
            if (callActive) {
                seenCallActive = true
            } else if (seenCallActive) {
                Log.d(TAG, "Call ended, releasing after ${elapsed}ms")
                break
            } else {
                val notifyAt = freshCallNotifyAt()
                val withinGrace = notifyAt != 0L &&
                        (SystemClock.elapsedRealtime() - notifyAt) < graceMs
                if (!withinGrace) {
                    Log.d(TAG, "Incoming call grace expired without active call, releasing")
                    break
                }
            }
            delay(1.seconds)
        }
    }

    private suspend inline fun <reified T : TdApi.Object> suspendTdRequest(
        query: TdApi.Function<*>
    ): T? = suspendCancellableCoroutine { cont ->
        TgClient.send(query) { result ->
            if (cont.isActive) cont.resume(result as? T)
        }
    }
}
