package com.tgwrist.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

internal const val ACTION_DISMISS_NOTIFICATION = "com.tgwrist.app.ACTION_DISMISS_NOTIFICATION"
internal const val NOTIFICATION_GROUP_ID = "com.tgwrist.app.NOTIFICATION_GROUP_ID"
internal const val NOTIFICATION_ID = "com.tgwrist.app.NOTIFICATION_ID"
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationGroupId = intent.getIntExtra(NOTIFICATION_GROUP_ID, -1)
        val notificationId = intent.getIntExtra(NOTIFICATION_ID, -1)

        if (notificationGroupId == -1 || notificationId == -1) return

        val workRequest = OneTimeWorkRequestBuilder<NotificationBackgroundWorker>()
            .setInputData(workDataOf(
                "ACTION" to ACTION_DISMISS_NOTIFICATION,
                NOTIFICATION_GROUP_ID to notificationGroupId,
                NOTIFICATION_ID to notificationId
            ))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "dismiss_notification_processing",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
    }
}
