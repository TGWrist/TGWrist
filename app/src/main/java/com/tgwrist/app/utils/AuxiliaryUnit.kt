package com.tgwrist.app.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.drinkless.tdlib.TdApi

/**
 * TDLib Message 的结构性复制
 * 用于 Compose 列表稳定性（非常重要）
 */
fun TdApi.Message.copy(
    id: Long = this.id,
    senderId: TdApi.MessageSender = this.senderId,
    chatId: Long = this.chatId,
    sendingState: TdApi.MessageSendingState? = this.sendingState,
    schedulingState: TdApi.MessageSchedulingState? = this.schedulingState,
    isOutgoing: Boolean = this.isOutgoing,
    isPinned: Boolean = this.isPinned,
    isFromOffline: Boolean = this.isFromOffline,
    canBeSaved: Boolean = this.canBeSaved,
    hasTimestampedMedia: Boolean = this.hasTimestampedMedia,
    isChannelPost: Boolean = this.isChannelPost,
    isPaidStarSuggestedPost: Boolean = this.isPaidStarSuggestedPost,
    isPaidTonSuggestedPost: Boolean = this.isPaidTonSuggestedPost,
    containsUnreadMention: Boolean = this.containsUnreadMention,
    date: Int = this.date,
    editDate: Int = this.editDate,
    forwardInfo: TdApi.MessageForwardInfo? = this.forwardInfo,
    importInfo: TdApi.MessageImportInfo? = this.importInfo,
    interactionInfo: TdApi.MessageInteractionInfo? = this.interactionInfo,
    unreadReactions: Array<TdApi.UnreadReaction>? = this.unreadReactions?.copyOf(),
    factCheck: TdApi.FactCheck? = this.factCheck,
    suggestedPostInfo: TdApi.SuggestedPostInfo? = this.suggestedPostInfo,
    replyTo: TdApi.MessageReplyTo? = this.replyTo,
    topicId: TdApi.MessageTopic? = this.topicId,
    selfDestructType: TdApi.MessageSelfDestructType? = this.selfDestructType,
    selfDestructIn: Double = this.selfDestructIn,
    autoDeleteIn: Double = this.autoDeleteIn,
    viaBotUserId: Long = this.viaBotUserId,
    senderBusinessBotUserId: Long = this.senderBusinessBotUserId,
    senderBoostCount: Int = this.senderBoostCount,
    senderTag: String = this.senderTag,
    paidMessageStarCount: Long = this.paidMessageStarCount,
    authorSignature: String = this.authorSignature,
    mediaAlbumId: Long = this.mediaAlbumId,
    effectId: Long = this.effectId,
    restrictionInfo: TdApi.RestrictionInfo? = this.restrictionInfo,
    summaryLanguageCode: String = this.summaryLanguageCode,
    content: TdApi.MessageContent = this.content,
    replyMarkup: TdApi.ReplyMarkup? = this.replyMarkup
): TdApi.Message {

    return TdApi.Message(
        id,
        senderId,
        chatId,
        sendingState,
        schedulingState,
        isOutgoing,
        isPinned,
        isFromOffline,
        canBeSaved,
        hasTimestampedMedia,
        isChannelPost,
        isPaidStarSuggestedPost,
        isPaidTonSuggestedPost,
        containsUnreadMention,
        date,
        editDate,
        forwardInfo,
        importInfo,
        interactionInfo,
        unreadReactions,
        factCheck,
        suggestedPostInfo,
        replyTo,
        topicId,
        selfDestructType,
        selfDestructIn,
        autoDeleteIn,
        viaBotUserId,
        senderBusinessBotUserId,
        senderBoostCount,
        senderTag,
        paidMessageStarCount,
        authorSignature,
        mediaAlbumId,
        effectId,
        restrictionInfo,
        summaryLanguageCode,
        content,
        replyMarkup
    )
}

/**
 * 将文本写入剪贴板 (Set)
 * * @param text 需要复制到剪贴板的文本内容
 * @param label 数据的内部标签，对用户不可见（默认为 "copied_text"）
 */
fun Context.setClipboardText(text: String, label: String = "copied_text") {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}

/**
 * 读取剪贴板中的文本 (Get)
 * * @return 剪贴板最新的一条文本，如果没有内容或者不是纯文本类型则返回 null
 */
fun Context.getClipboardText(): String? {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // 检查剪贴板中是否有内容，并且内容条目大于0
    if (clipboard.hasPrimaryClip() && (clipboard.primaryClip?.itemCount ?: 0) > 0) {
        val item = clipboard.primaryClip?.getItemAt(0)
        return item?.text?.toString()
    }
    return null
}


/**
 * 判断当前设备是否为三星设备
 */
fun isSamsungDevice(): Boolean {
    val manufacturer = Build.MANUFACTURER ?: ""
    val brand = Build.BRAND ?: ""

    return manufacturer.equals("samsung", ignoreCase = true) ||
            brand.equals("samsung", ignoreCase = true)
}
