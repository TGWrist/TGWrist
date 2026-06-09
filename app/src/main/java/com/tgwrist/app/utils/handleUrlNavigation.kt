package com.tgwrist.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavController
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import com.tgwrist.app.data.AlertDialogItem
import com.tgwrist.app.runtime.GlobalEventBus
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.Destinations
import org.drinkless.tdlib.TdApi


// 定义一个本地函数来处理 URL 跳转逻辑 (复用你原来的代码)
fun handleUrlNavigation(url: String, context: Context, navController: NavController) {
    val linkNotSupported = context.getString(R.string.link_not_supported)
    TgClient.send(TdApi.GetInternalLinkType(url)) { linkTypeResult ->
        Handler(Looper.getMainLooper()).post {
            if (linkTypeResult is TdApi.InternalLinkType) {
                println(linkTypeResult)
                when (linkTypeResult) {
                    // ====================================================
                    // 1. 聊天与消息跳转
                    // ====================================================
                    is TdApi.InternalLinkTypePublicChat -> {
                        val username = linkTypeResult.chatUsername
                        TgClient.send(TdApi.SearchPublicChat(username)) { chatResult ->
                            if (chatResult is TdApi.Chat) {
                                Handler(Looper.getMainLooper()).post {
                                    navController.navigate(Destinations.chat(chatResult.id))
                                }
                            }
                        }
                    }
                    is TdApi.InternalLinkTypeChatInvite -> showJoinChatDialog(linkTypeResult.inviteLink, context, navController)
                    is TdApi.InternalLinkTypeMessage -> handleMessageLink(linkTypeResult.url, context, navController)
                    is TdApi.InternalLinkTypeBotStart -> handleBotStart(linkTypeResult.botUsername, linkTypeResult.startParameter, context, navController)
                    is TdApi.InternalLinkTypeVideoChat -> {
                        // TODO: 跳转到语音/视频聊天界面
                        // navController.navigate(Destinations.videoChat(linkTypeResult.chatUsername))
                    }

                    // ====================================================
                    // 2. 设置页面跳转
                    // ====================================================
                    // is TdApi.InternalLinkTypeSettings -> navController.navigate(Destinations.Settings)
                    // ... 其他设置项保留你的注释 ...

                    // ====================================================
                    // 3. 内容与外观
                    // ====================================================
                    is TdApi.InternalLinkTypeStickerSet -> showStickerSetPreview(linkTypeResult.stickerSetName)
                    is TdApi.InternalLinkTypeTheme -> showThemePreview(linkTypeResult.themeName)
                    is TdApi.InternalLinkTypeBackground -> showBackgroundPreview(linkTypeResult.backgroundName)
                    is TdApi.InternalLinkTypeStory -> {
                        // TODO: 跳转 Story 查看器
                        Toast.makeText(context, linkNotSupported, Toast.LENGTH_SHORT).show()
                    }
                    is TdApi.InternalLinkTypeWebApp,
                    is TdApi.InternalLinkTypeMainWebApp -> showWebApp(linkTypeResult)

                    // ====================================================
                    // 4. 特殊功能与认证
                    // ====================================================
                    is TdApi.InternalLinkTypeAuthenticationCode -> {
                        copyToClipboard(linkTypeResult.code)
                        Toast.makeText(context, "Code copied: ${linkTypeResult.code}", Toast.LENGTH_SHORT).show()
                    }
                    is TdApi.InternalLinkTypeQrCodeAuthentication -> Toast.makeText(context, linkNotSupported, Toast.LENGTH_SHORT).show()
                    is TdApi.InternalLinkTypePassportDataRequest -> Toast.makeText(context, linkNotSupported, Toast.LENGTH_SHORT).show()

                    // ====================================================
                    // 5. 兜底逻辑
                    // ====================================================
                    is TdApi.InternalLinkTypeUnknownDeepLink -> openInBrowser(context, url)
                    else -> {
                        println("Unhandled link type: ${linkTypeResult.javaClass.simpleName}")
                        // 尝试作为普通网页打开
                        openInBrowser(context, url)
                    }
                }
            } else {
                // 如果解析失败，直接尝试浏览器打开
                openInBrowser(context, url)
            }
        }
    }
}

// -------------------------------------------------------------
// 辅助函数 (放在你的 Activity 或 Utils 文件中)
// -------------------------------------------------------------
fun showJoinChatDialog(inviteLink: String, context: Context, navController: NavController) {
    TgClient.send(TdApi.CheckChatInviteLink(inviteLink)) { result ->
        when (result) {
            is TdApi.ChatInviteLinkInfo -> {
                // chatId 不为 0 说明本地已能访问该会话（已加入或公开可查看），直接进入聊天
                // ChatScreen 内部会自检是否已加入并给出对应交互
                if (result.chatId != 0L) {
                    Handler(Looper.getMainLooper()).post {
                        navController.navigate(Destinations.chat(result.chatId))
                    }
                } else {
                    // 尚未加入且无法直接查看内容，弹出群组/频道预览 + 加入对话框
                    showInviteLinkDialog(inviteLink, result, context, navController)
                }
            }
            is TdApi.Error -> Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.link_not_supported), Toast.LENGTH_SHORT).show()
            }
            else -> Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.link_not_supported), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * 弹出「群组/频道预览 + 加入」对话框（通过 GlobalEventBus 投递 AlertDialogItem，由 MainActivity 的全局宿主渲染）
 */
private fun showInviteLinkDialog(
    inviteLink: String,
    info: TdApi.ChatInviteLinkInfo,
    context: Context,
    navController: NavController
) {
    val isChannel = info.type is TdApi.InviteLinkChatTypeChannel
    // 频道用「订阅者」，群组用「成员」
    val countLabel = if (isChannel) {
        context.getString(R.string.Subscribers)
    } else {
        context.getString(R.string.Member)
    }
    // 顶部明确提示：加入频道 / 加入群组
    val joinQuestion = if (isChannel) {
        context.getString(R.string.Join_channel_question)
    } else {
        context.getString(R.string.Join_group_question)
    }
    val title = info.title.ifBlank {
        if (isChannel) context.getString(R.string.Channel) else context.getString(R.string.Group_Chat)
    }
    val memberLine = "${info.memberCount} $countLabel"
    val description = info.description

    GlobalEventBus.send(
        AlertDialogItem(
            title = {
                ListHeader {
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = joinQuestion,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            text = {
                Text(
                    if (description.isBlank()) "$title\n$memberLine"
                    else "$title\n$memberLine\n\n$description"
                )
            },
            confirmButton = {
                joinChatByInviteLink(inviteLink, context, navController)
            }
        )
    )
}

/**
 * 通过邀请链接加入群组/频道。
 * - 加入后立即可访问：直接进入聊天页
 * - 仅创建加入申请（需审核）：提示已尝试加入
 */
private fun joinChatByInviteLink(inviteLink: String, context: Context, navController: NavController) {
    TgClient.send(TdApi.JoinChatByInviteLink(inviteLink)) { result ->
        Handler(Looper.getMainLooper()).post {
            when (result) {
                // 加入后立即可访问，直接进入聊天页
                is TdApi.Chat -> navController.navigate(Destinations.chat(result.id))
                is TdApi.Error -> {
                    // 仅创建了加入申请（需管理员审核）时，message 为 "INVITE_REQUEST_SENT"
                    // 兼容大小写，统一转大写后比较
                    if (result.message.uppercase() == "INVITE_REQUEST_SENT") {
                        Toast.makeText(context, context.getString(R.string.Join_request_sent), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.link_not_supported), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
fun showStickerSetPreview(name: String) { /* TODO: 弹窗显示贴纸包 */ }
fun showThemePreview(name: String) { /* TODO */ }
fun showBackgroundPreview(name: String) { /* TODO */ }
fun showWebApp(linkInfo: TdApi.InternalLinkType) { /* TODO: 启动 WebViewFragment */ }
fun handleBotStart(username: String, param: String, context: Context, navController: NavController) {
    TgClient.send(TdApi.SearchPublicChat(username)) { chatResult ->
        if (chatResult is TdApi.Chat) {
            val chatType = chatResult.type
            if (chatType is TdApi.ChatTypePrivate) {
                val botUserId = chatType.userId
                TgClient.send(TdApi.SendBotStartMessage(botUserId, chatResult.id, param)) { sendResult ->
                    Handler(Looper.getMainLooper()).post {
                        navController.navigate(Destinations.chat(chatResult.id))
                    }
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    navController.navigate(Destinations.chat(chatResult.id))
                }
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Bot not found: @$username", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

fun handleMessageLink(url: String, context: Context, navController: NavController) {
    TgClient.send(TdApi.GetMessageLinkInfo(url)) { result ->
        Handler(Looper.getMainLooper()).post {
            if (result is TdApi.MessageLinkInfo && result.chatId != 0L) {
                navController.navigate(Destinations.chat(result.chatId))
            } else {
                // 无法解析消息链接，尝试在浏览器中打开
                openInBrowser(context, url)
            }
        }
    }
}

fun copyToClipboard(text: String) {
    // 1. 获取主线程 Handler
    Handler(Looper.getMainLooper()).post {
        try {
            // 2. 【关键点】直接使用全局 Context
            val context = TGWrist.context

            context.setClipboardText(text)

            // Toast 也可以正常使用了
            Toast.makeText(context, context.getString(R.string.Copied_to_clipboard), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
