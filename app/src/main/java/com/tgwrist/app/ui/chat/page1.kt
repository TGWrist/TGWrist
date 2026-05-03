package com.tgwrist.app.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.core.view.ViewCompat
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.utils.TgClient
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import kotlin.apply

@Composable
fun Page1(chatId: Long, chatObject: TdApi.Chat?, pagerState: PagerState) {
    //val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val coroutineScope = rememberCoroutineScope()
    val transformationSpec = rememberTransformationSpec()
    val interactionSource1 = remember { MutableInteractionSource() }
    val interactionSource2 = remember { MutableInteractionSource() }

    var text by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        // 获取聊天草稿
        chatObject?.let {
            val draftMessage = it.draftMessage?.inputMessageText
            if (draftMessage is TdApi.InputMessageText) {
                draftMessage.text?.let {
                    text = it.text
                }
            }
        }

        TgClient.subscribe(TdApi.UpdateChatDraftMessage::class.java, lifecycleOwner) { update ->
            if (chatId == update.chatId) {
                val draftMessage = update.draftMessage
                if (draftMessage == null) {
                    text = ""
                } else {
                    draftMessage.inputMessageText?.let {
                        if (it is TdApi.InputMessageText) {
                            it.text?.let {
                                text = it.text
                            }
                        }
                    }
                }
            }
        }

        onDispose {
            // 退出作用域时要执行的代码
            /*val draftMessage = TdApi.DraftMessage().apply {
                this.inputMessageText= TdApi.InputMessageText().apply {
                    this.text = TdApi.FormattedText(text, null)
                }
            }
            TgClient.send(TdApi.SetChatDraftMessage(chatId, null, draftMessage)) { result ->
                if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                    println("Setting draft message successfully, ChatId is $chatId")
                } else {
                    println("Failed to set draft message is $result")
                }
            }*/
        }
    }

    // 文本消息发送界面，包含一个标题和一个输入框，输入框会根据内容自动调整圆角大小
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
                        text = stringResource(R.string.Send_message),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }

            item {
                CustomSearchInput(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    chatId = chatId,
                    text = text,
                    onTextChange = { text = it }
                )
            }

            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.transformedHeight(this, transformationSpec)
                ) {
                    ButtonGroup(Modifier.fillMaxWidth()) {
                        // 按钮1
                        Button(
                            onClick = {
                                text += "\n"
                            },
                            modifier = Modifier.animateWidth(interactionSource1),
                            transformation = SurfaceTransformation(transformationSpec),
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
                        // 按钮2
                        Button(
                            onClick = {
                                // 发送消息
                                TgClient.send(
                                    TdApi.SendMessage(
                                        chatId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        TdApi.InputMessageText().apply {
                                            this.text = TdApi.FormattedText(text, null)
                                        }
                                    )
                                ) {
                                    if (it is TdApi.Message) {
                                        println("Message sent successfully, ChatId is $chatId")
                                        text = ""
                                        TgClient.send(TdApi.SetChatDraftMessage(chatId, null, null)) { result ->
                                            if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                                                println("Setting draft message successfully, ChatId is $chatId")
                                            } else {
                                                println("Failed to set draft message is $result")
                                            }
                                        }
                                        coroutineScope.launch {
                                            pagerState.scrollToPage(0)
                                        }
                                    } else {
                                        println("Failed to send message is $it")
                                    }
                                }
                            },
                            modifier = Modifier.animateWidth(interactionSource2),
                            transformation = SurfaceTransformation(transformationSpec),
                            interactionSource = interactionSource2,
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    modifier = Modifier.size(32.dp),
                                    contentDescription = "Send"
                                )
                            }
                        }
                    }
                }
            }
            item {}
        }
    }
}

@Composable
fun CustomSearchInput(
    modifier: Modifier = Modifier,
    chatId: Long,
    text: String,
    onTextChange: (String) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    // 1. 记录当前的行数，默认为 1 行
    var lineCount by remember { mutableIntStateOf(1) }

    // 2. 根据行数计算目标圆角大小
    //    - 1行: 26.dp (胶囊)
    //    - 2行: 18.dp (稍微方一点)
    //    - 3行+: 12.dp (圆角矩形)
    val targetCornerRadius = when (lineCount) {
        1 -> 26.dp
        2 -> 18.dp
        else -> 12.dp
    }

    // 3. 添加一个动画状态，让圆角变化丝滑过渡
    val animatedCornerRadius by animateDpAsState(
        targetValue = targetCornerRadius,
        animationSpec = spring(),
        label = "CornerRadiusAnimation"
    )

    // 检测是否打开键盘
    ObserveImeVisibility(
        onImeOpen = {
            TgClient.send(TdApi.SendChatAction(
                chatId,
                null,
                null,
                TdApi.ChatActionTyping()
            ))
        },
        onImeClose = {
            TgClient.send(TdApi.SendChatAction(
                chatId,
                null,
                null,
                null
            ))
        },
    )

    // BasicTextField 本身没有任何样式，我们把它包在一个 Box 里给它加背景
    Box(
        modifier = modifier
            .background(
                color = Color(0xFF262626), // 这里的颜色是深灰色，模拟图中那个框的颜色
                shape = RoundedCornerShape(animatedCornerRadius)        // 胶囊形状/完全圆角
            )
            .padding(vertical = 8.dp), // 内部上下留白
        contentAlignment = Alignment.CenterStart //以此保证文字垂直居中，水平靠左
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                onTextChange(it)

                TgClient.send(TdApi.SendChatAction(
                    chatId,
                    null,
                    null,
                    TdApi.ChatActionTyping()
                ))
            },
            onTextLayout = { textLayoutResult ->
                lineCount = textLayoutResult.lineCount
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp), // 内部文字左右留白，不贴边
            singleLine = false,
            // 设置文字样式：白色，字号适中
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Start
            ),
            // 光标颜色设为白色
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    // 这里处理点击完成后的逻辑
                    println("User done for: $text")
                    keyboardController?.hide()

                    // 设置草稿
                    val draftMessage = TdApi.DraftMessage().apply {
                        this.inputMessageText= TdApi.InputMessageText().apply {
                            this.text = TdApi.FormattedText(text, null)
                        }
                    }
                    TgClient.send(TdApi.SetChatDraftMessage(chatId, null, draftMessage)) { result ->
                        if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                            println("Setting draft message successfully, ChatId is $chatId")
                        } else {
                            println("Failed to set draft message is $result")
                        }
                    }
                }
            ),
            // 如果你想加占位符(Hint)，可以用 decorationBox
            decorationBox = { innerTextField ->
                innerTextField()
            }
        )
    }
}

@Composable
fun ObserveImeVisibility(
    onImeOpen: () -> Unit,
    onImeClose: () -> Unit,
) {
    val view = LocalView.current
    var lastVisible by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(view) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())

            if (lastVisible != visible) {
                lastVisible = visible
                if (visible) onImeOpen() else onImeClose()
            }
            insets
        }

        ViewCompat.requestApplyInsets(view)

        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
        }
    }
}
