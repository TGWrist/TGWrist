package com.tgwrist.app.data

data class ForwardMessage (
    val chatId: Long,
    val messageIds: List<Long>, // 修改为 List
    val canBeCopied: Boolean = true,
    val canBeForwarded: Boolean = true
)
