package com.tgwrist.app.notification

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class NotificationBackgroundWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "NotifBgWorker"

        /**
         * FIX: 通话保持上限。
         *
         * WorkManager 单次任务大约有 10 分钟的硬性执行上限，超时会被强制取消。
         * 这里压到 9 分钟，给系统留点余量；远大于用户接听一次电话需要的时间。
         * 通话一旦结束（callState 回到 CALL_STATE_NONE）会立刻提前退出，不会真等满 9 分钟。
         */
        private val MAX_CALL_HOLD: Duration = 9.minutes

        /**
         * FIX: 来电通知弹出后，等 TgCallManager.callState 真正切到非 NONE 的宽限窗口。
         *
         * 场景：showIncomingCallNotifications 已经 nm.notify 弹出来电通知，但
         * TgCallManager 接收 UpdateCall 是异步的，可能慢几百毫秒到几秒。如果 Worker
         * 恰好在这个间隙 finally 退出，TgClient 会被 close，这通电话就接不到了。
         * 在这段窗口内，即使 callState 还是 NONE 也继续把 Worker 挂住等。
         */
        private val INCOMING_CALL_GRACE: Duration = 30.seconds
    }

    /**
     * FIX: 本次 Worker 启动的时间戳（elapsedRealtime）。
     * 用来判断 [Push.lastIncomingCallNotificationAt] 是否是"由本次 Worker 触发后弹出的来电通知"，
     * 避免被上一轮残留的时间戳误导而无谓挂起。
     */
    private val workStartedAt: Long = SystemClock.elapsedRealtime()

    override suspend fun doWork(): Result {
        return when (val action = inputData.getString("ACTION")) {
            ACTION_PROCESS_NOTIFICATION -> {
                // 如果是通知处理
                handleProcessNotification()
            }

            ACTION_DISMISS_NOTIFICATION -> {
                // 如果是处理用户删除通知
                handleDismissNotification()
            }

            ACTION_MARK_AS_READ -> {
                handleMarkAsRead()
            }

            ACTION_REPLY -> {
                handleReply()
            }

            else -> {
                Log.e(TAG, "Unknown action: $action")
                Result.failure()
            }
        }
    }

    /**
     * 处理推送通知。
     *
     * FIX:
     * 1. 原代码这里会调用 TgClient.init()，但多个 return / 异常分支都没有 close。
     * 2. 现在统一放进 withTgClientSession 中，确保无论成功、失败、异常还是提前 return，
     *    最终都会执行 TgClient.close()。
     */
    private suspend fun handleProcessNotification(): Result = withTgClientSession {
        val fcmData = inputData.getString("FCM_DATA")

        if (fcmData.isNullOrBlank()) {
            return@withTgClientSession Result.success()
        }

        try {
            // 1. 初始化 TDLib 客户端
            // FIX: 初始化挪到了 withTgClientSession() 内统一处理，避免遗漏 close。

            // 2. 获取推送接收者 ID
            val pushReceiverId = suspendTdRequest<TdApi.PushReceiverId>(
                TdApi.GetPushReceiverId(fcmData)
            )
            if (pushReceiverId == null) {
                Log.e(TAG, "GetPushReceiverId failed")
                return@withTgClientSession Result.failure()
            }

            // 3. 校验推送是否属于当前活跃用户
            val activeUser = UserManager.getActiveUser()
            if (activeUser == null || activeUser.pushReceiverId != pushReceiverId.id) {
                Log.d(TAG, "Push receiver ID ${pushReceiverId.id} doesn't match active user, skip")
                return@withTgClientSession Result.success()
            }

            // 4. 检查授权状态，必要时先设置 TDLib 参数
            ensureTdlibReady(activeUser.userId)

            // 5. 处理推送通知（TDLib 会触发 UpdateNotificationGroup 来显示通知）
            val processResult = suspendTdRequest<TdApi.Object>(
                TdApi.ProcessPushNotification(fcmData)
            )

            if (processResult is TdApi.Ok) {
                Log.d(TAG, "Push notification processed successfully")
                // 给 TDLib 一点时间分发 UpdateNotificationGroup 事件
                delay(3000.milliseconds)
                Result.success()
            } else if (processResult is TdApi.Error) {
                if (processResult.code == 406) {
                    delay(20000.milliseconds) // 等待 20秒 给tdlib接收信息
                    Result.success()
                } else {
                    // 可能是什么其他的问题，等待20秒关闭即可
                    delay(20000.milliseconds)
                    Result.failure()
                }
            } else {
                // 不懂什么鬼错误
                Log.e(TAG, "ProcessPushNotification failed: $processResult")
                Result.failure()
            }
        } catch (e: IOException) {
            Log.e(TAG, "IO error, will retry", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            Result.failure()
        } finally {
            // FIX: 如果当前正处于通话状态，就把 Worker 强行挂在这里直到通话结束（或达到上限）。
            // 原因：Worker 一旦结束，withTgClientSession 的 finally 会尝试 close TgClient；
            // 一旦 TgClient 被关掉，进行中的 / 即将到来的来电都会丢失。
            // 这里通过轮询 callState，让 TgClient 单例在整段通话期间持续存活，
            // 用户才有机会正常接听这个电话。1
            awaitCallSafely()
        }
    }

    /**
     * FIX: 当处于通话中时，尽可能长时间地把 Worker 挂住，避免 TgClient 被 close 后电话丢失。
     *
     * 决策依据：
     * - TgCallManager.callState != NONE：肯定要继续挂着。
     * - showIncomingCallNotifications 刚 nm.notify 完，但 callState 还没切：
     *   也要挂着（INCOMING_CALL_GRACE 宽限窗口内），给 TgCallManager 接收
     *   UpdateCall 的时间。
     *
     * 退出条件（任一满足）：
     * - 一旦 callState 进入过非 NONE 状态，再回到 NONE：通话已结束，立刻退出。
     * - 还没观察到通话激活，宽限期也已经走完：来电没有真正发生（或被对方挂掉了）。
     * - 总耗时达到 MAX_CALL_HOLD 上限：避免触碰 WorkManager 的 10 分钟硬上限。
     */
    private suspend fun awaitCallSafely() {
        // 计算"本次 Worker 触发的来电通知"的时间戳。早于 workStartedAt 的视为无效，避免被上轮残留状态影响。
        fun freshCallNotifyAt(): Long {
            val ts = Push.lastIncomingCallNotificationAt
            return if (ts >= workStartedAt) ts else 0L
        }

        val initialCallActive = TgCallManager.callState.value != CALL_STATE_NONE
        val initialNotifyAt = freshCallNotifyAt()

        if (!initialCallActive && initialNotifyAt == 0L) {
            // 没在通话，也没刚弹出来电通知：直接走原本的关闭流程。
            return
        }

        Log.d(
            TAG,
            "Holding worker alive (callActive=$initialCallActive, justNotifiedCall=${initialNotifyAt != 0L})"
        )

        val holdStart = SystemClock.elapsedRealtime()
        val maxHoldMs = MAX_CALL_HOLD.inWholeMilliseconds
        val graceMs = INCOMING_CALL_GRACE.inWholeMilliseconds
        val pollInterval = 1.seconds

        var seenCallActive = initialCallActive

        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - holdStart
            if (elapsed >= maxHoldMs) {
                Log.w(TAG, "Reached max call hold (${MAX_CALL_HOLD}), releasing worker")
                break
            }

            val callActive = TgCallManager.callState.value != CALL_STATE_NONE
            if (callActive) {
                seenCallActive = true
            } else if (seenCallActive) {
                // 曾经接通过 / 进入过通话流程，现在又回到 NONE：通话结束。
                Log.d(TAG, "Call ended, releasing worker after ${elapsed}ms")
                break
            } else {
                // 还没观察到通话激活：看刚弹出的来电通知是否还在宽限期内。
                val notifyAt = freshCallNotifyAt()
                val withinGrace = notifyAt != 0L &&
                        (SystemClock.elapsedRealtime() - notifyAt) < graceMs
                if (!withinGrace) {
                    Log.d(TAG, "Incoming call grace expired without active call, releasing")
                    break
                }
            }

            delay(pollInterval)
        }
    }

    /**
     * 处理用户删除通知。
     *
     * FIX:
     * 原代码中这个分支也会 init TgClient，但没有 close。
     * 现在改为统一生命周期管理。
     */
    private suspend fun handleDismissNotification(): Result = withTgClientSession {
        val notificationGroupId = inputData.getInt(NOTIFICATION_GROUP_ID, -1)
        val notificationId = inputData.getInt(NOTIFICATION_ID, -1)

        if (notificationGroupId == -1 || notificationId == -1) {
            return@withTgClientSession Result.failure()
        }

        try {
            // FIX: 初始化挪到了 withTgClientSession() 内统一处理，避免遗漏 close。
            val activeUser = UserManager.getActiveUser() ?: return@withTgClientSession Result.failure()

            ensureTdlibReady(activeUser.userId)

            val result = suspendTdRequest<TdApi.Object>(
                TdApi.RemoveNotificationGroup(notificationGroupId, notificationId)
            )

            if (result is TdApi.Ok) {
                Log.d(TAG, "Notification removed successfully")
                Result.success()
            } else {
                Log.e(TAG, "Failed to remove notification: $result")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing notification", e)
            Result.failure()
        }
    }

    /**
     * 标记消息为已读。
     *
     * FIX:
     * 原代码中这个分支也会 init TgClient，但没有 close。
     * 现在改为统一生命周期管理。
     */
    private suspend fun handleMarkAsRead(): Result = withTgClientSession {
        val chatId = inputData.getLong(EXTRA_CHAT_ID, -1L)
        if (chatId == -1L) return@withTgClientSession Result.failure()

        val notificationGroupId = inputData.getInt(NOTIFICATION_GROUP_ID, -1)
        val notificationId = inputData.getInt(NOTIFICATION_ID, -1)

        try {
            // FIX: 初始化挪到了 withTgClientSession() 内统一处理，避免遗漏 close。
            val activeUser = UserManager.getActiveUser() ?: return@withTgClientSession Result.failure()

            ensureTdlibReady(activeUser.userId)

            // 获取该会话信息，取最后一条消息的 id
            val chat = suspendTdRequest<TdApi.Chat>(TdApi.GetChat(chatId))
            val lastMessageId = chat?.lastMessage?.id ?: 0L

            if (lastMessageId != 0L) {
                val result = suspendTdRequest<TdApi.Object>(
                    TdApi.ViewMessages(
                        chatId,
                        longArrayOf(lastMessageId),
                        TdApi.MessageSourceNotification(),
                        true
                    )
                )
                if (result is TdApi.Ok) {
                    Log.d(TAG, "Messages marked as read for chat $chatId")
                } else {
                    Log.e(TAG, "Failed to mark messages as read: $result")
                }
            }

            // 通知 TDLib 移除通知
            if (notificationGroupId != -1 && notificationId != -1) {
                val removeResult = suspendTdRequest<TdApi.Object>(
                    TdApi.RemoveNotificationGroup(notificationGroupId, notificationId)
                )
                if (removeResult is TdApi.Ok) {
                    Log.d(TAG, "Notification removed successfully")
                } else {
                    Log.e(TAG, "Failed to remove notification: $removeResult")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error marking as read", e)
            Result.failure()
        }
    }

    /**
     * 处理通知内直接回复。
     *
     * FIX:
     * 原代码中这个分支也会 init TgClient，但没有 close。
     * 现在改为统一生命周期管理。
     */
    private suspend fun handleReply(): Result = withTgClientSession {
        val chatId = inputData.getLong(EXTRA_CHAT_ID, -1L)
        val replyText = inputData.getString(KEY_TEXT_REPLY)
        if (chatId == -1L || replyText.isNullOrBlank()) return@withTgClientSession Result.failure()

        val notificationGroupId = inputData.getInt(NOTIFICATION_GROUP_ID, -1)
        val notificationId = inputData.getInt(NOTIFICATION_ID, -1)

        try {
            // FIX: 初始化挪到了 withTgClientSession() 内统一处理，避免遗漏 close。
            val activeUser = UserManager.getActiveUser() ?: return@withTgClientSession Result.failure()

            ensureTdlibReady(activeUser.userId)

            val inputMessageContent = TdApi.InputMessageText().apply {
                text = TdApi.FormattedText(replyText, null)
            }

            val result = suspendTdRequest<TdApi.Object>(
                TdApi.SendMessage(
                    chatId,
                    null,
                    null,
                    null,
                    null,
                    inputMessageContent
                )
            )

            if (result is TdApi.Message) {
                Log.d(TAG, "Reply sent successfully to chat $chatId")
                // 发送成功后也标记已读
                val chat = suspendTdRequest<TdApi.Chat>(TdApi.GetChat(chatId))
                val lastMessageId = chat?.lastMessage?.id ?: 0L
                if (lastMessageId != 0L) {
                    suspendTdRequest<TdApi.Object>(
                        TdApi.ViewMessages(
                            chatId,
                            longArrayOf(lastMessageId),
                            TdApi.MessageSourceNotification(),
                            true
                        )
                    )
                }

                // 通知 TDLib 移除通知
                if (notificationGroupId != -1 && notificationId != -1) {
                    val removeResult = suspendTdRequest<TdApi.Object>(
                        TdApi.RemoveNotificationGroup(notificationGroupId, notificationId)
                    )
                    if (removeResult is TdApi.Ok) {
                        Log.d(TAG, "Notification removed successfully")
                    } else {
                        Log.e(TAG, "Failed to remove notification: $removeResult")
                    }
                }

                Result.success()
            } else {
                Log.e(TAG, "Failed to send reply: $result")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending reply", e)
            Result.failure()
        }
    }

    /**
     * FIX: 统一管理 TgClient 生命周期。
     *
     * 原问题：
     * - 原代码在多个 action 分支里直接 TgClient.init()
     * - 但是没有对应 close()
     * - 而且中途有很多 return Result.xxx()
     * - 以及异常分支 catch 后直接返回
     * 这些情况都会导致 TgClient 没有正确关闭
     *
     * 修复方式：
     * - 所有需要使用 TgClient 的逻辑都包在这个函数里
     * - 进入时 init()
     * - 退出时 finally 中强制 close()
     * - 这样即使中途 return / failure / retry / exception，也不会漏掉关闭
     */
    private suspend inline fun withTgClientSession(
        block: suspend () -> Result
    ): Result {
        // FIX: 统一在这里初始化 TgClient
        TgClient.init()
        return try {
            block()
        } finally {
            try {
                // 关闭 TgClient
                if (!Config.isMainActivityAlive && TgCallManager.callState.value == CALL_STATE_NONE) TgClient.close()
            } catch (closeError: Exception) {
                // FIX: 防止 close 自己抛异常影响 Worker 最终结果
                Log.e(TAG, "Failed to close TgClient", closeError)
            }
        }
    }

    /**
     * FIX: 把重复的授权状态检查抽出来。
     *
     * 原代码在多个分支中都重复：
     * 1. GetAuthorizationState()
     * 2. 判断是否为 AuthorizationStateWaitTdlibParameters
     * 3. 调用 setTdlibParameters()
     *
     * 统一封装后：
     * - 逻辑更集中
     * - 后续更不容易改漏
     */
    private suspend fun ensureTdlibReady(userId: Long) {
        // 4. 检查授权状态，必要时先设置 TDLib 参数
        val authState = suspendTdRequest<TdApi.Object>(TdApi.GetAuthorizationState())
        if (authState is TdApi.AuthorizationStateWaitTdlibParameters) {
            TGWrist.context.setTdlibParameters(userId.toString())
        }
    }

    /**
     * 将 TDLib 异步回调封装为挂起函数。
     * 返回目标类型 T 的结果，若类型不匹配则返回 null。
     */
    private suspend inline fun <reified T : TdApi.Object> suspendTdRequest(
        query: TdApi.Function<*>
    ): T? = suspendCancellableCoroutine { cont ->
        TgClient.send(query) { result ->
            if (cont.isActive) {
                cont.resume(result as? T)
            }
        }
    }
}
