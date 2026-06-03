package com.tgwrist.app.ui.message.info.message.renderer

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * 把字节大小格式化为易读文本（与 VideoMessageRenderer 保持一致的实现）
 */
private fun videoNoteFormatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    val idx = digitGroups.coerceIn(0, units.size - 1)
    return "%.1f %s".format(bytes / 1024.0.pow(idx.toDouble()), units[idx])
}

/**
 * 安全计算下载进度
 */
private fun videoNoteCalculateProgress(downloadedSize: Long, size: Long, expectedSize: Long): Float {
    val total = size.takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 } ?: 1L
    return (downloadedSize.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 把秒数格式化为 mm:ss
 */
private fun videoNoteFormatDuration(totalSeconds: Int): String {
    val seconds = max(totalSeconds, 0)
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}

/**
 * 圆形视频留言渲染器（TdApi.MessageVideoNote）。
 *
 * 视频留言是 Telegram 中的圆形短视频（必须等宽等高，裁切为圆形，MPEG4 格式）。
 * - 缩略图与预览全部圆形裁切
 * - 支持 isSecret（蒙层 + 点击揭示）
 * - 支持 isViewed 状态展示
 * - 视频本身没有 caption，故不渲染翻译/文字
 */
@Composable
fun VideoNoteMessageRenderer(
    content: TdApi.MessageVideoNote,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = messageRenderContext.navController
    val coroutineScope = rememberCoroutineScope()

    val videoNote = remember(content.videoNote) { content.videoNote }
    val isSecret = remember(content.isSecret) { content.isSecret }
    val isViewed = remember(content.isViewed) { content.isViewed }
    val durationSec = remember(videoNote) { videoNote?.duration ?: 0 }
    val durationText = remember(durationSec) { videoNoteFormatDuration(durationSec) }

    val videoFileSize = remember(videoNote) { videoNote?.video?.size ?: 0L }
    val fileSizeText = remember(videoFileSize) { videoNoteFormatFileSize(videoFileSize) }
    // VideoNote 没有 mimeType 字段，固定为 mp4
    val mimeType = "video/mp4"

    // 字符串资源
    val strFileSaveFailed = stringResource(R.string.file_save_failed)
    val strSavedToMediaLibrary = stringResource(R.string.saved_to_media_library)
    val strNoAppToOpen = stringResource(R.string.no_app_to_open)

    // ========== 缩略图下载 ==========
    val thumbnail = remember(videoNote?.thumbnail) { videoNote?.thumbnail }
    var thumbnailFileUrl by remember { mutableStateOf("") }
    var thumbnailDownloadProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(thumbnail?.file?.id) {
        thumbnail?.let {
            if (it.file.local.isDownloadingCompleted) {
                thumbnailFileUrl = it.file.local.path
                thumbnailDownloadProgress = 1f
            } else {
                TgClient.send(
                    TdApi.DownloadFile(it.file.id, 30, 0, 0, false)
                ) { result ->
                    if (result is TdApi.File) {
                        thumbnailDownloadProgress = videoNoteCalculateProgress(
                            result.local.downloadedSize,
                            result.size,
                            result.expectedSize
                        )
                        if (result.local.isDownloadingCompleted) {
                            thumbnailFileUrl = result.local.path
                            thumbnailDownloadProgress = 1f
                        }
                    }
                }
            }
        }
    }

    // ========== 视频文件状态 ==========
    val video = remember(videoNote?.video) { videoNote?.video }
    var videoFileUrl by remember { mutableStateOf("") }
    var videoDownloadProgress by remember { mutableFloatStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }

    // 保存到媒体库状态
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    // 涂抹（secret）显示状态：isSecret 为 true 时初始遮挡，需要点击揭示
    var isRevealed by remember(isSecret) { mutableStateOf(!isSecret) }

    // ========== 初始化视频文件状态 ==========
    LaunchedEffect(video?.id) {
        video?.let {
            if (it.local.isDownloadingCompleted) {
                videoFileUrl = it.local.path
                videoDownloadProgress = 1f
                isDownloading = false
            } else if (it.local.isDownloadingActive) {
                isDownloading = true
                videoDownloadProgress = videoNoteCalculateProgress(
                    it.local.downloadedSize,
                    it.size,
                    it.expectedSize
                )
            }

            // 异步通过 GetFile 获取最新文件状态
            TgClient.send(TdApi.GetFile(it.id)) { result ->
                if (result is TdApi.File) {
                    if (result.local.isDownloadingCompleted) {
                        videoFileUrl = result.local.path
                        videoDownloadProgress = 1f
                        isDownloading = false
                    } else if (result.local.isDownloadingActive) {
                        isDownloading = true
                        videoDownloadProgress = videoNoteCalculateProgress(
                            result.local.downloadedSize,
                            result.size,
                            result.expectedSize
                        )
                    } else {
                        isDownloading = false
                    }
                }
            }
        }
    }

    // ========== 订阅文件下载更新 ==========
    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateFile::class.java, lifecycleOwner) { update ->
            when (update.file.id) {
                thumbnail?.file?.id -> {
                    thumbnailDownloadProgress = videoNoteCalculateProgress(
                        update.file.local.downloadedSize,
                        update.file.size,
                        update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingCompleted) {
                        thumbnailFileUrl = update.file.local.path
                        thumbnailDownloadProgress = 1f
                    }
                }

                video?.id -> {
                    videoDownloadProgress = videoNoteCalculateProgress(
                        update.file.local.downloadedSize,
                        update.file.size,
                        update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingActive) {
                        isDownloading = true
                    }
                    if (update.file.local.isDownloadingCompleted) {
                        videoFileUrl = update.file.local.path
                        isDownloading = false
                        videoDownloadProgress = 1f
                    }
                }
            }
        }
    }

    // ========== 开始下载视频 ==========
    fun startDownload() {
        video?.let {
            isDownloading = true
            TgClient.send(
                TdApi.DownloadFile(it.id, 28, 0, 0, false)
            ) { result ->
                if (result is TdApi.File) {
                    videoDownloadProgress = videoNoteCalculateProgress(
                        result.local.downloadedSize,
                        result.size,
                        result.expectedSize
                    )
                    if (result.local.isDownloadingCompleted) {
                        videoFileUrl = result.local.path
                        isDownloading = false
                        videoDownloadProgress = 1f
                    }
                }
            }
        }
    }

    // ========== 取消下载视频 ==========
    fun cancelDownload() {
        video?.let {
            TgClient.send(TdApi.CancelDownloadFile(it.id, false)) { result ->
                if (result is TdApi.Ok) {
                    isDownloading = false
                    videoDownloadProgress = 0f
                }
            }
        }
    }

    // ========== 使用外部播放器打开 ==========
    fun openWithExternalPlayer() {
        if (videoFileUrl.isBlank()) return
        try {
            val file = File(videoFileUrl)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, null)
            context.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(context, strNoAppToOpen, Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 保存到系统媒体库 ==========
    fun saveToMediaLibrary() {
        if (videoFileUrl.isBlank() || isSaving) return
        isSaving = true
        coroutineScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    val sourceFile = File(videoFileUrl)
                    if (!sourceFile.exists()) return@withContext false

                    val fileName = sourceFile.name.ifBlank {
                        "video_note_${System.currentTimeMillis()}.mp4"
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = context.contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                            put(
                                MediaStore.Video.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_MOVIES + "/TGWrist"
                            )
                        }
                        val uri = resolver.insert(
                            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            contentValues
                        )
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { outputStream ->
                                sourceFile.inputStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            true
                        } else {
                            false
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val moviesDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                            "TGWrist"
                        )
                        if (!moviesDir.exists()) moviesDir.mkdirs()
                        val destFile = File(moviesDir, fileName)
                        sourceFile.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        destFile.exists()
                    }
                }

                if (success) {
                    isSaved = true
                    Toast.makeText(context, strSavedToMediaLibrary, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, strFileSaveFailed, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, strFileSaveFailed, Toast.LENGTH_SHORT).show()
            } finally {
                isSaving = false
            }
        }
    }

    // ========== UI ==========
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscroll,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ========== 圆形缩略图（含 secret 蒙层） ==========
            item(key = "thumbnail") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumbnailFileUrl.isNotBlank()) {
                            Image(
                                painter = rememberAsyncImagePainter(
                                    model = ImageRequest.Builder(context)
                                        .data(thumbnailFileUrl)
                                        .size(Size.ORIGINAL)
                                        .build()
                                ),
                                contentDescription = "VideoNote_Thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = thumbnailDownloadProgress,
                                modifier = Modifier.size(48.dp)
                            )
                        }

                        // Secret 蒙层（点击揭示）
                        if (isSecret && !isRevealed) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { isRevealed = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawRect(color = Color.Black.copy(alpha = 0.55f))
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = "Reveal",
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ========== 时长 ==========
            if (durationSec > 0) {
                item(key = "duration") {
                    Text(
                        text = stringResource(R.string.video_note_duration, durationText),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // ========== 已查看 / 未查看 状态 ==========
            item(key = "viewed_state") {
                Text(
                    text = if (isViewed) stringResource(R.string.video_note_viewed)
                    else stringResource(R.string.video_note_unviewed),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = if (isViewed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }

            // ========== Secret 提示 ==========
            if (isSecret) {
                item(key = "secret_hint") {
                    Text(
                        text = if (isRevealed) stringResource(R.string.video_note_secret)
                        else stringResource(R.string.video_note_reveal),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // ========== 文件大小 ==========
            if (videoFileSize > 0) {
                item(key = "file_size") {
                    Text(
                        text = fileSizeText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // ========== 下载进度条 ==========
            if (isDownloading) {
                item(key = "download_progress") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = videoDownloadProgress,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                // ========== 取消下载按钮 ==========
                item(key = "cancel_download_button") {
                    FilledTonalButton(
                        onClick = { cancelDownload() },
                        label = {
                            Text(
                                text = stringResource(R.string.cancel_download),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cancel"
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // ========== 下载按钮 ==========
            if (videoFileUrl.isBlank() && !isDownloading) {
                item(key = "download_button") {
                    FilledTonalButton(
                        onClick = { startDownload() },
                        label = {
                            Text(
                                text = stringResource(R.string.download_file),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Download"
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // ========== 播放按钮（视频已下载完成 且 已揭示） ==========
            if (videoFileUrl.isNotBlank() && !isDownloading && (!isSecret || isRevealed)) {
                item(key = "play_button") {
                    FilledTonalButton(
                        onClick = {
                            navController.navigate(Destinations.videoView(videoFileUrl))
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.play_video),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircle,
                                contentDescription = "Play",
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }

                // ========== 用外部应用打开 ==========
                item(key = "open_button") {
                    FilledTonalButton(
                        onClick = { openWithExternalPlayer() },
                        label = {
                            Text(
                                text = stringResource(R.string.open_with_external),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Open"
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }

                // ========== 保存到媒体库按钮 ==========
                item(key = "save_media_button") {
                    FilledTonalButton(
                        onClick = { saveToMediaLibrary() },
                        enabled = !isSaving && !isSaved,
                        label = {
                            Text(
                                text = if (isSaved) stringResource(R.string.saved_to_media_library)
                                else stringResource(R.string.save_to_media_library),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = "Save"
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // 回复按钮
            item(key = "reply") {
                ReplyMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            // 回复按钮
            item {
                ReplyMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            // 删除消息按钮
            if (messageRenderContext.chat != null) {
                item(key = "delete") {
                    DeleteMessageButton(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        surfaceTransformation = SurfaceTransformation(transformationSpec),
                        chat = messageRenderContext.chat,
                        messageId = messageRenderContext.messageId,
                        properties = messageRenderContext.properties,
                        useDialog = messageRenderContext.useDialog
                    )
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
