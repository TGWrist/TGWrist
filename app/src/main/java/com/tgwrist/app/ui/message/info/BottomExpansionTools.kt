package com.tgwrist.app.ui.message.info

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.data.AlertDialogItem
import com.tgwrist.app.data.ForwardMessages
import com.tgwrist.app.data.ReplyMessage
import com.tgwrist.app.runtime.Config.forwardMessages
import com.tgwrist.app.runtime.Config.forwardMessagesFlow
import com.tgwrist.app.runtime.Config.replyMessage
import com.tgwrist.app.runtime.Config.replyMessageFlow
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.utils.getUserTranslateLanguageCode
import org.drinkless.tdlib.TdApi

@Composable
fun TranslationButton(
    modifier: Modifier = Modifier,
    surfaceTransformation: SurfaceTransformation? = null,
    text: TdApi.FormattedText,
    toLanguageCode: String = getUserTranslateLanguageCode(),
    tone: String = "neutral",
    onDone: (TdApi.FormattedText) -> Unit
) {
    var doTranslate by remember { mutableStateOf(false) }

    FilledTonalButton(
        enabled = !doTranslate,
        onClick = {
            doTranslate = true
            TgClient.send(TdApi.TranslateText(text, toLanguageCode, tone)) { result ->
                if (result is TdApi.FormattedText) {
                    doTranslate = false
                    onDone(result)
                } else {
                    // 翻译失败
                    doTranslate = false
                }
            }
        },
        label = {
            Text(
                text = stringResource(R.string.Translate),
                style = MaterialTheme.typography.labelSmall
            )
        },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Translate,
                contentDescription = "Translate"
            )
        },
        transformation = surfaceTransformation,
        modifier = modifier
            .fillMaxWidth()
    )
}

@Composable
fun ReplyMessageButton(
    modifier: Modifier = Modifier,
    surfaceTransformation: SurfaceTransformation? = null,
    properties: TdApi.MessageProperties?,
    message: TdApi.Message
) {
    val replyMsg = replyMessageFlow.collectAsState()
    val isSelected = replyMsg.value?.chatId == message.chatId && replyMsg.value?.messageId == message.id

    FilledTonalButton(
        onClick = {
            replyMessage = if (isSelected) {
                null
            } else {
                ReplyMessage(
                    chatId = message.chatId,
                    messageId = message.id,
                    canBeReplied = properties?.canBeReplied ?: true,
                    canBeRepliedInAnotherChat = properties?.canBeRepliedInAnotherChat ?: false
                )
            }
        },
        label = {
            Text(
                text = if (isSelected) stringResource(R.string.Reply_selected) else stringResource(R.string.Reply),
                style = MaterialTheme.typography.labelSmall
            )
        },
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Reply,
                contentDescription = "Reply"
            )
        },
        colors = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.let { selectedColor ->
                androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = selectedColor,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        } else {
            androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors()
        },
        transformation = surfaceTransformation,
        modifier = modifier
            .fillMaxWidth()
    )
}

@Composable
fun ForwardMessageButton(
    modifier: Modifier = Modifier,
    surfaceTransformation: SurfaceTransformation? = null,
    properties: TdApi.MessageProperties?,
    message: TdApi.Message
) {
    val canBeForwarded = properties?.canBeForwarded ?: true
    val canBeCopied = properties?.canBeCopied ?: false
    val forwardMsgs by forwardMessagesFlow.collectAsState()
    val isSelected = forwardMsgs?.chatId == message.chatId && forwardMsgs?.messageIds?.contains(message.id) == true

    FilledTonalButton(
        onClick = {
            forwardMessages = if (isSelected) {
                null
            } else {
                // 如果当前已有转发任务，且 chatId 对应得上，就在原基础上叠加 messageId
                if (forwardMsgs != null && forwardMsgs!!.chatId == message.chatId) {
                    forwardMsgs!!.copy(
                        // 使用 + 运算符，会创建一个包含新元素的新 List 对象，触发 UI 刷新
                        messageIds = forwardMsgs!!.messageIds + message.id
                    )
                } else {
                    // 创建全新的转发任务
                    ForwardMessages(
                        chatId = message.chatId,
                        messageIds = listOf(message.id),
                        canBeCopied = canBeCopied,
                        canBeForwarded = canBeForwarded
                    )
                }
            }
        },
        label = {
            Text(
                text = if (isSelected) stringResource(R.string.Forward_selected) else
                    if (forwardMsgs == null || forwardMsgs!!.chatId != message.chatId) stringResource(R.string.Forward) else stringResource(R.string.Add_forward),
                style = MaterialTheme.typography.labelSmall
            )
        },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_forward),
                contentDescription = null
            )
        },
        colors = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.let { selectedColor ->
                androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = selectedColor,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        } else {
            androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors()
        },
        transformation = surfaceTransformation,
        modifier = modifier
            .fillMaxWidth()
    )
}

@Composable
fun DeleteMessageButton(
    modifier: Modifier = Modifier,
    surfaceTransformation: SurfaceTransformation? = null,
    chat: TdApi.Chat,
    messageId: Long,
    properties: TdApi.MessageProperties?,
    useDialog: (AlertDialogItem) -> Unit
) {
    // 判断删除选项
    val canDeleteForAllUsers = properties?.canBeDeletedForAllUsers ?: chat.canBeDeletedForAllUsers
    val canDeleteOnlyForSelf = properties?.canBeDeletedOnlyForSelf ?: chat.canBeDeletedOnlyForSelf

    // 判断消息是否不可以删除
    val canNotDelete = !canDeleteForAllUsers && !canDeleteOnlyForSelf

    // 如果消息不能删除，不显示按钮
    if (canNotDelete) {
        return
    }
    
    // 确定删除模式：0=只能为自己删除, 1=只能为所有人删除, 2=可以选择
    val deleteMode = when {
        canDeleteOnlyForSelf && !canDeleteForAllUsers -> 0 // 只能为自己删除
        !canDeleteOnlyForSelf -> 1 // 只能为所有人删除
        else -> 2 // 可以选择
    }
    
    val isRevokeState = remember { mutableStateOf(deleteMode != 0) }

    FilledTonalButton(
        onClick = {
            useDialog(
                AlertDialogItem(
                    title = {
                        Text(stringResource(R.string.Confirm_deletion))
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Delete"
                        )
                    },
                    confirmButton = {
                        TgClient.send(TdApi.DeleteMessages(chat.id, longArrayOf(messageId), isRevokeState.value))
                    },
                    content = {
                        when (deleteMode) {
                            0 -> {
                                // 只能为自己删除
                                item {
                                    Text(
                                        text = stringResource(R.string.Delete_for_me_only),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                            1 -> {
                                // 只能为所有人删除
                                item {
                                    Text(
                                        text = stringResource(R.string.Delete_for_everyone_only),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                            2 -> {
                                // 可以选择
                                item {
                                    RadioButton(
                                        selected = isRevokeState.value,
                                        onSelect = {
                                            isRevokeState.value = !isRevokeState.value
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.Delete_for_everyone))
                                    }
                                }
                            }
                        }
                    }
                )
            )
        },
        label = {
            Text(
                text = stringResource(R.string.Delete),
                style = MaterialTheme.typography.labelSmall
            )
        },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = "Delete"
            )
        },
        transformation = surfaceTransformation,
        modifier = modifier
            .fillMaxWidth()
    )
}
