package com.tgwrist.app.ui.login

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.messaging
import com.google.firebase.perf.FirebasePerformance
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import com.tgwrist.app.ui.settings.disableNotification
import com.tgwrist.app.ui.settings.registerFcmAndEnableNotification
import com.tgwrist.app.runtime.Config

@Composable
fun Page0(nextPage: () -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val context = LocalContext.current

    // Firebase 开关状态
    var fcmEnabled by remember { mutableStateOf(Firebase.messaging.isAutoInitEnabled) }
    var notificationEnabled by remember { mutableStateOf(Config.isOpenNotification) }
    var crashlyticsEnabled by remember {
        mutableStateOf(FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled)
    }
    var analyticsEnabled by remember {
        mutableStateOf(
            TGWrist.context.getSharedPreferences("com.google.firebase.analytics", 0)
                .getBoolean("collection_enabled", false)
        )
    }
    var performanceEnabled by remember {
        mutableStateOf(FirebasePerformance.getInstance().isPerformanceCollectionEnabled)
    }

    // 通知权限请求回调
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 用户同意权限后，注册 FCM 并开启通知
            registerFcmAndEnableNotification()
            notificationEnabled = true
        }
        // 用户拒绝则什么都不做，开关保持关闭
    }

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        edgeButton = {
            EdgeButton(
                onClick = {
                    nextPage.invoke()
                },
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDDFFD)),
                modifier =
                    // 如果用户开始从EdgeButton滚动
                    Modifier.scrollable(
                        listState,
                        orientation = Orientation.Vertical,
                        reverseDirection = true,
                        // 应对EdgeButton应用超滚动效果以适当调整滚动行为
                        overscrollEffect = overscroll,
                    ),
            ) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "go")
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscroll,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            item {
                ListHeader {
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.app_name),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // 欢迎使用 TG Wrist
            item {
                Text(
                    text = stringResource(R.string.welcome_message_1),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 快速开始使用
            item {
                Text(
                    text = stringResource(R.string.welcome_message_2),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 开始前设置标题
            item {
                ListHeader {
                    Text(
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Continue),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 功能提示
            item {
                Text(
                    text = stringResource(R.string.welcome_firebase_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // FCM 云消息推送
            item {
                RadioButton(
                    selected = fcmEnabled,
                    onSelect = {
                        val newValue = !fcmEnabled
                        Firebase.messaging.isAutoInitEnabled = newValue
                        fcmEnabled = newValue
                        if (!newValue) {
                            // 关闭 FCM 同时关闭通知
                            disableNotification()
                            notificationEnabled = false
                        }
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
                    enabled = true,
                    secondaryLabel = {
                        Text(
                            text = stringResource(R.string.Firebase_cloud_messaging_desc),
                            maxLines = 10
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(stringResource(R.string.Firebase_cloud_messaging))
                }
            }
            // 通知推送（开启时自动启用 FCM）
            item {
                RadioButton(
                    selected = notificationEnabled,
                    onSelect = {
                        val newValue = !notificationEnabled
                        if (newValue) {
                            // 如果 FCM 未开启，先自动启用
                            if (!fcmEnabled) {
                                Firebase.messaging.isAutoInitEnabled = true
                                fcmEnabled = true
                            }
                            // 检查通知权限
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                registerFcmAndEnableNotification()
                                notificationEnabled = true
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        } else {
                            disableNotification()
                            notificationEnabled = false
                        }
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
                    enabled = true,
                    secondaryLabel = {
                        Text(
                            text = stringResource(R.string.Push_notification_desc),
                            maxLines = 10
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(stringResource(R.string.Push_notification))
                }
            }
            // Firebase Crashlytics 崩溃日志
            item {
                RadioButton(
                    selected = crashlyticsEnabled,
                    onSelect = {
                        val newValue = !crashlyticsEnabled
                        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = newValue
                        crashlyticsEnabled = newValue
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                    enabled = true,
                    secondaryLabel = {
                        Text(
                            text = stringResource(R.string.Firebase_crashlytics_desc),
                            maxLines = 10
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(stringResource(R.string.Firebase_crashlytics))
                }
            }
            // Firebase Analytics 使用分析
            item {
                RadioButton(
                    selected = analyticsEnabled,
                    onSelect = {
                        val newValue = !analyticsEnabled
                        FirebaseAnalytics.getInstance(TGWrist.context)
                            .setAnalyticsCollectionEnabled(newValue)
                        TGWrist.context.getSharedPreferences("com.google.firebase.analytics", 0)
                            .edit { putBoolean("collection_enabled", newValue) }
                        analyticsEnabled = newValue
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Rounded.Analytics, contentDescription = null) },
                    enabled = true,
                    secondaryLabel = {
                        Text(
                            text = stringResource(R.string.Firebase_analytics_desc),
                            maxLines = 10
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(stringResource(R.string.Firebase_analytics))
                }
            }
            // Firebase Performance 性能分析
            item {
                RadioButton(
                    selected = performanceEnabled,
                    onSelect = {
                        val newValue = !performanceEnabled
                        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = newValue
                        performanceEnabled = newValue
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
                    enabled = true,
                    secondaryLabel = {
                        Text(
                            text = stringResource(R.string.Firebase_performance_desc),
                            maxLines = 10
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(stringResource(R.string.Firebase_performance))
                }
            }
            // 点击下方的>按钮登录
            item {
                Text(
                    text = stringResource(R.string.welcome_login_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
