package com.tgwrist.app.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import com.tgwrist.app.utils.Config.accentColorList
import com.tgwrist.app.utils.TgClient
import com.tgwrist.app.utils.generateChatTitleIconBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

@Composable
fun ThumbnailChatPhoto(
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    thumbnail: TdApi.File?,
    title: String = "",
    accentColorId: Int,
    contentDescription: String? = null,
    onClick: (String) -> Unit = {}
) {
    val context = LocalContext.current

    // 假设 Config.accentColorList 是你的数据源
    // 注意：不要用 val x by x，会报错。这里假设是从 Config 获取
    val accentColorList by accentColorList.collectAsState()

    // 1. 只有确信文件存在磁盘上时，才更新这个 file 对象
    var imageFile by remember { mutableStateOf<java.io.File?>(null) }

    // 2. 核心下载与检查逻辑
    LaunchedEffect(thumbnail?.id) {
        // 重置，防止复用错误的图片
        imageFile = null

        if (thumbnail == null) return@LaunchedEffect

        // 辅助函数：检查文件是否有效
        fun isValidFile(path: String): Boolean {
            val file = java.io.File(path)
            return file.exists() && file.length() > 0
        }

        // 如果本地已经下载完成且文件存在
        if (thumbnail.local.isDownloadingCompleted && isValidFile(thumbnail.local.path)) {
            imageFile = java.io.File(thumbnail.local.path)
        } else {
            // 需要下载
            try {
                // 使用协程包装下载请求
                val downloadedFile = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.DownloadFile(
                        thumbnail.id,
                        32,
                        0,
                        0,
                        true // 同步模式：TDLib 会阻塞直到下载完成（在后台线程中是安全的）
                    )) { res ->
                        if (res is TdApi.File) cont.resume(res)
                        else cont.resume(null)
                    }
                }

                // 下载回调后，再次检查文件真实性
                downloadedFile?.let {
                    if (it.local.isDownloadingCompleted && isValidFile(it.local.path)) {
                        imageFile = java.io.File(it.local.path)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 3. 准备 Fallback (文字头像)
    val fallbackPainter = remember(title, accentColorId) {
        val color = accentColorList[accentColorId] ?: Color(0xFF3E5369)
        val bitmap = generateChatTitleIconBitmap(context, title, color.toArgb())
        BitmapPainter(bitmap.asImageBitmap())
    }

    // 4. 准备真实图片 Painter
    // 使用 File 对象作为 model，Coil 处理 File 比处理 String 更稳健
    val imagePainter = rememberAsyncImagePainter(
        model = imageFile,
        // 这里可以配置淡入淡出等
    )

    // 5. 渲染逻辑
    Image(
        painter = if (imageFile != null) imagePainter else fallbackPainter,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.clickable {
            imageFile?.path?.let {
                onClick.invoke(it)
            }
        }
    )
}
