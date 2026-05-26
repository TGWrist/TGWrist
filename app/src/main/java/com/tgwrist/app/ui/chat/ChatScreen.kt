package com.tgwrist.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.PagerScaffoldDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText
import com.tgwrist.app.R
import com.tgwrist.app.data.SharedMessageInfoData
import com.tgwrist.app.data.SharedMessageInfoKey
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.StatusTimeText
import com.tgwrist.app.runtime.ChatMessagesRepository
import com.tgwrist.app.utils.ChatScreenKey
import com.tgwrist.app.utils.ChatScrollState
import com.tgwrist.app.runtime.ChatsRepository
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.utils.date
import com.tgwrist.app.utils.isSameDay
import com.tgwrist.app.utils.openChatOnPhone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import kotlin.time.Duration.Companion.milliseconds

private data class MessageGroup(
    val key: Long,
    val date: Int,
    val messages: List<TdApi.Message>,
    val messageIds: List<Long>
)

// chatMessages is assumed to be already sorted by message id descending
// (ChatMessagesRepository.getChatMessagesFlow sorts by TdApi.Message::id descending).
// TDLib message IDs encode their timestamp in the upper bits, so id-descending order
// is equivalent to (date DESC, id DESC) for all practical purposes.
//
// Groups themselves are ordered descending (newest first) to match reverseLayout = true,
// but messages *within* each group are sorted ascending (oldest first) to match the
// official Telegram client's album order (e.g. "Video Photo Caption").
private fun buildMessageGroups(chatMessages: List<TdApi.Message>): List<MessageGroup> {
    if (chatMessages.isEmpty()) return emptyList()

    val groups = ArrayList<MessageGroup>(chatMessages.size)
    var currentAlbumId = 0L
    var currentGroupKey = Long.MIN_VALUE
    var currentGroupDate = 0
    var currentMessages = ArrayList<TdApi.Message>(4)
    var currentMessageIds = ArrayList<Long>(4)

    fun flushCurrentGroup() {
        if (currentMessages.isEmpty()) return
        // 组内按 ID 升序，与官方客户端相册顺序一致
        // （单条消息的组排序无影响，相册组则保证 第一条=最小ID=带描述文字 的消息在前）
        currentMessages.sortBy { it.id }
        currentMessageIds.sort()
        groups += MessageGroup(
            key = currentGroupKey,
            date = currentGroupDate,
            messages = currentMessages,
            messageIds = currentMessageIds
        )
        currentAlbumId = 0L
        currentGroupKey = Long.MIN_VALUE
        currentGroupDate = 0
        currentMessages = ArrayList(4)
        currentMessageIds = ArrayList(4)
    }

    chatMessages.forEach { message ->
        val mediaAlbumId = message.mediaAlbumId
        val shouldMergeIntoCurrentAlbum =
            mediaAlbumId != 0L &&
                currentMessages.isNotEmpty() &&
                currentAlbumId == mediaAlbumId

        if (!shouldMergeIntoCurrentAlbum) {
            flushCurrentGroup()
            currentAlbumId = mediaAlbumId
            // Encode key with a kind bit to avoid collisions with negative TDLib message IDs.
            currentGroupKey = if (mediaAlbumId != 0L) {
                (mediaAlbumId shl 1) or 1L   // album group: odd key
            } else {
                (message.id shl 1)           // single-message group: even key
            }
            currentGroupDate = message.date
        }

        currentMessages += message
        currentMessageIds += message.id
    }

    flushCurrentGroup()
    return groups
}

/**
 * 根据保存的 item key（如 "msgs_123456" / "header_123456" / "unread_123456" / "top_spacer"）
 * 在当前 messageGroups 列表中计算出对应的 lazy column index。
 *
 * 必须与 TransformingLazyColumn 中的 item 发射顺序完全一致：
 *   messageGroups.forEachIndexed { index, group ->
 *       if (shouldShowUnreadMarker)
 *           item(key = "unread_${lastReadInboxMessageId}")  // 未读标记
 *       item(key = "msgs_${group.key}")   // 消息块
 *       if (shouldShowHeader)
 *           item(key = "header_${group.key}")  // 日期头
 *   }
 *   item(key = "top_spacer")
 */
private fun findItemIndexByKey(
    messageGroups: List<MessageGroup>,
    targetKey: String,
    lastReadInboxMessageId: Long = 0L,
    initialUnreadCount: Int = 0
): Int? {
    if (targetKey == "top_spacer") {
        // top_spacer 始终是最后一个 item
        var idx = 0
        for (i in messageGroups.indices) {
            val group = messageGroups[i]
            val shouldShowUnreadMarker = lastReadInboxMessageId > 0L &&
                    group.messageIds.contains(lastReadInboxMessageId) &&
                    initialUnreadCount != 0
            if (shouldShowUnreadMarker) idx++ // unread item
            idx++ // msgs item
            if (shouldShowDateHeader(messageGroups, i)) idx++ // header item
        }
        return idx
    }

    var idx = 0
    for (i in messageGroups.indices) {
        val group = messageGroups[i]
        val shouldShowUnreadMarker = lastReadInboxMessageId > 0L &&
                group.messageIds.contains(lastReadInboxMessageId) &&
                initialUnreadCount != 0
        
        // unread item
        if (shouldShowUnreadMarker) {
            if (targetKey == "unread_$lastReadInboxMessageId") return idx
            idx++
        }
        // msgs item
        if (targetKey == "msgs_${group.key}") return idx
        idx++
        // header item（条件与 TransformingLazyColumn 中一致）
        if (shouldShowDateHeader(messageGroups, i)) {
            if (targetKey == "header_${group.key}") return idx
            idx++
        }
    }
    return null
}

/** 判断 messageGroups[index] 是否应显示日期头，与列表中逻辑一致 */
private fun shouldShowDateHeader(messageGroups: List<MessageGroup>, index: Int): Boolean {
    val date = messageGroups[index].date
    return (index == messageGroups.lastIndex && index != 0) ||
            (index < messageGroups.lastIndex &&
                !isSameDay(date.toLong(), messageGroups[index + 1].date.toLong()))
}

@Composable
fun ChatScreen(chatId: Long) {
    val context = LocalContext.current
    val appState = LocalGlobalAppState.current
    val navController = appState.navController
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // 是否已恢复滚动位置（防止重复恢复）
    var hasRestoredScroll by remember { mutableStateOf(false) }

    // 媒体选择由 ChatViewModel 持有，跟随当前聊天页生命周期
    val chatViewModel: ChatViewModel = viewModel { ChatViewModel(chatId) }
    val mediaChose = chatViewModel.mediaChose

    // 多功能按钮配置
    var selecting by remember { mutableStateOf(false) }
    val showToolButton by remember {
        derivedStateOf {
            selecting ||
                    ((listState.layoutInfo.visibleItems.firstOrNull()?.index ?: 0) >= 1)
        }
    }

    // 为本 ChatScreen 实例分配唯一 ID，避免同一 chatId 多次打开时覆盖滚动状态
    // 使用 rememberSaveable 确保该 instanceId 在 Composable 被销毁重建后仍能恢复
    // （当用户导航到下一页面后返回时，Compose 可能会销毁并重建此页面）
    val instanceId by rememberSaveable(chatId) {
        mutableLongStateOf(appState.nextChatScreenInstanceId())
    }
    val screenKey = remember(chatId, instanceId) {
        ChatScreenKey(chatId, instanceId)
    }

    // 保存滚动位置 (当离开页面时)
    // 使用 anchor item key（基于消息ID）+ anchorItemScrollOffset，
    // 这样即使收到新消息导致 index 偏移，返回时仍能定位到正确位置。
    //
    // TransformingLazyColumn 使用 anchor（视口中心）定位系统：
    //   anchorItemIndex     = 当前处于视口中心的 item index
    //   anchorItemScrollOffset = 该 item 相对于中心锚点的像素偏移
    // scrollToItem(index, scrollOffset) 的 scrollOffset 与 anchorItemScrollOffset
    // 使用相同的坐标系，因此可直接保存 / 恢复。
    DisposableEffect(screenKey, listState) {
        onDispose {
            val anchorIdx = listState.anchorItemIndex
            val anchorOffset = listState.anchorItemScrollOffset
            val totalItems = listState.layoutInfo.totalItemsCount
            val anchorItem = listState.layoutInfo.visibleItems
                .firstOrNull { it.index == anchorIdx }
            if (anchorItem != null && totalItems > 0) {
                // 计算后备恢复比例：anchor index 在总 item 数中的位置
                val fallbackRatio = anchorIdx.toFloat() / totalItems.toFloat()
                appState.chatScrollStates[screenKey] = ChatScrollState(
                    firstVisibleItemKey = anchorItem.key.toString(),
                    firstVisibleItemScrollOffset = anchorOffset,
                    fallbackIndexRatio = fallbackRatio
                )
            }
        }
    }

    DisposableEffect(Unit) {
        TgClient.send(TdApi.OpenChat(chatId)) {
            if (it !is TdApi.Ok) navController?.popBackStack()
        }

        onDispose {
            TgClient.send(TdApi.CloseChat(chatId))
        }
    }

    val messagePreloadQuantity = 5

    var loadingHistory by remember(chatId) { mutableStateOf(false) }
    var noMoreHistory by remember(chatId) { mutableStateOf(false) }
    var loadingNewer by remember(chatId) { mutableStateOf(false) }
    var noMoreNewer by remember(chatId) { mutableStateOf(false) }

    var selectedGroupKey by remember { mutableStateOf<Long?>(null) }
    var selectedMessageInfoKey by remember { mutableStateOf<Long?>(null) }

    val openOnPhoneDialogDefaultsTextStyle = OpenOnPhoneDialogDefaults.curvedTextStyle
    val openOnPhoneDialogText = stringResource(R.string.open_on_phone)
    var openOnPhone by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        selectedMessageInfoKey?.let {
            appState.sharedMessageInfo.remove(SharedMessageInfoKey(chatId, it))
            selectedMessageInfoKey = null
            selectedGroupKey = null
        }
    }

    val chatObject by ChatsRepository.getChatFlow(chatId).collectAsState(initial = null)
    var requested by remember(chatId) { mutableStateOf(false) }

    LaunchedEffect(chatId, chatObject) {
        if (chatObject == null && !requested) {
            requested = true
            TgClient.send(TdApi.GetChat(chatId)) { result ->
                if (result is TdApi.Chat) {
                    ChatsRepository.upsertChats(listOf(result))
                } else {
                    TgClient.send(TdApi.GetChat(chatId)) { retry ->
                        if (retry is TdApi.Chat) {
                            ChatsRepository.upsertChats(listOf(retry))
                        } else {
                            navController?.popBackStack()
                        }
                    }
                }
            }
        }
    }

    var lastReadInboxMessageId by rememberSaveable(chatId) {
        mutableLongStateOf(0L)
    }

    var initialUnreadCount by rememberSaveable(chatId) {
        mutableIntStateOf(0)
    }

    LaunchedEffect(chatObject?.lastReadInboxMessageId, chatObject?.unreadCount) {
        val chat = chatObject ?: return@LaunchedEffect

        if (
            lastReadInboxMessageId == 0L &&
            chat.lastReadInboxMessageId != 0L
        ) {
            lastReadInboxMessageId = chat.lastReadInboxMessageId
            initialUnreadCount = chat.unreadCount
        }
    }

    LaunchedEffect(lifecycleOwner, chatId) {
        ChatMessagesRepository.bindChat(lifecycleOwner, chatId)
        // 轮询同步初始 noMoreNewer 状态（最多等3秒）
        repeat(15) {
            delay(200.milliseconds)
            if (ChatMessagesRepository.isAtLatestMessage(chatId)) {
                noMoreNewer = true
                return@LaunchedEffect
            }
        }
    }

    val chatMessages by ChatMessagesRepository.getChatMessagesFlow(chatId)
        .collectAsStateWithLifecycle(
            initialValue = emptyList(),
            lifecycleOwner = lifecycleOwner,
            minActiveState = Lifecycle.State.CREATED
        )

    // 订阅出站消息已读位置（用于判断自己发的消息对方是否已读）
    val lastReadOutboxMessageId by ChatMessagesRepository.getLastReadOutboxMessageIdFlow(chatId)
        .collectAsStateWithLifecycle(
            initialValue = 0L,
            lifecycleOwner = lifecycleOwner,
            minActiveState = Lifecycle.State.CREATED
        )

    val messageGroups by remember(chatMessages) {
        derivedStateOf { buildMessageGroups(chatMessages) }
    }
    val messageGroupsByKey by remember(messageGroups) {
        derivedStateOf { messageGroups.associateBy(MessageGroup::key) }
    }
    val messageGroupDateMap by remember(messageGroups) {
        derivedStateOf { messageGroups.associate { it.key to it.date } }
    }

    // 订阅初始化首屏滚动目标消息 ID
    val initialUnreadTargetMessageId by ChatMessagesRepository.getInitialUnreadTargetMessageIdFlow(chatId)
        .collectAsStateWithLifecycle(
            initialValue = 0L,
            lifecycleOwner = lifecycleOwner,
            minActiveState = Lifecycle.State.CREATED
        )

    // 订阅初始化加载完成状态
    val initialLoadFinished by ChatMessagesRepository.getInitialLoadFinishedFlow(chatId)
        .collectAsStateWithLifecycle(
            initialValue = false,
            lifecycleOwner = lifecycleOwner,
            minActiveState = Lifecycle.State.CREATED
        )

    LaunchedEffect(messageGroups.size, initialLoadFinished, initialUnreadTargetMessageId) {
        if (!hasRestoredScroll && messageGroups.isNotEmpty() && initialLoadFinished) {
            delay(100.milliseconds)

            // 优先恢复保存的滚动位置
            val savedState = appState.chatScrollStates[screenKey]
            if (savedState != null) {
                val totalItems = listState.layoutInfo.totalItemsCount
                if (totalItems > 0) {
                    // 首先尝试根据保存的 item key 恢复精确位置
                    val targetIndex = findItemIndexByKey(
                        messageGroups, 
                        savedState.firstVisibleItemKey,
                        lastReadInboxMessageId,
                        initialUnreadCount
                    )

                    if (targetIndex != null && targetIndex < totalItems) {
                        // 精确恢复：找到了原始 item
                        listState.scrollToItem(
                            targetIndex,
                            savedState.firstVisibleItemScrollOffset
                        )
                    } else {
                        // 后备恢复：原始 item 不存在（如消息被删除），使用保存的比例恢复到大概位置
                        val fallbackIndex = (savedState.fallbackIndexRatio * totalItems)
                            .toInt()
                            .coerceIn(0, totalItems - 1)
                        listState.scrollToItem(fallbackIndex, 0)
                    }
                }
            } else if (initialUnreadTargetMessageId > 0) {
                // 没有保存的滚动位置，使用 Repository 提供的第一条未读消息滚动
                val targetGroup = messageGroups.firstOrNull { group ->
                    group.messages.any { it.id == initialUnreadTargetMessageId }
                }
                if (targetGroup != null) {
                    val targetIndex = findItemIndexByKey(
                        messageGroups, 
                        "msgs_${targetGroup.key}",
                        lastReadInboxMessageId,
                        initialUnreadCount
                    )
                    if (targetIndex != null && targetIndex < listState.layoutInfo.totalItemsCount) {
                        listState.scrollToItem(targetIndex, 0)
                    }
                }
            }
            // 如果 initialUnreadTargetMessageId == 0（无未读）或找不到，默认停在最新（index = 0）
            hasRestoredScroll = true
        }
    }

    LaunchedEffect(selectedGroupKey, selectedMessageInfoKey) {
        val activeSelectedGroupKey = selectedGroupKey
        val activeSelectedMessageInfoKey = selectedMessageInfoKey
        if (activeSelectedGroupKey != null && activeSelectedMessageInfoKey != null) {
            val selectedGroup = messageGroupsByKey[activeSelectedGroupKey] ?: return@LaunchedEffect
            appState.sharedMessageInfo[SharedMessageInfoKey(chatId, activeSelectedMessageInfoKey)] =
                SharedMessageInfoData(selectedGroup.messageIds)
            selectedGroupKey = null
            navController?.navigate(Destinations.messageInfo(chatId, activeSelectedMessageInfoKey))
        }
    }

    AppScaffold(timeText = {
        if (pagerState.currentPage != 0) {
            StatusTimeText()
        }
    }) {
        HorizontalPagerScaffold(pagerState = pagerState) {
            HorizontalPager(
                state = pagerState,
                flingBehavior =
                    PagerScaffoldDefaults.snapWithSpringFlingBehavior(state = pagerState),
                rotaryScrollableBehavior = null,
            ) { page ->
                when(page) {
                    0 -> {
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            LaunchedEffect(listState) {
                                snapshotFlow {
                                    val layoutInfo = listState.layoutInfo
                                    val totalItems = layoutInfo.totalItemsCount
                                    val firstVisible = layoutInfo.visibleItems.minOfOrNull { it.index } ?: 0
                                    val lastVisible = layoutInfo.visibleItems.maxOfOrNull { it.index } ?: 0
                                    Triple(firstVisible, lastVisible, totalItems)
                                }
                                    .collect { (firstVisible, lastVisible, totalCount) ->
                                        // 滑到顶部（旧消息方向）
                                        if (totalCount > 0 &&
                                            !loadingHistory &&
                                            !noMoreHistory &&
                                            lastVisible >= (totalCount - messagePreloadQuantity)
                                        ) {
                                            loadingHistory = true
                                            ChatMessagesRepository.loadHistoryMessages(
                                                chatId = chatId,
                                                limit = 20
                                            ) { historyResult ->
                                                loadingHistory = false
                                                if (historyResult.reachedEnd) {
                                                    noMoreHistory = true
                                                }
                                                historyResult.errorMessage?.let {
                                                    noMoreHistory = false
                                                }
                                            }
                                        }

                                        // 滑到底部（新消息方向）
                                        if (totalCount > 0 &&
                                            !loadingNewer &&
                                            !noMoreNewer &&
                                            firstVisible <= messagePreloadQuantity
                                        ) {
                                            loadingNewer = true
                                            ChatMessagesRepository.loadNewerMessages(chatId) { result ->
                                                loadingNewer = false
                                                if (result.reachedLatest) {
                                                    noMoreNewer = true
                                                }
                                                // errorMessage 为 "initializing" 或 "inflight" 时保持 noMoreNewer = false
                                            }
                                        }
                                    }
                            }

                            // 新增：反馈当前可见消息给 Repository
                            LaunchedEffect(listState, messageGroups) {
                                snapshotFlow {
                                    val visibleItems = listState.layoutInfo.visibleItems
                                    // 获取视口中心的item
                                    val centerItem = visibleItems.getOrNull(visibleItems.size / 2)
                                    centerItem?.key
                                }
                                    .distinctUntilChanged()
                                    .collect { itemKey ->
                                        if (itemKey == null) return@collect
                                        val key = itemKey.toString()

                                        // 解析出 messageId
                                        val messageId = when {
                                            key.startsWith("msgs_") -> {
                                                val groupKey = key.removePrefix("msgs_").toLongOrNull() ?: return@collect
                                                val group = messageGroups.firstOrNull { it.key == groupKey }
                                                // 使用组中第一条消息的ID
                                                group?.messages?.firstOrNull()?.id
                                            }
                                            key.startsWith("header_") -> null
                                            else -> null
                                        }

                                        messageId?.let {
                                            ChatMessagesRepository.notifyViewingMessage(chatId, it)
                                        }
                                    }
                            }

                            // 消息过少时自动重试加载
                            // 解决刚打开聊天时只有 lastMessage、列表无法滚动、
                            // snapshotFlow 不再触发导致历史消息加载卡住的问题
                            LaunchedEffect(chatId) {
                                while (true) {
                                    delay(2000.milliseconds)
                                    if (noMoreHistory || messageGroups.size >= 3) break
                                    if (!loadingHistory) {
                                        loadingHistory = true
                                        ChatMessagesRepository.loadHistoryMessages(
                                            chatId = chatId,
                                            limit = 20
                                        ) { historyResult ->
                                            loadingHistory = false
                                            if (historyResult.reachedEnd) {
                                                noMoreHistory = true
                                            }
                                            historyResult.errorMessage?.let {
                                                noMoreHistory = false
                                            }
                                        }
                                    }
                                }
                            }

                            ScreenScaffold(
                                scrollState = listState,
                                overscrollEffect = overscroll,
                                edgeButton = {
                                    EdgeButton(
                                        onClick = {
                                            chatObject?.let {
                                                openOnPhone = true
                                                context.openChatOnPhone(it)
                                            }
                                        },
                                        buttonSize = EdgeButtonSize.Small,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDDFFD)),
                                        modifier =
                                            Modifier.scrollable(
                                                listState,
                                                orientation = Orientation.Vertical,
                                                reverseDirection = true,
                                                overscrollEffect = rememberOverscrollEffect(),
                                            ),
                                    ) {
                                        Icon(painter = painterResource(R.drawable.mobile_arrow_right), contentDescription = "OpenOnPhone")
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            ) { contentPadding ->
                                TransformingLazyColumn(
                                    state = listState,
                                    reverseLayout = true,
                                    overscrollEffect = overscroll,
                                    contentPadding = contentPadding,
                                    verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.Bottom),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    messageGroups.forEachIndexed { index, group ->
                                        val date = group.date
                                        val shouldShowHeader =
                                            (index == messageGroups.lastIndex && index != 0) ||
                                                (index < messageGroups.lastIndex &&
                                                    !isSameDay(date.toLong(),
                                                        messageGroups[index + 1].date.toLong()
                                                    ))

                                        // 显示未读消息指示
                                        val shouldShowUnreadMarker =
                                            lastReadInboxMessageId > 0L &&
                                                    group.messageIds.contains(lastReadInboxMessageId) &&
                                                    initialUnreadCount != 0

                                        if (shouldShowUnreadMarker) {
                                            item(key = "unread_$lastReadInboxMessageId") {
                                                Text(
                                                    text = stringResource(R.string.Unread_messages),
                                                    color = Color.White,
                                                    modifier = Modifier.padding(top = 3.dp, bottom = 3.dp)
                                                )
                                            }
                                        }

                                        item(key = "msgs_${group.key}") {
                                            chatObject?.let {
                                                MessageRouting(
                                                    modifier = Modifier
                                                        .transformedHeight(this, transformationSpec)
                                                        .padding(top = if (shouldShowHeader) 0.dp else 8.dp),
                                                    msgList = group.messages,
                                                    chatObject = it,
                                                    lastReadOutboxMessageId = lastReadOutboxMessageId,
                                                    transformation = SurfaceTransformation(transformationSpec),
                                                    onClick = {
                                                        selectedGroupKey = group.key
                                                        selectedMessageInfoKey = System.currentTimeMillis()
                                                    }
                                                )

                                                DoingOnViewChat(messageList = group.messages, chatObject = it)
                                            }
                                        }

                                        if (shouldShowHeader) {
                                            item(key = "header_${group.key}") {
                                                Text(
                                                    text = date(context, date.toLong() * 1000L),
                                                    color = Color(0xFFC4C4C5),
                                                    modifier = Modifier
                                                        .graphicsLayer {
                                                            val progress = scrollProgress
                                                            val fadeOutStart = 0.75f
                                                            val fadeOutEnd = 0.9f
                                                            val currentFraction =
                                                                progress.topOffsetFraction
                                                            val fadeProgress =
                                                                ((currentFraction - fadeOutStart) / (fadeOutEnd - fadeOutStart))
                                                                    .coerceIn(0f, 1f)
                                                            val targetAlpha = 1f - fadeProgress

                                                            this.alpha = targetAlpha

                                                            val scale = 1f - (0.1f * fadeProgress)
                                                            this.scaleX = scale
                                                            this.scaleY = scale
                                                        }
                                                        .padding(top = 3.dp, bottom = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                    item(key = "top_spacer") {
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            Page1(chatId, chatObject, mediaChose, pagerState)
                        }
                    }
                    2 -> {
                        AnimatedPage(pageIndex = page, pagerState = pagerState) {
                            Page2(chatObject)
                        }
                    }
                }
            }
        }
        OpenOnPhoneDialog(
            visible = openOnPhone,
            onDismissRequest = { openOnPhone = false },
            curvedText = {
                openOnPhoneDialogCurvedText(
                    text = openOnPhoneDialogText,
                    style = openOnPhoneDialogDefaultsTextStyle
                )
            }
        )
        if (pagerState.currentPage == 0) {
            // 日期显示
            val dataText: String? by remember(messageGroupDateMap, context) {
                derivedStateOf {
                    val topItem = listState.layoutInfo.visibleItems
                        .maxByOrNull { it.index }
                        ?: return@derivedStateOf null

                    val k = topItem.key.toString()

                    if (k == "top_spacer") return@derivedStateOf null

                    val groupKey = when {
                        k.startsWith("msgs_") -> k.removePrefix("msgs_").toLongOrNull()
                        k.startsWith("header_") -> k.removePrefix("header_").toLongOrNull()
                        else -> null
                    } ?: return@derivedStateOf null

                    val groupDate = messageGroupDateMap[groupKey] ?: return@derivedStateOf null
                    date(context, groupDate.toLong() * 1000L)
                }
            }
            StatusTimeText(dataText)

            // 多功能按钮
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                val screenSize = minOf(maxWidth, maxHeight)

                val buttonSize = (screenSize * 0.15f)
                    .coerceIn(40.dp, 52.dp)

                val cornerPadding = ((screenSize - buttonSize) * 0.16f)
                    .coerceAtLeast(16.dp)

                AnimatedVisibility(
                    visible = showToolButton,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 }
                    ) + fadeIn() + scaleIn(
                        initialScale = 0.85f
                    ),
                    exit = slideOutVertically(
                        targetOffsetY = { it / 2 }
                    ) + fadeOut() + scaleOut(
                        targetScale = 0.85f
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = cornerPadding,
                            bottom = cornerPadding
                        )
                ) {
                    FilledIconButton(
                        onClick = {
                            if (ChatMessagesRepository.needsJumpToLatest(chatId)) {
                                ChatMessagesRepository.jumpToLatestMessages(chatId)
                            } else {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF1D2B3A),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(buttonSize)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.stat_minus_1),
                            contentDescription = "Down Button",
                        )
                    }
                }
            }
        }
    }
}
