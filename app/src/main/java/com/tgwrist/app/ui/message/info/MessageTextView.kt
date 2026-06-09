package com.tgwrist.app.ui.message.info

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.utils.copyToClipboard
import com.tgwrist.app.utils.handleUrlNavigation
import org.drinkless.tdlib.TdApi

@Composable
fun MessageTextView(
    modifier: Modifier = Modifier,
    text: String,
    entities: Array<TdApi.TextEntity>?,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    navController: NavHostController
) {
    val context = LocalContext.current

    // 跟踪已揭示的 Spoiler（按 start offset 标识）
    var revealedSpoilers by remember { mutableStateOf(emptySet<Int>()) }

    // 颜色定义
    val linkColor = colorResource(R.color.blue)
    val codeBackground = Color(0x33FFFFFF)     // 半透明白色，适合深色手表背景
    val quoteBackground = Color(0x1AFFFFFF)    // 更淡的半透明白色
    val spoilerColor = MaterialTheme.colorScheme.onSurface // 文字=背景色，隐藏内容

    // 链接样式 (蓝色 + 下划线) — 用于 URL、邮箱、电话等
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline
        )
    )
    // 交互样式 (蓝色，无下划线) — 用于 @提及、#标签、/命令 等
    val interactiveLinkStyles = TextLinkStyles(
        style = SpanStyle(color = linkColor)
    )
    // 透明样式 (无额外视觉变化) — 用于代码块、引用块、剧透等点击复制/揭示
    val transparentLinkStyles = TextLinkStyles(
        style = SpanStyle()
    )

    val annotatedString = buildAnnotatedString {
        // 1. 先放入原始文本
        append(text)

        // 2. 遍历实体应用样式和点击逻辑
        entities?.forEach { entity ->
            val start = entity.offset
            val end = entity.offset + entity.length
            // 越界检查
            if (start < 0 || end > text.length) return@forEach

            // A. 处理视觉样式 (所有实体类型)
            when (entity.type) {
                // ── 基本文字格式 ──
                is TdApi.TextEntityTypeBold ->
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                is TdApi.TextEntityTypeItalic ->
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                is TdApi.TextEntityTypeUnderline ->
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is TdApi.TextEntityTypeStrikethrough ->
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)

                // ── 代码块 (等宽字体 + 半透明背景) ──
                is TdApi.TextEntityTypeCode ->
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground), start, end)
                is TdApi.TextEntityTypePre ->
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground), start, end)
                is TdApi.TextEntityTypePreCode ->
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground), start, end)

                // ── 引用块 (淡色背景) ──
                is TdApi.TextEntityTypeBlockQuote ->
                    addStyle(SpanStyle(background = quoteBackground), start, end)
                is TdApi.TextEntityTypeExpandableBlockQuote ->
                    addStyle(SpanStyle(background = quoteBackground), start, end)

                // ── 剧透 (未揭示时：文字颜色=背景色，隐藏内容；揭示后：正常显示) ──
                is TdApi.TextEntityTypeSpoiler -> {
                    if (start !in revealedSpoilers) {
                        addStyle(SpanStyle(background = spoilerColor, color = spoilerColor), start, end)
                    }
                }

                // ── 银行卡号 (等宽字体) ──
                is TdApi.TextEntityTypeBankCardNumber ->
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)

                // ── 日期时间 (下划线提示可点击) ──
                is TdApi.TextEntityTypeDateTime ->
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)

                // ── 自定义表情 (文本样式不变，渲染由其他逻辑处理) ──
                is TdApi.TextEntityTypeCustomEmoji -> { }

                // ── 以下实体的视觉样式由 addLink 的 linkStyles 统一处理 ──
                // URL, TextUrl, EmailAddress, PhoneNumber, Mention, MentionName,
                // BotCommand, Hashtag, Cashtag, MediaTimestamp
                else -> { }
            }

            // B. 处理可点击实体 (按类型分配不同的链接样式)
            val clickableStyles: TextLinkStyles? = when (entity.type) {
                // 链接类 (蓝色 + 下划线)
                is TdApi.TextEntityTypeUrl,
                is TdApi.TextEntityTypeTextUrl,
                is TdApi.TextEntityTypeEmailAddress,
                is TdApi.TextEntityTypePhoneNumber -> linkStyles

                // 交互类 (蓝色，无下划线)
                is TdApi.TextEntityTypeMention,
                is TdApi.TextEntityTypeMentionName,
                is TdApi.TextEntityTypeBotCommand,
                is TdApi.TextEntityTypeHashtag,
                is TdApi.TextEntityTypeCashtag,
                is TdApi.TextEntityTypeMediaTimestamp,
                is TdApi.TextEntityTypeBankCardNumber,
                is TdApi.TextEntityTypeCustomEmoji,
                is TdApi.TextEntityTypeDateTime -> interactiveLinkStyles

                // 可点击但保持原有样式 (代码块复制、引用块复制、剧透揭示)
                is TdApi.TextEntityTypeCode,
                is TdApi.TextEntityTypePre,
                is TdApi.TextEntityTypePreCode,
                is TdApi.TextEntityTypeBlockQuote,
                is TdApi.TextEntityTypeExpandableBlockQuote,
                is TdApi.TextEntityTypeSpoiler -> transparentLinkStyles

                // 纯样式，不可点击 (Bold, Italic, Underline, Strikethrough)
                else -> null
            }

            if (clickableStyles != null) {
                val clickedText = text.substring(start, end)

                addLink(
                    clickable = LinkAnnotation.Clickable(
                        tag = "entity",
                        styles = clickableStyles,
                        linkInteractionListener = {
                            if (entity.type is TdApi.TextEntityTypeSpoiler) {
                                // 点击切换 Spoiler 揭示/隐藏
                                revealedSpoilers = if (start in revealedSpoilers) {
                                    revealedSpoilers - start
                                } else {
                                    revealedSpoilers + start
                                }
                            } else {
                                onEntityClickLogic(clickedText, entity.type, navController, context)
                            }
                        }
                    ),
                    start = start,
                    end = end
                )
            }
        }
    }

    // 在 Wear OS 中，直接使用 Text 即可处理点击
    SelectionContainer {
        Text(
            text = annotatedString,
            modifier = modifier,
            style = style
        )
    }
}

fun onEntityClickLogic(
    clickedText: String,
    entityType: TdApi.TextEntityType,
    navController: NavHostController,
    context: Context
) {
    println("Clicked Text: $clickedText, Entity Type: ${entityType.javaClass.simpleName}")

    // 主逻辑：处理各种 Entity 类型
    when (entityType) {
        // -----------------------------------------------------------------------
        // 1. 链接与网络资源 (Links & URLs)
        // -----------------------------------------------------------------------
        is TdApi.TextEntityTypeTextUrl -> {
            // 文字超链接：点击文字是 "点击这里"，但 entityType.url 是 "https://google.com"
            handleUrlNavigation(entityType.url, context, navController)
        }
        is TdApi.TextEntityTypeUrl -> {
            // 普通链接：点击文字本身就是 URL
            handleUrlNavigation(clickedText, context, navController)
        }
        is TdApi.TextEntityTypeEmailAddress -> {
            // 邮箱：调用系统邮件应用
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = "mailto:$clickedText".toUri()
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
            }
        }
        is TdApi.TextEntityTypePhoneNumber -> {
            // 电话号码：调用系统拨号盘
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = "tel:$clickedText".toUri()
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "No dialer app found", Toast.LENGTH_SHORT).show()
            }
        }

        // -----------------------------------------------------------------------
        // 2. 用户与群组提及 (Mentions)
        // -----------------------------------------------------------------------
        is TdApi.TextEntityTypeMention -> {
            // @username：搜索该用户并跳转到 Chat 页面
            val username = clickedText.removePrefix("@")
            TgClient.send(TdApi.SearchPublicChat(username)) { chatResult ->
                Handler(Looper.getMainLooper()).post {
                    if (chatResult is TdApi.Chat) {
                        navController.navigate(Destinations.chat(chatResult.id))
                    } else {
                        Toast.makeText(context, "User not found: @$username", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        is TdApi.TextEntityTypeMentionName -> {
            // 文本形式的用户链接 (没有username，只有 userId)
            val userId = entityType.userId
            TgClient.send(TdApi.CreatePrivateChat(userId, false)) { chatResult ->
                Handler(Looper.getMainLooper()).post {
                    if (chatResult is TdApi.Chat) {
                        navController.navigate(Destinations.chat(chatResult.id))
                    } else {
                        Toast.makeText(context, "Cannot open chat for user", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // -----------------------------------------------------------------------
        // 3. 标签与指令 (Tags & Commands)
        // -----------------------------------------------------------------------
        is TdApi.TextEntityTypeHashtag -> {
            // #Hashtag
            // TODO: 跳转到全局搜索页面，并填入搜索词
            // navController.navigate(Destinations.GlobalSearch(query = clickedText))
            Toast.makeText(context, "Hashtag: $clickedText", Toast.LENGTH_SHORT).show()
        }
        is TdApi.TextEntityTypeCashtag -> {
            // $USD, $BTC
            // TODO: 类似 Hashtag，跳转搜索或股票查看页面
            Toast.makeText(context, "Cashtag: $clickedText", Toast.LENGTH_SHORT).show()
        }
        is TdApi.TextEntityTypeBotCommand -> {
            // /start, /help
            // TODO: 如果在聊天页面，应该直接发送该指令或填充到输入框
            // inputField.setText(clickedText)
            Toast.makeText(context, "Bot Command: $clickedText", Toast.LENGTH_SHORT).show()
        }

        // -----------------------------------------------------------------------
        // 4. 富媒体与特殊内容 (Rich Content)
        // -----------------------------------------------------------------------
        is TdApi.TextEntityTypeCustomEmoji -> {
            val customEmojiId = entityType.customEmojiId
            // TODO: 弹出一个 Dialog 显示该 Emoji 的大图和所属贴纸包信息
            // showCustomEmojiDetails(customEmojiId)
            Toast.makeText(context, "Custom Emoji ID: $customEmojiId", Toast.LENGTH_SHORT).show()
        }
        is TdApi.TextEntityTypeMediaTimestamp -> {
            val timestamp = entityType.mediaTimestamp
            // TODO: 如果当前正在播放视频/音频，跳转到指定时间 (seekTo)
            Toast.makeText(context, "Seek to: $timestamp seconds", Toast.LENGTH_SHORT).show()
        }
        is TdApi.TextEntityTypeDateTime -> {
            val unixTime = entityType.unixTime
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val dateStr = dateFormat.format(java.util.Date(unixTime.toLong() * 1000))
            copyToClipboard(dateStr)
            Toast.makeText(context, dateStr, Toast.LENGTH_SHORT).show()
        }
        is TdApi.TextEntityTypeBankCardNumber -> {
            // 银行卡号：通常操作是复制
            copyToClipboard(clickedText)
            Toast.makeText(context, "Card number copied", Toast.LENGTH_SHORT).show()
        }

        // -----------------------------------------------------------------------
        // 5. 格式化与交互式文本 (Formatting & Interactive)
        // -----------------------------------------------------------------------
        is TdApi.TextEntityTypeSpoiler -> {
            // Spoiler 的揭示/隐藏已在 Composable 层直接处理，此处不会被调用
        }
        is TdApi.TextEntityTypeExpandableBlockQuote -> {
            // 可折叠引用：点击复制内容
            copyToClipboard(clickedText)
            Toast.makeText(context, "Quote copied", Toast.LENGTH_SHORT).show()
        }
        is TdApi.TextEntityTypeBlockQuote -> {
            // 普通引用：通常也是复制
            copyToClipboard(clickedText)
            Toast.makeText(context, "Quote copied", Toast.LENGTH_SHORT).show()
        }
        is TdApi.TextEntityTypePre,
        is TdApi.TextEntityTypePreCode,
        is TdApi.TextEntityTypeCode -> {
            // 代码块：点击通常是复制内容
            copyToClipboard(clickedText)
            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
        }

        // -----------------------------------------------------------------------
        // 6. 纯样式文本 (Pure Styles)
        // -----------------------------------------------------------------------
        is TdApi.TextEntityTypeBold,
        is TdApi.TextEntityTypeItalic,
        is TdApi.TextEntityTypeUnderline,
        is TdApi.TextEntityTypeStrikethrough -> {
            // 粗体、斜体等。
            // 通常点击这些没有任何特殊反应，除非是为了复制该段文字。
            // 此处不做操作，或者你可以选择复制。
        }

        // 兜底
        else -> {
            println("Unknown Entity Type clicked: ${entityType.javaClass.simpleName}")
        }
    }
}
