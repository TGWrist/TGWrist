package com.tgwrist.app.ui.chat

import androidx.compose.runtime.Composable
import com.tgwrist.app.utils.TgClient
import org.drinkless.tdlib.TdApi

@Composable
fun DoingOnViewChat(messageList: List<TdApi.Message>, chatObject: TdApi.Chat) {
    messageList.forEach { message ->
        when (val content = message.content) {
            is TdApi.MessagePhoto -> {
                val thumbnail = content.photo.sizes.minByOrNull { it.width * it.height }
                thumbnail?.let {
                    if (!thumbnail.photo.local.isDownloadingCompleted && !thumbnail.photo.local.isDownloadingActive) {
                        TgClient.send(TdApi.DownloadFile(
                            thumbnail.photo.id,
                            5,
                            0,
                            0,
                            false
                        ))
                    }
                }
            }
            is TdApi.MessageVideo -> {
                val thumbnail = content.video.thumbnail
                thumbnail?.let {
                    if (!thumbnail.file.local.isDownloadingCompleted && !thumbnail.file.local.isDownloadingActive) {
                        TgClient.send(TdApi.DownloadFile(
                            thumbnail.file.id,
                            5,
                            0,
                            0,
                            false
                        ))
                    }
                }
            }
        }
    }
}
