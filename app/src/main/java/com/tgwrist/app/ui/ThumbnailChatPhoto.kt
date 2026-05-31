package com.tgwrist.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import com.tgwrist.app.runtime.Config.accentColorList
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.utils.generateChatTitleIconBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume

// 1. 定义自定义的 Saver
val NullableFileSaver = Saver<File?, String>(
    save = { file ->
        file?.absolutePath // 如果为 null，保存的就是 null
    },
    restore = { path ->
        File(path) // 只有当保存了具体的路径字符串时，才会触发 restore
    }
)

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
    var imageFile by rememberSaveable(stateSaver = NullableFileSaver) {
        mutableStateOf(null)
    }

    // 2. 核心下载与检查逻辑
    LaunchedEffect(thumbnail?.id) {
        // 1. 如果没有传入 thumbnail，才安全清空
        if (thumbnail == null) {
            imageFile = null
            return@LaunchedEffect
        }

        fun isValidFile(path: String?): Boolean {
            if (path.isNullOrEmpty()) return false
            val file = File(path)
            return file.exists() && file.length() > 0
        }

        // 2. 最高优先级：若当前 imageFile 在磁盘上仍然有效，直接保留。
        //    这能避免从导航栈返回时，因 TdApi.File 是新对象、其 local 状态尚未补齐
        //    （isDownloadingCompleted=false 或 path 为空），导致已有图片被错误清空。
        val currentValid = imageFile != null && isValidFile(imageFile!!.path)
        if (currentValid) {
            // 仅当 thumbnail 明确指向另一个已下载完成且路径不同的文件时才切换
            if (thumbnail.local.isDownloadingCompleted &&
                isValidFile(thumbnail.local.path) &&
                imageFile!!.path != thumbnail.local.path
            ) {
                imageFile = File(thumbnail.local.path)
            }
            return@LaunchedEffect
        }

        // 3. 当前没有有效图：直接用 thumbnail 已存在的本地文件
        if (thumbnail.local.isDownloadingCompleted && isValidFile(thumbnail.local.path)) {
            imageFile = File(thumbnail.local.path)
            return@LaunchedEffect
        }

        // 4. 否则发起下载
        try {
            val downloadedFile = suspendCancellableCoroutine { cont ->
                TgClient.send(TdApi.DownloadFile(
                    thumbnail.id,
                    32,
                    0,
                    0,
                    true // 同步模式
                )) { res ->
                    // 极其重要：必须判断协程是否存活，防止页面快速切换导致的异常
                    if (cont.isActive) {
                        if (res is TdApi.File) cont.resume(res)
                        else cont.resume(null)
                    }
                }
            }

            downloadedFile?.let {
                if (it.local.isDownloadingCompleted && isValidFile(it.local.path)) {
                    imageFile = File(it.local.path)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 3. 准备 Fallback (文字头像)
    val fallbackPainter = remember(title, accentColorId) {
        val color = accentColorList[accentColorId] ?: Color(0xFF3E5369)
        val bitmap = generateChatTitleIconBitmap(context, title, color.toArgb())
        BitmapPainter(bitmap.asImageBitmap())
    }

    // 4. 准备真实图片 Painter
    // 使用 File 对象作为 model，通过 ImageRequest 开启淡入淡出动画
    // 将 Compose 的 Painter 传递给 rememberAsyncImagePainter 自己的参数
    val imagePainter = rememberAsyncImagePainter(
        model = coil.request.ImageRequest.Builder(context)
            .data(imageFile) // 当 imageFile 为 null 时，会触发 fallback
            .crossfade(true) // 平滑淡入淡出
            .build(),
        fallback = fallbackPainter,    // 数据为 null 时的占位图
        error = fallbackPainter,       // 加载失败时的占位图
        placeholder = fallbackPainter  // (推荐加上) 正在下载/加载中时的占位图，防止空白闪烁
    )

// 5. 渲染逻辑 (保持纯粹)
    Image(
        painter = imagePainter,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.clickable {
            imageFile?.path?.let { onClick.invoke(it) }
        }
    )
}
