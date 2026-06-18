package com.tgwrist.app.ui.message.info.message.renderer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.ForwardMessageButton
import com.tgwrist.app.ui.message.info.MessageTextView
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.TranslationButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import org.drinkless.tdlib.TdApi

/** 测验答对的强调色（绿）。 */
private val PollCorrectColor = Color(0xFF4CAF50)

/**
 * 把秒数格式化为剩余时间文案（mm:ss 或 hh:mm:ss）。
 */
private fun pollFormatRemaining(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val secs = s % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

/**
 * 单个投票选项。
 *
 * @param option 选项数据
 * @param showResults 是否展示投票结果（百分比 + 票数）
 * @param selected 当前是否被选中（用于多选未提交时或单选高亮）
 * @param isCorrect 测验模式下该选项是否为正确答案；null 表示未知/非测验
 * @param enabled 是否可点击
 */
@Composable
private fun PollOptionItem(
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation,
    option: TdApi.PollOption,
    showResults: Boolean,
    selected: Boolean,
    isCorrect: Boolean?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val animatedPercent by animateFloatAsState(
        targetValue = if (showResults) option.votePercentage / 100f else 0f,
        label = "pollPercent"
    )

    // 强调色：测验答对=绿、答错跟随主题 error、其余跟随主题
    // 用于图标 tint、进度条与百分比文案，需与容器底色对比清晰
    val accentColor = when {
        showResults && isCorrect == true -> PollCorrectColor
        showResults && isCorrect == false && selected -> MaterialTheme.colorScheme.onErrorContainer
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }

    val defaultColors = ButtonDefaults.filledTonalButtonColors()

    // 容器底色：根据状态着色，让正确/错误/已选一眼可辨
    val containerColor = when {
        showResults && isCorrect == true -> PollCorrectColor.copy(alpha = 0.16f)
        showResults && isCorrect == false && selected -> MaterialTheme.colorScheme.errorContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> defaultColors.containerColor
    }

    // 文字/内容色：跟随容器，避免选中后文字仍为默认深色（看起来发黑）
    val contentColor = when {
        showResults && isCorrect == false && selected -> MaterialTheme.colorScheme.onErrorContainer
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> defaultColors.contentColor
    }

    val buttonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
        // 禁用态（投票/答题完成后 enabled=false）保持与正常态一致，避免内容变黑
        disabledContainerColor = containerColor,
        disabledContentColor = contentColor,
    )

    FilledTonalButton(
        transformation = transformation,
        onClick = onClick,
        enabled = enabled,
        colors = buttonColors,
        modifier = modifier.fillMaxWidth(),
        icon = {
            // 左侧选中指示
            when {
                showResults && isCorrect == true -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                showResults && isCorrect == false && selected -> Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                selected -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                else -> if (enabled) {
                    Icon(
                        imageVector = Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        secondaryLabel = if (showResults) {
            {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { animatedPercent },
                        colors = ProgressIndicatorDefaults.colors(
                            indicatorColor = accentColor,
                            trackColor = accentColor.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pluralVotes(option.voterCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else null,
        label = {
            Text(
                text = option.text.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (showResults) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${option.votePercentage}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
        }
    )
}

@Composable
private fun pluralVotes(count: Int): String =
    if (count == 1) stringResource(R.string.poll_vote_count_one)
    else stringResource(R.string.poll_vote_count_other, count)

@Composable
fun PollMessageRenderer(
    content: TdApi.MessagePoll,
    messageRenderContext: MessageRenderContext,
) {
    val navController = messageRenderContext.navController
    val lifecycleOwner = LocalLifecycleOwner.current
    val chatId = messageRenderContext.chatId
    val messageId = messageRenderContext.messageId

    // 实时投票状态：初始来自 content，后续通过 UpdateMessageContent 刷新
    var poll by remember(content.poll.id) { mutableStateOf(content.poll) }

    // 多选模式下，提交前用户暂时勾选的 0-based 选项下标
    var pendingSelection by remember(poll.id) { mutableStateOf(setOf<Int>()) }

    // 是否正在发送投票请求
    var isVoting by remember(poll.id) { mutableStateOf(false) }

    var translatedQuestion by remember { mutableStateOf<TdApi.FormattedText?>(null) }

    // ========== 订阅消息内容更新（投票结果变化） ==========
    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateMessageContent::class.java, lifecycleOwner, dispatchOnMain = true) { update ->
            if (update.chatId == chatId && update.messageId == messageId) {
                val newContent = update.newContent
                if (newContent is TdApi.MessagePoll) {
                    poll = newContent.poll
                }
            }
        }
    }

    val quizType = poll.type as? TdApi.PollTypeQuiz
    val isQuiz = quizType != null
    val isClosed = poll.isClosed
    val allowsMultiple = poll.allowsMultipleAnswers
    val hasVoted = poll.options.any { it.isChosen }
    val showResults = hasVoted || isClosed
    val correctOptionIds = quizType?.correctOptionIds?.toSet() ?: emptySet()

    // 显示顺序：optionOrder 非空时按其排列，否则原始顺序
    val orderedIndices = remember(poll) {
        if (poll.optionOrder.isNotEmpty()) {
            poll.optionOrder.filter { it in poll.options.indices }
        } else {
            poll.options.indices.toList()
        }
    }

    fun sendAnswer(optionIds: IntArray) {
        isVoting = true
        TgClient.send(TdApi.SetPollAnswer(chatId, messageId, optionIds)) {
            isVoting = false
        }
    }

    fun onOptionClick(index: Int) {
        if (isClosed || isVoting) return
        if (allowsMultiple && !hasVoted) {
            // 多选：切换暂存选择，等待提交
            pendingSelection = if (index in pendingSelection) {
                pendingSelection - index
            } else {
                pendingSelection + index
            }
        } else if (!hasVoted) {
            // 单选/测验：立即投票
            sendAnswer(intArrayOf(index))
        }
    }

    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()

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
            // ========== 头部：类型徽标 + 问题 ==========
            item(key = "poll_header") {
                ListHeader(
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding() * 0.2f,
                        bottom = 4.dp,
                        end = contentPadding.calculateEndPadding(LayoutDirection.Ltr) * 0.2f,
                        start = contentPadding.calculateStartPadding(LayoutDirection.Rtl) * 0.2f
                    ),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val badge = buildString {
                            append(
                                if (isQuiz) stringResource(R.string.poll_type_quiz)
                                else stringResource(R.string.poll_type_regular)
                            )
                        }
                        // 类型 + 匿名/公开 副标题
                        Text(
                            text = "$badge · " + (
                                if (poll.isAnonymous) stringResource(R.string.poll_anonymous)
                                else stringResource(R.string.poll_public)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        MessageTextView(
                            text = poll.question.text,
                            entities = poll.question.entities,
                            style = MaterialTheme.typography.titleMedium,
                            navController = navController,
                        )
                    }
                }
            }

            // ========== 状态行：已投票 / 已关闭提示 ==========
            item(key = "poll_status") {
                val statusText = when {
                    isClosed -> stringResource(R.string.poll_final_results)
                    allowsMultiple && !hasVoted -> stringResource(R.string.poll_multiple_choice)
                    !hasVoted -> stringResource(R.string.poll_tap_to_vote)
                    else -> stringResource(R.string.poll_voted_hint)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }

            // ========== 选项列表 ==========
            items(items = orderedIndices, key = { "poll_option_$it" }) { optionIndex ->
                val option = poll.options[optionIndex]
                val selected = when {
                    showResults -> option.isChosen
                    allowsMultiple -> optionIndex in pendingSelection
                    else -> false
                }
                val isCorrect = if (isQuiz && showResults) optionIndex in correctOptionIds else null
                PollOptionItem(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    option = option,
                    showResults = showResults,
                    selected = selected,
                    isCorrect = isCorrect,
                    enabled = !isClosed && !hasVoted && !isVoting,
                    onClick = { onOptionClick(optionIndex) }
                )
            }

            // ========== 多选提交按钮 ==========
            if (allowsMultiple && !hasVoted && !isClosed) {
                item(key = "poll_vote_button") {
                    FilledTonalButton(
                        onClick = {
                            if (pendingSelection.isNotEmpty()) {
                                sendAnswer(pendingSelection.sorted().toIntArray())
                            }
                        },
                        enabled = pendingSelection.isNotEmpty() && !isVoting,
                        label = {
                            Text(
                                text = stringResource(R.string.poll_submit_vote),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.HowToVote,
                                contentDescription = null
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // ========== 撤回投票按钮（允许重投且已投票且未关闭） ==========
            if (hasVoted && poll.allowsRevoting && !isClosed && !isQuiz) {
                item(key = "poll_retract_button") {
                    FilledTonalButton(
                        onClick = { sendAnswer(intArrayOf()) },
                        enabled = !isVoting,
                        label = {
                            Text(
                                text = stringResource(R.string.poll_retract_vote),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Replay,
                                contentDescription = null
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // ========== 测验解释（答错或已答时展示） ==========
            val explanation = quizType?.explanation
            if (isQuiz && showResults && explanation != null && explanation.text.isNotBlank()) {
                item(key = "poll_explanation") {
                    Card(
                        onClick = { },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Lightbulb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            MessageTextView(
                                text = explanation.text,
                                entities = explanation.entities,
                                style = MaterialTheme.typography.bodySmall,
                                navController = navController,
                            )
                        }
                    }
                }
            }

            // ========== 总票数 ==========
            item(key = "poll_total_voters") {
                Text(
                    text = if (poll.totalVoterCount == 1)
                        stringResource(R.string.poll_total_voters_one)
                    else
                        stringResource(R.string.poll_total_voters_other, poll.totalVoterCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }

            // ========== 翻译问题 ==========
            item(key = "poll_translate") {
                TranslationButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    text = poll.question,
                    onDone = { translatedQuestion = it }
                )
            }

            translatedQuestion?.let { tq ->
                item(key = "poll_translation_results") {
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Translation_results),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
                item(key = "poll_translated_question") {
                    MessageTextView(
                        text = tq.text,
                        entities = tq.entities,
                        navController = navController,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 10.dp)
                    )
                }
            }

            // ========== 停止投票按钮（可编辑且未关闭） ==========
            if (messageRenderContext.properties?.canBeEdited == true && !isClosed) {
                item(key = "poll_stop_button") {
                    val stopTitle = stringResource(R.string.poll_stop_confirm_title)
                    val stopBody = stringResource(R.string.poll_stop_confirm_body)
                    FilledTonalButton(
                        onClick = {
                            messageRenderContext.useDialog(
                                com.tgwrist.app.data.AlertDialogItem(
                                    title = { Text(stopTitle) },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = null
                                        )
                                    },
                                    confirmButton = {
                                        TgClient.send(TdApi.StopPoll(chatId, messageId, null))
                                    },
                                    content = {
                                        item {
                                            Text(
                                                text = stopBody,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            )
                                        }
                                    }
                                )
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.poll_stop),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // 回复按钮
            item(key = "poll_reply") {
                ReplyMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            // 转发按钮
            item(key = "poll_forward") {
                ForwardMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            // 删除按钮
            if (messageRenderContext.chat != null) {
                item(key = "poll_delete") {
                    DeleteMessageButton(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        surfaceTransformation = SurfaceTransformation(transformationSpec),
                        chat = messageRenderContext.chat,
                        messageId = messageRenderContext.messageId,
                        properties = messageRenderContext.properties,
                        useDialog = messageRenderContext.useDialog
                    )
                }
            }

            item(key = "poll_bottom_spacer") { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
