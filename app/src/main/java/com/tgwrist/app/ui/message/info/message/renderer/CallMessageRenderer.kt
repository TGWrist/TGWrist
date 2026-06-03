package com.tgwrist.app.ui.message.info.message.renderer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PhoneMissed
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import org.drinkless.tdlib.TdApi

/**
 * 把秒数格式化为 mm:ss（超过 1 小时则 h:mm:ss）
 */
private fun formatCallDuration(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, sec)
    } else {
        "%02d:%02d".format(m, sec)
    }
}

@Composable
fun CallMessageRenderer(
    content: TdApi.MessageCall,
    messageRenderContext: MessageRenderContext,
) {
    val chatId = messageRenderContext.chatId
    val chat = messageRenderContext.chat

    // ========== 解析通话信息 ==========
    val isVideo = content.isVideo
    val duration = content.duration          // 秒，0 表示未接通
    val uniqueId = content.uniqueId          // 0 = 其他设备的通话，无法重拨
    val discardReason = content.discardReason

    // 通话状态标题
    val callTitle = when (discardReason) {
        is TdApi.CallDiscardReasonMissed -> stringResource(R.string.Missed_call)
        is TdApi.CallDiscardReasonDeclined -> stringResource(R.string.Declined_call)
        is TdApi.CallDiscardReasonDisconnected -> stringResource(R.string.Disconnected_client)
        is TdApi.CallDiscardReasonHungUp -> stringResource(R.string.Hung_up)
        is TdApi.CallDiscardReasonUpgradeToGroupCall -> stringResource(R.string.call_upgraded_to_group)
        else -> stringResource(R.string.Call) // CallDiscardReasonEmpty 或 null
    }

    // 通话类型副标题
    val callTypeLabel = if (isVideo) {
        stringResource(R.string.call_type_video)
    } else {
        stringResource(R.string.call_type_voice)
    }

    // 时长文字（0 秒 = 未接通，不显示时长行）
    val durationText = if (duration > 0) {
        stringResource(R.string.call_duration_label, formatCallDuration(duration))
    } else null

    // uniqueId 行（非 0 才显示，0 = 其他设备通话）
    val uniqueIdText = if (uniqueId != 0L) {
        stringResource(R.string.call_unique_id_label, uniqueId)
    } else null

    // ========== 是否可以回拨 ==========
    // 只有私聊（ChatTypePrivate）才能发起通话
    val canCall = remember(chat) {
        chat?.type is TdApi.ChatTypePrivate
    }

    // ========== 发送方名字（用于卡片副标题） ==========
    var senderName by remember { mutableStateOf("") }
    LaunchedEffect(messageRenderContext.message.senderId) {
        when (val sender = messageRenderContext.message.senderId) {
            is TdApi.MessageSenderUser -> {
                TgClient.send(TdApi.GetUser(sender.userId)) { res ->
                    if (res is TdApi.User) {
                        senderName = listOfNotNull(
                            res.firstName.takeIf { it.isNotBlank() },
                            res.lastName.takeIf { it.isNotBlank() }
                        ).joinToString(" ")
                    }
                }
            }
            is TdApi.MessageSenderChat -> {
                TgClient.send(TdApi.GetChat(sender.chatId)) { res ->
                    if (res is TdApi.Chat) {
                        senderName = res.title
                    }
                }
            }
        }
    }

    // ========== UI ==========
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

            // ========== 通话情况图标 + 通话状态标题 ==========
            item(key = "call_title") {
                val (icon, tint) = when (discardReason) {
                    is TdApi.CallDiscardReasonMissed,
                    is TdApi.CallDiscardReasonDeclined ->
                        (if (isVideo) Icons.Rounded.Videocam else Icons.AutoMirrored.Rounded.PhoneMissed) to
                                MaterialTheme.colorScheme.error

                    is TdApi.CallDiscardReasonHungUp,
                    is TdApi.CallDiscardReasonDisconnected ->
                        (if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.CallEnd) to
                                MaterialTheme.colorScheme.onSurfaceVariant

                    else ->
                        (if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call) to
                                MaterialTheme.colorScheme.primary
                }
                ListHeader(
                    contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() * 0.2f, bottom = 4.dp, end = contentPadding.calculateEndPadding(
                        LayoutDirection.Ltr), start = contentPadding.calculateStartPadding(LayoutDirection.Rtl)),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = icon,
                            contentDescription = callTitle,
                            modifier = Modifier.size(48.dp),
                            tint = tint
                        )
                        Text(
                            text = callTitle,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // ========== 信息卡片（类型 + 发送方 + 时长 + uniqueId） ==========
            item(key = "call_info_card") {
                TitleCard(
                    onClick = {},
                    title = {
                        Text(
                            text = callTypeLabel,
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    // 发送方
                    if (senderName.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.call_from_label, senderName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    // 时长
                    if (durationText != null) {
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                    } else {
                        // 未接通时显示提示
                        Text(
                            text = stringResource(R.string.call_not_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    // uniqueId（仅当非 0 时显示，方便调试/客服）
                    /*if (uniqueIdText != null) {
                        Text(
                            text = uniqueIdText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }*/
                }
            }

            // ========== 回拨按钮（仅私聊可用） ==========
            if (canCall) {
                item(key = "call_back_button") {
                    FilledTonalButton(
                        onClick = { TgCallManager.createCall(chatId) },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Call,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.call_back_voice
                            )
                        )
                    }
                }
            }

            // ========== 回复按钮 ==========
            item(key = "reply_button") {
                ReplyMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            // 回复按钮
            item {
                ReplyMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            // ========== 删除按钮 ==========
            if (messageRenderContext.chat != null) {
                item(key = "delete_button") {
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

            item(key = "bottom_spacer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
