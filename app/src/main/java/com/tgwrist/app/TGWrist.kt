package com.tgwrist.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.lifecycle.LifecycleObserver
import com.tgwrist.app.notification.Push
import com.tgwrist.app.runtime.ChatMessagesRepository
import com.tgwrist.app.runtime.ChatsRepository
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.runtime.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.thunderdog.challegram.voip.VoIP
import java.io.File

class TGWrist : Application(), LifecycleObserver {
    // 伴生对象，相当于 Java 的 static
    companion object {
        // application 变量用于保存 Application 实例，提供全局访问
        private lateinit var application: Application
        fun getApplication(): Application = application
        // 使用 late init 延迟初始化，确保不为空
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
            private set // 外部只读，内部可改

        // 应用级协程作用域：用于跨页面/脱离 Composable 生命周期仍需继续执行的任务
        // （例如媒体选择器回调，回调触发时调用方的 Composable 可能已离开组合，
        //  其 rememberCoroutineScope() 已取消，故此处需要独立于组合的作用域）
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    override fun onCreate() {
        super.onCreate()
        application = this
        // 在应用启动时，将 applicationContext 赋值给静态变量
        context = applicationContext
        // 清理上次会话遗留的媒体选择器临时文件，避免 cacheDir/picker_share 无限膨胀
        Thread {
            clearPickerShareCache()
        }.start()
        UserManager.init(this) // 初始化 UserManager
        ChatsRepository.init() // 初始化 ChatsRepository
        ChatMessagesRepository.init() // 初始化聊天消息仓库
        Config.init(this) // 初始化 Config
        Push.init() // 初始化消息通知全局单例
        TgCallManager.init() // 初始化通话管理器

        try {
            VoIP.initialize(applicationContext)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * 删除 cacheDir/picker_share 下的所有文件。
     *
     * 该目录用于把 MediaStore content:// 流落盘成本地文件以喂给 TDLib，
     * 一旦消息发出便不再需要。由于 TDLib 在 SendMessage 回调返回后仍然会
     * 异步读取文件，安全的做法是把清理推迟到下一次进程启动统一清理。
     */
    private fun clearPickerShareCache() {
        runCatching {
            val dir = File(cacheDir, "picker_share")
            if (!dir.exists()) return@runCatching
            dir.listFiles()?.forEach { file ->
                runCatching { file.deleteRecursively() }
            }
        }
    }

    init {
        // 加载 c++ 库
        System.loadLibrary("tdjni")
        System.loadLibrary("tgcallsjni")
        System.loadLibrary("leveldbjni")
        System.loadLibrary("tgxjni")
        System.loadLibrary("tgcallsjni") // 重复加载一次可能没必要，可以去掉
    }
}
