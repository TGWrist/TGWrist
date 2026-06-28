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
    private const val TAG = "TdLibInitManage"
    private var isInit = false
    private val objectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val isPageOnLogin = MutableStateFlow(UserManager.getActiveUser() == null)
    val isPageOnLoginAndNeedReInitTG = MutableStateFlow(false)
    val needReInitOnDispose = MutableStateFlow(false)
    private val _navigateEvent = MutableSharedFlow<(NavController) -> Unit>(extraBufferCapacity = 1)
    val navigateEvent = _navigateEvent.asSharedFlow()

    fun init() {
        Log.i(TAG, "init() called (isInit = $isInit)")
        if (isInit) return
        isInit = true

        // 获取内部存储
        val internalDir: File = TGWrist.context.filesDir
        TgClient.subscribe(TdApi.UpdateAuthorizationState::class.java) { update ->
            val stateName = update.authorizationState.javaClass.simpleName
            Log.i(TAG, "UpdateAuthorizationState: $stateName")
            when (update.authorizationState) {
                is TdApi.AuthorizationStateWaitPhoneNumber -> {
                    Log.d(TAG, "Handling WaitPhoneNumber. isPageOnLogin = ${isPageOnLogin.value}")
                    if (!isPageOnLogin.value) {
                        val userInfoNow = UserManager.getActiveUser()
                        Log.w(TAG, "WaitPhoneNumber but currently NOT on login page. Active user: ${userInfoNow?.userId}")
                        if (userInfoNow != null) {
                            Log.w(TAG, "Removing user ${userInfoNow.userId} and cleaning directories")
                            UserManager.removeUser(userInfoNow.userId)
                            val deleted = internalDir.listFiles()?.find { it.name == userInfoNow.userId.toString() && it.isDirectory }?.deleteRecursively()
                            Log.d(TAG, "Deleted user directory result: $deleted")
                            // 清除缓存（应该不用清除）
                            //context.cacheDir.deleteRecursively()
                            if (UserManager.getActiveUser() == null) {
                                Log.i(TAG, "No more active users, navigating to login and reInit TGClient")
                                navigateToLoginAndClearStack()
                                isPageOnLogin.value = true
                                TgClient.reInit()
                            } else {
                                Log.i(TAG, "Active user exists, navigating to home and reInit TGClient")
                                navigateToHomeAndClearStack()
                                TgClient.reInit()
                            }
                        } else {
                            Log.w(TAG, "userInfoNow is null, redirecting to login page")
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
                        if (isPageOnLoginAndNeedReInitTG.value) {
                            Log.d(TAG, "isPageOnLoginAndNeedReInitTG is true, setting to false")
                            isPageOnLoginAndNeedReInitTG.value = false
                        }
                    }
                }
                is TdApi.AuthorizationStateReady -> {
                    Log.i(TAG, "AuthorizationStateReady. isPageOnLogin = ${isPageOnLogin.value}")
                    if (isPageOnLogin.value) {
                        // 登录成功
                        Log.i(TAG, "Login success, invoking GetMe...")
                        TgClient.send(TdApi.GetMe()) {
                            if (it is TdApi.User) {
                                Log.i(TAG, "GetMe returned user: ID=${it.id}, name=${it.firstName} ${it.lastName}")
                                needReInitOnDispose.value = false
                                objectScope.launch {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            // 选项设置
                                            Log.d(TAG, "Setting TdLib options...")
                                            TgClient.send(TdApi.SetOption("disable_network_statistics", TdApi.OptionValueBoolean(false)))
                                            TgClient.send(TdApi.SetOption("disable_persistent_network_statistics", TdApi.OptionValueBoolean(false)))
                                            TgClient.send(TdApi.SetOption("use_storage_optimizer", TdApi.OptionValueBoolean(true)))
                                            TgClient.send(TdApi.SetOption("use_pfs", TdApi.OptionValueBoolean(true)))
                                            TgClient.send(TdApi.SetOption("always_parse_markdown", TdApi.OptionValueBoolean(true)))
                                            TgClient.send(TdApi.SetOption("notification_group_count_max", TdApi.OptionValueInteger(15)))
                                            TgClient.send(TdApi.SetOption("notification_group_size_max", TdApi.OptionValueInteger(10)))
                                            if (Config.isOpenNotification) {
                                                Log.d(TAG, "Registering FCM token...")
                                                FirebaseMessaging.getInstance().token
                                                    .addOnCompleteListener { task ->
                                                        if (task.isSuccessful) {
                                                            Log.d(TAG, "FCM token retrieved successfully, registering device...")
                                                            TgClient.send(
                                                                TdApi.RegisterDevice(
                                                                    TdApi.DeviceTokenFirebaseCloudMessaging(task.result, true),
                                                                    null
                                                                )
                                                            )
                                                            Config.isOpenNotification = true
                                                        } else {
                                                            Log.w(TAG, "Failed to retrieve FCM token", task.exception)
                                                        }
                                                    }
                                            }

                                            // 等待并停止 Tdlib 实例，确保文件不被占用
                                            Log.d(TAG, "Closing client to ensure files are not occupied (waiting 3s first)...")
                                            delay(3000.milliseconds)
                                            TgClient.close(0)
                                            Log.d(TAG, "Client closed, waiting 1s...")
                                            delay(1000.milliseconds)

                                            // 使用 applicationContext.filesDir 作为 internalDir（如果你已有 internalDir，可替换）
                                            val internalDir = TGWrist.context.applicationContext.filesDir
                                            val targetParent = File(internalDir, it.id.toString())
                                            Log.d(TAG, "Checking target parent directory for existing tdlib: ${targetParent.absolutePath}")

                                            // 删除已有 /<id>/tdlib（如果存在）
                                            val deletedOld = targetParent.listFiles()?.find { file -> file.name == "tdlib" && file.isDirectory }?.deleteRecursively()
                                            if (deletedOld == true) {
                                                Log.d(TAG, "Deleted old tdlib directory in targetParent")
                                            }

                                            // 源目录： /files/tdlib
                                            val sourceDir = File(internalDir, "tdlib")
                                            if (!sourceDir.exists()) {
                                                throw IOException("Source folder does not exist: ${sourceDir.absolutePath}")
                                            }

                                            // 确保目标 parent 目录存在： /files/<id>
                                            if (!targetParent.exists() && !targetParent.mkdirs()) {
                                                throw IOException("Failed to create target parent dir: ${targetParent.absolutePath}")
                                            }

                                            // 目标： /files/<id>/tdlib
                                            val targetDir = File(targetParent, "tdlib")
                                            Log.d(TAG, "Moving tdlib from ${sourceDir.absolutePath} to ${targetDir.absolutePath}")

                                            // 尝试直接重命名
                                            if (!sourceDir.renameTo(targetDir)) {
                                                Log.d(TAG, "Rename failed, fallback to copy/delete")
                                                sourceDir.copyRecursively(targetDir, overwrite = true)
                                                sourceDir.deleteRecursively()
                                            } else {
                                                Log.d(TAG, "Rename successful")
                                            }

                                            Log.i(TAG, "Saving user info in UserManager for user ID: ${it.id}")
                                            UserManager.addUser(it.id, "${it.firstName} ${it.lastName}")
                                            UserManager.switchActiveUser(it.id)

                                            // 必须在 init() 之前置 false：
                                            // init() 是异步的，会从其他线程回调 WaitTdlibParameters，
                                            // 该回调依赖 isPageOnLogin 决定加载用户目录还是全新目录。
                                            // 性能好的设备上回调会抢在这一步之前执行，导致用错目录、登录丢失。
                                            isPageOnLogin.value = false

                                            // 重新初始化
                                            Log.i(TAG, "Re-initializing TgClient for the logged-in user")
                                            TgClient.init()
                                            // 登录完成
                                            Log.i(TAG, "Login process completed successfully")
                                            navigateToHomeAndClearStack()
                                            Firebase.analytics.logEvent(FirebaseAnalytics.Event.LOGIN, null)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error in post-login setup", e)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "Ready and not on login page. Setting online = true, loading chats...")
                        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true)))
                        TgClient.send(TdApi.LoadChats(TdApi.ChatListFolder(0), 15)) {
                            Log.d(TAG, "LoadChats result: $it")
                        }
                        if (Config.isOpenNotification) {
                            Log.d(TAG, "FCM notification enabled, checking token for registered user...")
                            FirebaseMessaging.getInstance().token
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        Log.d(TAG, "FCM token retrieved, registering device...")
                                        TgClient.send(
                                            TdApi.RegisterDevice(
                                                TdApi.DeviceTokenFirebaseCloudMessaging(task.result, true),
                                                null
                                            )
                                        ) {
                                            if (it is TdApi.PushReceiverId) {
                                                Log.d(TAG, "RegisterDevice response: PushReceiverId = ${it.id}")
                                                UserManager.updatePushReceiverId(pushReceiverId = it.id)
                                            } else {
                                                Log.d(TAG, "RegisterDevice response: $it")
                                            }
                                        }
                                        Config.isOpenNotification = true
                                    } else {
                                        Log.w(TAG, "FCM token retrieve failed for registered user", task.exception)
                                    }
                                }
                        }
                    }
                }
                is TdApi.AuthorizationStateLoggingOut -> {
                    Log.w(TAG, "Logging out... isPageOnLogin = ${isPageOnLogin.value}")
                    if (!isPageOnLogin.value) {
                        val userInfoNow = UserManager.getActiveUser()
                        Log.w(TAG, "Logging out user: ${userInfoNow?.userId}")
                        if (userInfoNow != null) {
                            UserManager.removeUser(userInfoNow.userId)
                            val deleted = internalDir.listFiles()?.find { it.name == userInfoNow.userId.toString() && it.isDirectory }?.deleteRecursively()
                            Log.d(TAG, "Deleted user directory result: $deleted")
                            if (UserManager.getActiveUser() == null) {
                                Log.i(TAG, "No other users active, navigating to login and reInit")
                                navigateToLoginAndClearStack()
                                isPageOnLogin.value = true
                                TgClient.reInit()
                            } else {
                                Log.i(TAG, "Active user exists, navigating to home and reInit")
                                navigateToHomeAndClearStack()
                                TgClient.reInit()
                            }
                        } else {
                            Log.w(TAG, "userInfoNow is null during logout, navigating to login")
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
                        if (isPageOnLoginAndNeedReInitTG.value) {
                            Log.d(TAG, "Logging out on login page, closing client...")
                            TgClient.close(0)
                        } else {
                            Log.d(TAG, "Logging out on non-login page, setting isPageOnLoginAndNeedReInitTG = true")
                            isPageOnLoginAndNeedReInitTG.value = true
                        }
                    }
                }
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    Log.i(TAG, "State: WaitTdlibParameters. isPageOnLogin = ${isPageOnLogin.value}")
                    if (isPageOnLogin.value) {
                        Log.d(TAG, "On login page. Resetting parameters (userId = null)")
                        isPageOnLoginAndNeedReInitTG.value = false
                        TGWrist.context.setTdlibParameters(null)
                    } else {
                        val userInfo = UserManager.getActiveUser()
                        Log.d(TAG, "Not on login page. Setting parameters for active user: ${userInfo?.userId}")
                        if (userInfo != null) {
                            TGWrist.context.setTdlibParameters(userInfo.userId.toString())
                        } else {
                            Log.w(TAG, "No active user found in WaitTdlibParameters, redirecting to login page")
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
                    Log.w(TAG, "State: Closed. isPageOnLogin = ${isPageOnLogin.value}, isPageOnLoginAndNeedReInitTG = ${isPageOnLoginAndNeedReInitTG.value}")
                    if (isPageOnLogin.value && isPageOnLoginAndNeedReInitTG.value) {
                        Log.i(TAG, "Re-initializing TgClient after closure on login page")
                        isPageOnLoginAndNeedReInitTG.value = false
                        TgClient.reInit()
                    }
                }
            }
        }
    }

    private fun navigateToLoginAndClearStack() {
        Log.d(TAG, "navigateToLoginAndClearStack called")
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
        Log.d(TAG, "navigateToHomeAndClearStack called")
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
