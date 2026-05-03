package com.tgwrist.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

internal const val ACTION_MARK_AS_READ = "com.tgwrist.app.ACTION_MARK_AS_READ"
internal const val ACTION_REPLY = "com.tgwrist.app.ACTION_REPLY"
internal const val KEY_TEXT_REPLY = "key_text_reply"
internal const val EXTRA_CHAT_ID = "com.tgwrist.app.EXTRA_CHAT_ID"

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).cancelUniqueWork("notification_processing")
        when (intent.action) {
            ACTION_MARK_AS_READ -> {
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                if (chatId == -1L) return
                val notificationGroupId = intent.getIntExtra(NOTIFICATION_GROUP_ID, -1)
                val notificationId = intent.getIntExtra(NOTIFICATION_ID, -1)

                // 取消通知
                val notifId = chatIdToNotifId(chatId)
                try {
                    NotificationManagerCompat.from(context).cancel(notifId)
                } catch (_: SecurityException) { }

                // 后台标记已读
                val workRequest = OneTimeWorkRequestBuilder<NotificationBackgroundWorker>()
                    .setInputData(workDataOf(
                        "ACTION" to ACTION_MARK_AS_READ,
                        EXTRA_CHAT_ID to chatId,
                        NOTIFICATION_GROUP_ID to notificationGroupId,
                        NOTIFICATION_ID to notificationId
                    ))
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    "mark_as_read_$chatId",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            }

            ACTION_REPLY -> {
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                if (chatId == -1L) return
                val notificationGroupId = intent.getIntExtra(NOTIFICATION_GROUP_ID, -1)
                val notificationId = intent.getIntExtra(NOTIFICATION_ID, -1)

                // 从 RemoteInput 获取回复文字
                val remoteInputBundle = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInputBundle?.getCharSequence(KEY_TEXT_REPLY)?.toString()
                if (replyText.isNullOrBlank()) return

                // 取消通知
                val notifId = chatIdToNotifId(chatId)
                try {
                    NotificationManagerCompat.from(context).cancel(notifId)
                } catch (_: SecurityException) { }

                // 后台发送回复
                val workRequest = OneTimeWorkRequestBuilder<NotificationBackgroundWorker>()
                    .setInputData(workDataOf(
                        "ACTION" to ACTION_REPLY,
                        EXTRA_CHAT_ID to chatId,
                        KEY_TEXT_REPLY to replyText,
                        NOTIFICATION_GROUP_ID to notificationGroupId,
                        NOTIFICATION_ID to notificationId
                    ))
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    "reply_$chatId",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            }
        }
    }

    /** 与 Push.kt 中的 chatIdToNotifId 保持一致 */
    private fun chatIdToNotifId(chatId: Long): Int {
        val id = (chatId xor (chatId ushr 32)).toInt() and 0x7FFFFFFE
        return if (id == 0) 1 else id
    }
}
