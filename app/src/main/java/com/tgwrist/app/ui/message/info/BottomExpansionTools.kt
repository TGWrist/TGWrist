package com.tgwrist.app.ui.message.info

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.tgwrist.app.utils.TgClient
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
