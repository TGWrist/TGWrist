package com.tgwrist.app.ui.message.info.message.renderer

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.MessageTextView
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.TranslationButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
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
private fun animationFormatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    val idx = digitGroups.coerceIn(0, units.size - 1)
    return "%.1f %s".format(bytes / 1024.0.pow(idx.toDouble()), units[idx])
}

/**
 * 安全计算下载进度
 */
private fun animationCalculateProgress(downloadedSize: Long, size: Long, expectedSize: Long): Float {
    val total = size.takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 } ?: 1L
    return (downloadedSize.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 内嵌 GIF / MP4 自动循环播放器（无声）
 */
@OptIn(UnstableApi::class)
@Composable
private fun AnimationInlinePlayer(
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember(filePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(File(filePath).toURI().toString()))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = modifier
    )
}

@OptIn(UnstableApi::class)
@Composable
fun AnimationMessageRenderer(
    content: TdApi.MessageAnimation,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = messageRenderContext.navController
    val coroutineScope = rememberCoroutineScope()

    val animation = remember(content.animation) { content.animation }
    val caption = remember(content.caption) { content.caption }
    val hasSpoiler = remember(content.hasSpoiler) { content.hasSpoiler }
    var translateCaption by remember { mutableStateOf<TdApi.FormattedText?>(null) }

    val animationFileSize = remember(animation) { animation?.animation?.size ?: 0L }
    val fileSizeText = remember(animationFileSize) { animationFormatFileSize(animationFileSize) }
    val mimeType = remember(animation) { animation?.mimeType?.ifBlank { "video/mp4" } ?: "video/mp4" }

    // 字符串变量
    val strFileSaveFailed = stringResource(R.string.file_save_failed)
    val strSavedToMediaLibrary = stringResource(R.string.saved_to_media_library)
    val strNoAppToOpen = stringResource(R.string.no_app_to_open)

    // ========== 缩略图下载 ==========
    val thumbnail = remember(animation?.thumbnail) { animation?.thumbnail }
    var thumbnailFileUrl by remember { mutableStateOf("") }
    var thumbnailDownloadProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(thumbnail?.file?.id) {
        thumbnail?.let {
            if (it.file.local.isDownloadingCompleted) {
                thumbnailFileUrl = it.file.local.path
                thumbnailDownloadProgress = 1f
            } else {
                TgClient.send(TdApi.DownloadFile(it.file.id, 30, 0, 0, false)) { result ->
                    if (result is TdApi.File) {
                        thumbnailDownloadProgress = animationCalculateProgress(
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

    // ========== 动画文件状态 ==========
    val animationFile = remember(animation?.animation) { animation?.animation }
    var animationFileUrl by remember { mutableStateOf("") }
    var animationDownloadProgress by remember { mutableFloatStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }

    // 保存到媒体库状态
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    // 涂抹（spoiler）显示状态
    var isRevealed by remember(hasSpoiler) { mutableStateOf(!hasSpoiler) }

    // ========== 初始化动画文件状态 ==========
    LaunchedEffect(animationFile?.id) {
        animationFile?.let {
            if (it.local.isDownloadingCompleted) {
                animationFileUrl = it.local.path
                animationDownloadProgress = 1f
                isDownloading = false
            } else if (it.local.isDownloadingActive) {
                isDownloading = true
                animationDownloadProgress = animationCalculateProgress(
                    it.local.downloadedSize,
                    it.size,
                    it.expectedSize
                )
            }

            // 异步通过 GetFile 获取最新文件状态
            TgClient.send(TdApi.GetFile(it.id)) { result ->
                if (result is TdApi.File) {
                    if (result.local.isDownloadingCompleted) {
                        animationFileUrl = result.local.path
                        animationDownloadProgress = 1f
                        isDownloading = false
                    } else if (result.local.isDownloadingActive) {
                        isDownloading = true
                        animationDownloadProgress = animationCalculateProgress(
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
                    thumbnailDownloadProgress = animationCalculateProgress(
                        update.file.local.downloadedSize,
                        update.file.size,
                        update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingCompleted) {
                        thumbnailFileUrl = update.file.local.path
                        thumbnailDownloadProgress = 1f
                    }
                }

                animationFile?.id -> {
                    animationDownloadProgress = animationCalculateProgress(
                        update.file.local.downloadedSize,
                        update.file.size,
                        update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingActive) {
                        isDownloading = true
                    }
                    if (update.file.local.isDownloadingCompleted) {
                        animationFileUrl = update.file.local.path
                        isDownloading = false
                        animationDownloadProgress = 1f
                    }
                }
            }
        }
    }

    // ========== 自动开始下载（动画通常很小，UX 上自动播放更合理） ==========
    LaunchedEffect(animationFile?.id) {
        animationFile?.let {
            if (!it.local.isDownloadingCompleted && !it.local.isDownloadingActive) {
                isDownloading = true
                TgClient.send(TdApi.DownloadFile(it.id, 30, 0, 0, false)) { result ->
                    if (result is TdApi.File) {
                        animationDownloadProgress = animationCalculateProgress(
                            result.local.downloadedSize,
                            result.size,
                            result.expectedSize
                        )
                        if (result.local.isDownloadingCompleted) {
                            animationFileUrl = result.local.path
                            isDownloading = false
                            animationDownloadProgress = 1f
                        }
                    }
                }
            }
        }
    }

    // ========== 手动重试下载 ==========
    fun startDownload() {
        animationFile?.let {
            isDownloading = true
            TgClient.send(TdApi.DownloadFile(it.id, 32, 0, 0, false)) { result ->
                if (result is TdApi.File) {
                    animationDownloadProgress = animationCalculateProgress(
                        result.local.downloadedSize,
                        result.size,
                        result.expectedSize
                    )
                    if (result.local.isDownloadingCompleted) {
                        animationFileUrl = result.local.path
                        isDownloading = false
                        animationDownloadProgress = 1f
                    }
                }
            }
        }
    }

    // ========== 取消下载 ==========
    fun cancelDownload() {
        animationFile?.let {
            TgClient.send(TdApi.CancelDownloadFile(it.id, false)) { result ->
                if (result is TdApi.Ok) {
                    isDownloading = false
                    animationDownloadProgress = 0f
                }
            }
        }
    }

    // ========== 使用外部播放器打开 ==========
    fun openWithExternalPlayer() {
        if (animationFileUrl.isBlank()) return
        try {
            val file = File(animationFileUrl)
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

    // ========== 保存到系统媒体库（视频目录） ==========
    fun saveToMediaLibrary() {
        if (animationFileUrl.isBlank() || isSaving) return
        isSaving = true
        coroutineScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    val sourceFile = File(animationFileUrl)
                    if (!sourceFile.exists()) return@withContext false

                    val originalName = animation?.fileName?.takeIf { it.isNotBlank() }
                        ?: sourceFile.name.ifBlank { "animation_${System.currentTimeMillis()}.mp4" }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = context.contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, originalName)
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
                        val destFile = File(moviesDir, originalName)
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
            // ========== 动画预览（含 spoiler 蒙层） ==========
            item(key = "preview") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        // 带 spoiler 且尚未点开：显示蒙层
                        hasSpoiler && !isRevealed -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { isRevealed = true },
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
                                        contentDescription = "Animation_Spoiler",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(0.dp),
                                ) {
                                    // 半透明蒙层
                                    androidx.compose.foundation.Canvas(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        drawRect(color = Color.Black.copy(alpha = 0.55f))
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = "Reveal",
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }

                        // 文件就绪 → 内嵌循环播放
                        animationFileUrl.isNotBlank() -> {
                            AnimationInlinePlayer(
                                filePath = animationFileUrl,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // 否则：缩略图 + 进度
                        else -> {
                            if (thumbnailFileUrl.isNotBlank()) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        model = ImageRequest.Builder(context)
                                            .data(thumbnailFileUrl)
                                            .size(Size.ORIGINAL)
                                            .build()
                                    ),
                                    contentDescription = "Animation_Thumbnail",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            CircularProgressIndicator(
                                progress = if (isDownloading || animationDownloadProgress > 0f) {
                                    animationDownloadProgress
                                } else {
                                    thumbnailDownloadProgress
                                },
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }
            }

            // ========== 文件大小 ==========
            if (animationFileSize > 0) {
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
                            modifier = Modifier.fillMaxWidth()
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

            // ========== 取消下载按钮 ==========
            if (isDownloading) {
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

            // ========== 下载按钮（自动下载失败/未触发时回退） ==========
            if (animationFileUrl.isBlank() && !isDownloading) {
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
                item(key = "translate") {
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

            // ========== 已下载完成时的额外操作 ==========
            if (animationFileUrl.isNotBlank() && !isDownloading) {
                // 用外部应用打开
                item(key = "open_with_button") {
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
                                contentDescription = "Open with"
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }

                // 保存到媒体库
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
