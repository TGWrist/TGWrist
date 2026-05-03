package com.tgwrist.app.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.utils.Config
import com.tgwrist.app.utils.TgClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

@Composable
fun MessageRouting(
    modifier: Modifier = Modifier,
    msgList: List<TdApi.Message>,
    chatObject: TdApi.Chat,
    lastReadOutboxMessageId: Long = 0L,
    transformation: SurfaceTransformation?,
    onClick: () -> Unit
) {
    val msg = msgList.singleOrNull()
    val youString = stringResource(R.string.You)
    val someoneString = stringResource(R.string.msg_someone)

    when (val content = msg?.content) {
        is TdApi.MessagePinMessage -> {
            BlueCircleChar(modifier = modifier, stringResource(R.string.Pinned_message), transformation = transformation)
        }
        is TdApi.MessageBasicGroupChatCreate -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_basic_group_created, senderName)
            }
        }
        is TdApi.MessageChatAddMembers -> {
            // 检查是否是自己加入（senderName 和 memberName 相同的情况）
            val senderId = msg.senderId
            val memberIds = content.memberUserIds.toList()
            val isSelfJoin = senderId is TdApi.MessageSenderUser &&
                    memberIds.size == 1 &&
                    memberIds[0] == senderId.userId

            if (isSelfJoin) {
                SystemMessageWithSender(
                    modifier = modifier,
                    msg = msg,
                    youString = youString,
                    someoneString = someoneString,
                    transformation = transformation
                ) { senderName ->
                    stringResource(R.string.msg_chat_self_joined, senderName)
                }
            } else {
                SystemMessageWithSenderAndMembers(
                    modifier = modifier,
                    msg = msg,
                    memberUserIds = memberIds,
                    youString = youString,
                    someoneString = someoneString,
                    transformation = transformation
                ) { senderName, membersName ->
                    stringResource(R.string.msg_chat_add_members, senderName, membersName)
                }
            }
        }
        is TdApi.MessageChatBoost -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_chat_boost, senderName)
            }
        }
        is TdApi.MessageChatChangePhoto -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_chat_change_photo, senderName)
            }
        }
        is TdApi.MessageChatChangeTitle -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_chat_change_title, senderName, content.title)
            }
        }
        is TdApi.MessageChatDeleteMember -> {
            // 检查是否是自己离开（senderName 和 memberName 相同的情况）
            val senderId = msg.senderId
            val isSelfLeft = senderId is TdApi.MessageSenderUser &&
                    senderId.userId == content.userId

            if (isSelfLeft) {
                SystemMessageWithSender(
                    modifier = modifier,
                    msg = msg,
                    youString = youString,
                    someoneString = someoneString,
                    transformation = transformation
                ) { senderName ->
                    stringResource(R.string.msg_chat_self_left, senderName)
                }
            } else {
                SystemMessageWithSenderAndMember(
                    modifier = modifier,
                    msg = msg,
                    memberUserId = content.userId,
                    youString = youString,
                    someoneString = someoneString,
                    transformation = transformation
                ) { senderName, memberName ->
                    stringResource(R.string.msg_chat_delete_member, senderName, memberName)
                }
            }
        }
        is TdApi.MessageChatDeletePhoto -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_chat_delete_photo, senderName)
            }
        }
        is TdApi.MessageChatHasProtectedContentDisableRequested -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_chat_protected_content_disable_requested, senderName)
            }
        }
        is TdApi.MessageChatHasProtectedContentToggled -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                if (content.newHasProtectedContent) {
                    stringResource(R.string.msg_chat_protected_content_enabled, senderName)
                } else {
                    stringResource(R.string.msg_chat_protected_content_disabled, senderName)
                }
            }
        }
        is TdApi.MessageChatJoinByLink -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_chat_join_by_link, senderName)
            }
        }
        is TdApi.MessageChatJoinByRequest -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_chat_join_by_request, senderName)
            }
        }
        is TdApi.MessageChatOwnerChanged -> {
            SystemMessageWithUser(
                modifier = modifier,
                userId = content.newOwnerUserId,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { ownerName ->
                stringResource(R.string.msg_chat_owner_changed, ownerName)
            }
        }
        is TdApi.MessageChatOwnerLeft -> {
            BlueCircleChar(modifier = modifier, stringResource(R.string.msg_chat_owner_left), transformation = transformation)
        }
        is TdApi.MessageChatSetBackground -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_chat_set_background, senderName)
            }
        }
        is TdApi.MessageChatSetMessageAutoDeleteTime -> {
            SystemMessageWithSenderAndAutoDelete(
                modifier = modifier,
                msg = msg,
                messageAutoDeleteTime = content.messageAutoDeleteTime,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            )
        }
        is TdApi.MessageChatSetTheme -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                when (val theme = content.theme) {
                    is TdApi.ChatThemeEmoji if theme.name.isNotEmpty() -> {
                        stringResource(R.string.msg_chat_set_theme, senderName, theme.name)
                    }

                    is TdApi.ChatThemeGift -> {
                        stringResource(R.string.msg_chat_set_theme, senderName, "Gift")
                    }

                    else -> {
                        stringResource(R.string.msg_chat_set_theme_reset, senderName)
                    }
                }
            }
        }
        is TdApi.MessageChatUpgradeFrom -> {
            BlueCircleChar(modifier = modifier, stringResource(R.string.msg_chat_upgrade_from), transformation = transformation)
        }
        is TdApi.MessageChatUpgradeTo -> {
            BlueCircleChar(modifier = modifier, stringResource(R.string.msg_chat_upgrade_to), transformation = transformation)
        }
        is TdApi.MessageForumTopicCreated -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_forum_topic_created, senderName, content.name)
            }
        }
        is TdApi.MessageForumTopicEdited -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_forum_topic_edited, senderName)
            }
        }
        is TdApi.MessageForumTopicIsClosedToggled -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                if (content.isClosed) {
                    stringResource(R.string.msg_forum_topic_closed, senderName)
                } else {
                    stringResource(R.string.msg_forum_topic_reopened, senderName)
                }
            }
        }
        is TdApi.MessageForumTopicIsHiddenToggled -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                if (content.isHidden) {
                    stringResource(R.string.msg_forum_topic_hidden, senderName)
                } else {
                    stringResource(R.string.msg_forum_topic_unhidden, senderName)
                }
            }
        }
        is TdApi.MessageSupergroupChatCreate -> {
            SystemMessageWithSender(
                modifier = modifier,
                msg = msg,
                youString = youString,
                someoneString = someoneString,
                transformation = transformation
            ) { senderName ->
                stringResource(R.string.msg_supergroup_created, senderName)
            }
        }
        else -> {
            MessageView(
                modifier = modifier,
                msgList = msgList,
                chatObject = chatObject,
                lastReadOutboxMessageId = lastReadOutboxMessageId,
                transformation = transformation,
                onClick = onClick
            )
        }
    }
}

/**
 * 获取用户名的挂起函数
 */
private suspend fun getUserName(userId: Long, youString: String, someoneString: String): String {
    if (userId == Config.currentUser.value?.id) {
        return youString
    }
    return suspendCancellableCoroutine { cont ->
        TgClient.send(TdApi.GetUser(userId)) { res ->
            // 检查协程是否已取消，防止崩溃
            if (!cont.isActive) return@send
            val name = if (res is TdApi.User) {
                listOfNotNull(res.firstName, res.lastName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifEmpty { someoneString }
            } else {
                someoneString
            }
            cont.resume(name)
        }
    }
}

/**
 * 从消息的 senderId 获取发送者名称
 */
private suspend fun getSenderName(msg: TdApi.Message, youString: String, someoneString: String): String {
    return when (val senderId = msg.senderId) {
        is TdApi.MessageSenderUser -> getUserName(senderId.userId, youString, someoneString)
        is TdApi.MessageSenderChat -> {
            suspendCancellableCoroutine { cont ->
                TgClient.send(TdApi.GetChat(senderId.chatId)) { res ->
                    // 检查协程是否已取消，防止崩溃
                    if (!cont.isActive) return@send
                    val title = (res as? TdApi.Chat)?.title ?: someoneString
                    cont.resume(title)
                }
            }
        }
        else -> someoneString
    }
}

/**
 * 带发送者名称的系统消息组件
 */
@Composable
private fun SystemMessageWithSender(
    modifier: Modifier = Modifier,
    msg: TdApi.Message,
    youString: String,
    someoneString: String,
    transformation: SurfaceTransformation?,
    textBuilder: @Composable (String) -> String
) {
    var senderName by remember { mutableStateOf(someoneString) }

    LaunchedEffect(msg.id) {
        senderName = getSenderName(msg, youString, someoneString)
    }

    BlueCircleChar(
        modifier = modifier,
        text = textBuilder(senderName),
        transformation = transformation
    )
}

/**
 * 带指定用户 ID 的系统消息组件
 */
@Composable
private fun SystemMessageWithUser(
    modifier: Modifier = Modifier,
    userId: Long,
    youString: String,
    someoneString: String,
    transformation: SurfaceTransformation?,
    textBuilder: @Composable (String) -> String
) {
    var userName by remember { mutableStateOf(someoneString) }

    LaunchedEffect(userId) {
        userName = getUserName(userId, youString, someoneString)
    }

    BlueCircleChar(
        modifier = modifier,
        text = textBuilder(userName),
        transformation = transformation
    )
}

/**
 * 带发送者和成员列表的系统消息组件 (用于添加成员)
 */
@Composable
private fun SystemMessageWithSenderAndMembers(
    modifier: Modifier = Modifier,
    msg: TdApi.Message,
    memberUserIds: List<Long>,
    youString: String,
    someoneString: String,
    transformation: SurfaceTransformation?,
    textBuilder: @Composable (String, String) -> String
) {
    var senderName by remember { mutableStateOf(someoneString) }
    var membersName by remember { mutableStateOf(someoneString) }

    // 使用 msg.id 作为 key，避免 List 对象导致的不必要重组
    LaunchedEffect(msg.id) {
        senderName = getSenderName(msg, youString, someoneString)
        membersName = if (memberUserIds.isEmpty()) {
            someoneString
        } else {
            memberUserIds.take(3).map { userId ->
                getUserName(userId, youString, someoneString)
            }.joinToString(", ") + if (memberUserIds.size > 3) "..." else ""
        }
    }

    BlueCircleChar(
        modifier = modifier,
        text = textBuilder(senderName, membersName),
        transformation = transformation
    )
}

/**
 * 带发送者和单个成员的系统消息组件 (用于删除成员)
 */
@Composable
private fun SystemMessageWithSenderAndMember(
    modifier: Modifier = Modifier,
    msg: TdApi.Message,
    memberUserId: Long,
    youString: String,
    someoneString: String,
    transformation: SurfaceTransformation?,
    textBuilder: @Composable (String, String) -> String
) {
    var senderName by remember { mutableStateOf(someoneString) }
    var memberName by remember { mutableStateOf(someoneString) }

    LaunchedEffect(msg.id, memberUserId) {
        senderName = getSenderName(msg, youString, someoneString)
        memberName = getUserName(memberUserId, youString, someoneString)
    }

    BlueCircleChar(
        modifier = modifier,
        text = textBuilder(senderName, memberName),
        transformation = transformation
    )
}

/**
 * 带发送者和自动删除时间的系统消息组件
 */
@Composable
private fun SystemMessageWithSenderAndAutoDelete(
    modifier: Modifier = Modifier,
    msg: TdApi.Message,
    messageAutoDeleteTime: Int,
    youString: String,
    someoneString: String,
    transformation: SurfaceTransformation?
) {
    var senderName by remember { mutableStateOf(someoneString) }

    val autoDeleteText = when (messageAutoDeleteTime) {
        0 -> null // off
        86400 -> stringResource(R.string.msg_auto_delete_1_day)
        604800 -> stringResource(R.string.msg_auto_delete_1_week)
        2678400 -> stringResource(R.string.msg_auto_delete_1_month)
        else -> stringResource(R.string.msg_auto_delete_seconds, messageAutoDeleteTime)
    }

    LaunchedEffect(msg.id) {
        senderName = getSenderName(msg, youString, someoneString)
    }

    val text = if (autoDeleteText != null) {
        stringResource(R.string.msg_chat_auto_delete_on, senderName, autoDeleteText)
    } else {
        stringResource(R.string.msg_chat_auto_delete_off, senderName)
    }

    BlueCircleChar(
        modifier = modifier,
        text = text,
        transformation = transformation
    )
}

@Composable
fun BlueCircleChar(
    modifier: Modifier = Modifier,
    text: String,
    transformation: SurfaceTransformation?,
    onClick: () -> Unit = {}
) {
    // 1. 换成标准的 Button，它能完美包容 bodyLarge 级别的字体高度
    Button(
        onClick = { onClick.invoke() },
        // 2. 删掉 .fillMaxHeight()，只保留传入的 modifier
        modifier = modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),

        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1E2C3A),
            contentColor = Color.White
        ),

        // 3. 保持圆形
        shape = CircleShape,

        // 4. 调整 Padding。水平稍微大一点，给文本两边留呼吸空间；
        // 垂直保留合适的距离，普通的 Button 不会因为这个距离去裁切内容
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),

        transformation = transformation
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge, // 大字体现在有空间了！
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
