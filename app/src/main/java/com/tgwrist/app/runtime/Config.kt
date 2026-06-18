package com.tgwrist.app.runtime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tgwrist.app.data.ChatFolderInfo
import com.tgwrist.app.data.ForwardMessages
import com.tgwrist.app.data.ProxyInfo
import com.tgwrist.app.data.ProxyKind
import com.tgwrist.app.data.ReplyMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi
import java.util.UUID

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

    // Gson 实例，用于代理列表序列化
    private val gson = Gson()

    // 代理列表持久化 key
    private const val KEY_PROXIES = "proxies"
    // 当前选中的代理 id（为空表示不使用代理 / 直连）
    private const val KEY_ACTIVE_PROXY_ID = "activeProxyId"

    // 本地保存的代理列表（StateFlow 供 Compose 订阅）
    private val _proxies = MutableStateFlow<List<ProxyInfo>>(emptyList())
    val proxiesFlow = _proxies.asStateFlow()

    // 当前选中的代理 id（StateFlow 供 Compose 订阅）
    private val _activeProxyId = MutableStateFlow<String?>(null)
    val activeProxyIdFlow = _activeProxyId.asStateFlow()

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
        loadProxies()

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
            } else if (update.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
                // 每次 tdlib 启动（多账户切换 / 重新初始化都会换一个 tdlib 实例）都要重新下发选中的代理。
                // AddProxy / DisableProxy 允许在授权前调用，所以这里直接下发即可。
                applyActiveProxyOnStart()
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

    // ============================== 代理（Proxy） ==============================

    /**
     * 从 SharedPreferences 加载代理列表与当前选中代理到内存。
     */
    private fun loadProxies() {
        val json = prefs.getString(KEY_PROXIES, "[]")
        val type = object : TypeToken<List<ProxyInfo>>() {}.type
        _proxies.value = runCatching { gson.fromJson<List<ProxyInfo>>(json, type) }
            .getOrNull() ?: emptyList()
        _activeProxyId.value = prefs.getString(KEY_ACTIVE_PROXY_ID, null)
            ?.takeIf { id -> _proxies.value.any { it.id == id } }
    }

    /**
     * 持久化当前内存中的代理列表。
     */
    private fun saveProxies() {
        prefs.edit { putString(KEY_PROXIES, gson.toJson(_proxies.value)) }
    }

    /**
     * 获取已保存的全部代理（只读快照）。
     */
    fun getProxies(): List<ProxyInfo> = _proxies.value

    /**
     * 获取当前选中的代理；未选中 / 直连时返回 null。
     */
    fun getActiveProxy(): ProxyInfo? =
        _activeProxyId.value?.let { id -> _proxies.value.find { it.id == id } }

    /**
     * 添加一个代理并保存到本地。
     *
     * @param setActive 是否在添加后立即将其设为当前选中代理（默认 true）
     * @return 新创建的 [ProxyInfo]
     */
    fun addProxy(
        server: String,
        port: Int,
        type: ProxyKind,
        username: String = "",
        password: String = "",
        secret: String = "",
        httpOnly: Boolean = false,
        setActive: Boolean = true
    ): ProxyInfo {
        val proxy = ProxyInfo(
            id = UUID.randomUUID().toString(),
            server = server,
            port = port,
            type = type,
            username = username,
            password = password,
            secret = secret,
            httpOnly = httpOnly
        )
        _proxies.value = _proxies.value + proxy
        saveProxies()
        if (setActive) setActiveProxy(proxy.id)
        return proxy
    }

    /**
     * 删除指定代理。若删除的是当前选中代理，则自动切换为直连。
     */
    fun removeProxy(proxyId: String) {
        _proxies.value = _proxies.value.filterNot { it.id == proxyId }
        saveProxies()
        if (_activeProxyId.value == proxyId) setActiveProxy(null)
    }

    /**
     * 设置当前选中代理（传 null 表示直连），并立即对正在运行的 tdlib 生效。
     *
     * 注意：仅更新内存 + 本地记录，并尝试对当前 tdlib 实例 enable/disable；
     * 真正在多账户下「每次 tdlib 启动时下发」由 [applyActiveProxyOnStart] 完成。
     */
    fun setActiveProxy(proxyId: String?) {
        val normalized = proxyId?.takeIf { id -> _proxies.value.any { it.id == id } }
        _activeProxyId.value = normalized
        prefs.edit {
            if (normalized == null) remove(KEY_ACTIVE_PROXY_ID)
            else putString(KEY_ACTIVE_PROXY_ID, normalized)
        }
        applyActiveProxyOnStart()
    }

    /**
     * 把当前选中代理下发给 tdlib。
     *
     * 应在每次 tdlib 启动（AuthorizationStateWaitTdlibParameters）后调用，
     * 因为代理列表保存在「每个账号各自的 tdlib 数据库」中，多账户切换 /
     * 重新初始化都会换一个 tdlib 实例，需要重新下发。
     *
     * - 选中某代理：AddProxy(enable = true) 直接添加并启用。
     * - 直连：DisableProxy() 关闭当前启用的代理。
     */
    fun applyActiveProxyOnStart() {
        val active = getActiveProxy()
        if (active == null) {
            TgClient.send(TdApi.DisableProxy()) { result ->
                Log.d("Config-Tdlib", "DisableProxy result: $result")
            }
        } else {
            TgClient.send(TdApi.AddProxy(active.toTdProxy(), true)) { result ->
                Log.d("Config-Tdlib", "AddProxy result: $result")
            }
        }
    }
}