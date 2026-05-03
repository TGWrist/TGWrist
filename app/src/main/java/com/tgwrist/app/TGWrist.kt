package com.tgwrist.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.lifecycle.LifecycleObserver
import com.tgwrist.app.notification.Push
import com.tgwrist.app.utils.ChatMessagesRepository
import com.tgwrist.app.utils.ChatsRepository
import com.tgwrist.app.utils.Config
import com.tgwrist.app.utils.UserManager

class TGWrist : Application(), LifecycleObserver {
    // 伴生对象，相当于 Java 的 static
    companion object {
        // 使用 late init 延迟初始化，确保不为空
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
            private set // 外部只读，内部可改
    }
    override fun onCreate() {
        super.onCreate()
        // 在应用启动时，将 applicationContext 赋值给静态变量
        context = applicationContext
        // 加载 tdlib 库
        System.loadLibrary("tdjni")
        UserManager.init(this) // 初始化 UserManager
        ChatsRepository.init() // 初始化 ChatsRepository
        ChatMessagesRepository.init() // 初始化聊天消息仓库
        Config.init(this) // 初始化 Config
        Push.init() // 初始化消息通知全局单例
    }
}
