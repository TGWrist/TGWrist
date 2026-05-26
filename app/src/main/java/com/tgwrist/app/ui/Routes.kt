package com.tgwrist.app.ui

import android.net.Uri

/*sealed interface Screen {
    @Serializable object Home : Screen
    @Serializable object Settings : Screen
    @Serializable object Login : Screen
    @Serializable data class Chat(val chatId: String) : Screen
}*/

object Destinations {
    const val HOME = "home"
    const val CHAT = "chat/{chatId}"
    const val SETTINGS = "settings/{index}"
    const val ABOUT = "about"
    const val LOGIN = "login"
    const val CALL = "call"
    const val IMG_VIEW = "IMGView/{path}"
    const val VIDEO_VIEW = "VideoView/{path}"
    const val TEXT_VIEW = "TextView?text={text}&textId={textId}"
    const val MESSAGE_INFO = "messageInfo/{chatId}/{key}"
    const val MEDIA_PICKER = "mediaPicker"
    const val TEST = "test"
    // 方便跳转的辅助函数
    fun chat(id: Long) = "chat/$id"
    fun messageInfo(chatId: Long, key: Long): String {
        return "messageInfo/$chatId/$key"
    }
    fun imgView(path: String?): String {
        val encoded = path?.let { Uri.encode(it) } ?: ""
        return "IMGView/$encoded"
    }
    fun videoView(path: String?): String {
        val encoded = path?.let { Uri.encode(it) } ?: ""
        return "VideoView/$encoded"
    }
    fun textView(text: String): String {
        val encoded = Uri.encode(text)
        return "TextView?text=$encoded"
    }
    fun textView(textId: Long): String {
        return "TextView?textId=$textId"
    }
    fun settings(index: Int) = "settings/$index"
}
