package com.tgwrist.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.VideoFile
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.wear.compose.material3.Icon
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.messaging
import com.google.firebase.perf.FirebasePerformance
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import com.tgwrist.app.data.SettingItem
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.runtime.UserManager
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.utils.openInBrowser
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.DecimalFormat

/**
 * 构建设置项列表（点击操作后通过 rebuildAfterAction 标记触发重新构建）
 */
fun buildSettingItems(
    index: Int,
    permissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    context: Context,
    storageFolderSizes: Map<String, String> = emptyMap()
): List<SettingItem> = when (index) {
    0 -> listOf(
        SettingItem.Title(titleRes = R.string.Settings),
        // 是否开启通知推送
        SettingItem.Switch(
            isSelected = Config.isOpenNotification,
            onCheckedChange = { enabled ->
                if (enabled) {
                    // 如果 FCM 未开启，先自动启用
                    if (!Firebase.messaging.isAutoInitEnabled) {
                        Firebase.messaging.isAutoInitEnabled = true
                    }
                    // 检查通知权限
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        // 已有权限，先设置状态再异步注册 FCM
                        Config.isOpenNotification = true
                        registerFcmAndEnableNotification()
                    } else {
                        // 请求权限（结果由 permissionLauncher 回调处理）
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                } else {
                    // 关闭通知
                    disableNotification()
                }
            },
            titleRes = R.string.Push_notification,
            icon = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
            descriptionRes = context.getString(R.string.Push_notification_desc),
            rebuildAfterAction = true
        ),
        // 增强通话通知权限获取
        /*SettingItem.Click(
            titleRes = R.string.Enhanced_call_notification,
            icon = { Icon(Icons.Rounded.Call, contentDescription = null) },
            onClick = {
                checkAndRequestFullScreenIntentPermission(context)
            }
        ),*/
        // 存储管理
        SettingItem.ClickAndOpenPage(
            titleRes = R.string.Storage_management,
            pageRoute = Destinations.settings(2),
            icon = { Icon(Icons.Rounded.Storage, contentDescription = null) }
        ),
        // Firebase设置
        SettingItem.ClickAndOpenPage(
            titleRes = R.string.Firebase_settings,
            pageRoute = Destinations.settings(1),
            icon = { Icon(Icons.Rounded.Pending, contentDescription = null) }
        ),
        // 检查更新
        SettingItem.Click(
            titleRes = R.string.Check_for_updates,
            icon = { Icon(Icons.Rounded.Autorenew, contentDescription = null) },
            onClick = {
                openInBrowser(TGWrist.context, "https://play.google.com/store/apps/details?id=com.tgwrist.app")
            }
        ),
        // 关于软件
        SettingItem.ClickAndOpenPage(
            titleRes = R.string.About,
            pageRoute = Destinations.ABOUT,
            icon = { Icon(Icons.Rounded.Info, contentDescription = null) }
        )
    )
    1 -> listOf(
        // Firebase设置
        SettingItem.Title(titleRes = R.string.Google_Firebase_settings),
        // Firebase Cloud Messaging 设置 - 建议开启，通知功能需要用到
        SettingItem.Switch(
            isSelected = Firebase.messaging.isAutoInitEnabled,
            onCheckedChange = { enabled ->
                Firebase.messaging.isAutoInitEnabled = enabled
                if (!enabled) {
                    // 关闭 FCM 同时关闭通知
                    disableNotification()
                }
            },
            titleRes = R.string.Firebase_cloud_messaging,
            icon = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
            descriptionRes = context.getString(R.string.Firebase_cloud_messaging_desc),
            rebuildAfterAction = true
        ),
        // Firebase Crashlytics 设置 - 建议开启，匿名发送错误日志
        SettingItem.Switch(
            isSelected = FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled,
            onCheckedChange = { enabled ->
                FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
            },
            titleRes = R.string.Firebase_crashlytics,
            icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
            descriptionRes = context.getString(R.string.Firebase_crashlytics_desc),
            rebuildAfterAction = true
        ),
        // Firebase Analytics 设置 - 选择性开启，匿名收集使用情况
        SettingItem.Switch(
            isSelected = FirebaseAnalytics.getInstance(TGWrist.context).let {
                // Analytics doesn't expose a getter; use SharedPreferences to track state
                TGWrist.context.getSharedPreferences("com.google.firebase.analytics", 0)
                    .getBoolean("collection_enabled", false)
            },
            onCheckedChange = { enabled ->
                FirebaseAnalytics.getInstance(TGWrist.context).setAnalyticsCollectionEnabled(enabled)
                // Persist the state for the switch UI
                TGWrist.context.getSharedPreferences("com.google.firebase.analytics", 0)
                    .edit { putBoolean("collection_enabled", enabled) }
            },
            titleRes = R.string.Firebase_analytics,
            icon = { Icon(Icons.Rounded.Analytics, contentDescription = null) },
            descriptionRes = context.getString(R.string.Firebase_analytics_desc),
            rebuildAfterAction = true
        ),
        // Firebase Performance 设置 - 性能好可以开启，性能不好可能卡顿
        SettingItem.Switch(
            isSelected = FirebasePerformance.getInstance().isPerformanceCollectionEnabled,
            onCheckedChange = { enabled ->
                FirebasePerformance.getInstance().isPerformanceCollectionEnabled = enabled
            },
            titleRes = R.string.Firebase_performance,
            icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
            descriptionRes = context.getString(R.string.Firebase_performance_desc),
            rebuildAfterAction = true
        )
    )
    2 -> {
        val baseDir = context.getExternalFilesDir(null)
        listOf(
            SettingItem.Title(titleRes = R.string.Storage_management),
            SettingItem.SmallTitle(titleRes = R.string.Click_button_clear),
            // Photos
            SettingItem.Click(
                titleRes = R.string.Storage_photos,
                icon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                descriptionRes = context.getString(R.string.Storage_size_desc) + (storageFolderSizes[STORAGE_FOLDER_PHOTOS] ?: ZERO_FILE_SIZE),
                onClick = {
                    deleteStorageFolder(context, baseDir, STORAGE_FOLDER_PHOTOS)
                },
                rebuildAfterAction = true
            ),
            // Temp
            SettingItem.Click(
                titleRes = R.string.Storage_temp,
                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                descriptionRes = context.getString(R.string.Storage_size_desc) + (storageFolderSizes[STORAGE_FOLDER_TEMP] ?: ZERO_FILE_SIZE),
                onClick = {
                    deleteStorageFolder(context, baseDir, STORAGE_FOLDER_TEMP)
                },
                rebuildAfterAction = true
            ),
            // Documents
            SettingItem.Click(
                titleRes = R.string.Storage_documents,
                icon = { Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null) },
                descriptionRes = context.getString(R.string.Storage_size_desc) + (storageFolderSizes[STORAGE_FOLDER_DOCUMENTS] ?: ZERO_FILE_SIZE),
                onClick = {
                    deleteStorageFolder(context, baseDir, STORAGE_FOLDER_DOCUMENTS)
                },
                rebuildAfterAction = true
            ),
            // Thumbnails
            SettingItem.Click(
                titleRes = R.string.Storage_thumbnails,
                icon = { Icon(Icons.Rounded.Theaters, contentDescription = null) },
                descriptionRes = context.getString(R.string.Storage_size_desc) + (storageFolderSizes[STORAGE_FOLDER_THUMBNAILS] ?: ZERO_FILE_SIZE),
                onClick = {
                    deleteStorageFolder(context, baseDir, STORAGE_FOLDER_THUMBNAILS)
                },
                rebuildAfterAction = true
            ),
            // Voice
            SettingItem.Click(
                titleRes = R.string.Storage_voice,
                icon = { Icon(Icons.Rounded.AudioFile, contentDescription = null) },
                descriptionRes = context.getString(R.string.Storage_size_desc) + (storageFolderSizes[STORAGE_FOLDER_VOICE] ?: ZERO_FILE_SIZE),
                onClick = {
                    deleteStorageFolder(context, baseDir, STORAGE_FOLDER_VOICE)
                },
                rebuildAfterAction = true
            ),
            // Videos
            SettingItem.Click(
                titleRes = R.string.Storage_videos,
                icon = { Icon(Icons.Rounded.VideoFile, contentDescription = null) },
                descriptionRes = context.getString(R.string.Storage_size_desc) + (storageFolderSizes[STORAGE_FOLDER_VIDEOS] ?: ZERO_FILE_SIZE),
                onClick = {
                    deleteStorageFolder(context, baseDir, STORAGE_FOLDER_VIDEOS)
                },
                rebuildAfterAction = true
            )
        )
    }
    else -> emptyList()
}

/**
 * 删除存储文件夹及其所有内容
 */
fun deleteStorageFolder(context: Context, baseDir: File?, folderName: String) {
    val folder = baseDir?.let { File(it, folderName) }
    if (folder == null) {
        Toast.makeText(context, context.getString(R.string.Storage_not_exist), Toast.LENGTH_SHORT).show()
        return
    }

    val deleted = clearStorageFolderContents(folder)
    val msg = if (deleted) {
        context.getString(R.string.Storage_deleted) + " " + folderName
    } else {
        context.getString(R.string.Storage_clear_failed) + " " + folderName
    }
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/**
 * 注册 FCM token 并开启通知
 */
fun loadStorageFolderSizes(context: Context): Map<String, String> {
    val baseDir = context.getExternalFilesDir(null)
    return STORAGE_FOLDERS.associateWith { folderName ->
        val folder = baseDir?.let { File(it, folderName) }
        formatFileSize(getFolderSizeBytes(folder))
    }
}

private fun clearStorageFolderContents(folder: File): Boolean {
    if (folder.exists() && folder.isFile && !folder.delete()) {
        return false
    }

    if (!folder.exists() && !folder.mkdirs()) {
        return false
    }

    val children = folder.listFiles() ?: return true
    return children.all { it.deleteRecursively() }
}

private fun getFolderSizeBytes(folder: File?): Long {
    if (folder == null || !folder.exists()) {
        return 0L
    }

    if (folder.isFile) {
        return folder.length()
    }

    return folder.listFiles()?.fold(0L) { total, child ->
        total + getFolderSizeBytes(child)
    } ?: 0L
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) {
        return ZERO_FILE_SIZE
    }

    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < FILE_SIZE_UNITS.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        "${sizeBytes}${FILE_SIZE_UNITS[unitIndex]}"
    } else {
        "${DecimalFormat("0.##").format(value)}${FILE_SIZE_UNITS[unitIndex]}"
    }
}

private const val ZERO_FILE_SIZE = "0B"
private const val STORAGE_FOLDER_PHOTOS = "photos"
private const val STORAGE_FOLDER_TEMP = "temp"
private const val STORAGE_FOLDER_DOCUMENTS = "documents"
private const val STORAGE_FOLDER_THUMBNAILS = "thumbnails"
private const val STORAGE_FOLDER_VOICE = "voice"
private const val STORAGE_FOLDER_VIDEOS = "videos"

private val STORAGE_FOLDERS = listOf(
    STORAGE_FOLDER_PHOTOS,
    STORAGE_FOLDER_TEMP,
    STORAGE_FOLDER_DOCUMENTS,
    STORAGE_FOLDER_THUMBNAILS,
    STORAGE_FOLDER_VOICE,
    STORAGE_FOLDER_VIDEOS
)

private val FILE_SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

fun registerFcmAndEnableNotification() {
    FirebaseMessaging.getInstance().token
        .addOnCompleteListener { task ->
            if (task.isSuccessful) {
                TgClient.send(
                    TdApi.RegisterDevice(
                        TdApi.DeviceTokenFirebaseCloudMessaging(task.result, true),
                        UserManager.getAllUserIds()
                    )
                ) {
                    if (it is TdApi.PushReceiverId) UserManager.updatePushReceiverId(pushReceiverId = it.id)
                }
                Config.isOpenNotification = true
            }
        }
}

/**
 * 关闭通知并注销 FCM device token
 */
fun disableNotification() {
    Config.isOpenNotification = false
    TgClient.send(
        TdApi.RegisterDevice(
            TdApi.DeviceTokenFirebaseCloudMessaging("", true),
            null
        )
    )
}
