package com.tgwrist.app.notification

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.runtime.UserManager
import org.drinkless.tdlib.TdApi
import org.json.JSONObject

internal const val ACTION_PROCESS_NOTIFICATION = "com.tgwrist.app.ACTION_PROCESS_NOTIFICATION"
class TGFCM : FirebaseMessagingService() {
    companion object {
        private const val TAG = "TGFCM"
    }
    override fun onNewToken(token: String) {
        super.onNewToken(token)

        val isOpenNotification = Config.isOpenNotification
        if (isOpenNotification) {
            TgClient.send(
                TdApi.RegisterDevice(
                    TdApi.DeviceTokenFirebaseCloudMessaging(
                        token,
                        true
                    ),
                    null
                )
            ) {
                if (it is TdApi.PushReceiverId) UserManager.updatePushReceiverId(pushReceiverId = it.id)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val isOpenNotification = Config.isOpenNotification
        if (isOpenNotification) {
            // 检查消息是否包含数据有效载荷。
            if (remoteMessage.data.isNotEmpty()) {
                //println("Message data payload: ${remoteMessage.data}")
                Log.d(TAG, "Message data payload: ${remoteMessage.data}")
                val workRequest = OneTimeWorkRequestBuilder<NotificationBackgroundWorker>()
                    .setInputData(workDataOf(
                        "ACTION" to ACTION_PROCESS_NOTIFICATION,
                        "FCM_DATA" to JSONObject(remoteMessage.data).toString())
                    )
                    .build()

                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    "notification_processing",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    workRequest
                )
            }
        }
    }
}
