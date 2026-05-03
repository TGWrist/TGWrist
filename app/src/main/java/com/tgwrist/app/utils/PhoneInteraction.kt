package com.tgwrist.app.utils

import android.content.Context
import android.content.Intent
import androidx.wear.remote.interactions.RemoteActivityHelper
import androidx.core.net.toUri

fun Context.openPhoneApp(appPackageName: String) {
    // 1. 创建 Helper 对象
    val remoteActivityHelper = RemoteActivityHelper(this)

    // 2. 定义想要在手机上执行的 Intent
    // 这里的 Intent 是在手机上运行的，所以要符合手机 App 的配置
    val intent = packageManager.getLaunchIntentForPackage(appPackageName)
        ?: Intent(Intent.ACTION_VIEW).apply {
            // 如果找不到包名启动入口，可以尝试用 VIEW 打开 Play Store 页面
            data = "market://details?id=$appPackageName".toUri()
        }

    // 3. 发送命令
    remoteActivityHelper.startRemoteActivity(intent) // 这是一个异步操作
}

fun Context.openDeepLinkOnPhone(url: String) {
    val remoteActivityHelper = RemoteActivityHelper(this)

    val intent = Intent(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        // 你的 Deep Link URL，手机会自动识别由哪个 App 打开 (浏览器或你的 App)
        data = url.toUri()
    }

    remoteActivityHelper.startRemoteActivity(intent)
}
