package com.tgwrist.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.ui.main.ThumbnailChatPhoto
import com.tgwrist.app.utils.Config
import com.tgwrist.app.utils.TgClient
import com.tgwrist.app.utils.handleAllMessages
import com.tgwrist.app.utils.time
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

@Composable
fun MessageView(
    modifier: Modifier = Modifier,
    msgList: List<TdApi.Message>,
    chatObject: TdApi.Chat,
    lastReadOutboxMessageId: Long = 0L,
    transformation: SurfaceTransformation?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val youString = stringResource(id = R.string.You)

    // State 初始化优化：
    // 1. 直接使用 chatObject 的数据作为默认值，防止异步加载失败或未执行时显示空白
    // 2. 对于私聊，这直接就是正确答案；对于群聊，这会先显示群头像，等异步加载完发送者后自动跳变（这是符合逻辑的）
    val senderName = remember { mutableStateOf(chatObject.title) }
    val accentColorId = remember { mutableIntStateOf(chatObject.accentColorId) }
    val chatPhoto = remember { mutableStateOf(chatObject.photo?.small) }
    var isYou by remember { mutableStateOf(false) }

    // 缓存
    val nameCache = remember { mutableMapOf<Long, String>() }
    val chatTitleCache = remember { mutableMapOf<Long, String>() }

    // 获取用于判断发送者的消息
    val representMessage = remember(msgList) { msgList.firstOrNull() }

    LaunchedEffect(representMessage, chatObject) {
        if (representMessage == null) {
            // 回退到 Chat 自身信息
            senderName.value = chatObject.title
            chatPhoto.value = chatObject.photo?.small
            return@LaunchedEffect
        }

        // 定义获取用户名的挂起函数
        suspend fun getUserName(userId: Long): String {
            if (userId == Config.currentUser.value?.id) {
                // 如果是自己，不需要头像（或者你可以设置为当前用户的头像）
                chatPhoto.value = null
                isYou = true
                return youString
            }
            nameCache[userId]?.let { return it }
            return suspendCancellableCoroutine { cont ->
                TgClient.send(TdApi.GetUser(userId)) { res ->
                    val name = if (res is TdApi.User) {
                        listOfNotNull(res.firstName, res.lastName).filter { it.isNotBlank() }.joinToString(" ")
                    } else ""

                    if (name.isNotEmpty()) nameCache[userId] = name

                    // 【关键点】在这里更新 State，确保 UI 刷新
                    if (res is TdApi.User) {
                        accentColorId.intValue = res.accentColorId
                        chatPhoto.value = res.profilePhoto?.small
                    }
                    cont.resume(name)
                }
            }
        }

        // 定义获取群/频道信息的挂起函数
        suspend fun getChatTitle(chatId: Long): String {
            chatTitleCache[chatId]?.let { return it }
            return suspendCancellableCoroutine { cont ->
                TgClient.send(TdApi.GetChat(chatId)) { res ->
                    val title = (res as? TdApi.Chat)?.title ?: ""
                    if (title.isNotEmpty()) chatTitleCache[chatId] = title

                    // 【关键点】更新 State
                    if (res is TdApi.Chat) {
                        accentColorId.intValue = res.accentColorId
                        chatPhoto.value = res.photo?.small
                    }
                    cont.resume(title)
                }
            }
        }

        // 解析逻辑
        val resolvedName = when {
            // 1. Saved Messages 或 自己发的消息
            chatObject.id == Config.currentUser.value?.id -> {
                val forwardInfo = representMessage.forwardInfo
                if (forwardInfo == null) {
                    // 只有这里特殊：显示“你”
                    chatPhoto.value = null
                    isYou = true
                    youString
                } else when (val origin = forwardInfo.origin) {
                    is TdApi.MessageOriginUser -> getUserName(origin.senderUserId)
                    is TdApi.MessageOriginChannel -> getChatTitle(origin.chatId)
                    is TdApi.MessageOriginHiddenUser -> forwardInfo.source?.senderName ?: ""
                    is TdApi.MessageOriginChat -> getChatTitle(origin.senderChatId)
                    else -> ""
                }
            }
            // 2. 群组/超级群
            chatObject.type is TdApi.ChatTypeBasicGroup || chatObject.type is TdApi.ChatTypeSupergroup -> {
                when (val senderId = representMessage.senderId) {
                    is TdApi.MessageSenderUser -> getUserName(senderId.userId)
                    is TdApi.MessageSenderChat -> {
                        if (senderId.chatId != chatObject.id) getChatTitle(senderId.chatId)
                        else {
                            // Channel身份发送，重置回 Chat 的信息
                            accentColorId.intValue = chatObject.accentColorId
                            chatPhoto.value = chatObject.photo?.small
                            chatObject.title
                        }
                    }
                    else -> chatObject.title
                }
            }
            // 3. 普通私聊 (Private)
            else -> {
                // 【致命问题修复】
                // 之前这里只返回了 title，导致 private chat 的头像一直是初始值 null
                // 现在我们显式重置回 chatObject 的数据
                accentColorId.intValue = chatObject.accentColorId
                chatPhoto.value = chatObject.photo?.small
                when (val senderId = representMessage.senderId) {
                    is TdApi.MessageSenderUser -> {
                        if (senderId.userId != chatObject.id) getUserName(senderId.userId)
                        else chatObject.title
                    }
                    is TdApi.MessageSenderChat -> {
                        if (senderId.chatId != chatObject.id) getChatTitle(senderId.chatId)
                        else {
                            // Channel身份发送，重置回 Chat 的信息
                            accentColorId.intValue = chatObject.accentColorId
                            chatPhoto.value = chatObject.photo?.small
                            chatObject.title
                        }
                    }
                    else -> chatObject.title
                }
            }
        }

        senderName.value = resolvedName
    }

    val messageText = buildAnnotatedString {
        // 是否是回复消息
        if (representMessage?.replyTo != null) {
            withStyle(style = SpanStyle(color = colorResource(id = R.color.blue))) {
                append(stringResource(R.string.Reply))
            }
            append(" ")
        }

        // 是否是转发消息
        if (representMessage?.forwardInfo != null) {
            withStyle(style = SpanStyle(color = colorResource(id = R.color.blue))) {
                append(stringResource(R.string.Forwarded))
            }
            append(" ")
        }

        if (msgList.size <= 1) {
            // 单条消息：直接输出（含描述）
            msgList.firstOrNull()?.let {
                append(handleAllMessages(context = context, message = it, maxText = 128))
            }
        } else {
            // 多条消息（相册）：先按升序输出所有媒体标签，再追加描述文字
            // 例如 [Photo+描述(100), Video(200)] → "Photo Video 描述"
            msgList.forEachIndexed { index, msg ->
                if (index > 0) append(" ")
                append(handleAllMessages(context = context, message = msg, maxText = 128, includeCaption = false))
            }
            // 收集所有描述文字，追加到末尾
            for (msg in msgList) {
                val caption = when (val c = msg.content) {
                    is TdApi.MessagePhoto -> c.caption.text
                    is TdApi.MessageVideo -> c.caption.text
                    is TdApi.MessageVoiceNote -> c.caption.text
                    is TdApi.MessageAnimation -> c.caption.text
                    is TdApi.MessageDocument -> c.caption.text
                    is TdApi.MessageText -> c.text.text
                    else -> null
                }?.replace('\n', ' ')?.trim()
                if (!caption.isNullOrEmpty()) {
                    append(" ")
                    append(if (caption.length > 128) caption.take(128) + "..." else caption)
                    break // 相册通常只有一条消息带描述
                }
            }
        }
    }

    AppCard(
        onClick = { onClick.invoke() },
        transformation = transformation,
        appName = {
            if (isYou || representMessage?.isOutgoing == true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = senderName.value,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 自己发的消息是否已读指示器
                    // 取代表消息（最新的一条）判断发送/已读状态
                    val statusIcon = when {
                        // 消息尚未发送成功（sendingState != null）
                        representMessage?.sendingState != null -> Icons.Rounded.HistoryToggleOff
                        // 消息已发送且对方已读（id <= lastReadOutboxMessageId）
                        representMessage != null && representMessage.id <= lastReadOutboxMessageId -> Icons.Rounded.DoneAll
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
            } else {
                Text(
                    // 此时 senderName 已经有了默认值（chatObject.title），几乎不会为空
                    text = senderName.value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        appImage = {
            if (!isYou) {
                ThumbnailChatPhoto(
                    thumbnail = chatPhoto.value, // 这里传入的是 State 的当前值
                    title = senderName.value,
                    accentColorId = accentColorId.intValue,
                    contentDescription = "Chat Photo",
                    modifier = Modifier
                        .size(CardDefaults.AppImageSize)
                        .clip(CircleShape)
                        .wrapContentSize(align = Alignment.Center)
                )
            }
        },
        title = {
            Text(
                text = messageText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        },
        time = {
            val date = msgList.firstOrNull()?.date ?: 0
            val timeText = time(context, (date.toLong() * 1000))
            Text(timeText)
        },
        modifier = modifier
    ) {}
}
