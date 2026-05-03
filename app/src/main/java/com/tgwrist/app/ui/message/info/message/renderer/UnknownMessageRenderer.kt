package com.tgwrist.app.ui.message.info.message.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.tgwrist.app.R
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import org.drinkless.tdlib.TdApi

@Composable
fun UnknownMessageRenderer(content: TdApi.MessageContent? = null, messageRenderContext: MessageRenderContext? = null) {
    // 上报 firebase
    LaunchedEffect(Unit) {
        content?.javaClass?.simpleName?.let {
            println("UnknownMessageRenderer: Unsupported message type: $it")
            Firebase.analytics.logEvent("unsupported_message") {
                param("message_type", it)
            }
        } ?: run {
            println("UnknownMessageRenderer: Unsupported message with null content")
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.Error_no_supported_message) +
                    if (content?.javaClass?.simpleName == null) "" else "\n${content.javaClass.simpleName}",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        if (messageRenderContext != null) {
            // 删除按钮
            if (messageRenderContext.chat != null) {
                DeleteMessageButton(
                    modifier = Modifier.padding(top = 8.dp),
                    chat = messageRenderContext.chat,
                    messageId = messageRenderContext.messageId,
                    properties = messageRenderContext.properties,
                    useDialog = messageRenderContext.useDialog
                )
            }
        }
    }
}
