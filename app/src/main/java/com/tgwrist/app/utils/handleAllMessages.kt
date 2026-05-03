package com.tgwrist.app.utils

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import org.drinkless.tdlib.TdApi

// 处理和简化消息
// includeCaption: 是否包含媒体消息的文字描述（用于相册分离媒体标签和描述文字）
fun handleAllMessages(
    context: Context = TGWrist.context,
    message: TdApi.Message? = null,
    messageContext: TdApi.MessageContent? = null,
    maxText: Int = 128,
    includeCaption: Boolean = true
): AnnotatedString {
    val content: TdApi.MessageContent = messageContext ?: message?.content
    ?: return buildAnnotatedString { append(context.getString(R.string.Unknown_message)) }

    return when (content) {
        is TdApi.MessageText -> buildAnnotatedString {
            if (includeCaption) {
                val text = content.text.text.replace('\n', ' ')
                append(if (text.length > maxText) text.take(maxText) + "..." else text)
            }
        }
        is TdApi.MessagePhoto -> buildAnnotatedString {
            withStyle(style = SpanStyle(color = Color(context.getColor(R.color.blue)))) {
                append(context.getString(R.string.Photo))
            }
            if (includeCaption) {
                val caption = content.caption.text.replace('\n', ' ').trim()
                if (caption.isNotEmpty()) {
                    append(" ")
                    append(if (caption.length > maxText) caption.take(maxText) + "..." else caption)
                }
            }
        }
        is TdApi.MessageVideo -> buildAnnotatedString {
            withStyle(style = SpanStyle(color = Color(context.getColor(R.color.blue)))) {
                append(context.getString(R.string.Video))
            }
            if (includeCaption) {
                val caption = content.caption.text.replace('\n', ' ').trim()
                if (caption.isNotEmpty()) {
                    append(" ")
                    append(if (caption.length > maxText) caption.take(maxText) + "..." else caption)
                }
            }
        }
        is TdApi.MessageVoiceNote -> buildAnnotatedString {
            withStyle(style = SpanStyle(color = Color(context.getColor(R.color.blue)))) {
                append(context.getString(R.string.Voice))
            }
            if (includeCaption) {
                val caption = content.caption.text.replace('\n', ' ').trim()
                if (caption.isNotEmpty()) {
                    append(" ")
                    append(if (caption.length > maxText) caption.take(maxText) + "..." else caption)
                }
            }
        }
        is TdApi.MessageAnimation -> buildAnnotatedString {
            withStyle(style = SpanStyle(color = Color(context.getColor(R.color.blue)))) {
                append(context.getString(R.string.Animation))
            }
            if (includeCaption) {
                val caption = content.caption.text.replace('\n', ' ').trim()
                if (caption.isNotEmpty()) {
                    append(" ")
                    append(if (caption.length > maxText) caption.take(maxText) + "..." else caption)
                }
            }
        }
        is TdApi.MessageDocument -> buildAnnotatedString {
            withStyle(style = SpanStyle(color = Color(context.getColor(R.color.blue)))) {
                append(context.getString(R.string.File))
            }
            if (includeCaption) {
                val fileName = content.document.fileName.replace('\n', ' ').trim()
                val caption = content.caption.text.replace('\n', ' ').trim()
                val combined = listOf(fileName, caption).filter { it.isNotEmpty() }.joinToString(" ")
                if (combined.isNotEmpty()) {
                    append(" ")
                    append(if (combined.length > maxText) combined.take(maxText) + "..." else combined)
                }
            }
        }
        is TdApi.MessageAnimatedEmoji -> buildAnnotatedString {
            if (content.emoji.isEmpty()) append(context.getString(R.string.Unknown_message))
            else append(content.emoji)
        }
        is TdApi.MessageSticker -> buildAnnotatedString {
            if (content.sticker.emoji.isEmpty()) append(context.getString(R.string.Unknown_message))
            else append(content.sticker.emoji)
        }
        is TdApi.MessageChatAddMembers -> buildAnnotatedString {
            withStyle(style = SpanStyle(color = Color(context.getColor(R.color.blue)))) {
                append(context.getString(R.string.Joined_the_group))
            }
        }
        is TdApi.MessageCall -> buildAnnotatedString {
            val text = when (content.discardReason) {
                is TdApi.CallDiscardReasonMissed -> context.getString(R.string.Missed_call)
                is TdApi.CallDiscardReasonDeclined -> context.getString(R.string.Declined_call)
                is TdApi.CallDiscardReasonDisconnected -> context.getString(R.string.Disconnected_client)
                is TdApi.CallDiscardReasonEmpty -> context.getString(R.string.Failed_call)
                is TdApi.CallDiscardReasonHungUp -> context.getString(R.string.Hung_up)
                else -> context.getString(R.string.Call)
            }

            withStyle(style = SpanStyle(color = Color(context.getColor(R.color.blue)))) {
                append(text)
            }
        }
        else -> buildAnnotatedString { append(context.getString(R.string.Unknown_message)) }
    }
}
