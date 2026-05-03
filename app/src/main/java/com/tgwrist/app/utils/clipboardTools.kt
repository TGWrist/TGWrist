package com.tgwrist.app.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 将文本写入剪贴板 (Set)
 * * @param text 需要复制到剪贴板的文本内容
 * @param label 数据的内部标签，对用户不可见（默认为 "copied_text"）
 */
fun Context.setClipboardText(text: String, label: String = "copied_text") {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}

/**
 * 读取剪贴板中的文本 (Get)
 * * @return 剪贴板最新的一条文本，如果没有内容或者不是纯文本类型则返回 null
 */
fun Context.getClipboardText(): String? {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // 检查剪贴板中是否有内容，并且内容条目大于0
    if (clipboard.hasPrimaryClip() && (clipboard.primaryClip?.itemCount ?: 0) > 0) {
        val item = clipboard.primaryClip?.getItemAt(0)
        return item?.text?.toString()
    }
    return null
}
