package com.tgwrist.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import androidx.work.WorkManager
import com.tgwrist.app.data.SharedMessageInfoKey
import com.tgwrist.app.ui.AboutScreen
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.IMGViewScreen
import com.tgwrist.app.ui.TestScreen
import com.tgwrist.app.ui.TextViewScreen
import com.tgwrist.app.ui.VideoPlayerScreen
import com.tgwrist.app.ui.chat.ChatScreen
import com.tgwrist.app.ui.login.SplashLoginScreen
import com.tgwrist.app.ui.main.SplashMainScreen
import com.tgwrist.app.ui.message.info.MessageInfo
import com.tgwrist.app.ui.settings.SplashSettingsScreen
import com.tgwrist.app.ui.theme.TGWristTheme
import com.tgwrist.app.utils.GlobalAppState
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.MainViewModel
import com.tgwrist.app.utils.TdLibInitManage
import com.tgwrist.app.utils.TgClient
import com.tgwrist.app.utils.UserManager
import com.tgwrist.app.utils.WifiNetworkRequester
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingOpenChatId by mutableLongStateOf(-1L)
    private lateinit var wifiRequester: WifiNetworkRequester // 声明一个 wifiRequester 属性，但不初始化

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenChatId = intent.getLongExtra("chatId", -1L)
    }

    override fun onDestroy() {
        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false)))
        TgClient.close()
        if (::wifiRequester.isInitialized) {
            wifiRequester.release()
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false)))
    }

    override fun onResume() {
        super.onResume()
        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true)))
    }

    override fun onStart() {
        super.onStart()
        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true)))
    }

    override fun onStop() {
        super.onStop()
        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 初始化启动页
        val splashScreen = installSplashScreen()
        // 设置停留条件 (Condition)
        splashScreen.setKeepOnScreenCondition {
            // 只要 isLoading.value 是 true，就会一直显示启动图标
            viewModel.isLoading.value
        }

        super.onCreate(savedInstanceState)

        WorkManager.getInstance(this).cancelUniqueWork("notification_processing")

        // 获取WiFi权限
        wifiRequester = WifiNetworkRequester(this)
        wifiRequester.requestWifi(
            onAvailable = { network ->
                // WiFi 已连接
            },
            onUnavailable = {
                // 没有可用 WiFi
            }
        )

        // 赋值传入的chatId
        pendingOpenChatId = intent?.getLongExtra("chatId", -1L) ?: -1L

        // 初始化 TdLib 管理器
        /*TdLibInitManage.init()

        // 初始化 TDLib 客户端
        TgClient.close()
        TgClient.init()*/

        setContent {
            //val coroutineScope = rememberCoroutineScope()
            val navController = rememberSwipeDismissableNavController()
            val swipeState = rememberSwipeDismissableNavHostState()

            // 初始化“数据共享区域”
            val appState = remember { GlobalAppState() }
            appState.navController = navController
            // 获取当前用户信息
            val userInfo = UserManager.getActiveUser()

            // 获取是否打开预先传入会话
            var consumedChatId by rememberSaveable { mutableLongStateOf(-1L) }

            LaunchedEffect(pendingOpenChatId, userInfo) {
                val chatId = pendingOpenChatId
                if (userInfo != null && chatId != -1L && chatId != consumedChatId) {
                    consumedChatId = chatId
                    pendingOpenChatId = -1L
                    intent.removeExtra("chatId")
                    navController.navigate(Destinations.chat(chatId))
                }
            }

            LaunchedEffect(Unit) {
                TdLibInitManage.navigateEvent.collect { navigationAction ->
                    // 把前台安全的 navController 交给这个动作去执行
                    navigationAction(navController)
                }
            }

            // Routes和UI
            TGWristTheme {
                // 注入数据共享
                CompositionLocalProvider(LocalGlobalAppState provides appState) {
                    // 跨页面UI元素流动
                    SharedTransitionLayout {
                        SwipeDismissableNavHost(
                            navController = navController,
                            state = swipeState,
                            startDestination = if (userInfo == null) Destinations.LOGIN else Destinations.HOME,
                            userSwipeEnabled = true
                        ) {

                            // 登录页面
                            composable(Destinations.LOGIN) {
                                SplashLoginScreen()
                            }

                            // 主页
                            composable(Destinations.HOME) {
                                SplashMainScreen()
                            }

                            // 关于
                            composable(Destinations.ABOUT) {
                                AboutScreen()
                            }

                            // 设置页面
                            composable(
                                route = Destinations.SETTINGS,
                                arguments = listOf(navArgument("index") { type = NavType.IntType })
                            ) { backStackEntry ->
                                val index = backStackEntry.arguments?.getInt("index") ?: 0
                                SplashSettingsScreen(index)
                            }

                            // 聊天页面
                            composable(
                                route = Destinations.CHAT,
                                arguments = listOf(navArgument("chatId") { type = NavType.LongType }),
                            ) { backStackEntry ->
                                val chatId = backStackEntry.arguments?.getLong("chatId")
                                if (chatId != null) {
                                    ChatScreen(chatId)
                                }
                            }

                            // 图片展示页面
                            composable(
                                route = Destinations.IMG_VIEW,
                                arguments = listOf(navArgument("path") { type = NavType.StringType }),
                            ) { backStackEntry ->
                                val path = backStackEntry.arguments?.getString("path")?.let { Uri.decode(it) }
                                IMGViewScreen(path)
                            }

                            // 视频播放页面
                            composable(
                                route = Destinations.VIDEO_VIEW,
                                arguments = listOf(navArgument("path") { type = NavType.StringType }),
                            ) { backStackEntry ->
                                val path = backStackEntry.arguments?.getString("path")?.let { Uri.decode(it) }
                                VideoPlayerScreen(path)
                            }

                            // 文本展示页面
                            composable(
                                route = Destinations.TEXT_VIEW,
                                arguments = listOf(
                                    navArgument("text") {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    },
                                    navArgument("textId") {
                                        type = NavType.LongType
                                        defaultValue = -1L
                                    }
                                ),
                            ) { backStackEntry ->
                                val text = backStackEntry.arguments?.getString("text")?.let { Uri.decode(it) }
                                val textId = backStackEntry.arguments
                                    ?.getLong("textId", -1L)
                                    ?.takeIf { it != -1L }
                                TextViewScreen(text, textId)
                            }

                            // 消息详情展示
                            composable(
                                route = Destinations.MESSAGE_INFO,
                                arguments = listOf(
                                    navArgument("chatId") { type = NavType.LongType },
                                    navArgument("key") { type = NavType.LongType }
                                )
                            ) {
                                val chatId = it.arguments?.getLong("chatId")
                                val key = it.arguments?.getLong("key")
                                if (chatId == null || key == null) {
                                    return@composable
                                }
                                val sharedData = appState.sharedMessageInfo[SharedMessageInfoKey(chatId, key)]
                                if (sharedData == null) {
                                    LaunchedEffect(chatId, key) {
                                        navController.popBackStack()
                                    }
                                    return@composable
                                }
                                MessageInfo(chatId, sharedData.msgIdList)
                            }

                            // 测试页面
                            composable(Destinations.TEST) {
                                TestScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
