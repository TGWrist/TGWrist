package com.tgwrist.app

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import androidx.work.WorkManager
import com.tgwrist.app.data.AlertDialogItem
import com.tgwrist.app.data.SharedMessageInfoKey
import com.tgwrist.app.runtime.CALL_STATE_NONE
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.GlobalEventBus
import com.tgwrist.app.runtime.OPEN_CALL_PAGE
import com.tgwrist.app.runtime.TdLibInitManage
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.runtime.UserManager
import com.tgwrist.app.ui.AboutScreen
import com.tgwrist.app.ui.CallScreen
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.IMGViewScreen
import com.tgwrist.app.ui.MediaPickerScreen
import com.tgwrist.app.ui.TestScreen
import com.tgwrist.app.ui.TextViewScreen
import com.tgwrist.app.ui.VideoPlayerScreen
import com.tgwrist.app.ui.chat.ChatScreen
import com.tgwrist.app.ui.login.SplashLoginScreen
import com.tgwrist.app.ui.main.SplashMainScreen
import com.tgwrist.app.ui.message.info.MessageInfo
import com.tgwrist.app.ui.settings.AddProxyScreen
import com.tgwrist.app.ui.settings.NetworkSettingsScreen
import com.tgwrist.app.ui.settings.SplashSettingsScreen
import com.tgwrist.app.ui.theme.TGWristTheme
import com.tgwrist.app.utils.GlobalAppState
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.MainViewModel
import com.tgwrist.app.utils.WifiNetworkRequester
import com.tgwrist.app.utils.getAppVersion
import com.tgwrist.app.utils.handleUrlNavigation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.drinkless.tdlib.TdApi
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.content.edit

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private val viewModel: MainViewModel by viewModels()
    private var pendingOpenChatId by mutableLongStateOf(-1L)
    private var openCallPage by mutableStateOf(false)
    private var pendingDeepLinkUrl by mutableStateOf<String?>(null)
    private lateinit var wifiRequester: WifiNetworkRequester // 声明一个 wifiRequester 属性，但不初始化

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenChatId = intent.getLongExtra("chatId", -1L)
        if (intent.getBooleanExtra("openCallPage", false)) openCallPage = true
        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { pendingDeepLinkUrl = it }
        }
    }

    override fun onDestroy() {
        Config.isMainActivityAlive = false
        Config.isMainActivityOnFront = false
        // 通话进行中不要把 TDLib 关掉：信令通道一旦断开，VoIP 会话会立刻挂掉
        if (TgCallManager.callState.value == CALL_STATE_NONE) {
            TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false)))
            TgClient.close()
        }
        if (::wifiRequester.isInitialized) {
            wifiRequester.release()
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        Config.isMainActivityOnFront = false
        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false)))
    }

    override fun onResume() {
        super.onResume()
        Config.isMainActivityOnFront = true
        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true)))
    }

    override fun onStart() {
        super.onStart()
        Config.isMainActivityOnFront = true
        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true)))
    }

    override fun onStop() {
        super.onStop()
        Config.isMainActivityOnFront = false
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

        Config.isMainActivityAlive = true
        Config.isMainActivityOnFront = true

        WorkManager.getInstance(this).cancelUniqueWork("notification_processing")

        // 版本更新检查：如果版本更新了，可以在这里执行一些一次性的更新逻辑
        prefs = getSharedPreferences("MainActivity", MODE_PRIVATE)
        prefs.getString("appVersion", null)?.let { savedVersion ->
            if (savedVersion != getAppVersion()) {
                // 版本更新了
                prefs.edit {putString("appVersion", getAppVersion())}
            }
        } ?: run {
            // 首次运行，记录版本号
            prefs.edit {putString("appVersion", getAppVersion())}
        }

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
        openCallPage = intent?.getBooleanExtra("openCallPage", false) ?: false
        if (intent?.action == Intent.ACTION_VIEW) {
            pendingDeepLinkUrl = intent?.dataString
        }

        // 让 Activity 能够在锁屏上显示，并点亮手表的屏幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContent {
            //val coroutineScope = rememberCoroutineScope()
            val navController = rememberSwipeDismissableNavController()
            val swipeState = rememberSwipeDismissableNavHostState()
            val lifecycleOwner = LocalLifecycleOwner.current
            val context = LocalContext.current
            val isCalling by TgCallManager.callState.collectAsState()

            // 初始化“数据共享区域”
            val appState = remember { GlobalAppState() }
            appState.navController = navController
            // 获取当前用户信息
            val userInfo = UserManager.getActiveUser()

            // 全局对话框宿主状态：任意非 Composable 代码可通过 GlobalEventBus.send(AlertDialogItem(...)) 弹窗
            var isShowGlobalDialog by remember { mutableStateOf(false) }
            var globalDialogItem by remember { mutableStateOf(AlertDialogItem()) }

            // 获取是否打开预先传入会话
            var consumedChatId by rememberSaveable { mutableLongStateOf(-1L) }

            LaunchedEffect(isCalling) {
                // 通话进行中：保持屏幕常亮、允许锁屏上方显示并点亮屏幕，方便用户随时操作
                // 非通话状态：移除这些标志，遵循正常的息屏 / 锁屏行为
                val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                if (isCalling != CALL_STATE_NONE) {
                    window.addFlags(flags)
                } else {
                    window.clearFlags(flags)
                }
            }

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

            // tdlib 心跳
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (isActive) {
                        delay(10000.milliseconds)
                        TgClient.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true)))
                    }
                }
            }

            LaunchedEffect(openCallPage) {
                if (openCallPage) {
                    openCallPage = false
                    intent.removeExtra("openCallPage")
                    navController.navigate(Destinations.CALL) {
                        // 如果目标页面已经在栈顶，则不会重新创建和打开新页面
                        launchSingleTop = true
                    }
                }
            }

            // 处理 tg:// 等深链接跳转
            LaunchedEffect(pendingDeepLinkUrl, userInfo) {
                val url = pendingDeepLinkUrl
                if (userInfo != null && url != null) {
                    pendingDeepLinkUrl = null
                    intent.data = null
                    handleUrlNavigation(url, context, navController)
                }
            }

            LaunchedEffect(Unit) {
                GlobalEventBus.subscribe<String>(
                    scope = this,
                    lifecycleOwner = lifecycleOwner
                ) { event ->
                    // 处理打开通话页面事件
                    if (event == OPEN_CALL_PAGE) {
                        navController.navigate(Destinations.CALL) {
                            // 如果目标页面已经在栈顶，则不会重新创建和打开新页面
                            launchSingleTop = true
                        }
                    }
                }
            }

            // 订阅全局对话框事件：任意代码 GlobalEventBus.send(AlertDialogItem(...)) 即可弹窗
            LaunchedEffect(Unit) {
                GlobalEventBus.subscribe<AlertDialogItem>(
                    scope = this,
                    lifecycleOwner = lifecycleOwner
                ) { item ->
                    globalDialogItem = item
                    isShowGlobalDialog = true
                }
            }

            // Routes和UI
            TGWristTheme {
                // 注入数据共享
                CompositionLocalProvider(LocalGlobalAppState provides appState) {
                    // 全局对话框宿主：覆盖在所有页面之上，由 GlobalEventBus.send(AlertDialogItem(...)) 触发
                    AlertDialog(
                        visible = isShowGlobalDialog,
                        onDismissRequest = {
                            globalDialogItem.onDismissRequest()
                            isShowGlobalDialog = false
                        },
                        confirmButton = {
                            AlertDialogDefaults.ConfirmButton(
                                onClick = {
                                    globalDialogItem.confirmButton()
                                    isShowGlobalDialog = false
                                }
                            )
                        },
                        title = globalDialogItem.title,
                        modifier = globalDialogItem.modifier,
                        icon = globalDialogItem.icon,
                        text = globalDialogItem.text,
                        verticalArrangement = globalDialogItem.verticalArrangement,
                        contentPadding = globalDialogItem.contentPadding ?: if (globalDialogItem.icon != null) {
                            AlertDialogDefaults.confirmDismissWithIconContentPadding()
                        } else {
                            AlertDialogDefaults.confirmDismissContentPadding()
                        },
                        properties = globalDialogItem.properties,
                        content = globalDialogItem.content,
                    )

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

                            // 网络设置
                            composable(Destinations.NETWORK) {
                                NetworkSettingsScreen()
                            }

                            // 添加代理
                            composable(Destinations.ADD_PROXY) {
                                AddProxyScreen()
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
                                    navArgument("key") { type = NavType.LongType },
                                    navArgument("showMsgsInfo") {
                                        type = NavType.BoolType
                                        defaultValue = true
                                    }
                                )
                            ) { backStackEntry ->
                                val chatId = backStackEntry.arguments?.getLong("chatId")
                                val showMsgsInfo = backStackEntry.arguments?.getBoolean("showMsgsInfo") ?: true
                                val key = backStackEntry.arguments?.getLong("key")
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
                                MessageInfo(chatId, sharedData.msgIdList, showMsgsInfo)
                            }

                            // 通话页面
                            composable(Destinations.CALL) {
                                CallScreen()
                            }

                            // 媒体选择器
                            composable(Destinations.MEDIA_PICKER) {
                                MediaPickerScreen()
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
