package com.tgwrist.app.runtime

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tgwrist.app.utils.copy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import kotlin.time.Duration.Companion.milliseconds

/**
 * 聊天消息仓库，负责把 TDLib 的消息接口包装成 UI 更容易消费的状态。
 *
 * 主要职责：
 * 1. 只为当前仍被界面绑定的 chat 缓存消息，避免后台无限增长。
 * 2. 启动时围绕已读位置加载一段消息，让未读入口附近的数据优先可见。
 * 3. 在用户上滑/下滑时分别补旧消息和新消息。
 * 4. 订阅 TDLib 的实时更新，把新增、编辑、删除、发送成功、已读位置同步到 StateFlow。
 */
object ChatMessagesRepository {
    // 仓库内部统一使用 Default 线程池处理 TDLib 回调后的状态合并，SupervisorJob 避免单个任务失败影响其它任务。
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // chatId -> (messageId -> message)。value 允许为 null，是为了兼容“知道有这个 id 但消息内容还没加载”的占位场景。
    private val _messagesByChat = MutableStateFlow<Map<Long, Map<Long, TdApi.Message?>>>(emptyMap())
    val messagesByChat: StateFlow<Map<Long, Map<Long, TdApi.Message?>>> = _messagesByChat.asStateFlow()

    // 出站消息已读位置: chatId -> lastReadOutboxMessageId
    // 当 message.id <= lastReadOutboxMessageId 时，表示对方已读
    private val _lastReadOutboxMessageId = MutableStateFlow<Map<Long, Long>>(emptyMap())

    // 入站消息已读位置: chatId -> lastReadInboxMessageId
    // 当 message.id <= lastReadInboxMessageId 时，表示我已读该消息
    private val _lastReadInboxMessageId = MutableStateFlow<Map<Long, Long>>(emptyMap())

    // 话题信息缓存: chatId -> (topicId -> ForumTopic)
    private val _forumTopicsByChat = MutableStateFlow<Map<Long, Map<Long, TdApi.ForumTopic>>>(emptyMap())
    val forumTopicsByChat: StateFlow<Map<Long, Map<Long, TdApi.ForumTopic>>> = _forumTopicsByChat.asStateFlow()

    // 初始化首屏滚动目标：第一条未读消息 id（无未读时为 0）
    // Repository 在初始化加载到第一条未读后写入；UI 用它做首次滚动 target
    private val _initialUnreadTargetMessageId = MutableStateFlow<Map<Long, Long>>(emptyMap())

    // 聊天初始化加载状态，UI 用它区分 "初始化中" 和 "真的没有未读"
    private val _initialLoadFinished = MutableStateFlow<Map<Long, Boolean>>(emptyMap())

    // lock 保护下面这些“是否绑定/是否加载中/是否到头”的普通集合，避免 TDLib 回调和 UI 调用并发改状态。
    private val lock = Any()
    // 每个 LifecycleOwner 当前绑定了哪些 chat；owner 销毁时会根据这里统一解绑。
    private val ownerChatIds = mutableMapOf<LifecycleOwner, MutableSet<Long>>()
    // 每个 chat 被多少个界面观察。计数降到 0 时清理该 chat 的缓存和加载状态。
    private val chatObserverCount = mutableMapOf<Long, Int>()
    // 保存已注册的生命周期观察者，防止同一个 owner 重复注册 onDestroy 回调。
    private val ownerObservers = mutableMapOf<LifecycleOwner, DefaultLifecycleObserver>()

    // 记录已完成初始化加载的 chatId
    private val initialLoadDone = mutableSetOf<Long>()
    // 记录向新消息方向的加载锚点：key不存在=未初始化；value=Long=还有更新消息未加载；value=null=已到最新
    private val newerAnchorMessageId = mutableMapOf<Long, Long?>()
    // 防止同一 chatId 重复触发初始化加载
    private val initialLoadInflight = mutableSetOf<Long>()
    // 防止同一 chatId 重复触发向新消息加载
    private val newerLoadInflight = mutableSetOf<Long>()
    // 防止同一 chatId 重复触发向旧历史加载
    private val olderLoadInflight = mutableSetOf<Long>()
    // 记录已经没有更旧历史消息的 chatId
    private val olderHistoryEndReached = mutableSetOf<Long>()
    // 记录旧历史加载无推进次数
    private val olderNoProgressCount = mutableMapOf<Long, Int>()

    private var initialized = false

    data class LoadHistoryResult(
        val loadedCount: Int,
        val reachedEnd: Boolean,
        val errorMessage: String? = null
    )

    data class LoadNewerResult(
        val loadedCount: Int,
        val reachedLatest: Boolean,
        val errorMessage: String? = null
    )

    /**
     * 初始化仓库级订阅。应用启动时调用一次即可，重复调用会被 initialized 挡住。
     */
    fun init() {
        if (initialized) return
        initialized = true
        subscribeAll()
    }

    /**
     * 将某个界面生命周期和 chat 绑定起来。
     *
     * 绑定后这个 chat 才被认为是 active，TDLib 回调和分页加载才会写入缓存。
     * 第一个观察者出现时会初始化已读位置，并启动“围绕已读点”的首屏加载。
     */
    fun bindChat(lifecycleOwner: LifecycleOwner, chatId: Long) {
        val shouldStartInitialLoad = synchronized(lock) {
            val chatIds = ownerChatIds.getOrPut(lifecycleOwner) { mutableSetOf() }
            val isNewBinding = chatIds.add(chatId)
            if (!isNewBinding) return

            attachOwnerDestroyObserverIfNeed(lifecycleOwner)

            val nextCount = (chatObserverCount[chatId] ?: 0) + 1
            chatObserverCount[chatId] = nextCount
            if (nextCount == 1) {
                // 从 ChatsRepository 初始化出站/入站已读位置
                seedLastReadOutboxMessageId(chatId)
                seedLastReadInboxMessageId(chatId)
            }

            // 在锁内检查并设置 initialLoadInflight
            if (chatId !in initialLoadDone && chatId !in initialLoadInflight) {
                initialLoadInflight.add(chatId)
                true
            } else {
                false
            }
        }

        // 在锁外启动初始化加载（避免死锁）
        if (shouldStartInitialLoad) {
            scope.launch { initialLoadAroundReadPositionReserved(chatId) }
        }
    }

    /**
     * 主动解绑 chat。通常界面销毁时会自动解绑，手动调用可提前释放缓存。
     */
    fun unbindChat(lifecycleOwner: LifecycleOwner, chatId: Long) {
        synchronized(lock) {
            removeOwnerChatBindingLocked(lifecycleOwner, chatId)
        }
    }

    /**
     * UI 订阅某个 chat 的消息列表。
     *
     * 内部缓存按 messageId 存 Map，这里转成 List 并按 id 倒序排列，方便聊天页直接渲染。
     */
    fun getChatMessagesFlow(chatId: Long): Flow<List<TdApi.Message>> =
        _messagesByChat
            .map { it[chatId] }
            .distinctUntilChanged()
            .map { it?.values?.filterNotNull()?.sortedByDescending(TdApi.Message::id) ?: emptyList() }

    /**
     * 获取某个 chat 的出站消息已读位置 Flow
     * 当 message.id <= 返回值时，表示对方已读该消息
     */
    fun getLastReadOutboxMessageIdFlow(chatId: Long): Flow<Long> =
        _lastReadOutboxMessageId
            .map { it[chatId] ?: 0L }
            .distinctUntilChanged()

    /**
     * 获取某个 chat 的入站消息已读位置 Flow
     * 当 message.id <= 返回值时，表示我已读该消息
     */
    fun getLastReadInboxMessageIdFlow(chatId: Long): Flow<Long> =
        _lastReadInboxMessageId
            .map { it[chatId] ?: 0L }
            .distinctUntilChanged()

    /**
     * 获取初始化首屏滚动目标消息 ID
     * 返回第一条未读消息的 ID，无未读时返回 0
     */
    fun getInitialUnreadTargetMessageIdFlow(chatId: Long): Flow<Long> =
        _initialUnreadTargetMessageId
            .map { it[chatId] ?: 0L }
            .distinctUntilChanged()

    /**
     * 获取聊天初始化加载完成状态
     * 用于 UI 区分 "初始化中" 和 "真的没有未读"
     */
    fun getInitialLoadFinishedFlow(chatId: Long): Flow<Boolean> =
        _initialLoadFinished
            .map { it[chatId] ?: false }
            .distinctUntilChanged()

    /**
     * 将历史消息写入缓存。
     * 注意：不再内部启动协程，调用方应确保已在协程上下文中调用，
     * 这样 onResult 才会在 state 真正更新之后执行。
     */
    private fun addHistoryMessages(chatId: Long, messages: Array<TdApi.Message?>?) {
        val validMessages = messages?.filterNotNull().orEmpty()
        if (validMessages.isEmpty()) return
        // 二次检查 chat 是否仍然活跃（防止异步竞态）
        if (!isChatActive(chatId)) return

        _messagesByChat.update { prev ->
            // 在 update 块内再次检查，防止在等待锁期间 chat 已被解绑
            if (!isChatActive(chatId)) return@update prev
            val current = prev[chatId].orEmpty().toMutableMap()
            validMessages.forEach { message ->
                current[message.id] = message
            }
            prev + (chatId to current.toMap())
        }
    }


    private const val MAX_RETRY_COUNT = 3
    private const val BASE_RETRY_DELAY_MS = 1000L // 初始重试延迟 1 秒，之后指数退避
    private const val TD_REQUEST_TIMEOUT_MS = 15_000L

    /**
     * 把 TDLib 的 callback 风格请求转换成 suspend 函数，方便用顺序代码写重试和状态更新。
     */
    private suspend fun sendTdRequest(request: TdApi.Function<*>): TdApi.Object {
        return withTimeoutOrNull(TD_REQUEST_TIMEOUT_MS.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                TgClient.send(request) { result ->
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(result))
                    }
                }
            }
        } ?: TdApi.Error(408, "TDLib request timed out: ${request.javaClass.simpleName}")
    }

    /**
     * 初始化加载前先拿 chat 元信息。
     *
     * 优先使用 ChatsRepository 里已有的 chat；没有的话主动向 TDLib 查询，并回填到 ChatsRepository。
     */
    private suspend fun getChatForInitialLoad(chatId: Long): TdApi.Chat? {
        ChatsRepository.getChatOnce(chatId)?.let { return it }

        return when (val result = sendTdRequest(TdApi.GetChat(chatId))) {
            is TdApi.Chat -> {
                ChatsRepository.upsertChats(listOf(result))
                result
            }
            is TdApi.Error -> {
                Log.e("ChatMessagesRepository", "getChatForInitialLoad failed: ${result.message}")
                null
            }
            else -> {
                Log.e("ChatMessagesRepository", "getChatForInitialLoad unknown result: ${result::class.java.simpleName}")
                null
            }
        }
    }

    /**
     * 第一次进入 chat 时的加载入口。
     *
     * 这里不会简单拉最新 20 条，而是优先围绕 lastReadInboxMessageId 拉取：
     * 有未读时，用户更容易直接看到“已读/未读分界线”附近的消息。
     */
    private suspend fun initialLoadAroundReadPosition(chatId: Long) {
        // 在锁内做幂等检查：
        // - 已完成初始化（initialLoadDone）：无需重复加载，直接返回。
        // - 已有同 chatId 的初始化在途（initialLoadInflight）：避免并发重复触发，直接返回。
        // 通过检查后，将 chatId 加入 inflight 集合，表示本次加载已"占坑"。
        synchronized(lock) {
            if (chatId in initialLoadDone || chatId in initialLoadInflight) return
            initialLoadInflight.add(chatId)
        }

        try {
            var retryCount = 0
            // 循环条件：未超过最大重试次数，且 chat 仍处于活跃状态（界面未销毁）。
            while (retryCount <= MAX_RETRY_COUNT && isChatActive(chatId)) {
                // 执行一次加载尝试；返回 true 表示本次流程可以正常结束（成功或无需继续）。
                if (doSingleInitialLoadAttempt(chatId, retryCount)) return

                // 已达最大重试次数，放弃继续重试，直接退出。
                if (retryCount == MAX_RETRY_COUNT) return

                // 指数退避等待：第 0 次失败等 1s，第 1 次等 2s，第 2 次等 4s……
                delay((BASE_RETRY_DELAY_MS shl retryCount).milliseconds)
                retryCount++
            }
        } finally {
            // 无论正常结束还是异常退出，都要把 chatId 从 inflight 中移除，
            // 以便后续在必要时可以重新触发初始化加载。
            synchronized(lock) { initialLoadInflight.remove(chatId) }
        }
    }

    /**
     * 第一次进入 chat 时的加载入口（已在 bindChat 锁内设置 initialLoadInflight）。
     *
     * 这里不会简单拉最新 20 条，而是优先围绕 lastReadInboxMessageId 拉取：
     * 有未读时，用户更容易直接看到"已读/未读分界线"附近的消息。
     */
    private suspend fun initialLoadAroundReadPositionReserved(chatId: Long) {
        try {
            var retryCount = 0
            // 循环条件：未超过最大重试次数，且 chat 仍处于活跃状态（界面未销毁）。
            while (retryCount <= MAX_RETRY_COUNT && isChatActive(chatId)) {
                // 执行一次加载尝试；返回 true 表示本次流程可以正常结束（成功或无需继续）。
                if (doSingleInitialLoadAttempt(chatId, retryCount)) return

                // 已达最大重试次数，放弃继续重试，退出并fallback。
                if (retryCount == MAX_RETRY_COUNT) {
                    fallbackLoadLatestMessages(chatId)
                    return
                }

                // 指数退避等待：第 0 次失败等 1s，第 1 次等 2s，第 2 次等 4s……
                delay((BASE_RETRY_DELAY_MS shl retryCount).milliseconds)
                retryCount++
            }
        } finally {
            // 无论正常结束还是异常退出，都要把 chatId 从 inflight 中移除，
            // 以便后续在必要时可以重新触发初始化加载。
            synchronized(lock) { initialLoadInflight.remove(chatId) }
        }
    }

    /**
     * 执行一次初始化加载尝试。
     *
     * 返回 true 表示这次流程已经可以结束；返回 false 表示可以让外层按指数退避重试。
     */
    private suspend fun doSingleInitialLoadAttempt(chatId: Long, retryCount: Int): Boolean {
        val chat = getChatForInitialLoad(chatId)
        if (chat == null) {
            Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad: chat not available for $chatId")
            return false
        }

        val lastReadId = chat.lastReadInboxMessageId
        val unreadCount = chat.unreadCount
        val hasUnread = unreadCount > 0

        Log.d("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad chatId=$chatId " +
                "unreadCount=$unreadCount lastReadId=$lastReadId hasUnread=$hasUnread " +
                "attempt=${retryCount + 1}")

        if (!hasUnread) {
            // 无未读分支：简化为加载最新消息，类似参考代码的 firstLoad 逻辑
            // 使用 fromMessageId=0L 从最新消息开始加载
            val request = TdApi.GetChatHistory(chatId, 0L, 0, 20, false)
            return when (val result = sendTdRequest(request)) {
                is TdApi.Messages -> {
                    if (!isChatActive(chatId)) return true

                    val validMessages = result.messages?.filterNotNull().orEmpty()
                    addHistoryMessages(chatId, validMessages.toTypedArray())

                    var chatStillActive = true
                    synchronized(lock) {
                        chatStillActive = (chatObserverCount[chatId] ?: 0) > 0
                        if (!chatStillActive) return@synchronized

                        // 无未读，直接标记为已到最新（anchor=null 表示已经在最新位置）
                        Log.d("ChatMessagesRepository", "[CMR-ANCHOR] chatId=$chatId → anchor=null (no unread), totalMsgs=${validMessages.size}")
                        newerAnchorMessageId[chatId] = null
                        initialLoadDone.add(chatId)

                        // 写入 initialUnreadTargetMessageId = 0（无未读）
                        _initialUnreadTargetMessageId.update { it + (chatId to 0L) }
                        _initialLoadFinished.update { it + (chatId to true) }
                    }

                    Log.d("ChatMessagesRepository", "[CMR-ANCHOR] chatId=$chatId no unread init complete, loaded ${validMessages.size} messages")
                    chatStillActive
                }
                is TdApi.Error -> {
                    Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad(no unread) error: ${result.message} (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                    false
                }
                else -> {
                    Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad(no unread) unknown: ${result::class.java.simpleName}")
                    false
                }
            }
        } else {
            // 有未读分支
            if (lastReadId > 0) {
                // lastReadId > 0：围绕 lastReadId 加载
                // a. 先加载第一条未读消息
                val firstUnreadRequest = TdApi.GetChatHistory(chatId, lastReadId, -1, 1, false)
                val firstUnreadResult = sendTdRequest(firstUnreadRequest)

                if (firstUnreadResult !is TdApi.Messages) {
                    Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad: failed to load first unread")
                    return false
                }

                val validFirstUnread = firstUnreadResult.messages?.filterNotNull().orEmpty()
                if (validFirstUnread.isEmpty()) {
                    Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad: first unread empty")
                    return false
                }

                if (!isChatActive(chatId)) return true

                addHistoryMessages(chatId, validFirstUnread.toTypedArray())

                // b. 找到第一条未读入站消息作为 target
                val targetId = findFirstUnreadInLoaded(validFirstUnread, lastReadId)
                if (targetId != null) {
                    synchronized(lock) {
                        _initialUnreadTargetMessageId.update { it + (chatId to targetId) }
                    }
                }

                // c. delay 200ms 等待 UI 消费 target
                delay(200.milliseconds)

                // d. 加载未读上下文：lastReadId 前后各 10 条
                val contextRequest = TdApi.GetChatHistory(chatId, lastReadId, -10, 20, false)
                return when (val contextResult = sendTdRequest(contextRequest)) {
                    is TdApi.Messages -> {
                        if (!isChatActive(chatId)) return true

                        val validContext = contextResult.messages?.filterNotNull().orEmpty()
                        addHistoryMessages(chatId, validContext.toTypedArray())

                        var chatStillActive = true
                        synchronized(lock) {
                            chatStillActive = (chatObserverCount[chatId] ?: 0) > 0
                            if (!chatStillActive) return@synchronized

                            // 判断是否还有更新的消息
                            val newerMessages = validContext.filter { it.id > lastReadId }
                            val newerCount = newerMessages.size
                            val newerMaxId = if (newerMessages.isNotEmpty()) newerMessages.maxOf { it.id } else 0L
                            val cachedLatestKnownId = ChatsRepository.getChatLastMessage(chatId)?.id
                                ?: chat.lastMessage?.id ?: 0L

                            val anchor = if (newerCount < 10) {
                                if (cachedLatestKnownId > 0 && newerMaxId < cachedLatestKnownId) {
                                    newerMaxId
                                } else {
                                    null
                                }
                            } else {
                                newerMaxId
                            }

                            Log.d("ChatMessagesRepository", "[CMR-ANCHOR] chatId=$chatId (has unread) " +
                                    "newerCount=$newerCount newerMaxId=$newerMaxId " +
                                    "cachedLatestKnownId=$cachedLatestKnownId " +
                                    "→ anchor=$anchor")
                            newerAnchorMessageId[chatId] = anchor
                            initialLoadDone.add(chatId)
                            _initialLoadFinished.update { it + (chatId to true) }
                        }

                        chatStillActive
                    }
                    is TdApi.Error -> {
                        Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad(has unread context) error: ${contextResult.message} (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                        false
                    }
                    else -> {
                        Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad(has unread context) unknown: ${contextResult::class.java.simpleName}")
                        false
                    }
                }
            } else {
                // lastReadId == 0：加载最新一页，target 取已加载入站消息里最旧的一条
                val request = TdApi.GetChatHistory(chatId, 0L, 0, 50, false)
                return when (val result = sendTdRequest(request)) {
                    is TdApi.Messages -> {
                        if (!isChatActive(chatId)) return true

                        val validMessages = result.messages?.filterNotNull().orEmpty()
                        addHistoryMessages(chatId, validMessages.toTypedArray())

                        var chatStillActive = true
                        synchronized(lock) {
                            chatStillActive = (chatObserverCount[chatId] ?: 0) > 0
                            if (!chatStillActive) return@synchronized

                            // 找到第一条未读入站消息作为 target
                            val targetId = findFirstUnreadInLoaded(validMessages, 0L)
                            _initialUnreadTargetMessageId.update { it + (chatId to (targetId ?: 0L)) }

                            // 标记为已到最新
                            newerAnchorMessageId[chatId] = null
                            initialLoadDone.add(chatId)
                            _initialLoadFinished.update { it + (chatId to true) }

                            Log.d("ChatMessagesRepository", "[CMR-ANCHOR] chatId=$chatId (lastReadId=0) → anchor=null, target=$targetId")
                        }

                        chatStillActive
                    }
                    is TdApi.Error -> {
                        Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad(lastReadId=0) error: ${result.message} (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                        false
                    }
                    else -> {
                        Log.e("ChatMessagesRepository", "[CMR-ANCHOR] doSingleInitialLoad(lastReadId=0) unknown: ${result::class.java.simpleName}")
                        false
                    }
                }
            }
        }
    }

    /**
     * 找到第一条未读入站消息
     */
    private fun findFirstUnreadInLoaded(messages: List<TdApi.Message>, lastReadId: Long): Long? =
        messages
            .filter { !it.isOutgoing && (lastReadId == 0L || it.id > lastReadId) }
            .minOfOrNull { it.id }

    /**
     * Fallback: 加载最新消息并标记初始化完成
     * 当有未读消息的复杂加载逻辑失败时，降级为简单的最新消息加载
     */
    private suspend fun fallbackLoadLatestMessages(chatId: Long) {
        Log.w("ChatMessagesRepository", "[FALLBACK] chatId=$chatId loading latest messages as fallback")
        val request = TdApi.GetChatHistory(chatId, 0L, 0, 20, false)
        when (val result = sendTdRequest(request)) {
            is TdApi.Messages -> {
                if (!isChatActive(chatId)) return
                val validMessages = result.messages?.filterNotNull().orEmpty()
                addHistoryMessages(chatId, validMessages.toTypedArray())
                synchronized(lock) {
                    if ((chatObserverCount[chatId] ?: 0) > 0) {
                        // 降级为无未读模式：anchor=null 表示已到最新
                        newerAnchorMessageId[chatId] = null
                        initialLoadDone.add(chatId)
                        _initialUnreadTargetMessageId.update { it + (chatId to 0L) }
                        _initialLoadFinished.update { it + (chatId to true) }
                        Log.d("ChatMessagesRepository", "[FALLBACK] chatId=$chatId fallback complete, loaded ${validMessages.size} messages")
                    }
                }
            }
            else -> {
                Log.e("ChatMessagesRepository", "[FALLBACK] chatId=$chatId failed, marking done anyway")
                synchronized(lock) {
                    // 即使加载失败也标记完成，避免界面永久卡在加载状态
                    newerAnchorMessageId[chatId] = null
                    initialLoadDone.add(chatId)
                    _initialUnreadTargetMessageId.update { it + (chatId to 0L) }
                    _initialLoadFinished.update { it + (chatId to true) }
                }
            }
        }
    }

    /**
     * 判断是否需要跳转到最新消息。
     *
     * 返回 `true` 表示当前已加载窗口未抵达最新，调用 [jumpToLatestMessages] 有意义；
     * 返回 `false` 表示已经在最新位置，无需重新加载。
     *
     * UI 可以用这个方法决定是否显示"查看最新消息"按钮，或在点击前做快速判断。
     */
    fun needsJumpToLatest(chatId: Long): Boolean = synchronized(lock) {
        // 未初始化或初始化中：认为需要跳转（实际会等初始化完成）
        if (chatId !in initialLoadDone) return true
        // anchor 不为 null 说明还有更新的消息未加载
        newerAnchorMessageId[chatId] != null
    }

    /**
     * 跳转到最新消息并重新初始化。
     *
     * 调用前建议先用 [needsJumpToLatest] 判断是否真的需要跳转，避免无谓的缓存清空。
     *
     * 调用后会：
     * 1. 清空该 chat 的分页/加载锚点；
     * 2. 用 [ChatsRepository.getChatLastMessage] 拿到的最后一条消息做即时占位
     *    （若存在），让 UI 一次刷新就能渲染，而不是先看到空白加载态；
     * 3. 异步从 `fromMessageId=0L` 拉一页真正的最新消息；新拉到的消息会
     *    通过 [addHistoryMessages] 自然合并进缓存，覆盖/补全占位数据。
     *
     * 注意 [ChatsRepository.getChatLastMessage] 不一定是真正最新（可能滞后），
     * 所以 0L 那次拉取是必须的，不能省。
     *
     * 约束：
     * - chat 必须仍处于 bind 状态（[bindChat] 过且未解绑）；
     * - 若已有初始化/跳转在途，本次调用会被幂等忽略。
     */
    fun jumpToLatestMessages(chatId: Long) {
        // 在锁外读 ChatsRepository，避免锁嵌套
        val seedMessage = ChatsRepository.getChatLastMessage(chatId)

        val shouldStartLoad = synchronized(lock) {
            // chat 必须仍被界面绑定，否则跳转无意义
            if ((chatObserverCount[chatId] ?: 0) <= 0) return
            // 已有同 chatId 的初始化/跳转在途，避免并发重复触发
            if (chatId in initialLoadInflight) return

            // 清空分页/加载相关的非消息状态
            olderHistoryEndReached.remove(chatId)
            olderNoProgressCount.remove(chatId)
            currentViewingMessageId.remove(chatId)
            preloadInflight.remove(chatId)

            // 占坑：防止其它入口（bindChat 等）并发进入初始化
            initialLoadInflight.add(chatId)

            _initialUnreadTargetMessageId.update { it + (chatId to 0L) }

            if (seedMessage != null) {
                // 有 lastMessage：用它做即时占位，UI 立刻可渲染
                _messagesByChat.update { it + (chatId to mapOf(seedMessage.id to seedMessage)) }
                // 暂视为已抵达最新；后台 0L 拉回更新的消息会通过 update 自然合并
                newerAnchorMessageId[chatId] = null
                initialLoadDone.add(chatId)
                _initialLoadFinished.update { it + (chatId to true) }
            } else {
                // 无 lastMessage：清空缓存并进入加载态，等 0L 拉回
                _messagesByChat.update { it - chatId }
                initialLoadDone.remove(chatId)
                newerAnchorMessageId.remove(chatId)
                _initialLoadFinished.update { it + (chatId to false) }
            }
            true
        }

        if (!shouldStartLoad) return

        Log.d("ChatMessagesRepository", "[JUMP-LATEST] chatId=$chatId start (seed=${seedMessage?.id ?: "none"})")
        scope.launch { loadLatestMessagesFromScratch(chatId) }
    }

    /**
     * 从最新消息拉取一页，用于 [jumpToLatestMessages] 场景。
     *
     * 与 [fallbackLoadLatestMessages] 的区别：该函数自带指数退避重试，
     * 即使最终失败也会把初始化状态标记完成，避免 UI 卡在加载态。
     */
    private suspend fun loadLatestMessagesFromScratch(chatId: Long) {
        try {
            var retryCount = 0
            while (retryCount <= MAX_RETRY_COUNT && isChatActive(chatId)) {
                val request = TdApi.GetChatHistory(chatId, 0L, 0, 20, false)
                when (val result = sendTdRequest(request)) {
                    is TdApi.Messages -> {
                        if (!isChatActive(chatId)) return

                        val validMessages = result.messages?.filterNotNull().orEmpty()
                        addHistoryMessages(chatId, validMessages.toTypedArray())

                        synchronized(lock) {
                            if ((chatObserverCount[chatId] ?: 0) > 0) {
                                // anchor=null 表示已经在最新位置；旧历史仍可通过 loadHistoryMessages 继续拉
                                newerAnchorMessageId[chatId] = null
                                initialLoadDone.add(chatId)
                                _initialLoadFinished.update { it + (chatId to true) }
                            }
                        }
                        Log.d("ChatMessagesRepository", "[JUMP-LATEST] chatId=$chatId complete, loaded ${validMessages.size} messages")
                        return
                    }
                    is TdApi.Error -> {
                        Log.e("ChatMessagesRepository", "[JUMP-LATEST] error: ${result.message} (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                    }
                    else -> {
                        Log.e("ChatMessagesRepository", "[JUMP-LATEST] unknown: ${result::class.java.simpleName} (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                    }
                }

                if (retryCount == MAX_RETRY_COUNT) {
                    // 到达最大重试次数仍失败：标记初始化完成，防止 UI 永久停留在加载态
                    synchronized(lock) {
                        if ((chatObserverCount[chatId] ?: 0) > 0) {
                            newerAnchorMessageId[chatId] = null
                            initialLoadDone.add(chatId)
                            _initialLoadFinished.update { it + (chatId to true) }
                        }
                    }
                    Log.w("ChatMessagesRepository", "[JUMP-LATEST] chatId=$chatId gave up after retries, marked done anyway")
                    return
                }
                delay((BASE_RETRY_DELAY_MS shl retryCount).milliseconds)
                retryCount++
            }
        } finally {
            synchronized(lock) { initialLoadInflight.remove(chatId) }
        }
    }

    fun loadHistoryMessages(
        chatId: Long,
        limit: Int,
        onResult: (LoadHistoryResult) -> Unit
    ) {
        // 先在锁里做快速状态判断：chat 不活跃、已经到旧历史末尾、或者已有同向请求时，直接回调。
        var immediateResult: LoadHistoryResult? = null
        synchronized(lock) {
            when {
                !isChatActive(chatId) -> {
                    immediateResult = LoadHistoryResult(0, false, "chat inactive")
                }
                chatId !in initialLoadDone -> {
                    // 初始化未完成，拦截
                    immediateResult = LoadHistoryResult(0, false, "initializing")
                }
                chatId in olderHistoryEndReached -> {
                    immediateResult = LoadHistoryResult(0, true)
                }
                chatId in olderLoadInflight -> {
                    immediateResult = LoadHistoryResult(0, false, "inflight")
                }
                else -> {
                    olderLoadInflight.add(chatId)
                }
            }
        }

        immediateResult?.let {
            onResult(it)
            return
        }

        loadHistoryMessagesInternal(chatId, limit, retryCount = 0) { result ->
            synchronized(lock) {
                olderLoadInflight.remove(chatId)
            }
            onResult(result)
        }
    }

    /**
     * 加载更新的消息（向最新方向）
     *
     * newerAnchorMessageId 记录“当前已加载窗口里最新的一条消息 id”。
     * 如果 anchor 为 null，说明窗口已经抵达 TDLib 已知的最新消息。
     */
    fun loadNewerMessages(
        chatId: Long,
        limit: Int = 10,
        onResult: (LoadNewerResult) -> Unit
    ) {
        var immediateResult: LoadNewerResult? = null
        var anchorToLoad: Long? = null

        synchronized(lock) {
            when {
                chatId !in initialLoadDone -> {
                    // 初始化尚未完成，明确拦截
                    Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId → skip(initializing)")
                    immediateResult = LoadNewerResult(0, false, "initializing")
                }
                chatId !in newerAnchorMessageId -> {
                    // initialLoadDone 里有 chatId，却没有 anchor——理论上不应出现
                    Log.w("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId → null(no anchor key, initDone=true) ← 可疑!")
                    newerAnchorMessageId[chatId] = null
                    immediateResult = LoadNewerResult(0, true)
                }
                newerAnchorMessageId[chatId] == null -> {
                    // anchor 已为 null，认为是最新，直接返回
                    Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId → skip(anchor==null, already latest)")
                    immediateResult = LoadNewerResult(0, true)
                }
                chatId in newerLoadInflight -> {
                    Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId → skip(inflight)")
                    immediateResult = LoadNewerResult(0, false, "inflight")
                }
                else -> {
                    anchorToLoad = newerAnchorMessageId[chatId]
                    newerLoadInflight.add(chatId)
                }
            }
        }

        immediateResult?.let {
            onResult(it)
            return
        }

        val anchor = anchorToLoad ?: return
        scope.launch {
            try {
                onResult(loadNewerMessagesWithRetry(chatId, anchor, limit))
            } finally {
                synchronized(lock) { newerLoadInflight.remove(chatId) }
            }
        }
    }

    private suspend fun loadNewerMessagesWithRetry(
        chatId: Long,
        anchor: Long,
        limit: Int
    ): LoadNewerResult {
        var retryCount = 0
        var lastErrorMessage: String? = null

        while (retryCount <= MAX_RETRY_COUNT && isChatActive(chatId)) {
            // 请求 anchor 之后的 limit 条消息
            // offset = -limit 表示包含 limit 条比 anchor 更新的消息并填满 limit 上限，
            // 不会包含 anchor 本身及更旧的消息。
            // 返回的消息全部满足 id > anchor；若返回少于 limit 条，说明已到最新。
            val request = TdApi.GetChatHistory(chatId, anchor, -limit, limit, false)
            when (val result = sendTdRequest(request)) {
                is TdApi.Messages -> {
                    if (!isChatActive(chatId)) {
                        return LoadNewerResult(0, false, "chat inactive")
                    }

                    val validMessages = result.messages?.filterNotNull().orEmpty()
                    addHistoryMessages(chatId, validMessages.toTypedArray())

                    // 过滤出真正比 anchor 新的消息（id 越大消息越新）
                    val newerMessages = validMessages.filter { it.id > anchor }

                    // 获取已知最新消息 ID
                    val latestKnownId = maxOf(
                        ChatsRepository.getChatLastMessage(chatId)?.id ?: 0L,
                        ChatsRepository.getChatOnce(chatId)?.lastMessage?.id ?: 0L,
                        _messagesByChat.value[chatId]?.keys?.maxOrNull() ?: 0L
                    )

                    var reachedLatest = false
                    var chatStillActive = true
                    synchronized(lock) {
                        chatStillActive = (chatObserverCount[chatId] ?: 0) > 0
                        if (!chatStillActive) return@synchronized

                        val newerMaxId = newerMessages.maxOfOrNull { it.id } ?: anchor

                        when {
                            // anchor 之后没有任何新消息
                            newerMessages.isEmpty() -> {
                                // 若 latestKnown 仍比 anchor 大，说明 TDLib 本地缓存不全，继续以 anchor 为锚点
                                if (latestKnownId > 0 && anchor < latestKnownId) {
                                    newerAnchorMessageId[chatId] = anchor
                                    reachedLatest = false
                                    Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId anchor=$anchor → keep anchor (empty but latestKnown=$latestKnownId)")
                                } else {
                                    newerAnchorMessageId[chatId] = null
                                    reachedLatest = true
                                    Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId anchor=$anchor → null (empty response) latestKnown=$latestKnownId")
                                }
                            }
                            // 已加载最大 id >= 已知最新消息 id，到达最新
                            latestKnownId in 1..newerMaxId -> {
                                newerAnchorMessageId[chatId] = null
                                reachedLatest = true
                                Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId anchor=$anchor → null (newerMaxId=$newerMaxId >= latestKnown=$latestKnownId)")
                            }
                            // 返回不足 limit 条
                            newerMessages.size < limit -> {
                                // 若 latestKnown 仍比本批最大 id 大，说明 TDLib 本地缓存不全，设置新锚点继续加载
                                if (latestKnownId > 0) {
                                    newerAnchorMessageId[chatId] = newerMaxId
                                    reachedLatest = false
                                    Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId anchor=$anchor → newAnchor=$newerMaxId (size=${newerMessages.size} < limit=$limit, latestKnown=$latestKnownId tdlib cache incomplete)")
                                } else {
                                    newerAnchorMessageId[chatId] = null
                                    reachedLatest = true
                                    Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId anchor=$anchor → null (size=${newerMessages.size} < limit=$limit) latestKnown=$latestKnownId")
                                }
                            }
                            // 返回了 limit 条，还有更多新消息未加载，更新锚点
                            else -> {
                                newerAnchorMessageId[chatId] = newerMaxId
                                reachedLatest = false
                                Log.d("ChatMessagesRepository", "[CMR-ANCHOR] loadNewer chatId=$chatId anchor=$anchor → newAnchor=$newerMaxId (size=${newerMessages.size}) latestKnown=$latestKnownId")
                            }
                        }
                    }

                    if (!chatStillActive) {
                        return LoadNewerResult(0, false, "chat inactive")
                    }
                    return LoadNewerResult(newerMessages.size, reachedLatest)
                }
                is TdApi.Error -> {
                    lastErrorMessage = result.message
                    Log.e("ChatMessagesRepository", "loadNewerMessages error: ${result.message} (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                }
                else -> {
                    lastErrorMessage = "loadNewerMessages unknown result: ${result::class.java.simpleName}"
                    Log.e("ChatMessagesRepository", "loadNewerMessages unknown result: ${result::class.java.simpleName} (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                }
            }

            if (retryCount == MAX_RETRY_COUNT) break
            delay((BASE_RETRY_DELAY_MS shl retryCount).milliseconds)
            retryCount++
        }

        return LoadNewerResult(0, false, lastErrorMessage ?: "chat inactive")
    }

    private fun loadHistoryMessagesInternal(
        chatId: Long,
        limit: Int,
        retryCount: Int,
        onResult: (LoadHistoryResult) -> Unit
    ) {
        // 防御性检查：确保初始化已完成
        val isInitialized = synchronized(lock) { chatId in initialLoadDone }
        if (!isInitialized) {
            onResult(LoadHistoryResult(0, false, "not initialized"))
            return
        }

        // 从当前缓存中最小的 messageId 继续往更旧方向拉
        val fromMessageId = _messagesByChat.value[chatId]
            ?.keys
            ?.minOrNull()
            ?: 0L

        // 请求 limit + 1 条，因为 offset=0 可能包含锚点本身
        val requestLimit = (limit + 1).coerceAtMost(100)
        val getChatMessages = TdApi.GetChatHistory(
            chatId,
            fromMessageId,
            0,
            requestLimit,
            false
        )

        // 这里仍使用 callback，是因为外层调用者本身就是 callback API；回调里再切回仓库 scope 统一更新状态。
        TgClient.send(getChatMessages) { result ->
            scope.launch {
                when (result) {
                    is TdApi.Messages -> {
                        val validMessages = result.messages?.filterNotNull().orEmpty()

                        // 过滤出真正比 fromMessageId 更旧的消息
                        val newOlderMessages = if (fromMessageId == 0L) {
                            validMessages
                        } else {
                            validMessages.filter { it.id < fromMessageId }
                        }

                        if (!isChatActive(chatId)) {
                            onResult(LoadHistoryResult(loadedCount = 0, reachedEnd = false, errorMessage = "chat inactive"))
                            return@launch
                        }

                        // 写入所有有效消息
                        addHistoryMessages(chatId, validMessages.toTypedArray())

                        if (!isChatActive(chatId)) {
                            onResult(LoadHistoryResult(loadedCount = 0, reachedEnd = false, errorMessage = "chat inactive"))
                            return@launch
                        }

                        // 判断是否到达历史末尾
                        val reachedEnd = synchronized(lock) {
                            if (newOlderMessages.isNotEmpty()) {
                                // 有推进，重置计数
                                olderNoProgressCount.remove(chatId)
                                false
                            } else {
                                // 无推进，增加计数
                                val count = (olderNoProgressCount[chatId] ?: 0) + 1
                                olderNoProgressCount[chatId] = count
                                if (count >= 2) {
                                    olderHistoryEndReached.add(chatId)
                                    true
                                } else {
                                    false
                                }
                            }
                        }

                        onResult(
                            LoadHistoryResult(
                                loadedCount = newOlderMessages.size,
                                reachedEnd = reachedEnd
                            )
                        )
                    }
                    is TdApi.Error -> {
                        Log.e("ChatMessagesRepository", "GetChatHistory error: ${result.message} (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                        if (retryCount < MAX_RETRY_COUNT && isChatActive(chatId)) {
                            // 指数退避重试: 1s, 2s, 4s
                            val delayMs = BASE_RETRY_DELAY_MS shl retryCount
                            Log.d("ChatMessagesRepository", "GetChatHistory retry in ${delayMs}ms...")
                            delay(delayMs.milliseconds)
                            // 延迟后再次检查 chat 是否仍然活跃
                            if (isChatActive(chatId)) {
                                loadHistoryMessagesInternal(chatId, limit, retryCount + 1, onResult)
                            } else {
                                onResult(LoadHistoryResult(loadedCount = 0, reachedEnd = false, errorMessage = result.message))
                            }
                        } else {
                            onResult(LoadHistoryResult(loadedCount = 0, reachedEnd = false, errorMessage = result.message))
                        }
                    }
                    else -> {
                        val message = "GetChatHistory unknown result: ${result::class.java.simpleName}"
                        Log.e("ChatMessagesRepository", "$message (attempt ${retryCount + 1}/${MAX_RETRY_COUNT + 1})")
                        if (retryCount < MAX_RETRY_COUNT && isChatActive(chatId)) {
                            val delayMs = BASE_RETRY_DELAY_MS shl retryCount
                            Log.d("ChatMessagesRepository", "GetChatHistory retry in ${delayMs}ms...")
                            delay(delayMs.milliseconds)
                            if (isChatActive(chatId)) {
                                loadHistoryMessagesInternal(chatId, limit, retryCount + 1, onResult)
                            } else {
                                onResult(LoadHistoryResult(loadedCount = 0, reachedEnd = false, errorMessage = message))
                            }
                        } else {
                            onResult(LoadHistoryResult(loadedCount = 0, reachedEnd = false, errorMessage = message))
                        }
                    }
                }
            }
        }
    }

    /**
     * 当前已加载窗口是否已经抵达最新消息。
     */
    fun isAtLatestMessage(chatId: Long): Boolean =
        synchronized(lock) { chatId in newerAnchorMessageId && newerAnchorMessageId[chatId] == null }

    // 当前正在查看的消息ID记录
    private val currentViewingMessageId = mutableMapOf<Long, Long>()
    // 预加载进行中标记
    private val preloadInflight = mutableMapOf<Long, Boolean>()

    /**
     * ChatScreen 调用此方法告知当前正在查看的消息
     * Repository 会自动检查并预加载前后消息
     */
    fun notifyViewingMessage(chatId: Long, messageId: Long) {
        synchronized(lock) {
            currentViewingMessageId[chatId] = messageId
        }

        // 异步检查并预加载
        scope.launch {
            checkAndPreloadAroundMessage(chatId, messageId)
        }
    }

    /**
     * 检查messageId前后的消息，如果缺失则自动加载
     */
    private suspend fun checkAndPreloadAroundMessage(chatId: Long, messageId: Long) {
        if (!isChatActive(chatId)) return

        // 初始化未完成时直接返回
        val isInitialized = synchronized(lock) { chatId in initialLoadDone }
        if (!isInitialized) return

        // 防止重复预加载
        val shouldPreload = synchronized(lock) {
            if (preloadInflight[chatId] == true) return@synchronized false
            preloadInflight[chatId] = true
            true
        }

        if (!shouldPreload) return

        try {
            val messages = _messagesByChat.value[chatId] ?: return
            val sortedIds = messages.keys.sorted()
            val currentIndex = sortedIds.indexOf(messageId)

            if (currentIndex == -1) return

            // 检查前5条消息
            val olderIds = sortedIds.take(currentIndex).takeLast(5)
            val missingOlderCount = 5 - olderIds.size
            val hasNullOlder = olderIds.any { messages[it] == null }

            // 检查后5条消息
            val newerIds = sortedIds.drop(currentIndex + 1).take(5)
            val missingNewerCount = 5 - newerIds.size
            val hasNullNewer = newerIds.any { messages[it] == null }

            // 如果前面缺少消息或有null，加载更旧的消息
            if ((missingOlderCount > 0 || hasNullOlder) && !noMoreHistory(chatId)) {
                loadHistoryMessagesAwait(chatId, 10)
            }

            // 如果后面缺少消息或有null，检查是否需要加载更新的消息
            val anchor = synchronized(lock) { newerAnchorMessageId[chatId] }
            if (anchor != null) {
                val hasAnchorInNewerWindow = messageId == anchor || newerIds.contains(anchor)
                if (hasAnchorInNewerWindow || missingNewerCount > 0 || hasNullNewer) {
                    loadNewerMessagesAwait(chatId, 10)
                }
            }

            // 如果前后10条消息中有为null的，自动获取对应的消息
            val idsToLoad = mutableListOf<Long>()

            val olderIds10 = sortedIds.take(currentIndex).takeLast(10)
            olderIds10.forEach { id ->
                if (messages[id] == null) {
                    idsToLoad.add(id)
                }
            }

            val newerIds10 = sortedIds.drop(currentIndex + 1).take(10)
            newerIds10.forEach { id ->
                if (messages[id] == null) {
                    idsToLoad.add(id)
                }
            }

            if (idsToLoad.isNotEmpty()) {
                loadMessagesByIds(chatId, idsToLoad.distinct().toLongArray())
            }
        } finally {
            synchronized(lock) {
                preloadInflight[chatId] = false
            }
        }
    }

    private suspend fun loadHistoryMessagesAwait(chatId: Long, limit: Int): LoadHistoryResult =
        suspendCancellableCoroutine { continuation ->
            loadHistoryMessages(chatId, limit) { result ->
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(result))
                }
            }
        }

    private suspend fun loadNewerMessagesAwait(chatId: Long, limit: Int): LoadNewerResult =
        suspendCancellableCoroutine { continuation ->
            loadNewerMessages(chatId, limit) { result ->
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(result))
                }
            }
        }

    /**
     * 根据消息ID列表批量加载消息
     */
    private suspend fun loadMessagesByIds(chatId: Long, messageIds: LongArray) {
        if (messageIds.isEmpty() || !isChatActive(chatId)) return

        val request = TdApi.GetMessages(chatId, messageIds)
        when (val result = sendTdRequest(request)) {
            is TdApi.Messages -> {
                if (!isChatActive(chatId)) return
                val validMessages = result.messages?.filterNotNull().orEmpty()
                addHistoryMessages(chatId, validMessages.toTypedArray())
            }
            is TdApi.Error -> {
                Log.e("ChatMessagesRepository", "loadMessagesByIds error: ${result.message}")
            }
            else -> {
                Log.e("ChatMessagesRepository", "loadMessagesByIds unknown result: ${result::class.java.simpleName}")
            }
        }
    }

    /**
     * 检查是否已经没有更旧的历史消息
     */
    private fun noMoreHistory(chatId: Long): Boolean {
        return synchronized(lock) { chatId in olderHistoryEndReached }
    }

    /**
     * Returns the first loaded unread message id, or null when none is available.
     */
    fun getFirstUnreadMessageId(chatId: Long): Long? {
        val chat = ChatsRepository.getChatOnce(chatId) ?: return null
        if (chat.unreadCount == 0) return null
        val lastReadId = chat.lastReadInboxMessageId
        if (lastReadId == 0L) return null

        val messages = _messagesByChat.value[chatId] ?: return null
        return messages.values
            .filterNotNull()
            .filter { it.id > lastReadId }
            .minByOrNull { it.id }
            ?.id
    }

    private fun removeOwnerChatBindingLocked(lifecycleOwner: LifecycleOwner, chatId: Long) {
        val chatIds = ownerChatIds[lifecycleOwner] ?: return
        if (!chatIds.remove(chatId)) return

        // owner 已经没有任何 chat 绑定时，移除生命周期观察者，避免持有无用引用。
        if (chatIds.isEmpty()) {
            ownerChatIds.remove(lifecycleOwner)
            ownerObservers.remove(lifecycleOwner)?.let { lifecycleOwner.lifecycle.removeObserver(it) }
        }

        // 同一个 chat 可能被多个界面同时观察，只有最后一个观察者离开时才真正清理缓存。
        val count = (chatObserverCount[chatId] ?: 0) - 1
        if (count <= 0) {
            chatObserverCount.remove(chatId)
            // 清理所有加载状态
            initialLoadDone.remove(chatId)
            newerAnchorMessageId.remove(chatId)
            initialLoadInflight.remove(chatId)
            newerLoadInflight.remove(chatId)
            olderLoadInflight.remove(chatId)
            olderHistoryEndReached.remove(chatId)
            olderNoProgressCount.remove(chatId)
            currentViewingMessageId.remove(chatId)
            preloadInflight.remove(chatId)
            _messagesByChat.update { it - chatId }
            // 清理出站/入站已读位置缓存
            _lastReadOutboxMessageId.update { it - chatId }
            _lastReadInboxMessageId.update { it - chatId }
            // 清理新增的初始化状态
            _initialUnreadTargetMessageId.update { it - chatId }
            _initialLoadFinished.update { it - chatId }
        } else {
            chatObserverCount[chatId] = count
        }
    }

    private fun subscribeAll() {
        // 新消息：只写入当前 active 的 chat，避免未打开的会话把仓库缓存撑大。
        TgClient.subscribe(TdApi.UpdateNewMessage::class.java) { update ->
            if (!isChatActive(update.message.chatId)) return@subscribe
            scope.launch { addOrReplaceMessage(update.message) }
        }

        // 消息内容变更：例如文本、媒体状态或服务消息内容变化，保留原 message 的其它字段。
        TgClient.subscribe(TdApi.UpdateMessageContent::class.java) { update ->
            if (!isChatActive(update.chatId)) return@subscribe
            scope.launch {
                _messagesByChat.update { prev ->
                    val current = prev[update.chatId] ?: return@update prev
                    val old = current[update.messageId] ?: return@update prev
                    val next = old.copy(content = update.newContent)
                    val newChatMap = current.toMutableMap().apply {
                        this[update.messageId] = next
                    }
                    prev + (update.chatId to newChatMap.toMap())
                }
            }
        }

        // 编辑时间变更：TDLib 只给 editDate，这里用 copy 更新已有消息。
        TgClient.subscribe(TdApi.UpdateMessageEdited::class.java) { update ->
            if (!isChatActive(update.chatId)) return@subscribe
            scope.launch {
                _messagesByChat.update { prev ->
                    val current = prev[update.chatId] ?: return@update prev
                    val old = current[update.messageId] ?: return@update prev
                    val next = old.copy(editDate = update.editDate)
                    val newChatMap = current.toMutableMap().apply {
                        this[update.messageId] = next
                    }
                    prev + (update.chatId to newChatMap.toMap())
                }
            }
        }

        // 只处理永久删除，忽略缓存清理（fromCache=true 时只是 TDLib 本地缓存回收，不是真正删除）
        TgClient.subscribe(TdApi.UpdateDeleteMessages::class.java) { update ->
            if (!update.isPermanent) return@subscribe // 不可见的消息不删除（tdlib没有要求强制删除，该消息仅仅无法访问，如果后续要求请注释）
            if (update.fromCache) return@subscribe
            if (!isChatActive(update.chatId)) return@subscribe
            scope.launch {
                _messagesByChat.update { prev ->
                    val current = prev[update.chatId] ?: return@update prev
                    val newChatMap = current.toMutableMap()
                    update.messageIds.forEach { newChatMap.remove(it) }
                    if (newChatMap.isEmpty()) prev - update.chatId else prev + (update.chatId to newChatMap.toMap())
                }
                // 修复被删除的锚点消息
                repairMessageAnchorsAfterDelete(update.chatId, update.messageIds)
            }
        }

        // 本机发送成功：TDLib 会把临时 oldMessageId 替换成服务器分配的新 messageId。
        TgClient.subscribe(TdApi.UpdateMessageSendSucceeded::class.java) { update ->
            val chatId = update.message.chatId
            if (!isChatActive(chatId)) return@subscribe
            scope.launch {
                _messagesByChat.update { prev ->
                    val current = prev[chatId] ?: return@update prev
                    val newChatMap = current.toMutableMap().apply {
                        remove(update.oldMessageId)
                        this[update.message.id] = update.message
                    }
                    prev + (chatId to newChatMap.toMap())
                }
            }
        }

        // 订阅出站消息已读更新（对方读了我发的消息）
        TgClient.subscribe(TdApi.UpdateChatReadOutbox::class.java) { update ->
            scope.launch {
                _lastReadOutboxMessageId.update { prev ->
                    prev + (update.chatId to update.lastReadOutboxMessageId)
                }
            }
        }

        // 订阅入站消息已读更新（我读了对方发的消息）
        TgClient.subscribe(TdApi.UpdateChatReadInbox::class.java) { update ->
            scope.launch {
                _lastReadInboxMessageId.update { prev ->
                    prev + (update.chatId to update.lastReadInboxMessageId)
                }
            }
        }

        // 订阅消息反应更新
        TgClient.subscribe(TdApi.UpdateMessageReactions::class.java) { update ->
            updateMessageReactions(update)
        }

        // 订阅消息交互信息更新
        TgClient.subscribe(TdApi.UpdateMessageInteractionInfo::class.java) { update ->
            updateMessageInteractionInfo(update)
        }
    }

    private fun updateMessageInteractionInfo(update: TdApi.UpdateMessageInteractionInfo) {
        if (!isChatActive(update.chatId)) return
        scope.launch {
            _messagesByChat.update { prev ->
                val current = prev[update.chatId] ?: return@update prev
                val oldMsg = current[update.messageId] ?: return@update prev

                val next = oldMsg.copy(interactionInfo = update.interactionInfo)
                val newChatMap = current.toMutableMap().apply {
                    this[update.messageId] = next
                }

                prev + (update.chatId to newChatMap.toMap())
            }
        }
    }

    /**
     * 某条消息新增反应
     */
    private fun updateMessageReactions(update: TdApi.UpdateMessageReactions) {
        if (!isChatActive(update.chatId)) return
        scope.launch {
            _messagesByChat.update { prev ->
                val current = prev[update.chatId] ?: return@update prev
                val oldMsg = current[update.messageId] ?: return@update prev

                val oldInteractionInfo = oldMsg.interactionInfo
                val oldReactionsObj = oldInteractionInfo?.reactions

                // 1. 完整提取旧的反应属性。如果原本是 null（首次添加），则赋予默认值
                val oldAreTags = oldReactionsObj?.areTags ?: false
                val oldPaidReactors = oldReactionsObj?.paidReactors ?: emptyArray()
                val oldCanGetAddedReactions = oldReactionsObj?.canGetAddedReactions ?: false

                // 2. 使用完整的 4 参数构造函数创建全新的 Reactions 对象
                val newReactionsObj = TdApi.MessageReactions(
                    update.reactions,            // 从 Update 中带来的最新反应数组
                    oldAreTags,                  // 保留原状态
                    oldPaidReactors,             // 保留原状态
                    oldCanGetAddedReactions      // 保留原状态
                )

                // 3. 创建全新的 InteractionInfo 对象
                val newInteractionInfo = if (oldInteractionInfo != null) {
                    TdApi.MessageInteractionInfo(
                        oldInteractionInfo.viewCount,
                        oldInteractionInfo.forwardCount,
                        oldInteractionInfo.replyInfo,
                        newReactionsObj
                    )
                } else {
                    // 如果该消息原本完全没有任何互动数据（没被看过、没被转发过、没反应过）
                    TdApi.MessageInteractionInfo(0, 0, null, newReactionsObj)
                }

                // 4. 生成新消息并放入 Map
                val next = oldMsg.copy(interactionInfo = newInteractionInfo)

                val newChatMap = current.toMutableMap().apply {
                    this[update.messageId] = next
                }

                prev + (update.chatId to newChatMap.toMap())
            }
        }
    }

    /**
     * 新增或覆盖单条消息。实时新消息和发送成功后的最终消息都会走这里或同等逻辑。
     */
    private fun addOrReplaceMessage(message: TdApi.Message) {
        _messagesByChat.update { prev ->
            val current = prev[message.chatId].orEmpty().toMutableMap()
            current[message.id] = message
            prev + (message.chatId to current.toMap())
        }
    }

    /**
     * 公开函数 经过校验的新增或覆盖单条消息
     */
    fun publicAddOrReplaceMessage(message: TdApi.Message) {
        if (!isChatActive(message.chatId)) return
        scope.launch { addOrReplaceMessage(message) }
    }

    /**
     * 把 ChatsRepository 里记录的最后一条消息塞进消息缓存。
     *
     * 当前文件暂未调用，保留这个工具方法可以在以后需要“先展示最后一条消息占位”时复用。
     */
    private fun seedLastMessage(chatId: Long) {
        val lastMessage = ChatsRepository.getChatLastMessage(chatId) ?: return
        _messagesByChat.update { prev ->
            val current = prev[chatId].orEmpty().toMutableMap()
            current[lastMessage.id] = lastMessage
            prev + (chatId to current.toMap())
        }
    }

    /**
     * 从 chat 元信息初始化“对方读到我哪条消息”的位置。
     */
    private fun seedLastReadOutboxMessageId(chatId: Long) {
        val chat = ChatsRepository.getChatOnce(chatId) ?: return
        _lastReadOutboxMessageId.update { prev ->
            prev + (chatId to chat.lastReadOutboxMessageId)
        }
    }

    /**
     * 从 chat 元信息初始化“我读到对方哪条消息”的位置。
     */
    private fun seedLastReadInboxMessageId(chatId: Long) {
        val chat = ChatsRepository.getChatOnce(chatId) ?: return
        _lastReadInboxMessageId.update { prev ->
            prev + (chatId to chat.lastReadInboxMessageId)
        }
    }

    /**
     * 判断 chat 当前是否还有界面在观察。
     */
    private fun isChatActive(chatId: Long): Boolean = synchronized(lock) {
        (chatObserverCount[chatId] ?: 0) > 0
    }

    /**
     * 获取话题信息 Flow
     */
    fun getForumTopicFlow(chatId: Long, topicId: Int): Flow<TdApi.ForumTopic?> =
        _forumTopicsByChat
            .map { it[chatId]?.get(topicId.toLong()) }
            .distinctUntilChanged()

    /**
     * 获取或加载话题信息
     */
    fun getOrLoadForumTopic(chatId: Long, topicId: Int, onResult: (TdApi.ForumTopic?) -> Unit) {
        // 先检查缓存
        val cached = _forumTopicsByChat.value[chatId]?.get(topicId.toLong())
        if (cached != null) {
            onResult(cached)
            return
        }
        // 从 TDLib 加载
        TgClient.send(TdApi.GetForumTopic(chatId, topicId)) { result ->
            scope.launch {
                if (result is TdApi.ForumTopic) {
                    _forumTopicsByChat.update { prev ->
                        val chatTopics = prev[chatId].orEmpty().toMutableMap()
                        chatTopics[topicId.toLong()] = result
                        prev + (chatId to chatTopics.toMap())
                    }
                    onResult(result)
                } else {
                    onResult(null)
                }
            }
        }
    }

    /**
     * 给 LifecycleOwner 注册一次 onDestroy 监听。
     *
     * 这样 Compose/Activity 页面销毁时，即使调用方忘了手动 unbind，也能清理 chat 绑定和缓存。
     */
    private fun attachOwnerDestroyObserverIfNeed(lifecycleOwner: LifecycleOwner) {
        if (ownerObservers.containsKey(lifecycleOwner)) return
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                synchronized(lock) {
                    val chatIds = ownerChatIds[owner]?.toSet().orEmpty()
                    chatIds.forEach { chatId ->
                        removeOwnerChatBindingLocked(owner, chatId)
                    }
                }
            }
        }
        ownerObservers[lifecycleOwner] = observer
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    /**
     * 在删除消息后修复可能被删除的锚点消息ID
     */
    private fun repairMessageAnchorsAfterDelete(chatId: Long, deletedIds: LongArray) {
        val deletedSet = deletedIds.toSet()
        val messages = _messagesByChat.value[chatId] ?: return

        synchronized(lock) {
            // 修复 initialUnreadTarget
            val oldTarget = _initialUnreadTargetMessageId.value[chatId]
            if (oldTarget != null && oldTarget in deletedSet) {
                val replacement = findReplacementMessageId(messages, oldTarget, preferNewer = true)
                _initialUnreadTargetMessageId.update { it + (chatId to (replacement ?: 0L)) }
                Log.d("ChatMessagesRepository", "[REPAIR] initialUnreadTarget $oldTarget → $replacement")
            }

            // 修复 newerAnchor
            val oldAnchor = newerAnchorMessageId[chatId]
            if (oldAnchor != null && oldAnchor in deletedSet) {
                val replacement = findReplacementMessageId(messages, oldAnchor, preferNewer = false)
                newerAnchorMessageId[chatId] = replacement
                Log.d("ChatMessagesRepository", "[REPAIR] newerAnchor $oldAnchor → $replacement")
            }

            // 修复 currentViewing
            val oldViewing = currentViewingMessageId[chatId]
            if (oldViewing != null && oldViewing in deletedSet) {
                val replacement = findReplacementMessageId(messages, oldViewing, preferNewer = false)
                if (replacement != null) {
                    currentViewingMessageId[chatId] = replacement
                    Log.d("ChatMessagesRepository", "[REPAIR] currentViewing $oldViewing → $replacement")
                } else {
                    currentViewingMessageId.remove(chatId)
                    Log.d("ChatMessagesRepository", "[REPAIR] currentViewing $oldViewing → removed")
                }
            }
        }
    }

    /**
     * 查找替代消息ID
     * @param preferNewer true时优先找更新的消息，false时优先找更旧的消息
     */
    private fun findReplacementMessageId(messages: Map<Long, TdApi.Message?>, deletedId: Long, preferNewer: Boolean): Long? {
        val validIds = messages.keys.filter { messages[it] != null }
        if (validIds.isEmpty()) return null

        return if (preferNewer) {
            validIds.filter { it > deletedId }.minOrNull() ?: validIds.filter { it < deletedId }.maxOrNull()
        } else {
            validIds.filter { it < deletedId }.maxOrNull() ?: validIds.filter { it > deletedId }.minOrNull()
        }
    }
}
