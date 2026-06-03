package com.tgwrist.app.ui.message.info.message.renderer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.ForwardMessageButton
import com.tgwrist.app.ui.message.info.MessageTextView
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.TranslationButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import org.drinkless.tdlib.TdApi

@Composable
fun TextMessageRenderer(
    content: TdApi.MessageText,
    messageRenderContext: MessageRenderContext,
) {
    val navController = messageRenderContext.navController
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    // 只有当最大滚动值 > 0 时，才认为需要显示滚动条
    val isScrollable = scrollState.maxValue > 0

    var translatedText by remember { mutableStateOf<TdApi.FormattedText?>(null) }

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
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = scrollState), focusRequester)
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 20.dp)
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MessageTextView(
                text = content.text.text,
                entities = content.text.entities,
                modifier = Modifier,
                navController = navController,
            )

            translatedText?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    text = stringResource(R.string.Translation_results),
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                MessageTextView(
                    text = it.text,
                    entities = it.entities,
                    modifier = Modifier,
                    navController = navController,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 翻译按钮
            TranslationButton(
                text = content.text,
                onDone = {
                    translatedText = it
                }
            )

            // 回复按钮
            ReplyMessageButton(
                modifier = Modifier.padding(top = 8.dp),
                properties = messageRenderContext.properties,
                message = messageRenderContext.message
            )

            // 转发按钮
            ForwardMessageButton(
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
