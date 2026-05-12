package com.tgwrist.app.ui.message.info.message.renderer

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.ui.graphics.Color
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
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.MessageTextView
import com.tgwrist.app.ui.message.info.TranslationButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.TgClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

/**
 * 格式化文件大小为可读的字符串
 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    val idx = digitGroups.coerceIn(0, units.size - 1)
    return "%.1f %s".format(bytes / 1024.0.pow(idx.toDouble()), units[idx])
}

/**
 * 安全计算下载进度
 */
private fun calculateProgress(downloadedSize: Long, size: Long, expectedSize: Long): Float {
    val total = size.takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 } ?: 1L
    return (downloadedSize.toFloat() / total).coerceIn(0f, 1f)
}

@Composable
fun VideoMessageRenderer(
    content: TdApi.MessageVideo,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = messageRenderContext.navController
    val coroutineScope = rememberCoroutineScope()

    val caption = remember(content.caption) { content.caption }
    var translateCaption by remember { mutableStateOf<TdApi.FormattedText?>(null) }
    val videoFileSize = remember { content.video.video.size }
    val fileSizeText = remember(videoFileSize) { formatFileSize(videoFileSize) }
    val mimeType = remember(content.video) { content.video.mimeType.ifBlank { "video/*" } }

    // 字符串变量
    val strFileSaveFailed = stringResource(R.string.file_save_failed)
    val strSavedToMediaLibrary = stringResource(R.string.saved_to_media_library)
    val strNoAppToOpen = stringResource(R.string.no_app_to_open)

    // ========== 缩略图下载 ==========
    var thumbnailDownloadProgress by remember { mutableFloatStateOf(0f) }
    val thumbnail = remember(content.video.thumbnail) { content.video.thumbnail }
    var thumbnailFileUrl by remember { mutableStateOf("") }

    LaunchedEffect(thumbnail?.file?.id) {
        thumbnail?.let {
            if (!thumbnail.file.local.isDownloadingCompleted) {
                TgClient.send(
                    TdApi.DownloadFile(thumbnail.file.id, 30, 0, 0, false)
                ) { result ->
                    if (result is TdApi.File) {
                        thumbnailDownloadProgress = calculateProgress(
                            result.local.downloadedSize,
                            result.size,
                            result.expectedSize
                        )
                        if (result.local.isDownloadingCompleted) {
                            thumbnailFileUrl = result.local.path
                        }
                    }
                }
            } else {
                thumbnailFileUrl = thumbnail.file.local.path
            }
        }
    }

    // ========== 视频文件状态 ==========
    var videoDownloadProgress by remember { mutableFloatStateOf(0f) }
    val video = remember(content.video.video) { content.video.video }
    var videoFileUrl by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }

    // 保存到媒体库状态
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    // ========== 初始化视频文件状态 ==========
    LaunchedEffect(Unit) {
        if (video != null) {
            if (video.local.isDownloadingCompleted) {
                videoFileUrl = video.local.path
                videoDownloadProgress = 1f
            } else if (video.local.isDownloadingActive) {
                isDownloading = true
                videoDownloadProgress = calculateProgress(
                    video.local.downloadedSize,
                    video.size,
                    video.expectedSize
                )
            }

            // 异步通过 GetFile 获取最新文件状态
            TgClient.send(TdApi.GetFile(video.id)) { result ->
                if (result is TdApi.File) {
                    if (result.local.isDownloadingCompleted) {
                        videoFileUrl = result.local.path
                        videoDownloadProgress = 1f
                        isDownloading = false
                    } else if (result.local.isDownloadingActive) {
                        isDownloading = true
                        videoDownloadProgress = calculateProgress(
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
                    thumbnailDownloadProgress = calculateProgress(
                        update.file.local.downloadedSize,
                        update.file.size,
                        update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingCompleted) {
                        thumbnailFileUrl = update.file.local.path
                    }
                }

                video?.id -> {
                    videoDownloadProgress = calculateProgress(
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
                TdApi.DownloadFile(video.id, 28, 0, 0, false)
            ) { result ->
                if (result is TdApi.File) {
                    videoDownloadProgress = calculateProgress(
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
            TgClient.send(TdApi.CancelDownloadFile(video.id, false)) { result ->
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

                    val fileName = sourceFile.name.ifBlank { "video_${System.currentTimeMillis()}.mp4" }

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
            // ========== 视频缩略图 ==========
            item(key = "thumbnail") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnailFileUrl.isNotBlank()) {
                        Image(
                            painter = rememberAsyncImagePainter(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(thumbnailFileUrl)
                                    .size(Size.ORIGINAL)
                                    .build()
                            ),
                            contentDescription = "Video_Thumbnail",
                            modifier = Modifier.aspectRatio(1f)
                        )
                    } else {
                        // 缩略图加载中
                        CircularProgressIndicator(
                            progress = thumbnailDownloadProgress,
                            modifier = Modifier.size(48.dp)
                        )
                    }
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

            // ========== 说明文字 ==========
            if (caption != null && !caption.text.isNullOrBlank()) {
                item(key = "caption") {
                    MessageTextView(
                        text = caption.text,
                        entities = caption.entities,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 10.dp),
                        navController = navController,
                    )
                }
                translateCaption?.let {
                    item(key = "Translation_results") {
                        Text(
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            text = stringResource(R.string.Translation_results),
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                        )
                    }

                    item(key = "translated_caption") {
                        MessageTextView(
                            text = it.text,
                            entities = it.entities,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                                .padding(horizontal = 10.dp),
                            navController = navController,
                        )
                    }
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

            // ========== 翻译按钮 ==========
            if (caption != null && !caption.text.isNullOrBlank()) {
                item(key = "translate"){
                    TranslationButton(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        surfaceTransformation = SurfaceTransformation(transformationSpec),
                        text = caption,
                        onDone = {
                            translateCaption = it
                        }
                    )
                }
            }

            // ========== 播放按钮（视频已下载完成时显示） ==========
            if (videoFileUrl.isNotBlank() && !isDownloading) {
                item(key = "play_button") {
                    FilledTonalButton(
                        onClick = {
                            if (Config.isNotUseBuiltVideoPlayer) {
                                // 使用外部播放器
                                openWithExternalPlayer()
                            } else {
                                // 使用内置播放器
                                navController.navigate(Destinations.videoView(videoFileUrl))
                            }
                        },
                        label = {
                            Text(
                                text = if (Config.isNotUseBuiltVideoPlayer)
                                    stringResource(R.string.open_with_external)
                                else
                                    stringResource(R.string.play_video),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = if (Config.isNotUseBuiltVideoPlayer)
                                    Icons.AutoMirrored.Rounded.OpenInNew
                                else
                                    Icons.Rounded.PlayCircle,
                                contentDescription = "Play",
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

            // 删除消息按钮
            if (messageRenderContext.chat != null) {
                item(key = "Delete") {
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

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
