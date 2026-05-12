package com.tgwrist.app.runtime

import android.util.Log
import com.tgwrist.app.data.UserInfoEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * ChatsRepository
 *
 * 设计要点：
 * - 不再把 positions / lastMessage 合并回原始 TdApi.Chat（避免直接修改 TdLib 对象导致混乱）
 * - 单独维护可订阅的数据结构：
 *     - rawPositions: Map<chatId, Array<TdApi.ChatPosition>?>   （位置，包含 order & list）
 *     - lastMessages: Map<chatId, TdApi.Message?>               （最后消息，UI 单独订阅）
 *     - chatLists: Map<chatId, List<TdApi.ChatList>>            （一个 chat 可属于多个 list）
 * - chatsListFlow: 根据 _chatLists 与 _rawPositions 决定某 chat 是否属于当前列表并计算 order
 *     - 逻辑：优先查 _chatLists 是否声明该 chat 属于当前列表（按 listKey 比较）
 *         - 若存在：优先使用 rawPositions 中对应该 list 的 position 来计算 order；
 *           若没有 position，则 fallback 使用 rawPositions 中的 main list position（若有）
 *         - 若 _chatLists 未记录该 chat：退回直接用 rawPositions 匹配 currList（有对应 position 则属于）
 * - Update 处理：所有 Update 将把 positions 写入 _rawPositions，将 chat -> lists 的关系写入 _chatLists（list 集合），
 *   将最后消息写入 _lastMessages（不改 _chats.lastMessage）
 *
 * 注：UI 若需要展示最后一条消息，应同时订阅 chatsListFlow（列表/排序）与 lastMessages（内容）。
 */
const val SWITCHING_CHAT_LIST = "switching_chat_list"
object ChatsRepository {
    private const val TAG = "ChatsRepository"

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Epoch 计数器：每次 restart 递增，使旧协程中的写操作失效
    @Volatile
    private var epoch = 0

    // 待处理字段更新缓冲区：存储在 UpdateNewChat 之前到达的字段更新
    private val pendingLock = Any()
    private val _pendingFieldUpdates = mutableMapOf<Long, MutableList<TdApi.Chat.() -> Unit>>()

    // 原始 chat 缓存（只保存 chat 的基本信息，不写入 positions/lastMessage）
    private val _chats = MutableStateFlow<Map<Long, TdApi.Chat>>(emptyMap())
    val chats: StateFlow<Map<Long, TdApi.Chat>> = _chats.asStateFlow()

    // 原始 positions（可订阅）
    private val _rawPositions = MutableStateFlow<Map<Long, Array<TdApi.ChatPosition>?>>(emptyMap())
    val rawPositions: StateFlow<Map<Long, Array<TdApi.ChatPosition>?>> = _rawPositions.asStateFlow()

    // 每个 chat 对应的 ChatList 列表（一个会话可以属于多个列表）
    private val _chatLists = MutableStateFlow<Map<Long, Array<TdApi.ChatList>>>(emptyMap())
    val chatLists: StateFlow<Map<Long, Array<TdApi.ChatList>>> = _chatLists.asStateFlow()

    // 每个 chat 的 lastMessage 单独维护（可订阅）
    private val _lastMessages = MutableStateFlow<Map<Long, TdApi.Message?>>(emptyMap())
    val lastMessages: StateFlow<Map<Long, TdApi.Message?>> = _lastMessages.asStateFlow()

    // 主列表的 position（来自 UpdateChatFolders）
    val mainChatListPosition = MutableStateFlow(0)

    // 当前的文件夹列表（TdApi.ChatFolderInfo）
    private val _chatFolders = MutableStateFlow<List<TdApi.ChatFolderInfo>>(emptyList())
    val chatFolders: StateFlow<List<TdApi.ChatFolderInfo>> = _chatFolders.asStateFlow()

    // 当前显示的 chat list（主/归档/文件夹）
    private val _currentChatList = MutableStateFlow<TdApi.ChatList>(TdApi.ChatListMain())
    val currentChatList: StateFlow<TdApi.ChatList> = _currentChatList.asStateFlow()

    // ===========================
    // helpers: 抽出复用逻辑
    // ===========================

    /**
     * 从 positions 中提取对应的 ChatList 列表（按出现顺序去重）
     */
    private fun positionsToChatLists(positions: Array<TdApi.ChatPosition>?): Array<TdApi.ChatList> {
        if (positions.isNullOrEmpty()) return emptyArray()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<TdApi.ChatList>()
        for (p in positions) {
            val l = p.list ?: continue
            val k = listKey(l) ?: continue
            if (!seen.contains(k)) {
                seen.add(k)
                out.add(l)
            }
        }
        return out.toTypedArray() // 返回新的数组实例，确保不可变语意（不要复用）
    }

    /** 判断 chatListsMap[chatId] 是否包含与 currList 相同的 list（按 listKey 比较） */
    private fun chatListsContain(currList: TdApi.ChatList, chatListsMap: Map<Long, Array<TdApi.ChatList>>, chatId: Long): Boolean {
        val lists = chatListsMap[chatId] ?: return false
        val targetKey = listKey(currList) ?: return false
        return lists.any { listKey(it) == targetKey }
    }

    /** 从 positions 中筛选出属于 targetList 的 ChatPosition（按 listKey 比较） */
    private fun positionsForList(positions: Array<TdApi.ChatPosition>?, targetList: TdApi.ChatList): List<TdApi.ChatPosition> {
        if (positions == null) return emptyList()
        val targetKey = listKey(targetList) ?: return emptyList()
        return positions.filter { p -> listKey(p.list) == targetKey }
    }

    /**
     * 关键函数：计算某 chat 在当前 currList 下是否属于该列表，以及其 order（最大 order）
     *
     * 返回 Pair(belongsToCurrList, maxOrder)
     *
     * 规则：
     *  1) 如果 _chatLists 有该 chat 的记录：
     *       - 若 chatLists 中包含 currList（按 listKey 匹配）：
     *           * 优先取 rawPositions 中对应 currList 的 positions 计算 max order（若有）
     *           * 若没有对应 position，则 fallback 取 rawPositions 中 main list 的 positions（若有）
     *           * 若两者都没有，仍认为 chat 属于当前列表，但 order = 0
     *       - 若 chatLists 存在但不包含 currList：属于 = false
     *  2) 如果 _chatLists 没有该 chat 的记录：
     *       - 退回到 rawPositions：若存在匹配 currList 的 position 则属于（并计算 order），否则不属于
     */
    private fun computeOrderRespectingChatLists(
        chatId: Long,
        rawPosMap: Map<Long, Array<TdApi.ChatPosition>?>,
        chatListsMap: Map<Long, Array<TdApi.ChatList>>,
        currList: TdApi.ChatList
    ): Pair<Boolean, Long> {
        val positions = rawPosMap[chatId]
        val hasChatLists = chatListsMap.containsKey(chatId)

        if (hasChatLists) {
            if (chatListsContain(currList, chatListsMap, chatId)) {
                // 修改：优先取  main list 的 positions（如果有）
                /*val posForMain = positionsForList(positions, TdApi.ChatListMain())
                if (posForMain.isNotEmpty()) {
                    return Pair(true, posForMain.maxOf { it.order })
                }

                // fallback: 取 currList 对应的 positions
                val posForCurr = positionsForList(positions, currList)
                if (posForCurr.isNotEmpty()) {
                    return Pair(true, posForCurr.maxOf { it.order })
                }*/

                // 优先取 currList 对应的 positions
                val posForCurr = positionsForList(positions, currList)
                if (posForCurr.isNotEmpty()) {
                    return Pair(true, posForCurr.maxOf { it.order })
                }
                // fallback: 使用 main list 的 positions（如果有）
                val posForMain = positionsForList(positions, TdApi.ChatListMain())
                if (posForMain.isNotEmpty()) {
                    return Pair(true, posForMain.maxOf { it.order })
                }
                // 无 position 信息，但 chatLists 明确包含该 list -> 属于，order 为 0
                return Pair(true, 0L)
            } else {
                // chatLists 明确存在但不包含 currList -> 不属于
                return Pair(false, 0L)
            }
        } else {
            // 没有 chatLists 信息，退回到 rawPositions 直接匹配 currList
            val posForCurr = positionsForList(positions, currList)
            if (posForCurr.isNotEmpty()) {
                return Pair(true, posForCurr.maxOf { it.order })
            }
            return Pair(false, 0L)
        }
    }

    // ===========================
    // chatsListFlow：流计算（不包含 lastMessages）
    // ===========================
    /**
     * chatsListFlow:
     * 使用 _chats + rawPositions + chatLists + _currentChatList 来计算当前列表应该展示的 chats
     * 排序：order DESC, chat.id DESC
     * distinctBy：只关注 chat.id / draftFlag / posOrder（lastMessage 单独订阅）
     */
    val chatsListFlow: StateFlow<List<TdApi.Chat>> = combine(
        _chats,
        _rawPositions,
        _chatLists,
        _currentChatList
    ) { chatsMap, rawPosMap, chatListsMap, currList ->
        val entries = ArrayList<Pair<TdApi.Chat, Long>>(chatsMap.size)

        for (chat in chatsMap.values) {
            val (belongs, maxOrder) = computeOrderRespectingChatLists(chat.id, rawPosMap, chatListsMap, currList)
            if (!belongs) continue
            entries.add(Pair(chat, maxOrder))
        }

        // 排序：(order DESC, chat.id DESC)
        entries.sortedWith(Comparator { a, b ->
            val orderCmp = a.second.compareTo(b.second)
            if (orderCmp != 0) -orderCmp else -a.first.id.compareTo(b.first.id)
        }).map { it.first }
    }
        .distinctUntilChangedBy { list ->
            // 只关心 chat.id / draftFlag / posOrder（lastMessage 单独订阅）
            val rpSnap = _rawPositions.value
            list.map { chat ->
                val draftFlag = chat.draftMessage != null
                val posOrder = rpSnap[chat.id]?.maxOfOrNull { it.order } ?: 0L
                Triple(chat.id, draftFlag, posOrder)
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    // ===========================
    // 初始化 / 订阅 TdLib 更新
    // ===========================
    private var initialized = false
    private var firstChatFoldersUpdate = true

    fun init() {
        if (initialized) return
        initialized = true
        subscribeAll()
    }

    /** 辅助函数：带 epoch 检查的订阅，restart 后自动丢弃旧更新 */
    private inline fun <reified T : TdApi.Object> subscribeWithEpoch(
        crossinline handler: suspend (T) -> Unit
    ) {
        TgClient.subscribe(T::class.java) { update ->
            val e = epoch
            scope.launch {
                if (e != epoch) return@launch
                handler(update)
            }
        }
    }

    private fun subscribeAll() {
        // 新聊天
        subscribeWithEpoch<TdApi.UpdateNewChat> { update ->
            Log.d(TAG, "UpdateNewChat: $update")
            handleNewChat(update.chat)
        }

        // 聊天标题更新
        subscribeWithEpoch<TdApi.UpdateChatTitle> { update ->
            Log.d(TAG, "UpdateChatTitle: $update")
            handleChatTitle(update)
        }

        // 聊天最后一条消息更新（不写入 _chats，只写入 _lastMessages）
        subscribeWithEpoch<TdApi.UpdateChatLastMessage> { update ->
            Log.d(TAG, "UpdateChatLastMessage: $update")
            handleChatLastMessage(update)
        }

        // 聊天位置（排序/文件夹等）更新（只更新 rawPositions & chatLists）
        subscribeWithEpoch<TdApi.UpdateChatPosition> { update ->
            Log.d(TAG, "UpdateChatPosition: $update")
            handleChatPosition(update.chatId, update.position)
        }

        // 聊天草稿更新（继续更新到原始 chat 的 draftMessage 字段）
        subscribeWithEpoch<TdApi.UpdateChatDraftMessage> { update ->
            Log.d(TAG, "UpdateChatDraftMessage: $update")
            handleChatDraft(update)
        }

        // 聊天添加到某个列表（单独维护 chatLists）
        subscribeWithEpoch<TdApi.UpdateChatAddedToList> { update ->
            Log.d(TAG, "UpdateChatAddedToList: $update")
            handleChatAddedToList(update)
        }

        // 聊天从列表移除
        subscribeWithEpoch<TdApi.UpdateChatRemovedFromList> { update ->
            Log.d(TAG, "UpdateChatRemovedFromList: $update")
            handleChatRemovedFromList(update)
        }

        // 聊天文件夹更新
        subscribeWithEpoch<TdApi.UpdateChatFolders> { update ->
            Log.d(TAG, "UpdateChatFolders: $update")
            mainChatListPosition.value = update.mainChatListPosition
            _chatFolders.value = update.chatFolders.toMutableList()
            if (firstChatFoldersUpdate) {
                firstChatFoldersUpdate = false
                // 添加chatList切换逻辑：如果当前是主列表但 mainChatListPosition > 0，尝试切换到对应文件夹列表
                val first = update.chatFolders.firstOrNull()
                val folderId = first?.let { try { it.id } catch (_: Throwable) { null } }
                if (folderId != null && folderId >= 0 && mainChatListPosition.value > 0) {
                    setCurrentChatList(TdApi.ChatListFolder(folderId))
                } else {
                    setCurrentChatList(TdApi.ChatListMain())
                }
            }
        }

        // 聊天头像更新
        subscribeWithEpoch<TdApi.UpdateChatPhoto> { update ->
            Log.d(TAG, "UpdateChatPhoto: $update")
            handleChatPhoto(update)
        }

        // 聊天未读消息更新
        subscribeWithEpoch<TdApi.UpdateChatReadInbox> { update ->
            Log.d(TAG, "UpdateChatReadInbox: $update")
            handleChatReadInbox(update)
        }

        // 聊天已读状态更新
        subscribeWithEpoch<TdApi.UpdateChatReadOutbox> { update ->
            Log.d(TAG, "UpdateChatReadOutbox: $update")
            handleChatReadOutbox(update)
        }

        // 聊天是否标记未读更新
        subscribeWithEpoch<TdApi.UpdateChatIsMarkedAsUnread> { update ->
            Log.d(TAG, "UpdateChatIsMarkedAsUnread: $update")
            handleChatIsMarkedAsUnread(update)
        }

        // 聊天默认通知设置更新
        /*subscribeWithEpoch<TdApi.UpdateChatDefaultDisableNotification> { update ->
            Log.d(TAG, "UpdateChatDefaultDisableNotification: $update")
            handleChatDefaultDisableNotification(update)
        }*/

        // 聊天通知设置更新
        subscribeWithEpoch<TdApi.UpdateChatNotificationSettings> { update ->
            Log.d(TAG, "UpdateChatNotificationSettings: $update")
            handleChatNotificationSettings(update)
        }

        // TdLib 授权状态变更 — 用于检测意外关闭（close 事件未必通过 GlobalEventBus 发送）
        /*subscribeWithEpoch<TdApi.UpdateAuthorizationState> { update ->
            if (update.authorizationState is TdApi.AuthorizationStateClosed) {
                Log.d(TAG, "AuthorizationStateClosed detected, restarting if needed")
                // 仅在存在旧数据时才 restart，避免与已执行的 restart 重复
                if (_chats.value.isNotEmpty()) {
                    restart()
                }
            }
        }*/

        // 订阅 TgClientClose 事件（不使用 epoch，需始终响应）
        /*GlobalEventBus.subscribe<String>(scope) { event ->
            if (event == TgClientClose || event == TgClientReInit) {
                restart()
            }
        }*/

        // 处理用户切换事件（不使用 epoch，需始终响应）
        GlobalEventBus.subscribe<UserInfoEvent>(
            scope = scope,
        ) { event ->
            if (event.message == ActiveUserSwitch) {
                restart()
            }
        }
    }

    // ===========================
    // Update handlers
    // ===========================
    private fun handleChatNotificationSettings(update: TdApi.UpdateChatNotificationSettings) {
        _chats.updateFields(update.chatId) {
            notificationSettings = update.notificationSettings
        }
    }

    private fun handleChatDefaultDisableNotification(update: TdApi.UpdateChatDefaultDisableNotification) {
        _chats.updateFields(update.chatId) {
            defaultDisableNotification = update.defaultDisableNotification
        }
    }

    private fun handleChatIsMarkedAsUnread(update: TdApi.UpdateChatIsMarkedAsUnread) {
        _chats.updateFields(update.chatId) {
            isMarkedAsUnread = update.isMarkedAsUnread
        }
    }

    private fun handleChatReadOutbox(update: TdApi.UpdateChatReadOutbox) {
        _chats.updateFields(update.chatId) {
            lastReadOutboxMessageId = update.lastReadOutboxMessageId
        }
    }

    private fun handleChatReadInbox(update: TdApi.UpdateChatReadInbox) {
        _chats.updateFields(update.chatId) {
            unreadCount = update.unreadCount
            lastReadInboxMessageId = update.lastReadInboxMessageId
        }
    }

    private fun handleChatPhoto(update: TdApi.UpdateChatPhoto) {
        _chats.updateFields(update.chatId) {
            photo = update.photo
        }
    }

    private fun handleNewChat(chat: TdApi.Chat) {
        // 合并 rawPositions（避免覆盖导致丢失）
        _rawPositions.update { prev ->
            val merged = mergeChatPositions(prev[chat.id], chat.positions)
            if (merged == null) prev - chat.id else prev + (chat.id to merged)
        }

        // positions -> chatLists (List)
        /*val lists = positionsToChatLists(_rawPositions.value[chat.id])
        _chatLists.update { prev ->
            val merged = mergeChatLists(prev[chat.id], lists)
            if (merged == null) prev - chat.id else prev + (chat.id to merged)
        }*/

        // 合并基本 chat 信息 + 应用待处理的字段更新（在锁内保证原子性）
        synchronized(pendingLock) {
            _chats.update { prev ->
                val map = prev.toMutableMap()
                val merged = mergeChatBasic(prev[chat.id], chat)
                map[chat.id] = merged
                map
            }

            // 取出并应用所有在 UpdateNewChat 之前到达的字段更新（如头像、未读数等）
            val pending = _pendingFieldUpdates.remove(chat.id)
            if (!pending.isNullOrEmpty()) {
                Log.d(TAG, "Applying ${pending.size} pending field updates for chat ${chat.id}")
                _chats.update { prev ->
                    val map = prev.toMutableMap()
                    val c = map[chat.id]
                    if (c != null) {
                        for (block in pending) { c.block() }
                        map[chat.id] = c
                    }
                    map
                }
            }
        }
    }

    private fun handleChatTitle(update: TdApi.UpdateChatTitle) {
        _chats.updateFields(update.chatId) {
            title = update.title
        }
    }

    private fun handleChatLastMessage(update: TdApi.UpdateChatLastMessage) {
        // 更新 rawPositions（如果包含 positions）
        _rawPositions.update { rp ->
            val merged = mergeChatPositions(rp[update.chatId], update.positions)
            if (merged == null) rp - update.chatId else rp + (update.chatId to merged)
        }

        // positions -> chatLists (根据最新 positions)  <- 改为合并
        //val lists = positionsToChatLists(_rawPositions.value[update.chatId])
        /*_chatLists.update { prev ->
            val merged = mergeChatLists(prev[update.chatId], lists)
            if (merged == null) prev - update.chatId else prev + (update.chatId to merged)
        }*/

        // 更新 lastMessages map（不变更 _chats 内的 lastMessage）
        _lastMessages.update { prev ->
            val m = prev.toMutableMap()
            m[update.chatId] = update.lastMessage
            m.toMap()
        }
    }


    private fun handleChatPosition(chatId: Long, newPosition: TdApi.ChatPosition?) {
        if (newPosition == null) return

        _rawPositions.update { prev ->
            val merged = mergeChatPositions(prev[chatId], arrayOf(newPosition))
            if (merged == null) prev - chatId else prev + (chatId to merged)
        }

        // positions -> chatLists (根据最新 positions)
        /*val lists = positionsToChatLists(_rawPositions.value[chatId])
        _chatLists.update { prev ->
            val merged = mergeChatLists(prev[chatId], lists)
            if (merged == null) prev - chatId else prev + (chatId to merged)
        }*/

        // 不修改 _chats（positions 已从 rawPositions 获取）
    }

    private fun handleChatAddedToList(update: TdApi.UpdateChatAddedToList) {
        _chatLists.update { prev ->
            val prevArr = prev[update.chatId]
            val newArr = arrayOf(update.chatList)
            val merged = mergeChatLists(prevArr, newArr)
            // 使用 merged（可能为 null）
            if (merged == null) prev - update.chatId else prev + (update.chatId to merged)
        }

        // 注意：UpdateChatAddedToList 本身不一定携带 positions；如需要可另外请求或等待 UpdateChatPosition
    }

    private fun handleChatRemovedFromList(update: TdApi.UpdateChatRemovedFromList) {
        // 基于 listKey 精确移除该 chat 在 _chatLists 中对应的 list
        _chatLists.update { prev ->
            val cur = prev[update.chatId]?.toMutableList() ?: mutableListOf()
            val removeKey = listKey(update.chatList)
            if (removeKey == null) {
                // 无法计算 key 的 list：谨慎策略 -> 尝试按 equals 移除，否则记录日志
                val removed = cur.removeAll { it == update.chatList }
                if (!removed) {
                    Log.w(TAG, "handleChatRemovedFromList: couldn't find exact match for list (no key), chatId=${update.chatId}")
                }
            } else {
                val beforeSize = cur.size
                cur.removeAll { listKey(it) == removeKey }
                val afterSize = cur.size
                if (beforeSize == afterSize) {
                    Log.d(TAG, "handleChatRemovedFromList: no matching listKey found to remove for chat=${update.chatId}, key=$removeKey")
                }
            }
            if (cur.isEmpty()) prev - update.chatId else prev + (update.chatId to cur.toTypedArray())
        }

        // optional: rawPositions 也可能需要同步移除对应 position（如果 server 同时给了 UpdateChatPosition 会处理）
    }

    private fun handleChatDraft(update: TdApi.UpdateChatDraftMessage) {
        // 草稿继续保存在 chat 对象中（保持原先逻辑）
        _rawPositions.update { rp ->
            val merged = mergeChatPositions(rp[update.chatId], update.positions)
            if (merged == null) rp - update.chatId else rp + (update.chatId to merged)
        }

        _chats.update { prev ->
            val map = prev.toMutableMap()
            val chat = map[update.chatId] ?: createStubChat(update.chatId)
            chat.draftMessage = update.draftMessage
            map[update.chatId] = chat
            map
        }

        // positions -> chatLists (根据最新 positions)
        /*val lists = positionsToChatLists(_rawPositions.value[update.chatId])
        _chatLists.update { prev ->
            val merged = mergeChatLists(prev[update.chatId], lists)
            if (merged == null) prev - update.chatId else prev + (update.chatId to merged)
        }*/
    }

    // ===========================
    // 公共 API
    // ===========================
    /** 返回某个 chat 的 Flow，可实时监听更新 */
    fun getChatFlow(chatId: Long): Flow<TdApi.Chat?> =
        _chats.map { it[chatId] }.distinctUntilChanged()

    /** 返回某个 chat 的 lastMessage（不会订阅） */
    fun getChatLastMessage(chatId: Long): TdApi.Message? =
        _lastMessages.value[chatId]

    /** 只取一次 chat（不会订阅） */
    fun getChatOnce(chatId: Long): TdApi.Chat? = _chats.value[chatId]

    /** 切换当前 chat list（主/归档/文件夹） */
    fun setCurrentChatList(chatList: TdApi.ChatList) {
        _currentChatList.value = chatList

        TgClient.send(TdApi.LoadChats(chatList, 15)) {
            Log.d("Tdlib", "LoadChats result: $it")
        }

        GlobalEventBus.send(SWITCHING_CHAT_LIST)
    }

    /**
     * 批量插入或更新 chats（启动时使用）
     * 注意：这里只合并基本信息，不触碰 lastMessage / positions（这些由独立 map 管理）
     */
    fun upsertChats(chatsList: List<TdApi.Chat>) {
        scope.launch {
            // 先合并 rawPositions
            _rawPositions.update { prev ->
                val newMap = prev.toMutableMap()
                for (c in chatsList) {
                    val merged = mergeChatPositions(prev[c.id], c.positions)
                    if (merged == null) newMap.remove(c.id) else newMap[c.id] = merged
                }
                newMap.toMap()
            }

            // positions -> chatLists （合并写入，避免覆盖已有由 UpdateChatAddedToList 带来的列表）
            val rpSnapshot = _rawPositions.value
            _chatLists.update { prev ->
                val newMap = prev.toMutableMap()
                for (c in chatsList) {
                    val lists = positionsToChatLists(rpSnapshot[c.id])
                    val merged = mergeChatLists(newMap[c.id], lists)
                    if (merged == null) {
                        newMap.remove(c.id)
                    } else {
                        newMap[c.id] = merged
                    }
                }
                newMap.toMap()
            }

            // 合并 chats（仅基本字段）
            _chats.update { prev ->
                val newMap = prev.toMutableMap()
                for (c in chatsList) {
                    val merged = mergeChatBasic(prev[c.id], c)
                    newMap[c.id] = merged
                }
                newMap.toMap()
            }
        }
    }

    fun listKey(list: TdApi.ChatList?): String? {
        return when (list) {
            is TdApi.ChatListMain -> "main"
            is TdApi.ChatListArchive -> "archive"
            is TdApi.ChatListFolder -> {
                try {
                    "folder:${list.chatFolderId}"
                } catch (_: Throwable) {
                    null
                }
            }
            else -> null
        }
    }

    fun restart() {
        if (!initialized) return

        epoch++ // 使正在执行的旧协程中的写操作失效

        _chats.value = emptyMap()
        _rawPositions.value = emptyMap()
        _chatLists.value = emptyMap()
        _lastMessages.value = emptyMap()
        _chatFolders.value = emptyList()
        _currentChatList.value = TdApi.ChatListMain()
        mainChatListPosition.value = 0
        firstChatFoldersUpdate = true

        synchronized(pendingLock) {
            _pendingFieldUpdates.clear()
        }
    }

    // ===========================
    // 辅助函数
    // ===========================
    private fun mergeChatPositions(
        old: Array<TdApi.ChatPosition>?,
        newPos: Array<TdApi.ChatPosition>?
    ): Array<TdApi.ChatPosition>? {
        if (old == null && newPos == null) return null
        val map = LinkedHashMap<String, TdApi.ChatPosition>()
        if (old != null) {
            for (p in old) {
                val k = listKey(p.list) ?: continue
                map[k] = p
            }
        }
        if (newPos != null) {
            for (p in newPos) {
                val k = listKey(p.list) ?: continue
                map[k] = p // 新的覆盖旧的（相同 listKey）
            }
        }
        return if (map.isEmpty()) null else map.values.toTypedArray()
    }

    private fun createStubChat(chatId: Long): TdApi.Chat {
        return TdApi.Chat().apply {
            id = chatId
            title = "Loading..."
        }
    }

    /**
     * 合并两个 ChatList 数组（按 listKey 去重，保持出现顺序）
     * 返回 null 表示没有任何 list
     */
    private fun mergeChatLists(
        old: Array<TdApi.ChatList>?,
        incoming: Array<TdApi.ChatList>?
    ): Array<TdApi.ChatList>? {
        if ((old == null || old.isEmpty()) && (incoming == null || incoming.isEmpty())) return null
        val seen = LinkedHashSet<String>()
        val out = ArrayList<TdApi.ChatList>()

        fun addAll(arr: Array<TdApi.ChatList>?) {
            if (arr == null) return
            for (l in arr) {
                val k = listKey(l) ?: continue // 如果无法计算 key，跳过（避免不稳定的比较）
                if (seen.add(k)) out.add(l)
            }
        }

        // 保持旧的顺序优先，随后是新的（但去重）
        addAll(old)
        addAll(incoming)

        return if (out.isEmpty()) null else out.toTypedArray()
    }

    /**
     * 合并 chat 的“基本字段”而不触碰 positions/lastMessage/draft（除非需要）
     */
    private fun mergeChatBasic(old: TdApi.Chat?, incoming: TdApi.Chat): TdApi.Chat {
        if (old == null) return incoming
        val newChat = incoming.apply {
            id = old.id
            title = incoming.title ?: old.title
            // 不覆盖 positions / lastMessage / draftMessage
        }
        return newChat
    }

    /**
     * 对 Map<Long, TdApi.Chat> 的 StateFlow 进行字段级更新：
     * 找到对应 chatId 的 chat（若存在），在 block 中修改字段，然后写回 map。
     * 若 chat 尚未通过 UpdateNewChat 到达，则将更新缓存到 pending buffer，
     * 等 UpdateNewChat 到达后统一应用（避免更新丢失）。
     */
    private fun MutableStateFlow<Map<Long, TdApi.Chat>>.updateFields(
        chatId: Long,
        block: TdApi.Chat.() -> Unit
    ) {
        synchronized(pendingLock) {
            val existing = value[chatId]
            if (existing != null) {
                update { prev ->
                    val map = prev.toMutableMap()
                    val chat = map[chatId]
                    if (chat != null) {
                        chat.block()
                        map[chatId] = chat
                    }
                    map
                }
            } else {
                // Chat 尚未通过 UpdateNewChat 到达，缓存更新以便稍后应用
                _pendingFieldUpdates.getOrPut(chatId) { mutableListOf() }.add(block)
                Log.d(TAG, "Buffered field update for unknown chat $chatId, pending count: ${_pendingFieldUpdates[chatId]?.size}")
            }
        }
    }
}
