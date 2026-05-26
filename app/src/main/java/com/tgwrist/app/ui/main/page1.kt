package com.tgwrist.app.ui.main

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.runtime.CALL_STATE_NONE
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.runtime.ChatsRepository
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.GlobalEventBus
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.MainViewModel
import com.tgwrist.app.runtime.SWITCHING_CHAT_LIST
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.ThumbnailChatPhoto
import com.tgwrist.app.utils.formatChatTimestamp
import com.tgwrist.app.utils.handleAllMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun Page1(viewModel: MainViewModel = viewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val appState = LocalGlobalAppState.current
    val navController = appState.navController
    val overscroll = rememberOverscrollEffect()
    val coroutineScope = rememberCoroutineScope()
    val transformationSpec = rememberTransformationSpec()

    // 订阅当前 chat list （已经按当前 active list 排序）
    val chats by ChatsRepository.chatsListFlow.collectAsStateWithLifecycle()

    // 订阅最后一条聊天记录
    val lastMessages by ChatsRepository.lastMessages.collectAsStateWithLifecycle()

    // 订阅当前显示的 chat list
    val currentChatList by ChatsRepository.currentChatList.collectAsStateWithLifecycle()

    // 订阅当前用户信息
    val currentUser by Config.currentUser.collectAsStateWithLifecycle()

    // 第一次翻滚到最顶上
    val mainPage1FristScrollTop by viewModel.mainPage1FristScrollTop.collectAsStateWithLifecycle()

    val isCalling by TgCallManager.callState.collectAsState()

    // 简单缓存，避免重复网络请求
    val nameCache = remember { mutableMapOf<Long, String>() }
    val chatTitleCache = remember { mutableMapOf<Long, String>() }
    currentUser?.let {
        nameCache[it.id] = stringResource(R.string.You)
    }

    // 延迟加载时检测到滚动接近底部时加载更多聊天项
    LaunchedEffect(listState) {
        snapshotFlow { listState.anchorItemIndex }
            .collect { index ->
                if (index >= chats.size - 5) {
                    TgClient.send(TdApi.LoadChats(currentChatList, chats.size + 1)) {
                        Log.d("Tdlib", "LoadChats result: $it")
                    }
                }
            }
    }

    // 翻滚到最顶上
    LaunchedEffect(Unit) {
        if (!mainPage1FristScrollTop) {
            // 标记已经执行过首次滚动
            viewModel.mainPage1FristScrollTop.value = true
            // 挂起等待 1 秒
            delay(1000.milliseconds)
            // 滚动
            listState.scrollToItem(index = 0)
        }
    }

    LaunchedEffect(Unit) {
        GlobalEventBus.subscribe<String>(
            scope = this,
            lifecycleOwner = lifecycleOwner
        ) { event ->
            // 处理用户切换事件
            if (event == SWITCHING_CHAT_LIST) {
                coroutineScope.launch {
                    listState.scrollToItem(index = 0)
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
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
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
                Icon(Icons.Rounded.KeyboardDoubleArrowUp, contentDescription = "Up")
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
            item {
                ListHeader {
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Chats),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }

            // 通话提示
            if (isCalling != CALL_STATE_NONE) {
                item(key = "calling") {
                    AppCard(
                        onClick = {
                            navController?.navigate(Destinations.CALL)
                        },
                        appName = {
                            Text(
                                text = stringResource(R.string.Calling),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        appImage = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Call,
                                    contentDescription = "Call",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        title = {
                            Text(
                                text = stringResource(R.string.Tap_to_return_to_call),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.transformedHeight(this, transformationSpec)
                    ) {}
                }
            }

            items(count = chats.size,
                key = { index ->
                    val chat = chats[index]
                    "${chat.id}_${lastMessages[chat.id]?.id}"
                }
            ) { index ->
                val chat = remember(index) { chats[index] }
                val lastMessageText = buildAnnotatedString {
                    // 是否是回复消息
                    if (lastMessages[chat.id]?.replyTo != null) {
                        withStyle(style = SpanStyle(color = colorResource(id = R.color.blue))) {
                            append(stringResource(R.string.Reply))
                        }
                        append(" ")
                    }

                    // 是否是转发消息
                    if (lastMessages[chat.id]?.forwardInfo != null) {
                        withStyle(style = SpanStyle(color = colorResource(id = R.color.blue))) {
                            append(stringResource(R.string.Forwarded))
                        }
                        append(" ")
                    }

                    append(handleAllMessages(context, lastMessages[chat.id]))
                }
                val senderName = remember(index) { mutableStateOf("") }
                val isYou = remember(index) { mutableStateOf(false) }
                ChatRowSenderName(
                    chat = chat,
                    lastMessages = lastMessages[chat.id],
                    currentUser = currentUser,
                    senderName = senderName,
                    isYou = isYou,
                    nameCache = nameCache,
                    chatTitleCache = chatTitleCache
                )

                AppCard(
                    onClick = {
                        navController?.navigate(Destinations.chat(chat.id))
                    },
                    appName = { Text(
                        text = if (chat.id == currentUser?.id) stringResource(R.string.Saved_messages) else chat.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) },
                    appImage = {
                        if (chat.id == currentUser?.id) {
                            Box(
                                modifier = Modifier
                                    .size(CardDefaults.AppImageSize)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4E9DE4)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.bookmark_24dp),
                                    contentDescription = "Saved Messages",
                                    modifier = Modifier
                                        .size(CardDefaults.AppImageSize * 0.75f) // 图标占卡片大小的75%
                                        .fillMaxSize(),
                                )
                            }
                        } else {
                            ThumbnailChatPhoto(
                                thumbnail = chat.photo?.small,
                                title = chat.title,
                                accentColorId = chat.accentColorId,
                                contentDescription = "Chat Photo",
                                modifier = Modifier
                                    .size(CardDefaults.AppImageSize)
                                    .clip(CircleShape)
                                    .wrapContentSize(align = Alignment.Center)
                            )
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val draftMessage = chat.draftMessage
                                val text = if (
                                    draftMessage != null &&
                                    draftMessage.date > (lastMessages[chat.id]?.date ?: 0) &&
                                    draftMessage.inputMessageText is TdApi.InputMessageText
                                ) {
                                    val content = draftMessage.inputMessageText as TdApi.InputMessageText
                                    buildAnnotatedString {
                                        withStyle(style = SpanStyle(color = colorResource(R.color.red))) {
                                            append(stringResource(R.string.Draft))
                                        }
                                        append(" ")
                                        append(content.text.text.takeIf { it.isNotBlank() } ?: "")
                                    }
                                } else {
                                    if (senderName.value.isNotBlank()) buildAnnotatedString {
                                        withStyle(style = SpanStyle(color = colorResource(R.color.blue))) {
                                            append(senderName.value)
                                        }
                                        append(": ")
                                        append(lastMessageText)
                                    }
                                    else lastMessageText
                                }
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                if (isYou.value && chat.draftMessage == null && chat.id != currentUser?.id) {
                                    // 自己发的消息是否已读指示器
                                    // 取代表消息（最新的一条）判断发送/已读状态
                                    val statusIcon = when {
                                        // 消息尚未发送成功（sendingState != null）
                                        lastMessages[chat.id]?.sendingState != null -> Icons.Rounded.HistoryToggleOff
                                        // 消息已发送且对方已读（id <= lastReadOutboxMessageId）
                                        lastMessages[chat.id]?.id != null && lastMessages[chat.id]?.id!! <= chat.lastReadOutboxMessageId -> Icons.Rounded.DoneAll
                                        // 消息已发送但对方未读
                                        else -> Icons.Rounded.Check
                                    }
                                    val textStyle = MaterialTheme.typography.labelMedium
                                    val iconSize = with(LocalDensity.current) { (textStyle.fontSize * 1.25f).toDp() }
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        imageVector = statusIcon,
                                        contentDescription = "",
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                            }

                            //val unreadCount = "1" // 可以换成 "99" 试试看
                            val unreadCount =
                                if (chat.unreadCount > 0) chat.unreadCount.toString()
                                else if (chat.isMarkedAsUnread) ""
                                else null

                            unreadCount?.let {
                                Box(
                                    modifier = Modifier
                                        // 1. 加点左边距，让徽章不和文字贴死
                                        .padding(start = 4.dp)
                                        .background(color = if (chat.notificationSettings.muteFor == 0) Color(0xFF3F81BB) else Color(0xFF3E5369), shape = CircleShape)
                                        // 2. 优化：16dp 通常是较完美且紧凑的高度
                                        .defaultMinSize(minWidth = 18.dp, minHeight = 16.dp)
                                        // 3. 只保留 horizontal padding，删掉 vertical 保证高度紧凑
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = unreadCount,
                                        color = Color.White,
                                        // 关键：强制设置 Text 内部文本对齐为居中
                                        style = MaterialTheme.typography.labelSmall.copy(textAlign = TextAlign.Center)
                                    )
                                }
                            }
                        }
                    },
                    time = { Text(context.formatChatTimestamp((lastMessages[chat.id]?.date ?: 0).toLong() * 1000)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec)
                ) {}
            }
        }
    }
}

@Composable
fun ChatRowSenderName(
    chat: TdApi.Chat,
    lastMessages: TdApi.Message?,
    currentUser: TdApi.User?,
    senderName: MutableState<String>,
    isYou: MutableState<Boolean>,
    nameCache: MutableMap<Long, String>,
    chatTitleCache: MutableMap<Long, String>
) {
    // 拼接用户名字的辅助
    fun fullName(user: TdApi.User?): String {
        if (user == null) return ""
        return listOfNotNull(
            user.firstName.takeIf { it.isNotBlank() },
            user.lastName.takeIf { it.isNotBlank() }
        ).joinToString(" ")
    }

    // 包装 TgClient.send 为 suspend 函数（获取用户名）
    suspend fun getUserName(userId: Long): String {
        nameCache[userId]?.let { return it }
        return suspendCancellableCoroutine { cont ->
            TgClient.send(TdApi.GetUser(userId)) { res ->
                val name = if (res is TdApi.User) fullName(res) else ""
                if (name.isNotEmpty()) nameCache[userId] = name
                cont.resume(name)
            }
        }
    }

    // 包装 TgClient.send 为 suspend 函数（获取群/频道标题）
    suspend fun getChatTitle(chatId: Long): String {
        chatTitleCache[chatId]?.let { return it }
        return suspendCancellableCoroutine { cont ->
            TgClient.send(TdApi.GetChat(chatId)) { res ->
                val title = if (res is TdApi.Chat) res.title else ""
                if (title.isNotEmpty()) chatTitleCache[chatId] = title
                cont.resume(title)
            }
        }
    }

    // 将原逻辑集中在这个函数里，返回要展示的 senderName 和是否为自己发送
    suspend fun resolveSenderName(
        chat: TdApi.Chat,
        message: TdApi.Message
    ): Pair<String, Boolean> {
        // 自己的对话（Saved Messages / 自己发的）
        if (chat.id == currentUser?.id) {
            val forwardInfo = message.forwardInfo
            return if (forwardInfo == null) {
                fullName(currentUser) to true
            } else {
                when (val origin = forwardInfo.origin) {
                    is TdApi.MessageOriginUser -> {
                        val isSelf = origin.senderUserId == currentUser.id
                        getUserName(origin.senderUserId) to isSelf
                    }
                    is TdApi.MessageOriginChannel -> {
                        getChatTitle(origin.chatId) to false
                    }
                    is TdApi.MessageOriginHiddenUser -> {
                        (forwardInfo.source?.senderName ?: "") to false
                    }
                    is TdApi.MessageOriginChat -> {
                        getChatTitle(origin.senderChatId) to false
                    }
                    else -> "" to false
                }
            }
        }

        // 群/超级群的消息需要解析 senderId
        val chatType = chat.type
        if (chatType is TdApi.ChatTypeBasicGroup || chatType is TdApi.ChatTypeSupergroup) {
            return when (val senderId = message.senderId) {
                is TdApi.MessageSenderUser -> {
                    val isSelf = senderId.userId == currentUser?.id
                    getUserName(senderId.userId) to isSelf
                }
                is TdApi.MessageSenderChat -> {
                    if (senderId.chatId != chat.id) {
                        getChatTitle(senderId.chatId) to false
                    } else {
                        // 如果发送者就是当前 chat，展示 chat.title（supergroup 且为 channel 时不展示）
                        val title = if (chatType is TdApi.ChatTypeSupergroup) {
                            if (!chatType.isChannel) chat.title else ""
                        } else {
                            chat.title
                        }
                        title to false
                    }
                }
                else -> "" to false
            }
        }

        // 私聊等场景
        return when (val senderId = message.senderId) {
            is TdApi.MessageSenderUser -> {
                val isSelf = senderId.userId == currentUser?.id
                if (isSelf) {
                    (fullName(currentUser).ifBlank { getUserName(senderId.userId) }) to true
                } else {
                    getUserName(senderId.userId) to false
                }
            }
            else -> "" to false
        }
    }

    LaunchedEffect(lastMessages?.id, chat.id, currentUser?.id) {
        if (lastMessages == null) {
            senderName.value = ""
            isYou.value = false
            return@LaunchedEffect
        }

        // 调用集中逻辑
        val (name, you) = resolveSenderName(chat, lastMessages)
        senderName.value = name
        isYou.value = you
    }
}
