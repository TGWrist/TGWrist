package com.tgwrist.app.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.tgwrist.app.R

fun openInBrowser(context: Context, url: String?) {
    if (url.isNullOrBlank()) return

    val noBrowserMsg = context.getString(R.string.No_browser_to_handle_this_url)
    val errorMsg = context.getString(R.string.Unable_open_url)

    try {
        val uri = url.toUri()
        val finalUri = if (uri.scheme == null) "https://$url".toUri() else uri

        val intent = Intent(Intent.ACTION_VIEW, finalUri).apply {
            // 确保非 Activity Context 也能启动
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // 检查是否有应用能处理该 Intent
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            // 备选方案：直接尝试启动，通过 catch 捕获 ActivityNotFoundException
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, noBrowserMsg, Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
    }
}
