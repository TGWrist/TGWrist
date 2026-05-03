package com.tgwrist.app.utils

import android.content.Context
import java.io.IOException
import java.util.Properties

fun Context.loadConfig(): Properties {
    val properties = Properties()
    try {
        val inputStream = assets.open("config.properties")
        inputStream.use { properties.load(it) }
    } catch (e: IOException) {
        e.printStackTrace()
        // 处理异常，例如返回默认配置或通知用户
    }
    return properties
}
