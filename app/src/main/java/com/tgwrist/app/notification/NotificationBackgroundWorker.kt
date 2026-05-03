package com.tgwrist.app.notification

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tgwrist.app.TGWrist
import com.tgwrist.app.utils.TgClient
import com.tgwrist.app.utils.UserManager
import com.tgwrist.app.utils.setTdlibParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import java.io.IOException
import kotlin.coroutines.resume

class NotificationBackgroundWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "NotifBgWorker"
    }

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
                delay(3000)
                Result.success()
            } else if (processResult is TdApi.Error) {
                if (processResult.code == 406) {
                    delay(20000) // 等待 20秒 给tdlib接收信息
                    Result.success()
                } else {
                    // 可能是什么其他的问题，等待20秒关闭即可
                    delay(20000)
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
                // FIX: 无论任何分支退出，都确保关闭 TgClient
                TgClient.close()
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
