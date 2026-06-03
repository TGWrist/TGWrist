package com.tgwrist.app.runtime

import android.util.Log
import androidx.navigation.NavController
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.messaging.FirebaseMessaging
import com.tgwrist.app.TGWrist
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.utils.setTdlibParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

object TdLibInitManage {
    private var isInit = false
    private val objectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val isPageOnLogin = MutableStateFlow(UserManager.getActiveUser() == null)
    val isPageOnLoginAndNeedReInitTG = MutableStateFlow(false)
    val needReInitOnDispose = MutableStateFlow(false)
    private val _navigateEvent = MutableSharedFlow<(NavController) -> Unit>(extraBufferCapacity = 1)
    val navigateEvent = _navigateEvent.asSharedFlow()

    fun init() {
        if (isInit) return
        isInit = true

        // 获取内部存储
        val internalDir: File = TGWrist.context.filesDir
        TgClient.subscribe(TdApi.UpdateAuthorizationState::class.java) { update ->
            when (update.authorizationState) {
                is TdApi.AuthorizationStateWaitPhoneNumber -> {
                    if (!isPageOnLogin.value) {
                        val userInfoNow = UserManager.getActiveUser()
                        if (userInfoNow != null) {
                            UserManager.removeUser(userInfoNow.userId)
                            internalDir.listFiles()?.find { it.name == userInfoNow.userId.toString() && it.isDirectory }?.deleteRecursively()
                            // 清除缓存（应该不用清除）
                            //context.cacheDir.deleteRecursively()
                            if (UserManager.getActiveUser() == null) {
                                navigateToLoginAndClearStack()
                                isPageOnLogin.value = true
                                TgClient.reInit()
                            } else {
                                navigateToHomeAndClearStack()
                                TgClient.reInit()
                            }
                        } else {
                            isPageOnLogin.value = true
                            _navigateEvent.tryEmit { navController ->
                                navController.navigate(Destinations.LOGIN) {
                                    popUpTo(Destinations.HOME) {
                                        inclusive = true
                                    }
                                }
                            }
                        }
                    } else {
                        if (isPageOnLoginAndNeedReInitTG.value) isPageOnLoginAndNeedReInitTG.value = false
                    }
                }
                is TdApi.AuthorizationStateReady -> {
                    if (isPageOnLogin.value) {
                        // 登录成功
                        TgClient.send(TdApi.GetMe()) {
                            if (it is TdApi.User) {
                                needReInitOnDispose.value = false
                                objectScope.launch {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            // 选项设置
                                            TgClient.send(TdApi.SetOption("disable_network_statistics", TdApi.OptionValueBoolean(false)))
                                            TgClient.send(TdApi.SetOption("disable_persistent_network_statistics", TdApi.OptionValueBoolean(false)))
                                            TgClient.send(TdApi.SetOption("use_storage_optimizer", TdApi.OptionValueBoolean(true)))
                                            TgClient.send(TdApi.SetOption("use_pfs", TdApi.OptionValueBoolean(true)))
                                            TgClient.send(TdApi.SetOption("always_parse_markdown", TdApi.OptionValueBoolean(true)))
                                            TgClient.send(TdApi.SetOption("notification_group_count_max", TdApi.OptionValueInteger(15)))
                                            TgClient.send(TdApi.SetOption("notification_group_size_max", TdApi.OptionValueInteger(10)))
                                            if (Config.isOpenNotification) {
                                                FirebaseMessaging.getInstance().token
                                                    .addOnCompleteListener { task ->
                                                        if (task.isSuccessful) {
                                                            TgClient.send(
                                                                TdApi.RegisterDevice(
                                                                    TdApi.DeviceTokenFirebaseCloudMessaging(task.result, true),
                                                                    null
                                                                )
                                                            )
                                                            Config.isOpenNotification = true
                                                        }
                                                    }
                                            }

                                            // 等待并停止 Tdlib 实例，确保文件不被占用
                                            delay(3000.milliseconds)
                                            TgClient.close(0)
                                            delay(1000.milliseconds)

                                            // 使用 applicationContext.filesDir 作为 internalDir（如果你已有 internalDir，可替换）
                                            val internalDir = TGWrist.context.applicationContext.filesDir

                                            // 删除已有 /<id>/tdlib（如果存在）
                                            val targetParent = File(internalDir, it.id.toString())
                                            targetParent.listFiles()?.find { file -> file.name == "tdlib" && file.isDirectory }?.deleteRecursively()

                                            // 源目录： /files/tdlib
                                            val sourceDir = File(internalDir, "tdlib")
                                            if (!sourceDir.exists()) {
                                                throw IOException("Source folder does not exist: ${sourceDir.absolutePath}")
                                            }

                                            // 确保目标父目录存在： /files/<id>
                                            if (!targetParent.exists() && !targetParent.mkdirs()) {
                                                throw IOException("Failed to create target parent dir: ${targetParent.absolutePath}")
                                            }

                                            // 目标： /files/<id>/tdlib
                                            val targetDir = File(targetParent, "tdlib")

                                            // 尝试直接重命名
                                            if (!sourceDir.renameTo(targetDir)) {
                                                sourceDir.copyRecursively(targetDir, overwrite = true)
                                                sourceDir.deleteRecursively()
                                            }

                                            UserManager.addUser(it.id, "${it.firstName} ${it.lastName}")
                                            UserManager.switchActiveUser(it.id)

                                            // 必须在 init() 之前置 false：
                                            // init() 是异步的，会从其他线程回调 WaitTdlibParameters，
                                            // 该回调依赖 isPageOnLogin 决定加载用户目录还是全新目录。
                                            // 性能好的设备上回调会抢在这一步之前执行，导致用错目录、登录丢失。
                                            isPageOnLogin.value = false

                                            // 重新初始化
                                            TgClient.init()
                                            // 登录完成
                                            println("Login success")
                                            navigateToHomeAndClearStack()
                                            Firebase.analytics.logEvent(FirebaseAnalytics.Event.LOGIN, null)
                                        } catch (e: Exception) {
                                            Log.e("Login", "Login failed", e)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true)))
                        TgClient.send(TdApi.LoadChats(TdApi.ChatListFolder(0), 15)) {
                            Log.d("Tdlib", "LoadChats result: $it")
                        }
                        if (Config.isOpenNotification) {
                            FirebaseMessaging.getInstance().token
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        TgClient.send(
                                            TdApi.RegisterDevice(
                                                TdApi.DeviceTokenFirebaseCloudMessaging(task.result, true),
                                                null
                                            )
                                        ) {
                                            if (it is TdApi.PushReceiverId) UserManager.updatePushReceiverId(pushReceiverId = it.id)
                                        }
                                        Config.isOpenNotification = true
                                    }
                                }
                        }
                    }
                }
                is TdApi.AuthorizationStateLoggingOut -> {
                    if (!isPageOnLogin.value) {
                        val userInfoNow = UserManager.getActiveUser()
                        if (userInfoNow != null) {
                            UserManager.removeUser(userInfoNow.userId)
                            internalDir.listFiles()?.find { it.name == userInfoNow.userId.toString() && it.isDirectory }?.deleteRecursively()
                            // 清除缓存（应该不用清除）
                            //context.cacheDir.deleteRecursively()
                            if (UserManager.getActiveUser() == null) {
                                navigateToLoginAndClearStack()
                                isPageOnLogin.value = true
                                TgClient.reInit()
                            } else {
                                navigateToHomeAndClearStack()
                                TgClient.reInit()
                            }
                        } else {
                            isPageOnLogin.value = true
                            _navigateEvent.tryEmit { navController ->
                                navController.navigate(Destinations.LOGIN) {
                                    popUpTo(Destinations.HOME) {
                                        inclusive = true
                                    }
                                }
                            }
                        }
                    } else {
                        if (isPageOnLoginAndNeedReInitTG.value) TgClient.close(0)
                    }
                }
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    if (isPageOnLogin.value) {
                        isPageOnLoginAndNeedReInitTG.value = false
                        TGWrist.context.setTdlibParameters(null)
                    } else {
                        val userInfo = UserManager.getActiveUser()
                        if (userInfo != null) TGWrist.context.setTdlibParameters(userInfo.userId.toString())
                        else {
                            _navigateEvent.tryEmit { navController ->
                                navController.navigate(Destinations.LOGIN) {
                                    popUpTo(Destinations.HOME) {
                                        inclusive = true
                                    }
                                }
                            }
                        }
                    }
                }
                is TdApi.AuthorizationStateClosed -> {
                    if (isPageOnLogin.value && isPageOnLoginAndNeedReInitTG.value) {
                        isPageOnLoginAndNeedReInitTG.value = false
                        TgClient.reInit()
                    }
                }
            }
        }
    }

    private fun navigateToLoginAndClearStack() {
        _navigateEvent.tryEmit { navController ->
            navController.navigate(Destinations.LOGIN) {
                popUpTo(navController.graph.id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    private fun navigateToHomeAndClearStack() {
        _navigateEvent.tryEmit { navController ->
            navController.navigate(Destinations.HOME) {
                popUpTo(navController.graph.id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }
}
