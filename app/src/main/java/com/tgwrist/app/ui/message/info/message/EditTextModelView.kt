package com.tgwrist.app.ui.message.info.message

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Icon
import com.tgwrist.app.runtime.ChatMessagesRepository
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.chat.CustomTextInput
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

@Composable
fun EditTextModelView(
    editText: MutableState<String>,
    isEditing: MutableState<Boolean>,
    messageRenderContext: MessageRenderContext
) {
    val interactionSource1 = remember { MutableInteractionSource() }
    val interactionSource2 = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()

    // 文本编辑框
    Column {
        CustomTextInput(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            text = editText.value,
            onTextChange = { editText.value = it }
        )

        ButtonGroup(Modifier.fillMaxWidth()) {
            // 按钮1 换行按钮
            Button(
                onClick = {
                    editText.value += "\n"
                },
                modifier = Modifier.animateWidth(interactionSource1),
                interactionSource = interactionSource1,
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardReturn,
                        modifier = Modifier.size(32.dp),
                        contentDescription = "Enter"
                    )
                }
            }
            // 按钮2 完成编辑按钮
            Button(
                onClick = {
                    TgClient.send(TdApi.EditMessageText(
                        messageRenderContext.chatId,
                        messageRenderContext.messageId,
                        null,
                        TdApi.InputMessageText().apply {
                            this.text = TdApi.FormattedText(editText.value, null)
                            this.clearDraft = false
                        }
                    )) { result ->
                        if (result is TdApi.Message) {
                            scope.launch(Dispatchers.Main) {
                                isEditing.value = false
                                editText.value = ""
                                ChatMessagesRepository.publicAddOrReplaceMessage(result)
                            }
                        } else if (result is TdApi.Error && result.code == 400) {
                            // 如果编辑文本失败，尝试编辑消息的标题（可能是媒体消息）
                            TgClient.send(TdApi.EditMessageCaption(
                                messageRenderContext.chatId,
                                messageRenderContext.messageId,
                                null,
                                TdApi.FormattedText(editText.value, null),
                                false
                            )) {
                                if (it is TdApi.Message) {
                                    scope.launch(Dispatchers.Main) {
                                        isEditing.value = false
                                        editText.value = ""
                                        ChatMessagesRepository.publicAddOrReplaceMessage(it)
                                    }
                                } else {
                                    println("Failed to edit message caption: $it")
                                }
                            }
                        }
                        else {
                            println("Failed to edit message text: $result")
                        }
                    }
                },
                modifier = Modifier.animateWidth(interactionSource2),
                interactionSource = interactionSource2,
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Check,
                        modifier = Modifier.size(32.dp),
                        contentDescription = "Done"
                    )
                }
            }
        }
    }
}
