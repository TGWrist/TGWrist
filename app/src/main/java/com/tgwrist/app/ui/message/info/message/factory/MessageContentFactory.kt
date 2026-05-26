package com.tgwrist.app.ui.message.info.message.factory

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.tgwrist.app.data.AlertDialogItem
import com.tgwrist.app.ui.message.info.message.renderer.AnimatedEmojiMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.AnimationMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.AudioMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.CallMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.DocumentMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.PhotoMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.StickerMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.TextMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.UnknownMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.VideoMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.VideoNoteMessageRenderer
import com.tgwrist.app.ui.message.info.message.renderer.VoiceNoteMessageRenderer
import org.drinkless.tdlib.TdApi
import kotlin.reflect.KClass

data class MessageRenderContext(
    val navController: NavHostController,
    val chatId: Long,
    val messageId: Long,
    val chat: TdApi.Chat? = null,
    val message: TdApi.Message,
    val properties: TdApi.MessageProperties?,
    val useDialog: (AlertDialogItem) -> Unit
    // 以后要加参数，就在这里加，Factory 签名永远不变
)

// 定义一个函数类型，统一所有渲染器的签名
// 输入：(MessageContent, NavController) -> 输出：Unit
typealias ContentRenderer = @Composable (TdApi.MessageContent, MessageRenderContext) -> Unit

object MessageContentFactory {

    // 路由表：在此处注册你的消息类型
    private val renderers = mapOf(
        // 注册文本
        register<TdApi.MessageText> { content, messageRenderContext ->
            TextMessageRenderer(content, messageRenderContext)
        },
        // 注册图片
        register<TdApi.MessagePhoto> { content, messageRenderContext ->
            PhotoMessageRenderer(content, messageRenderContext)
        },
        // 注册视频
        register<TdApi.MessageVideo> { content, messageRenderContext ->
            VideoMessageRenderer(content, messageRenderContext)
        },
        // 注册文件
        register<TdApi.MessageDocument> { content, messageRenderContext ->
            DocumentMessageRenderer(content, messageRenderContext)
        },
        // 注册动画 emoji
        register<TdApi.MessageAnimatedEmoji> { content, messageRenderContext ->
            AnimatedEmojiMessageRenderer(content, messageRenderContext)
        },
        // 注册贴纸
        register<TdApi.MessageSticker> { content, messageRenderContext ->
            StickerMessageRenderer(content, messageRenderContext)
        },
        // 注册语音消息
        register<TdApi.MessageVoiceNote> { content, messageRenderContext ->
            VoiceNoteMessageRenderer(content, messageRenderContext)
        },
        // 注册通话消息
        register<TdApi.MessageCall> { content, messageRenderContext ->
            CallMessageRenderer(content, messageRenderContext)
        },
        // 注册动画 (GIF / MP4)
        register<TdApi.MessageAnimation> { content, messageRenderContext ->
            AnimationMessageRenderer(content, messageRenderContext)
        },
        // 注册圆形视频留言
        register<TdApi.MessageVideoNote> { content, messageRenderContext ->
            VideoNoteMessageRenderer(content, messageRenderContext)
        },
        // 注册音频文件
        register<TdApi.MessageAudio> { content, messageRenderContext ->
            AudioMessageRenderer(content, messageRenderContext)
        }
    )

    // 对外暴露的渲染入口
    @Composable
    fun Render(
        content: TdApi.MessageContent?,
        messageRenderContext: MessageRenderContext
    ) {
        if (content == null) {
            UnknownMessageRenderer()
        } else {
            // 查表
            val renderer = renderers[content::class]

            if (renderer != null) {
                renderer(content, messageRenderContext)
            } else {
                // 未知消息类型
                UnknownMessageRenderer(content, messageRenderContext)
            }
        }
    }

    // --- 内部黑魔法：用于消除泛型强转警告 ---
    private inline fun <reified T : TdApi.MessageContent> register(
        crossinline renderer: @Composable (T, MessageRenderContext) -> Unit
    ): Pair<KClass<*>, ContentRenderer> {
        // 将具体的 renderer (T) 包装成通用的 ContentRenderer (MessageContent)
        // 并进行安全的类型转换
        return T::class to { content, messageRenderContext ->
            renderer(content as T, messageRenderContext)
        }
    }
}
