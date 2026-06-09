package com.tgwrist.app.ui.message.info

import android.widget.Toast
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.PagerScaffoldDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.openOnPhoneDialogCurvedText
import com.tgwrist.app.R
import com.tgwrist.app.data.AlertDialogItem
import com.tgwrist.app.data.SharedMessageInfoData
import com.tgwrist.app.data.SharedMessageInfoKey
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.StatusTimeText
import com.tgwrist.app.ui.message.info.message.factory.MessageContentFactory
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import com.tgwrist.app.runtime.ChatMessagesRepository
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.runtime.UserManager
import com.tgwrist.app.utils.dateTimeUserPref
import com.tgwrist.app.utils.handleAllMessages
import com.tgwrist.app.utils.openChatOnPhone
import com.tgwrist.app.utils.setClipboardText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MessageInfo(chatId: Long, msgIdList: List<Long>, showMsgsInfo: Boolean = true) {
    val context = LocalContext.current
    val appState = LocalGlobalAppState.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = appState.navController ?: return

    // 字符串变量
    val copiedClipboard = stringResource(id = R.string.Copied_clipboard)

    var openOnPhone by remember { mutableStateOf(false) }
    val openOnPhoneDialogDefaultsTextStyle = OpenOnPhoneDialogDefaults.curvedTextStyle
    val openOnPhoneDialogText = stringResource(R.string.open_on_phone)
    var chatObject by remember { mutableStateOf<TdApi.Chat?>(null) }

    // Dialog 相关
    var isShowDialog by remember { mutableStateOf(false) }
    var dialogItem by remember { mutableStateOf(AlertDialogItem()) }

    LaunchedEffect(chatId) {
        TgClient.send(TdApi.GetChat(chatId)) {
            if (it is TdApi.Chat) {
                chatObject = it
            }
        }
    }

    LaunchedEffect(lifecycleOwner, chatId) {
        ChatMessagesRepository.bindChat(lifecycleOwner, chatId)
    }

    val chatMessages by ChatMessagesRepository.getChatMessagesFlow(chatId)
        .collectAsStateWithLifecycle(
            initialValue = emptyList(),
            lifecycleOwner = lifecycleOwner,
            minActiveState = Lifecycle.State.CREATED
        )

    // 使用 mutableStateOf 来管理 messageIds，以便在删除时更新
    var messageIds by remember(msgIdList) {
        mutableStateOf(msgIdList.distinct().sorted())
    }
    
    val chatMessagesById by remember(chatMessages) {
        derivedStateOf { chatMessages.associateBy(TdApi.Message::id) }
    }

    // 订阅消息删除事件
    LaunchedEffect(lifecycleOwner, chatId) {
        TgClient.subscribe(TdApi.UpdateDeleteMessages::class.java, lifecycleOwner) { update ->
            // 只处理当前聊天的永久删除
            if (update.chatId == chatId && update.isPermanent && !update.fromCache) {
                val deletedIds = update.messageIds.toSet()
                // 从 messageIds 中移除已删除的消息
                messageIds = messageIds.filter { it !in deletedIds }
            }
        }
    }

    // 对于缓存中没有的消息，手动获取
    var manuallyFetchedMessages by remember { mutableStateOf<Map<Long, TdApi.Message>>(emptyMap()) }
    val missingIds by remember(messageIds, chatMessagesById, manuallyFetchedMessages) {
        derivedStateOf { messageIds.filter { it !in chatMessagesById && it !in manuallyFetchedMessages } }
    }

    LaunchedEffect(missingIds) {
        for (msgId in missingIds) {
            val msg = suspendCancellableCoroutine { cont ->
                TgClient.send(TdApi.GetMessage(chatId, msgId)) { res ->
                    if (cont.isActive) { // 检查协程是否仍然活跃
                        cont.resume(res as? TdApi.Message)
                    }
                }
            }
            if (msg != null) {
                manuallyFetchedMessages = manuallyFetchedMessages + (msgId to msg)
            }
        }
    }

    val messages by remember(messageIds, chatMessagesById, manuallyFetchedMessages) {
        derivedStateOf {
            messageIds.mapNotNull { id ->
                chatMessagesById[id] ?: manuallyFetchedMessages[id]
            }
        }
    }

    val messagePropertiesById = remember(chatId) {
        mutableStateMapOf<Long, TdApi.MessageProperties?>()
    }

    val loadingIds = remember(chatId) {
        mutableStateSetOf<Long>()
    }

    LaunchedEffect(chatId, messageIds) {
        val idsToLoad = messageIds.filter { msgId ->
            msgId !in messagePropertiesById && msgId !in loadingIds
        }

        idsToLoad.forEach { msgId ->
            loadingIds.add(msgId)

            launch {
                try {
                    val properties = suspendCancellableCoroutine { cont ->
                        TgClient.send(TdApi.GetMessageProperties(chatId, msgId)) { res ->
                            if (cont.isActive) {
                                cont.resume(res as? TdApi.MessageProperties)
                            }
                        }
                    }

                    messagePropertiesById[msgId] = properties
                } finally {
                    loadingIds.remove(msgId)
                }
            }
        }
    }

    val shouldAutoClose by remember(messageIds, messages, missingIds) {
        derivedStateOf { 
            // 自动关闭条件：
            // 1. messageIds 为空（所有消息都被删除）
            // 2. 或者 messageIds 不为空但没有可显示的消息且没有待加载的消息
            messageIds.isEmpty() || (messageIds.isNotEmpty() && messages.isEmpty() && missingIds.isEmpty())
        }
    }
    val latestShouldAutoClose by rememberUpdatedState(shouldAutoClose)

    LaunchedEffect(shouldAutoClose) {
        if (shouldAutoClose) {
            delay(300.milliseconds)
            if (latestShouldAutoClose) {
                navController.popBackStack()
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

    if (messages.isEmpty()) {
        return
    }

    val pagerState = rememberPagerState(initialPage = 0) { messages.size + if (showMsgsInfo) 1 else 0 }

    AppScaffold(timeText = { StatusTimeText() }) {
        // Dialog 提示
        AlertDialog(
            visible = isShowDialog,
            onDismissRequest = {
                dialogItem.onDismissRequest()
                isShowDialog = false
            },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(
                    onClick = {
                        dialogItem.confirmButton()
                        isShowDialog = false
                    }
                )
            },
            title = dialogItem.title,
            modifier = dialogItem.modifier,
            icon = dialogItem.icon,
            text = dialogItem.text,
            verticalArrangement = dialogItem.verticalArrangement,
            contentPadding = dialogItem.contentPadding ?: if (dialogItem.icon != null) {
                AlertDialogDefaults.confirmDismissWithIconContentPadding()
            } else {
                AlertDialogDefaults.confirmDismissContentPadding()
            },
            properties = dialogItem.properties,
            content = dialogItem.content,
        )

        HorizontalPagerScaffold(pagerState = pagerState) {
            HorizontalPager(
                state = pagerState,
                flingBehavior =
                    PagerScaffoldDefaults.snapWithSpringFlingBehavior(state = pagerState),
                rotaryScrollableBehavior = null
            ) { page ->
                AnimatedPage(pageIndex = page, pagerState = pagerState) {
                    if (page == messages.size) {
                        val listState = rememberTransformingLazyColumnState()
                        val overscroll = rememberOverscrollEffect()

                        ScreenScaffold(
                            scrollState = listState,
                            overscrollEffect = overscroll,
                            edgeButton = {
                                EdgeButton(
                                    onClick = {
                                        chatObject?.let {
                                            openOnPhone = true
                                            context.openChatOnPhone(it, messages.firstOrNull()?.id)
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
                            val firstMessage = messages.firstOrNull()

                            // 获取发送者
                            var isYou by remember { mutableStateOf(firstMessage?.isOutgoing ?: false) }
                            var senderName by remember { mutableStateOf("") }
                            var senderChatId by remember { mutableStateOf<Long?>(null) }
                            if (firstMessage != null) {
                                LaunchedEffect(firstMessage.senderId) {
                                    if (!isYou) {
                                        when (val sender = firstMessage.senderId) {
                                            is TdApi.MessageSenderUser -> {
                                                if (sender.userId == UserManager.getActiveUser()?.userId) isYou = true
                                                else {
                                                    // 获取用户名
                                                    val user = suspendCancellableCoroutine { cont ->
                                                        TgClient.send(TdApi.GetUser(sender.userId)) { res ->
                                                            cont.resume(res as? TdApi.User)
                                                        }
                                                    }
                                                    user?.let { u ->
                                                        senderName = listOfNotNull(
                                                            u.firstName.takeIf { it.isNotBlank() },
                                                            u.lastName.takeIf { it.isNotBlank() }
                                                        ).joinToString(" ")
                                                    }
                                                    // 通过 CreatePrivateChat 获取可导航的 chatId
                                                    val chat = suspendCancellableCoroutine { cont ->
                                                        TgClient.send(TdApi.CreatePrivateChat(sender.userId, false)) { res ->
                                                            cont.resume(res as? TdApi.Chat)
                                                        }
                                                    }
                                                    chat?.let { senderChatId = it.id }
                                                }
                                            }
                                            is TdApi.MessageSenderChat -> {
                                                senderChatId = sender.chatId
                                                val chat = suspendCancellableCoroutine { cont ->
                                                    TgClient.send(TdApi.GetChat(sender.chatId)) { res ->
                                                        cont.resume(res as? TdApi.Chat)
                                                    }
                                                }
                                                chat?.let { senderName = it.title }
                                            }
                                        }
                                    }
                                }
                            }

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
                                            text = stringResource(R.string.Message_info),
                                            color = Color.White,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                        )
                                    }
                                }
                                item {
                                    val timeText = dateTimeUserPref(
                                        context,
                                        (messages.firstOrNull()?.date ?: 0) * 1000L
                                    )
                                    TitleCard(
                                        title = { Text(stringResource(R.string.Sent_time)) },
                                        onClick = { /* Not do something */ },
                                        onLongClick = {
                                            // 复制文本
                                            context.setClipboardText(timeText)

                                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Text(timeText)
                                    }
                                }

                                // 发送者信息
                                if (firstMessage != null) {
                                    item {
                                        TitleCard(
                                            title = { Text(stringResource(R.string.Sender)) },
                                            onClick = {
                                                if (!isYou) {
                                                    senderChatId?.let {
                                                        navController.navigate(Destinations.chat(it))
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                context.setClipboardText(senderName)
                                                Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text(
                                                text = when {
                                                    isYou -> stringResource(R.string.You)
                                                    senderName.isNotBlank() -> senderName
                                                    else -> "..."
                                                },
                                                maxLines = 5,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                if (firstMessage != null && firstMessage.editDate > 0) {
                                    item {
                                        val editTimeText = dateTimeUserPref(
                                            context,
                                            firstMessage.editDate * 1000L
                                        )
                                        TitleCard(
                                            title = { Text(stringResource(R.string.Edit_time)) },
                                            onClick = { /* Not do something */ },
                                            onLongClick = {
                                                context.setClipboardText(editTimeText)
                                                Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text(editTimeText)
                                        }
                                    }
                                }

                                // 2. 转发信息（如果有）
                                val forwardInfo = firstMessage?.forwardInfo
                                if (forwardInfo != null) {
                                    item {
                                        // 解析转发来源名称
                                        var forwardName by remember { mutableStateOf("") }
                                        var forwardChatId by remember { mutableStateOf<Long?>(null) }
                                        var isAnonymousForward by remember { mutableStateOf(false) }

                                        LaunchedEffect(forwardInfo) {
                                            when (val origin = forwardInfo.origin) {
                                                is TdApi.MessageOriginUser -> {
                                                    val name = suspendCancellableCoroutine { cont ->
                                                        TgClient.send(TdApi.GetUser(origin.senderUserId)) { res ->
                                                            cont.resume(
                                                                if (res is TdApi.User) {
                                                                    listOfNotNull(
                                                                        res.firstName.takeIf { it.isNotBlank() },
                                                                        res.lastName.takeIf { it.isNotBlank() }
                                                                    ).joinToString(" ")
                                                                } else ""
                                                            )
                                                        }
                                                    }
                                                    forwardName = name
                                                    // 获取可导航的 chatId
                                                    val chat = suspendCancellableCoroutine { cont ->
                                                        TgClient.send(TdApi.CreatePrivateChat(origin.senderUserId, false)) { res ->
                                                            cont.resume(res as? TdApi.Chat)
                                                        }
                                                    }
                                                    chat?.let { forwardChatId = it.id }
                                                }
                                                is TdApi.MessageOriginChannel -> {
                                                    forwardChatId = origin.chatId
                                                    val title = suspendCancellableCoroutine { cont ->
                                                        TgClient.send(TdApi.GetChat(origin.chatId)) { res ->
                                                            cont.resume(if (res is TdApi.Chat) res.title else "")
                                                        }
                                                    }
                                                    forwardName = title
                                                }
                                                is TdApi.MessageOriginChat -> {
                                                    forwardChatId = origin.senderChatId
                                                    val title = suspendCancellableCoroutine { cont ->
                                                        TgClient.send(TdApi.GetChat(origin.senderChatId)) { res ->
                                                            cont.resume(if (res is TdApi.Chat) res.title else "")
                                                        }
                                                    }
                                                    forwardName = title
                                                }
                                                is TdApi.MessageOriginHiddenUser -> {
                                                    forwardName = origin.senderName
                                                    isAnonymousForward = true
                                                }
                                            }
                                        }

                                        if (forwardName.isNotEmpty()) {
                                            TitleCard(
                                                title = { Text(stringResource(R.string.Forwarded_from)) },
                                                onClick = {
                                                    if (!isAnonymousForward && forwardChatId != null) {
                                                        navController.navigate(Destinations.chat(forwardChatId!!))
                                                    }
                                                },
                                                onLongClick = {
                                                    context.setClipboardText(forwardName)
                                                    Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Text(
                                                    text = forwardName,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }

                                // 3. 回复消息（如果有）
                                val replyTo = firstMessage?.replyTo
                                if (replyTo is TdApi.MessageReplyToMessage) {
                                    item {
                                        var replyMessage by remember { mutableStateOf<TdApi.Message?>(null) }
                                        var replyLoaded by remember { mutableStateOf(false) }
                                        val isSameChat = replyTo.chatId == chatId || replyTo.chatId == 0L

                                        // 尝试从本地缓存获取，否则通过 TgClient 获取
                                        LaunchedEffect(replyTo) {
                                            val targetChatId = if (replyTo.chatId == 0L) chatId else replyTo.chatId
                                            // 先尝试从 ChatMessagesRepository 中获取
                                            if (isSameChat) {
                                                val cached = chatMessagesById[replyTo.messageId]
                                                if (cached != null) {
                                                    replyMessage = cached
                                                    replyLoaded = true
                                                    return@LaunchedEffect
                                                }
                                            }
                                            // 否则通过网络获取
                                            val msg = suspendCancellableCoroutine { cont ->
                                                TgClient.send(TdApi.GetMessage(targetChatId, replyTo.messageId)) { res ->
                                                    cont.resume(res as? TdApi.Message)
                                                }
                                            }
                                            replyMessage = msg
                                            replyLoaded = true
                                        }

                                        val replyPreviewText = remember(replyMessage) {
                                            if (replyMessage != null) {
                                                handleAllMessages(context, replyMessage, maxText = 48).text
                                            } else null
                                        }

                                        TitleCard(
                                            title = { Text(stringResource(R.string.Reply_to)) },
                                            onClick = {
                                                if (isSameChat && replyMessage != null) {
                                                    // 打开回复消息的 MessageInfo
                                                    val key = System.currentTimeMillis()
                                                    appState.sharedMessageInfo[SharedMessageInfoKey(chatId, key)] =
                                                        SharedMessageInfoData(listOf(replyMessage!!.id))
                                                    navController.navigate(Destinations.messageInfo(chatId, key))
                                                }
                                            },
                                            onLongClick = {
                                                if (replyPreviewText != null) {
                                                    context.setClipboardText(replyPreviewText)
                                                    Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = if (replyLoaded) {
                                                    replyPreviewText ?: stringResource(R.string.Message_deleted)
                                                } else {
                                                    "..."
                                                },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                // 4. 管理员署名（如果有）
                                val authorSignature = firstMessage?.authorSignature
                                if (!authorSignature.isNullOrEmpty()) {
                                    item {
                                        TitleCard(
                                            title = { Text(stringResource(R.string.Author_signature)) },
                                            onClick = { /* Not do something */ },
                                            onLongClick = {
                                                context.setClipboardText(authorSignature)
                                                Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text(authorSignature)
                                        }
                                    }
                                }

                                // 5. 消息交互信息（查看次数、转发次数、表情反应）
                                val interactionInfo = firstMessage?.interactionInfo
                                if (interactionInfo != null) {
                                    // 查看次数
                                    val viewCount = interactionInfo.viewCount
                                    if (viewCount > 0) {
                                        item {
                                            TitleCard(
                                                title = { Text(stringResource(R.string.View_count)) },
                                                onClick = { /* Not do something */ },
                                                onLongClick = {
                                                    context.setClipboardText(viewCount.toString())
                                                    Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Text(viewCount.toString())
                                            }
                                        }
                                    }
                                    // 转发次数
                                    val forwardCount = interactionInfo.forwardCount
                                    if (forwardCount > 0) {
                                        item {
                                            TitleCard(
                                                title = { Text(stringResource(R.string.Forward_count)) },
                                                onClick = { /* Not do something */ },
                                                onLongClick = {
                                                    context.setClipboardText(forwardCount.toString())
                                                    Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Text(forwardCount.toString())
                                            }
                                        }
                                    }
                                    // 表情反应（如果有）
                                    val reactions = interactionInfo.reactions?.reactions
                                    if (!reactions.isNullOrEmpty()) {
                                        item {
                                            // 收集自定义表情 ID
                                            val customEmojiIds = remember(reactions) {
                                                reactions.mapNotNull { reaction ->
                                                    (reaction.type as? TdApi.ReactionTypeCustomEmoji)?.customEmojiId
                                                }.toLongArray()
                                            }
                                            // 异步获取自定义表情的 emoji 文本
                                            var customEmojiMap by remember { mutableStateOf(emptyMap<Long, String>()) }
                                            LaunchedEffect(customEmojiIds.toList()) {
                                                if (customEmojiIds.isNotEmpty()) {
                                                    val stickers = suspendCancellableCoroutine { cont ->
                                                        TgClient.send(TdApi.GetCustomEmojiStickers(customEmojiIds)) { res ->
                                                            cont.resume(res as? TdApi.Stickers)
                                                        }
                                                    }
                                                    stickers?.let { s ->
                                                        customEmojiMap = customEmojiIds.zip(s.stickers.map { it.emoji }).toMap()
                                                    }
                                                }
                                            }

                                            // 构建带颜色的 AnnotatedString
                                            val reactionsAnnotated = remember(reactions, customEmojiMap) {
                                                buildAnnotatedString {
                                                    reactions.forEachIndexed { index, reaction ->
                                                        if (index > 0) append("  ")
                                                        val emoji = when (val type = reaction.type) {
                                                            is TdApi.ReactionTypeEmoji -> type.emoji
                                                            is TdApi.ReactionTypeCustomEmoji -> customEmojiMap[type.customEmojiId] ?: "❓"
                                                            is TdApi.ReactionTypePaid -> "⭐"
                                                            else -> "?"
                                                        }
                                                        append("$emoji ")
                                                        if (reaction.isChosen) {
                                                            withStyle(SpanStyle(color = Color(0xFF64B5F6))) {
                                                                append("${reaction.totalCount}")
                                                            }
                                                        } else {
                                                            append("${reaction.totalCount}")
                                                        }
                                                    }
                                                }
                                            }
                                            // 纯文本用于复制
                                            val reactionsPlainText = remember(reactions, customEmojiMap) {
                                                reactions.joinToString(separator = "  ") { reaction ->
                                                    val emoji = when (val type = reaction.type) {
                                                        is TdApi.ReactionTypeEmoji -> type.emoji
                                                        is TdApi.ReactionTypeCustomEmoji -> customEmojiMap[type.customEmojiId] ?: "❓"
                                                        is TdApi.ReactionTypePaid -> "⭐"
                                                        else -> "?"
                                                    }
                                                    "$emoji ${reaction.totalCount}"
                                                }
                                            }
                                            TitleCard(
                                                title = { Text(stringResource(R.string.Reactions)) },
                                                onClick = {
                                                    // TODO: 点击发送表情功能待实现
                                                },
                                                onLongClick = {
                                                    context.setClipboardText(reactionsPlainText)
                                                    Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Text(
                                                    text = reactionsAnnotated,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }

                                // 6. 话题信息（如果有）
                                val topicId = firstMessage?.topicId
                                if (topicId is TdApi.MessageTopicForum) {
                                    item {
                                        var topicName by remember { mutableStateOf<String?>(null) }
                                        var topicLoaded by remember { mutableStateOf(false) }

                                        LaunchedEffect(topicId) {
                                            // 论坛话题，需要获取话题信息
                                            ChatMessagesRepository.getOrLoadForumTopic(chatId, topicId.forumTopicId) { topic ->
                                                topicName = topic?.info?.name
                                                topicLoaded = true
                                            }
                                        }

                                        // 只有在加载完成且话题名称存在时才显示
                                        if (topicLoaded && !topicName.isNullOrEmpty()) {
                                            TitleCard(
                                                title = { Text(stringResource(R.string.Topic)) },
                                                onClick = { /* Not do something */ },
                                                onLongClick = {
                                                    topicName?.let {
                                                        context.setClipboardText(it)
                                                        Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text(
                                                    text = topicName ?: "",
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val message = messages[page]
                        // 标记已经查看消息
                        LaunchedEffect(message.id, chatId) {
                            TgClient.send(
                                TdApi.ViewMessages(
                                    chatId,
                                    longArrayOf(message.id),
                                    TdApi.MessageSourceChatHistory(),
                                    true
                                )
                            )
                        }
                        message.content.let {
                            MessageContentFactory.Render(
                                content = it,
                                messageRenderContext = MessageRenderContext(
                                    navController = navController,
                                    chatId = chatId,
                                    messageId = message.id,
                                    chat = chatObject,
                                    message = message,
                                    properties = messagePropertiesById[message.id],
                                    useDialog = { dialog ->
                                        dialogItem = dialog
                                        isShowDialog = true
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
