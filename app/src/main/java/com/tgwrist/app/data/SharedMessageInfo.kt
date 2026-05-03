package com.tgwrist.app.data

data class SharedMessageInfoKey(
    val chatId: Long,
    val key: Long
)
data class SharedMessageInfoData(
    val msgIdList: List<Long>
)
