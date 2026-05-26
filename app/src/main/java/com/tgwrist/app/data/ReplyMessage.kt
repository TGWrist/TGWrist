package com.tgwrist.app.data

import org.drinkless.tdlib.TdApi

data class ReplyMessage (
    val chatId: Long,
    val messageId: Long,
    val quote: TdApi.InputTextQuote? = null,
    val canBeReplied: Boolean = true,
    val canBeRepliedInAnotherChat: Boolean = false,
)
