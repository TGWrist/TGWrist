package com.tgwrist.app.utils

import android.content.Context
import kotlin.toString

fun Context.getAppVersion(): String {
    return try {
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        pInfo.versionName
    } catch (_: Exception) {
        "1.0.0"
    }.toString()
}
