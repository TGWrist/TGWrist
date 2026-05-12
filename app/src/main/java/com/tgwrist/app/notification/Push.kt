package com.tgwrist.app.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.tgwrist.app.MainActivity
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.utils.generateChatTitleIconBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object Push {

    private const val TAG = "Push"

    // 通知渠道 ID
    private const val CHANNEL_MESSAGES = "tg_messages"
    private const val CHANNEL_MENTIONS = "tg_mentions"
    private const val CHANNEL_CALLS    = "tg_calls"
    private const val CHANNEL_SECRET   = "tg_secret_chats"

    // 所有聊天通知共享的 group key（用于系统折叠多 chat 通知）
    private const val NOTIFICATION_GROUP = "tg_notifications"
    // Summary 通知的 ID（当多个 chat 同时有通知时显示）
    private const val SUMMARY_NOTIFICATION_ID = Int.MAX_VALUE - 1
    private const val MAX_TEXT = 128

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ======================================================================
    // 消息缓存：同一 chatId 的消息合并到一条通知
    // ======================================================================

    /** 单条消息的缓存 */
    private data class CachedMessage(
        val notificationId: Int,   // TDLib notification.id
        val person: Person,        // 发送者（含可选头像 icon）
        val text: String,
        val timestamp: Long,       // 毫秒
        val isOutgoing: Boolean,
        val isSilent: Boolean,
    )

    /** 会话元信息 */
    private data class ChatMeta(
        val chatTitle: String,
        val isGroup: Boolean,
        val chatIcon: Bitmap?,
        val channelId: String,
        val notificationGroupId: Int,
    )

    // chatId → 消息列表
    private val chatMessages = ConcurrentHashMap<Long, MutableList<CachedMessage>>()
    // chatId → 聊天元信息
    private val chatMetas = ConcurrentHashMap<Long, ChatMeta>()
    // tdNotificationId → chatId（用于 removedNotificationIds 反查）
    private val notifIdToChatId = ConcurrentHashMap<Int, Long>()

    // ======================================================================
    // 入口
    // ======================================================================

    fun init() {
        createNotificationChannels()
        subscribeNotificationGroup()
    }

    // ======================================================================
    // 创建通知渠道
    // ======================================================================

    private fun createNotificationChannels() {
        val nm = TGWrist.context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        listOf(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "New messages" },
            NotificationChannel(CHANNEL_MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Mentions and replies" },
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Incoming calls" },
            NotificationChannel(CHANNEL_SECRET, "Secret Chats", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "New secret chats" },
        ).forEach { nm.createNotificationChannel(it) }
    }

    // ======================================================================
    // 订阅 TDLib UpdateNotificationGroup
    // ======================================================================

    @SuppressLint("WearRecents")
    private fun subscribeNotificationGroup() {
        TgClient.subscribe(TdApi.UpdateNotificationGroup::class.java) { update ->
            if (!Config.isOpenNotification) return@subscribe

            scope.launch {
                val chatId = update.chatId
                val context = TGWrist.context
                val nm = NotificationManagerCompat.from(context)
                var needRebuild = false

                // ── 1. 处理移除 ──
                for (id in update.removedNotificationIds) {
                    val cid = notifIdToChatId.remove(id)
                    if (cid != null) {
                        chatMessages[cid]?.removeAll { it.notificationId == id }
                        if (cid == chatId) needRebuild = true
                    }
                }

                // ── 2. 处理新增 ──
                if (update.addedNotifications.isNotEmpty()) {
                    // 确保 ChatMeta 存在（首次为该 chat 创建）
                    if (!chatMetas.containsKey(chatId)) {
                        val chat = awaitChat(chatId)
                        val chatTitle = chat?.title ?: "Chat"
                        val isGroup = chat?.type is TdApi.ChatTypeBasicGroup
                                || chat?.type is TdApi.ChatTypeSupergroup
                        val chatIconBitmap = chat?.let { loadChatIcon(it) }
                        val channelId = channelForGroupType(update.type)
                        chatMetas[chatId] = ChatMeta(chatTitle, isGroup, chatIconBitmap, channelId, update.notificationGroupId)
                    }

                    val meta = chatMetas[chatId]!!
                    val list = chatMessages.getOrPut(chatId) {
                        mutableListOf()
                    }

                    for (notification in update.addedNotifications) {
                        val content = resolveContent(notification.type, chatId)
                        val senderIcon = if (meta.isGroup) {
                            resolveSenderIcon(notification.type)
                        } else null

                        val personBuilder = Person.Builder()
                            .setName(content.senderName)
                            .setKey(extractSenderKey(notification.type))
                        senderIcon?.let { personBuilder.setIcon(it) }

                        list.add(
                            CachedMessage(
                                notificationId = notification.id,
                                person         = personBuilder.build(),
                                text           = content.text,
                                timestamp      = notification.date.toLong() * 1000,
                                isOutgoing     = content.isOutgoing,
                                isSilent       = notification.isSilent,
                            )
                        )
                        notifIdToChatId[notification.id] = chatId
                    }
                    needRebuild = true
                }

                // ── 3. 重建该 chat 的通知 ──
                if (needRebuild) {
                    rebuildChatNotification(context, nm, chatId)
                }
            }
        }
    }

    // ======================================================================
    // 重建某个 chatId 的合并通知
    // ======================================================================

    @SuppressLint("MissingPermission", "WearRecents")
    private fun rebuildChatNotification(
        context: Context,
        nm: NotificationManagerCompat,
        chatId: Long,
    ) {
        val messages = chatMessages[chatId]
        val meta = chatMetas[chatId]
        val notifId = chatIdToNotifId(chatId)

        // 已无消息 → 取消通知 & 清理缓存
        if (messages.isNullOrEmpty()) {
            nm.cancel(notifId)
            chatMessages.remove(chatId)
            chatMetas.remove(chatId)
            // 如果全部 chat 都没消息了，也取消 summary
            if (chatMessages.isEmpty()) {
                nm.cancel(SUMMARY_NOTIFICATION_ID)
            }
            return
        }

        val selfPerson = buildSelfPerson()
        val channelId  = meta?.channelId ?: CHANNEL_MESSAGES
        val chatTitle  = meta?.chatTitle ?: "Chat"
        val isGroup    = meta?.isGroup ?: false
        val chatIcon   = meta?.chatIcon
        val notificationGroupId = meta?.notificationGroupId ?: 0
        // 取当前该 chat 缓存中最大的 TDLib notification id，用于滑动清除时通知 TDLib
        val maxNotifId = messages.maxOfOrNull { it.notificationId } ?: 0

        // ── 构建 MessagingStyle（合并所有消息） ──
        val style = NotificationCompat.MessagingStyle(selfPerson)
        if (isGroup) {
            style.conversationTitle = chatTitle
            style.isGroupConversation = true
        }

        val sorted = messages
            .toList() // 先复制快照，避免后面被并发修改
            .sortedBy { it.timestamp }

        val lastMessage = sorted.lastOrNull()
        if (lastMessage == null) {
            nm.cancel(notifId)
            chatMessages.remove(chatId)
            chatMetas.remove(chatId)

            if (chatMessages.isEmpty()) {
                nm.cancel(SUMMARY_NOTIFICATION_ID)
            } else {
                rebuildSummary(context, nm)
            }
            return
        }

        for (msg in sorted) {
            style.addMessage(
                msg.text,
                msg.timestamp,
                if (msg.isOutgoing) null else msg.person,
            )
        }

        val lastTimestamp = lastMessage.timestamp
        val allSilent = sorted.all { it.isSilent }

        // 构造 DeleteIntent (滑动清除时通知 TDLib 移除通知)
        val deleteIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            action = ACTION_DISMISS_NOTIFICATION
            putExtra(NOTIFICATION_GROUP_ID, notificationGroupId)
            putExtra(NOTIFICATION_ID, maxNotifId)
        }
        val deletePending = PendingIntent.getBroadcast(
            context,
            notifId,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 标记已读 Intent
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_MARK_AS_READ
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(NOTIFICATION_GROUP_ID, notificationGroupId)
            putExtra(NOTIFICATION_ID, maxNotifId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 回复 Intent（需要 FLAG_MUTABLE 因为 RemoteInput 会向 Intent 追加数据）
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(NOTIFICATION_GROUP_ID, notificationGroupId)
            putExtra(NOTIFICATION_ID, maxNotifId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // 创建 RemoteInput for Reply Action
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).run {
            setLabel(context.getString(R.string.Reply))
            build()
        }

        // 创建 Notification Actions
        val markReadAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            context.getString(R.string.Mark_as_read),
            markReadPendingIntent
        ).build()

        val replyAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            context.getString(R.string.Reply),
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        // 添加前往相应Activity
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", chatId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channelId) // 构建消息通知对象
            .setSmallIcon(R.drawable.airplane_logo_white) // 设置通知小图标
            .setStyle(style) // 设置通知内容展示样式（如多条聊天消息展开）
            .setAutoCancel(true) // 点击通知后自动关闭通知
            .setContentIntent(pendingIntent) // 点击通知时跳转到对应聊天界面
            .setGroup(NOTIFICATION_GROUP) // 将同类聊天通知放入同一个分组
            .setWhen(lastTimestamp) // 使用最后一条消息时间作为通知时间
            .setDeleteIntent(deletePending) // 用户手动清除通知时回调，用于同步通知已移除状态
            .setShowWhen(true) // 在通知 UI 上显示时间
            .setPriority(NotificationCompat.PRIORITY_HIGH) // 提高通知优先级，让消息提醒更明显
            .setCategory(NotificationCompat.CATEGORY_MESSAGE) // 告诉系统这是消息类通知
            .addAction(markReadAction) // 添加“已读”按钮
            .addAction(replyAction) // 添加“回复”按钮

        chatIcon?.let { builder.setLargeIcon(it) }
        if (allSilent) builder.setSilent(true)

        try {
            nm.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
        }

        // ── Summary（仅当 ≥2 个 chat 同时有通知时才显示，用于系统折叠） ──
        rebuildSummary(context, nm)
    }

    @SuppressLint("MissingPermission")
    private fun rebuildSummary(context: Context, nm: NotificationManagerCompat) {
        if (chatMessages.size > 1) {
            try {
                val inboxStyle = NotificationCompat.InboxStyle()
                for ((cid, msgs) in chatMessages) {
                    val title = chatMetas[cid]?.chatTitle ?: "Chat"
                    val lastText = msgs.lastOrNull()?.text ?: ""
                    inboxStyle.addLine("$title: $lastText")
                }

                nm.notify(
                    SUMMARY_NOTIFICATION_ID,
                    NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setGroup(NOTIFICATION_GROUP)
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .setStyle(inboxStyle)
                        .build(),
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
            }
        } else {
            // 只有一个 chat，不需要 summary
            nm.cancel(SUMMARY_NOTIFICATION_ID)
        }
    }

    // ======================================================================
    // chatId → 安卓通知 ID（Long → 正 Int，避免与 SUMMARY_NOTIFICATION_ID 冲突）
    // ======================================================================

    private fun chatIdToNotifId(chatId: Long): Int {
        val id = (chatId xor (chatId ushr 32)).toInt() and 0x7FFFFFFE
        return if (id == 0) 1 else id
    }

    // ======================================================================
    // 构建"自己"的 Person
    // ======================================================================

    private fun buildSelfPerson(): Person {
        val selfName = Config.currentUser.value?.let { user ->
            listOfNotNull(user.firstName, user.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        } ?: "Me"
        return Person.Builder().setName(selfName).build()
    }

    // ======================================================================
    // 从 NotificationType 解析发送者名称 + 文本内容 + 是否为自己发出
    // ======================================================================

    private data class NotificationContent(
        val senderName: String,
        val text: String,
        val isOutgoing: Boolean,
    )

    private suspend fun resolveContent(
        type: TdApi.NotificationType,
        chatId: Long,
    ): NotificationContent = when (type) {
        is TdApi.NotificationTypeNewMessage -> {
            val msg = type.message
            NotificationContent(
                senderName = resolveSenderName(msg.senderId),
                text       = messageContentToText(msg.content),
                isOutgoing = msg.isOutgoing,
            )
        }
        is TdApi.NotificationTypeNewPushMessage -> {
            val name = type.senderName.ifEmpty { awaitChat(chatId)?.title ?: "Chat" }
            NotificationContent(
                senderName = name,
                text       = type.content?.let { pushContentToText(it) } ?: "",
                isOutgoing = type.isOutgoing,
            )
        }
        is TdApi.NotificationTypeNewCall -> NotificationContent(
            senderName = TGWrist.context.getString(R.string.Call),
            text       = "",
            isOutgoing = false,
        )
        is TdApi.NotificationTypeNewSecretChat -> NotificationContent(
            senderName = "Secret Chat",
            text       = "",
            isOutgoing = false,
        )
        else -> NotificationContent("TG Wrist", "", false)
    }

    // ======================================================================
    // 发送者 Key（用于 Person.setKey 标识唯一发送者）
    // ======================================================================

    private fun extractSenderKey(type: TdApi.NotificationType): String = when (type) {
        is TdApi.NotificationTypeNewMessage     -> senderToKey(type.message.senderId)
        is TdApi.NotificationTypeNewPushMessage -> senderToKey(type.senderId)
        else -> "unknown"
    }

    private fun senderToKey(sender: TdApi.MessageSender): String = when (sender) {
        is TdApi.MessageSenderUser -> "user_${sender.userId}"
        is TdApi.MessageSenderChat -> "chat_${sender.chatId}"
        else -> "unknown"
    }

    // ======================================================================
    // 异步解析发送者显示名称（参考 MessageView 的实现）
    // ======================================================================

    private suspend fun resolveSenderName(sender: TdApi.MessageSender): String = when (sender) {
        is TdApi.MessageSenderUser -> {
            val user = awaitUser(sender.userId)
            user?.let {
                listOfNotNull(it.firstName, it.lastName)
                    .filter { s -> s.isNotBlank() }
                    .joinToString(" ")
            }?.ifEmpty { null } ?: "User"
        }
        is TdApi.MessageSenderChat -> awaitChat(sender.chatId)?.title ?: "Chat"
        else -> "Unknown"
    }

    // ======================================================================
    // 解析发送者头像 IconCompat（群组消息使用，获取失败返回 null）
    // ======================================================================

    private suspend fun resolveSenderIcon(type: TdApi.NotificationType): IconCompat? {
        val sender = when (type) {
            is TdApi.NotificationTypeNewMessage     -> type.message.senderId
            is TdApi.NotificationTypeNewPushMessage -> type.senderId
            else -> return null
        }
        return try {
            when (sender) {
                is TdApi.MessageSenderUser -> {
                    val user = awaitUser(sender.userId) ?: return null
                    user.profilePhoto?.small?.let { file ->
                        loadFileBitmap(file)?.let { IconCompat.createWithBitmap(it) }
                    }
                }
                is TdApi.MessageSenderChat -> {
                    val chat = awaitChat(sender.chatId) ?: return null
                    chat.photo?.small?.let { file ->
                        loadFileBitmap(file)?.let { IconCompat.createWithBitmap(it) }
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load sender icon", e)
            null
        }
    }

    // ======================================================================
    // 加载聊天头像 Bitmap（参考 ThumbnailChatPhoto 的下载逻辑）
    // ======================================================================

    private suspend fun loadChatIcon(chat: TdApi.Chat): Bitmap {
        // 尝试下载真实头像
        chat.photo?.small?.let { file ->
            loadFileBitmap(file)?.let { return it }
        }
        // Fallback：用 accentColor + title 首字母生成文字头像
        val context = TGWrist.context
        val color = Config.accentColorList.value[chat.accentColorId] ?: Color(0xFF3E5369)
        return generateChatTitleIconBitmap(context, chat.title, color.toArgb())
    }

    private suspend fun loadFileBitmap(file: TdApi.File): Bitmap? {
        fun tryDecode(path: String): Bitmap? {
            if (path.isEmpty()) return null
            val f = java.io.File(path)
            if (!f.exists() || f.length() == 0L) return null
            return BitmapFactory.decodeFile(path)
        }

        // 本地已下载完成
        if (file.local.isDownloadingCompleted) {
            tryDecode(file.local.path)?.let { return it }
        }

        // 同步下载（在 IO 协程中安全调用）
        val downloaded = suspendCancellableCoroutine<TdApi.File?> { cont ->
            TgClient.send(TdApi.DownloadFile(file.id, 32, 0, 0, true)) { res ->
                cont.resume(res as? TdApi.File)
            }
        } ?: return null

        return if (downloaded.local.isDownloadingCompleted) tryDecode(downloaded.local.path)
        else null
    }

    // ======================================================================
    // TDLib 异步请求封装
    // ======================================================================

    private suspend fun awaitChat(chatId: Long): TdApi.Chat? =
        suspendCancellableCoroutine { cont ->
            TgClient.send(TdApi.GetChat(chatId)) { res ->
                cont.resume(res as? TdApi.Chat)
            }
        }

    private suspend fun awaitUser(userId: Long): TdApi.User? =
        suspendCancellableCoroutine { cont ->
            TgClient.send(TdApi.GetUser(userId)) { res ->
                cont.resume(res as? TdApi.User)
            }
        }

    // ======================================================================
    // 通知渠道选择
    // ======================================================================

    private fun channelForGroupType(type: TdApi.NotificationGroupType): String = when (type) {
        is TdApi.NotificationGroupTypeMessages   -> CHANNEL_MESSAGES
        is TdApi.NotificationGroupTypeMentions   -> CHANNEL_MENTIONS
        is TdApi.NotificationGroupTypeCalls      -> CHANNEL_CALLS
        is TdApi.NotificationGroupTypeSecretChat -> CHANNEL_SECRET
        else -> CHANNEL_MESSAGES
    }

    // ======================================================================
    // 文本工具（统一风格）
    // ======================================================================

    /** 将文本转换为单行，并截断到 [max] 字符 */
    private fun limitText(text: String, max: Int = MAX_TEXT): String {
        val singleLine = text.replace('\n', ' ').trim()
        return if (singleLine.length > max) singleLine.take(max) + "…" else singleLine
    }

    /** 带有 caption 的类型统一格式化：`[label] caption` */
    private fun formatWithCaption(label: String, caption: String): String {
        val trimmed = caption.replace('\n', ' ').trim()
        return if (trimmed.isEmpty()) label else limitText("$label $trimmed")
    }

    // ======================================================================
    // MessageContent → 纯文本
    // ======================================================================

    private fun messageContentToText(content: TdApi.MessageContent): String {
        val ctx = TGWrist.context
        return when (content) {
            is TdApi.MessageText ->
                limitText(content.text.text)
            is TdApi.MessagePhoto ->
                formatWithCaption(ctx.getString(R.string.Photo), content.caption.text)
            is TdApi.MessageVideo ->
                formatWithCaption(ctx.getString(R.string.Video), content.caption.text)
            is TdApi.MessageVideoNote ->
                ctx.getString(R.string.Video)
            is TdApi.MessageVoiceNote ->
                formatWithCaption(ctx.getString(R.string.Voice), content.caption.text)
            is TdApi.MessageAnimation ->
                formatWithCaption(ctx.getString(R.string.Animation), content.caption.text)
            is TdApi.MessageDocument ->
                formatWithCaption(
                    ctx.getString(R.string.File),
                    content.document.fileName + " " + content.caption.text,
                )
            is TdApi.MessageAnimatedEmoji ->
                content.emoji.ifEmpty { ctx.getString(R.string.Unknown_message) }
            is TdApi.MessageSticker ->
                content.sticker.emoji.ifEmpty { ctx.getString(R.string.Unknown_message) }
            is TdApi.MessageCall -> when (content.discardReason) {
                is TdApi.CallDiscardReasonMissed        -> ctx.getString(R.string.Missed_call)
                is TdApi.CallDiscardReasonDeclined       -> ctx.getString(R.string.Declined_call)
                is TdApi.CallDiscardReasonDisconnected   -> ctx.getString(R.string.Disconnected_client)
                is TdApi.CallDiscardReasonEmpty           -> ctx.getString(R.string.Failed_call)
                is TdApi.CallDiscardReasonHungUp          -> ctx.getString(R.string.Hung_up)
                else -> ctx.getString(R.string.Call)
            }
            else -> ctx.getString(R.string.Unknown_message)
        }
    }

    // ======================================================================
    // PushMessageContent → 纯文本
    // ======================================================================

    private fun pushContentToText(content: TdApi.PushMessageContent): String {
        val ctx = TGWrist.context
        return when (content) {
            is TdApi.PushMessageContentText ->
                limitText(content.text)
            is TdApi.PushMessageContentPhoto ->
                formatWithCaption(ctx.getString(R.string.Photo), content.caption)
            is TdApi.PushMessageContentVideo ->
                formatWithCaption(ctx.getString(R.string.Video), content.caption)
            is TdApi.PushMessageContentVideoNote ->
                ctx.getString(R.string.Video)
            is TdApi.PushMessageContentVoiceNote ->
                ctx.getString(R.string.Voice)
            is TdApi.PushMessageContentAnimation ->
                formatWithCaption(ctx.getString(R.string.Animation), content.caption)
            is TdApi.PushMessageContentSticker ->
                content.emoji.ifEmpty { ctx.getString(R.string.Unknown_message) }
            is TdApi.PushMessageContentDocument ->
                ctx.getString(R.string.File)
            is TdApi.PushMessageContentMessageForwards ->
                "${content.totalCount} forwarded messages"
            is TdApi.PushMessageContentHidden ->
                ctx.getString(R.string.Unknown_message)
            else -> ctx.getString(R.string.Unknown_message)
        }
    }
}
