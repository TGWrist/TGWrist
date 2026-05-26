package com.tgwrist.app.ui.message.info.message.renderer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.tgwrist.app.R
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import org.drinkless.tdlib.TdApi

@Composable
fun UnknownMessageRenderer(content: TdApi.MessageContent? = null, messageRenderContext: MessageRenderContext? = null) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    // 只有当最大滚动值 > 0 时，才认为需要显示滚动条
    val isScrollable = scrollState.maxValue > 0

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

    ScreenScaffold(
        scrollState = scrollState,
        scrollIndicator = {
            // 只有在可以滚动时才显示指示器
            if (isScrollable) {
                ScrollIndicator(state = scrollState)
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .requestFocusOnHierarchyActive()
                .rotaryScrollable(
                    RotaryScrollableDefaults.behavior(scrollableState = scrollState),
                    focusRequester
                )
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 20.dp)
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SelectionContainer {
                Text(
                    text = stringResource(R.string.Error_no_supported_message) +
                            if (content?.javaClass?.simpleName == null) "" else "\n${content.javaClass.simpleName}",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            if (messageRenderContext != null) {
                // 回复按钮
                ReplyMessageButton(
                    modifier = Modifier.padding(top = 8.dp),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
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
}
