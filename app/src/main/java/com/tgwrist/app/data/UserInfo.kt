package com.tgwrist.app.data

data class UserInfo(
    val userId: Long,            // Telegram user_id
    val userName: String,        // 用户名称
    val isActive: Boolean = false, // 是否是当前活跃用户
    val lastLogin: Long = System.currentTimeMillis(),
    val pushReceiverId: Long = -1
)
