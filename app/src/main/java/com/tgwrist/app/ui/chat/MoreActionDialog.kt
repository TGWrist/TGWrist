package com.tgwrist.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.data.ForwardMessages
import com.tgwrist.app.runtime.Config.forwardMessages
import com.tgwrist.app.runtime.Config.forwardMessagesFlow
import com.tgwrist.app.runtime.TgClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

@Composable
fun MoreActionDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    chat: TdApi.Chat?,
    selectedMessages: List<TdApi.Message>,
    onCancelRequest: () -> Unit,
) {
    if (chat == null) return

    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()

    val selectedMessageIds = remember(selectedMessages) {
        selectedMessages.map { it.id }.toSet()
    }

    val forwardMsgs by forwardMessagesFlow.collectAsState()
    val isSelected = forwardMsgs?.chatId == chat.id &&
            (forwardMsgs?.messageIds?.containsAll(selectedMessageIds) == true)

    // 删除确认 Dialog 的显示状态（内聚在本组件内部，不再上抛到 ChatScreen）
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 异步查询每条选中消息的删除权限（TdApi.GetMessageProperties）
    // key = messageId, value = 该消息的属性（可能为 null）
    val messageProperties = remember { mutableStateMapOf<Long, TdApi.MessageProperties?>() }

    LaunchedEffect(selectedMessages) {
        messageProperties.clear()
        selectedMessages.forEach { msg ->
            launch {
                val props = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetMessageProperties(msg.chatId, msg.id)) { res ->
                        if (cont.isActive) cont.resume(res as? TdApi.MessageProperties)
                    }
                }
                messageProperties[msg.id] = props
            }
        }
    }

    // 聚合删除能力：只有当“所有”选中消息都允许某种删除方式时，该方式才可用
    // 单条消息的属性优先，未取到时回退到聊天级别的能力
    val canDeleteForAllUsers = selectedMessages.isNotEmpty() && selectedMessages.all { msg ->
        messageProperties[msg.id]?.canBeDeletedForAllUsers ?: chat.canBeDeletedForAllUsers
    }
    val canDeleteOnlyForSelf = selectedMessages.isNotEmpty() && selectedMessages.all { msg ->
        messageProperties[msg.id]?.canBeDeletedOnlyForSelf ?: chat.canBeDeletedOnlyForSelf
    }
    val canDelete = canDeleteForAllUsers || canDeleteOnlyForSelf

    // 确定删除模式：0=只能为自己删除, 1=只能为所有人删除, 2=可以选择
    val deleteMode = when {
        canDeleteOnlyForSelf && !canDeleteForAllUsers -> 0
        !canDeleteOnlyForSelf -> 1
        else -> 2
    }
    val isRevokeState = remember(deleteMode) { mutableStateOf(deleteMode != 0) }

    val confirmDeletionText = stringResource(R.string.Confirm_deletion)
    val deleteForMeOnlyText = stringResource(R.string.Delete_for_me_only)
    val deleteForEveryoneOnlyText = stringResource(R.string.Delete_for_everyone_only)
    val deleteForEveryoneText = stringResource(R.string.Delete_for_everyone)

    val canBeForwarded = selectedMessages.isNotEmpty() && selectedMessages.all { msg ->
        messageProperties[msg.id]?.canBeForwarded ?: true
    }
    val canBeCopied = selectedMessages.isNotEmpty() && selectedMessages.all { msg ->
        messageProperties[msg.id]?.canBeCopied ?: true
    }

    Dialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
    ) {
        ScreenScaffold(
            scrollState = listState,
            overscrollEffect = overscroll,
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
                            text = stringResource(R.string.Message_Options),
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                        )
                    }
                }

                // 显示选中消息的数量
                item {
                    Text(
                        text = stringResource(R.string.Selected_messages_count, selectedMessages.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )
                }

                // 消息转发选项
                item {
                    FilledTonalButton(
                        onClick = {
                            forwardMessages = if (isSelected) {
                                // 【反选逻辑】：从当前转发任务中移除这些 messageId
                                if (forwardMsgs != null && forwardMsgs!!.chatId == chat.id) {
                                    // 使用 "-" 运算符减去要取消的 IDs (转换为 Set 可以确保正确移除集合)
                                    val remainingIds = forwardMsgs!!.messageIds - selectedMessageIds.toSet()

                                    if (remainingIds.isEmpty()) {
                                        null // 减到没有了，清空整个转发任务
                                    } else {
                                        // 还有剩余的消息，仅更新 messageIds 列表
                                        forwardMsgs!!.copy(messageIds = remainingIds)
                                    }
                                } else {
                                    null
                                }
                            } else {
                                // 如果当前已有转发任务，且 chatId 对应得上，就在原基础上叠加 messageId
                                if (forwardMsgs != null && forwardMsgs!!.chatId == chat.id) {
                                    forwardMsgs!!.copy(
                                        // 使用 + 运算符，会创建一个包含新元素的新 List 对象，触发 UI 刷新
                                        messageIds = (forwardMsgs!!.messageIds + selectedMessageIds).distinct()
                                    )
                                } else {
                                    // 创建全新的转发任务
                                    ForwardMessages(
                                        chatId = chat.id,
                                        messageIds = selectedMessageIds.toList(),
                                        canBeCopied = canBeCopied,
                                        canBeForwarded = canBeForwarded
                                    )
                                }
                            }
                        },
                        label = {
                            Text(
                                text = if (isSelected) stringResource(R.string.Forward_selected) else
                                    if (forwardMsgs == null || forwardMsgs!!.chatId != chat.id) stringResource(R.string.Forward) else stringResource(R.string.Add_forward),
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
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec)
                            .fillMaxWidth()
                    )
                }

                // 删除消息选项：仅在消息可删除时显示
                if (canDelete) {
                    item {
                        FilledTonalButton(
                            onClick = { showDeleteConfirm = true },
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
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier
                                .transformedHeight(this, transformationSpec)
                                .fillMaxWidth()
                        )
                    }
                }

                // 取消选择
                item {
                    FilledTonalButton(
                        onClick = { onCancelRequest.invoke() },
                        label = {
                            Text(
                                text = stringResource(R.string.Cancel),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cancel"
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec)
                            .fillMaxWidth()
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // 删除确认 Dialog（内聚于 MoreActionDialog 内部）
    AlertDialog(
        visible = showDeleteConfirm,
        onDismissRequest = { showDeleteConfirm = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    val chatId = chat.id
                    val messageIds = selectedMessages.map { it.id }.toLongArray()
                    if (messageIds.isNotEmpty()) {
                        TgClient.send(
                            TdApi.DeleteMessages(chatId, messageIds, isRevokeState.value)
                        )
                        onCancelRequest()
                    }
                    showDeleteConfirm = false
                    onDismissRequest()
                }
            )
        },
        title = { Text(confirmDeletionText) },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = "Delete"
            )
        },
        content = {
            when (deleteMode) {
                0 -> item {
                    Text(
                        text = deleteForMeOnlyText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                1 -> item {
                    Text(
                        text = deleteForEveryoneOnlyText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                2 -> item {
                    RadioButton(
                        selected = isRevokeState.value,
                        onSelect = { isRevokeState.value = !isRevokeState.value },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(deleteForEveryoneText)
                    }
                }
            }
        },
    )
}
