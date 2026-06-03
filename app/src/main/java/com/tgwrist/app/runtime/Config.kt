package com.tgwrist.app.runtime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import com.tgwrist.app.data.ChatFolderInfo
import com.tgwrist.app.data.ForwardMessages
import com.tgwrist.app.data.ReplyMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi

object Config {
    // MainActivity 是否存在
    @Volatile
    var isMainActivityAlive: Boolean = false
    var isMainActivityOnFront: Boolean = false

    private const val PREF_NAME = "Config"

    // ConnectionState 状态码（给 Compose 订阅用）
    enum class ConnectionState(val code: Int) {
        // 1 = ConnectionStateReady
        Ready(1),
        // 2 = ConnectionStateConnecting
        Connecting(2),
        // 3 = ConnectionStateConnectingToProxy
        ConnectingToProxy(3),
        // 4 = ConnectionStateUpdating
        Updating(4),
        // 5 = ConnectionStateWaitingForNetwork
        WaitingForNetwork(5),
        // 6 = Unknown
        Unknown(6)
    }

    // 是否开启通知功能（StateFlow 供 Compose 订阅）
    private val _isOpenNotification = MutableStateFlow(false)
    val isOpenNotificationFlow = _isOpenNotification.asStateFlow()

    var isOpenNotification: Boolean
        get() = _isOpenNotification.value
        set(value) {
            prefs.edit { putBoolean("isOpenNotification", value) }
            _isOpenNotification.value = value
        }

    // 回复消息信息记录
    private val _replyMessage = MutableStateFlow<ReplyMessage?>(null)
    val replyMessageFlow = _replyMessage.asStateFlow()
    var replyMessage: ReplyMessage?
        get() = _replyMessage.value
        set(value) {
            _replyMessage.value = value
        }

    // 待转发消息记录
    private val _forwardMessages = MutableStateFlow<ForwardMessages?>(null)
    val forwardMessagesFlow = _forwardMessages.asStateFlow()
    var forwardMessages: ForwardMessages?
        get() = _forwardMessages.value
        set(value) {
            _forwardMessages.value = value
        }

    // 当前登录用户信息
    private val _currentUser = MutableStateFlow<TdApi.User?>(null)
    val currentUser = _currentUser.asStateFlow()

    // 当前登录用户详细信息
    private val _currentUserFullInfo = MutableStateFlow<TdApi.UserFullInfo?>(null)
    val currentUserFullInfo = _currentUserFullInfo.asStateFlow()

    // SharedPreferences（在 Application 的 onCreate 里初始化）
    private lateinit var prefs: SharedPreferences

    // 存储可用的 accent colors（只取 darkThemeColors）
    private val _accentColorList = MutableStateFlow<Map<Int, Color>>(
        mutableMapOf(
            0 to Color(0xFFD32F2F),
            1 to Color(0xFFF89A46),
            2 to Color(0xFF8B76E8),
            3 to Color(0xFF6FC352),
            4 to Color(0xFF52BEDE),
            5 to Color(0xFF438ED3),
            6 to Color(0xFFE76B8B)
        )
    )
    val accentColorList = _accentColorList.asStateFlow()

    // 当前连接状态（提供给 Compose 收集）
    private val _connectionState = MutableStateFlow(ConnectionState.WaitingForNetwork)
    val connectionState = _connectionState.asStateFlow()

    // 聊天文件夹未读信息
    private val _chatFolderInfo = MutableStateFlow<Map<String, ChatFolderInfo>>(emptyMap())
    val chatFolderInfo = _chatFolderInfo.asStateFlow()

    private const val MAIN_LIST = "MAIN"
    private const val ARCHIVE_LIST = "ARCHIVE"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _isOpenNotification.value = prefs.getBoolean("isOpenNotification", false)

        // 订阅连接状态更新
        // 订阅连接状态更新
        TgClient.subscribe(TdApi.UpdateConnectionState::class.java) { update ->
            Log.d("Config-Tdlib", "UpdateConnectionState received, update=$update")
            _connectionState.value = when (update.state.constructor) {
                TdApi.ConnectionStateReady.CONSTRUCTOR -> ConnectionState.Ready
                TdApi.ConnectionStateConnecting.CONSTRUCTOR -> ConnectionState.Connecting
                TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR -> ConnectionState.ConnectingToProxy
                TdApi.ConnectionStateUpdating.CONSTRUCTOR -> ConnectionState.Updating
                TdApi.ConnectionStateWaitingForNetwork.CONSTRUCTOR -> ConnectionState.WaitingForNetwork
                else -> ConnectionState.Unknown
            }
        }

        // 订阅 accent colors 更新（假设 TgClient.subscribe 可直接把 UpdateAccentColors 传入）
        TgClient.subscribe(TdApi.UpdateAccentColors::class.java) { update ->
            Log.d("Config-Tdlib", "UpdateAccentColors received, colorsCount=${update.colors.size}")
            // 处理 accent colors
            handleAccentColors(update)
        }

        // 订阅 authorization 更新
        TgClient.subscribe(TdApi.UpdateAuthorizationState::class.java) { update ->
            Log.d("Config-Tdlib", "UpdateAuthorizationState received, update=$update")
            if (update.authorizationState is TdApi.AuthorizationStateReady) {
                TgClient.send(TdApi.GetMe()) {
                    Log.d("Config-Tdlib", "GetMe result: $it")
                    if (it is TdApi.User) {
                        _currentUser.value = it
                        if (!it.firstName.isNullOrBlank()) {
                            UserManager.updateUserName(
                                it.id,
                                it.firstName + if (it.lastName.isNullOrBlank()) "" else " ${it.lastName}"
                            )
                        }

                        TgClient.send(TdApi.GetUserFullInfo(it.id)) { userFullInfo ->
                            Log.d("Config-Tdlib", "GetUserFullInfo result: $userFullInfo")
                            if (userFullInfo is TdApi.UserFullInfo) {
                                _currentUserFullInfo.value = userFullInfo
                            }
                        }
                    }
                }
            }
        }

        // 订阅用户自己更新
        TgClient.subscribe(TdApi.UpdateUser::class.java) { update ->
            if (update.user.id == _currentUser.value?.id) {
                _currentUser.value = update.user

                if (!update.user.firstName.isNullOrBlank()) {
                    UserManager.updateUserName(
                        update.user.id,
                        update.user.firstName + if (update.user.lastName.isNullOrBlank()) "" else " ${update.user.lastName}"
                    )
                }
            }
        }

        // 订阅用户自己更新
        TgClient.subscribe(TdApi.UpdateUserFullInfo::class.java) { update ->
            if (update.userId == _currentUser.value?.id) {
                _currentUserFullInfo.value = update.userFullInfo
            }
        }

        // 订阅聊天列表中未读消息相关会话的更新
        TgClient.subscribe(TdApi.UpdateUnreadChatCount::class.java) { update ->
            val index = when (val chatList = update.chatList) {
                is TdApi.ChatListMain -> MAIN_LIST
                is TdApi.ChatListArchive -> ARCHIVE_LIST
                is TdApi.ChatListFolder -> chatList.chatFolderId.toString()
                else -> null
            }
            index?.let {
                _chatFolderInfo.value = _chatFolderInfo.value.toMutableMap().apply {
                    this[it] = ChatFolderInfo(
                        markedAsUnreadCount = update.markedAsUnreadCount,
                        markedAsUnreadUnmutedCount = update.markedAsUnreadUnmutedCount,
                        totalCount = update.totalCount,
                        unreadCount = update.unreadCount,
                        unreadUnmutedCount = update.unreadUnmutedCount,
                    )
                }
            }
        }
    }

    private fun handleAccentColors(update: TdApi.UpdateAccentColors) {
        val map = _accentColorList.value.toMutableMap()

        // tdlib 文档要求：id 0..6 必须始终受支持，且不会出现在返回列表里 —— 在这里补上默认值（如不存在）
        //map[0] = Color(0xFFD32F2F) // 红色
        //map[1] = Color(0xFFF89A46) // 橙色
        //map[2] = Color(0xFF8B76E8) // 紫色/紫罗兰色
        //map[3] = Color(0xFF6FC352) // 绿色
        //map[4] = Color(0xFF52BEDE) // 青色
        //map[5] = Color(0xFF438ED3) // 蓝色
        //map[6] = Color(0xFFE76B8B) // 粉色

        // 从服务器返回的 accent colors 计算代表色（按 darkThemeColors 平均）
        for (color in update.colors) {
            val darkColors = color.darkThemeColors
            if (darkColors.isEmpty()) continue

            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (rgb in darkColors) {
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                sumR += r
                sumG += g
                sumB += b
            }
            val n = darkColors.size
            val avgR = sumR / n
            val avgG = sumG / n
            val avgB = sumB / n

            val composeColor = Color(
                red = avgR / 255f,
                green = avgG / 255f,
                blue = avgB / 255f,
                alpha = 1f
            )

            map[color.id] = composeColor
            Log.d("Tdlib", "AccentColor id=${color.id}, darkThemeColors=${darkColors.joinToString()}, Color=$composeColor")
        }

        // 原子替换整个 map
        _accentColorList.value = map
    }
}