package com.tgwrist.app.utils

import android.content.Context
import org.drinkless.tdlib.TdApi

/**
 * 在手机上打开指定类型的聊天界面
 *
 * @param chatObject 聊天对象，TdApi.Chat
 * @param messageId 可选参数，指定要打开的消息ID (Type: Long)。如果提供，则会在打开聊天时定位到该消息
 */
fun Context.openChatOnPhone(chatObject: TdApi.Chat, messageId: Long? = null) {
    val type = chatObject.type
    val provideMessageId = if (messageId != null) "&message_id=$messageId" else ""
    when (type) {
        is TdApi.ChatTypeBasicGroup -> {
            val basicGroupId = type.basicGroupId
            openDeepLinkOnPhone("tg://openmessage?chat_id=$basicGroupId$provideMessageId")
        }
        is TdApi.ChatTypePrivate -> {
            val userId = type.userId
            openDeepLinkOnPhone("tg://openmessage?user_id=$userId$provideMessageId")
        }
        is TdApi.ChatTypeSecret -> {
            val userId = type.userId
            openDeepLinkOnPhone("tg://openmessage?user_id=$userId$provideMessageId")
        }
        is TdApi.ChatTypeSupergroup -> {
            val supergroupId = type.supergroupId
            openDeepLinkOnPhone("tg://openmessage?chat_id=$supergroupId$provideMessageId")
        }
        else -> {
            openDeepLinkOnPhone("tg://openmessage?user_id=${chatObject.id}$provideMessageId")
        }
    }
}
