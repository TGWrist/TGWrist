package com.tgwrist.app.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TgClient
import org.drinkless.tdlib.TdApi

/**
 * 录制 / 预览状态下的输入框替代品。
 *
 * - 录制中：左侧显示峰值条 + 滑动窗口波形，中间显示动态时长；
 * - 预览中：显示完整波形 + 播放按钮 + 总时长。
 */
@Composable
fun VoiceRecordingDisplay(
    modifier: Modifier = Modifier,
    isPaused: Boolean,
    isPreview: Boolean,
    durationMs: Long,
    currentLevel: Float,
    liveWaveform: IntArray,
    finalWaveform: IntArray,
    isPlayingPreview: Boolean,
    previewPositionMs: Long,
    previewTotalMs: Long,
    onTogglePreview: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .background(
                color = Color(0xFF262626),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isPreview) {
                    // 预览阶段：播放按钮
                    FilledIconButton(
                        onClick = onTogglePreview,
                        modifier = Modifier.size(36.dp),
                        shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(10.dp)),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = accent,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlayingPreview) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.Voice_record_play),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    val progress = if (previewTotalMs > 0L) {
                        (previewPositionMs.toFloat() / previewTotalMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        WaveformBars(
                            bars = finalWaveform,
                            playedColor = accent,
                            idleColor = idle,
                            playProgress = progress
                        )
                    }
                } else {
                    // 录制阶段：峰值条 + 实时波形
                    Box(
                        modifier = Modifier
                            .size(width = 8.dp, height = 40.dp)
                            .background(
                                color = if (isPaused) idle else accent.copy(
                                    alpha = (0.4f + currentLevel * 0.6f).coerceIn(0.4f, 1f)
                                ),
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        WaveformBars(
                            bars = liveWaveform,
                            playedColor = if (isPaused) idle else accent,
                            idleColor = idle,
                            playProgress = 1f
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val timeText = if (isPreview) {
                val total = if (previewTotalMs > 0L) previewTotalMs else durationMs
                "${formatVoiceDuration(previewPositionMs)} | ${formatVoiceDuration(total)}"
            } else {
                formatVoiceDuration(durationMs)
            }
            Text(
                text = timeText,
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WaveformBars(
    bars: IntArray,
    playedColor: Color,
    idleColor: Color,
    playProgress: Float,
) {
    if (bars.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val barCount = bars.size
        val totalWidth = size.width
        val totalHeight = size.height
        val barWidth = (totalWidth / barCount) * 0.6f
        val gap = (totalWidth / barCount) * 0.4f
        val playedBoundary = totalWidth * playProgress.coerceIn(0f, 1f)
        for (i in 0 until barCount) {
            val amp = bars[i] / 31f
            val barHeight = (totalHeight * amp).coerceAtLeast(2f)
            val x = i * (barWidth + gap) + barWidth / 2f
            val y = (totalHeight - barHeight) / 2f
            val color = if (x <= playedBoundary) playedColor else idleColor
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x, y + barHeight),
                strokeWidth = barWidth.coerceAtLeast(1f),
                cap = StrokeCap.Round
            )
        }
    }
}

private fun formatVoiceDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

@Composable
fun CustomTextInput(
    modifier: Modifier = Modifier,
    chatId: Long = -1L,
    text: String,
    onTextChange: (String) -> Unit
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

    var lastTypingTime by remember { mutableLongStateOf(0L) }

    // 检测是否打开键盘
    ObserveImeVisibility(
        onImeOpen = {
            // 记录键盘打开的时间，避免敲击第一个字时重复发送
            lastTypingTime = System.currentTimeMillis()

            if (chatId != -1L) {
                TgClient.send(TdApi.SendChatAction(
                    chatId,
                    null,
                    null,
                    TdApi.ChatActionTyping()
                ))
            }
        },
        onImeClose = {
            if (chatId != -1L) {
                TgClient.send(TdApi.SendChatAction(
                    chatId,
                    null,
                    null,
                    null
                ))
            }
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
                // 1. 更新输入的文本
                onTextChange(it)

                // 2. 获取当前时间
                val currentTime = System.currentTimeMillis()

                // 3. 判断：如果当前时间减去上次发送时间大于 10 秒（10000 毫秒）
                if (currentTime - lastTypingTime > 10000L) {
                    // 更新记录的时间
                    lastTypingTime = currentTime

                    // 发送 Typing 状态
                    if (chatId != -1L) {
                        TgClient.send(TdApi.SendChatAction(
                            chatId,
                            null,
                            null,
                            TdApi.ChatActionTyping()
                        ))
                    }
                }
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
                    if (chatId != -1L) {
                        TgClient.send(TdApi.SetChatDraftMessage(chatId, null, draftMessage)) { result ->
                            if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                                println("Setting draft message successfully, ChatId is $chatId")
                            } else {
                                println("Failed to set draft message is $result")
                            }
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
