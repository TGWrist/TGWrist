package com.tgwrist.app.ui.message.info.message.renderer

import android.content.ContentValues
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
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
import com.tgwrist.app.utils.setClipboardText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

/**
 * 格式化文件大小为可读的字符串
 */
private fun audioFormatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    val idx = digitGroups.coerceIn(0, units.size - 1)
    return "%.1f %s".format(bytes / 1024.0.pow(idx.toDouble()), units[idx])
}

/**
 * 安全计算下载进度
 */
private fun audioCalculateProgress(downloadedSize: Long, size: Long, expectedSize: Long): Float {
    val total = size.takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 } ?: 1L
    return (downloadedSize.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 把秒数格式化为 mm:ss
 */
private fun audioFormatDuration(totalSeconds: Int): String {
    val seconds = max(totalSeconds, 0)
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}
@Composable
fun AudioMessageRenderer(
    content: TdApi.MessageAudio,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = messageRenderContext.navController
    val coroutineScope = rememberCoroutineScope()

    val audio = content.audio
    val duration = remember(audio) { audio.duration }
    val title = remember(audio) { audio.title.orEmpty() }
    val performer = remember(audio) { audio.performer.orEmpty() }
    val fileName = remember(audio) { audio.fileName.ifBlank { "audio" } }
    val mimeType = remember(audio) { audio.mimeType.ifBlank { "audio/mpeg" } }
    val fileSize = remember(audio) { audio.audio.size }
    val fileSizeText = remember(fileSize) { audioFormatFileSize(fileSize) }

    val caption = remember(content.caption) { content.caption }
    var translateCaption by remember { mutableStateOf<TdApi.FormattedText?>(null) }

    // 字符串变量
    val copiedClipboard = stringResource(R.string.Copied_clipboard)
    val strFileSavedToPath = stringResource(R.string.file_saved_to_path)
    val strFileSaveFailed = stringResource(R.string.file_save_failed)
    val strNoAppToOpen = stringResource(R.string.no_app_to_open)
    val strPlayFailed = stringResource(R.string.audio_play_failed)
    val strUnknownTitle = stringResource(R.string.audio_unknown_title)
    val strUnknownArtist = stringResource(R.string.audio_unknown_artist)

    val displayTitle = remember(title, fileName, strUnknownTitle) {
        title.ifBlank { fileName.ifBlank { strUnknownTitle } }
    }
    val displayPerformer = remember(performer, strUnknownArtist) {
        performer.ifBlank { strUnknownArtist }
    }

    // ========== 专辑封面缩略图 ==========
    val albumCoverThumbnail = remember(audio) { audio.albumCoverThumbnail }
    var albumCoverPath by remember { mutableStateOf("") }

    LaunchedEffect(albumCoverThumbnail?.file?.id) {
        albumCoverThumbnail?.let {
            if (it.file.local.isDownloadingCompleted) {
                albumCoverPath = it.file.local.path
            } else {
                TgClient.send(TdApi.DownloadFile(it.file.id, 30, 0, 0, false)) { result ->
                    if (result is TdApi.File && result.local.isDownloadingCompleted) {
                        albumCoverPath = result.local.path
                    }
                }
            }
        }
    }

    // ========== 主音频文件状态 ==========
    val audioFile = remember(audio) { audio.audio }
    val fileId by remember(audioFile) { mutableIntStateOf(audioFile.id) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var fileLocalPath by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }

    // 保存状态
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    var savedFilePath by remember { mutableStateOf("") }

    // 播放状态
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableIntStateOf(0) }
    var playbackDurationMs by remember { mutableIntStateOf(duration * 1000) }

    // ========== 初始化文件状态 ==========
    LaunchedEffect(fileId) {
        val initialFile = audioFile
        if (initialFile.local.isDownloadingCompleted) {
            fileLocalPath = initialFile.local.path
            downloadProgress = 1f
        } else if (initialFile.local.isDownloadingActive) {
            isDownloading = true
            downloadProgress = audioCalculateProgress(
                initialFile.local.downloadedSize,
                initialFile.size,
                initialFile.expectedSize
            )
        }

        TgClient.send(TdApi.GetFile(fileId)) { result ->
            if (result is TdApi.File) {
                if (result.local.isDownloadingCompleted) {
                    fileLocalPath = result.local.path
                    downloadProgress = 1f
                    isDownloading = false
                } else if (result.local.isDownloadingActive) {
                    isDownloading = true
                    downloadProgress = audioCalculateProgress(
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

    // ========== 订阅文件下载更新（音频文件 + 封面） ==========
    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateFile::class.java, lifecycleOwner) { update ->
            when (update.file.id) {
                fileId -> {
                    downloadProgress = audioCalculateProgress(
                        update.file.local.downloadedSize,
                        update.file.size,
                        update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingActive) {
                        isDownloading = true
                    }
                    if (update.file.local.isDownloadingCompleted) {
                        fileLocalPath = update.file.local.path
                        isDownloading = false
                        downloadProgress = 1f
                    }
                }

                albumCoverThumbnail?.file?.id -> {
                    if (update.file.local.isDownloadingCompleted) {
                        albumCoverPath = update.file.local.path
                    }
                }
            }
        }
    }
    // ========== 播放进度轮询 ==========
    LaunchedEffect(isPlaying) {
        while (isPlaying && this.coroutineContext.isActive) {
            mediaPlayer?.let { mp ->
                runCatching {
                    if (mp.isPlaying) {
                        playbackPositionMs = mp.currentPosition
                    }
                }
            }
            delay(200L.milliseconds)
        }
    }

    // ========== 释放 MediaPlayer ==========
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.stop()
                    mp.release()
                }
            }
            mediaPlayer = null
            isPlaying = false
        }
    }

    fun startDownload() {
        isDownloading = true
        TgClient.send(TdApi.DownloadFile(fileId, 28, 0, 0, false)) { result ->
            if (result is TdApi.File) {
                downloadProgress = audioCalculateProgress(
                    result.local.downloadedSize,
                    result.size,
                    result.expectedSize
                )
                if (result.local.isDownloadingCompleted) {
                    fileLocalPath = result.local.path
                    isDownloading = false
                    downloadProgress = 1f
                }
            }
        }
    }

    fun cancelDownload() {
        TgClient.send(TdApi.CancelDownloadFile(fileId, false)) { result ->
            if (result is TdApi.Ok) {
                isDownloading = false
                downloadProgress = 0f
            }
        }
    }

    fun togglePlayback() {
        if (fileLocalPath.isBlank()) return
        val current = mediaPlayer
        if (current != null) {
            runCatching {
                if (current.isPlaying) {
                    current.pause()
                    isPlaying = false
                } else {
                    current.start()
                    isPlaying = true
                }
            }
            return
        }
        coroutineScope.launch {
            val mp = withContext(Dispatchers.IO) {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(fileLocalPath)
                        prepare()
                    }
                }.getOrNull()
            }
            if (mp == null) {
                Toast.makeText(context, strPlayFailed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            mp.setOnCompletionListener {
                isPlaying = false
                playbackPositionMs = 0
                runCatching { it.seekTo(0) }
            }
            mp.setOnErrorListener { player, _, _ ->
                isPlaying = false
                runCatching { player.release() }
                if (mediaPlayer === player) mediaPlayer = null
                true
            }
            mediaPlayer = mp
            playbackDurationMs = if (mp.duration > 0) mp.duration else duration * 1000
            mp.start()
            isPlaying = true
        }
    }

    // ========== 保存到媒体库（Music 目录） ==========
    fun saveToMusic() {
        if (fileLocalPath.isBlank() || isSaving) return
        isSaving = true
        coroutineScope.launch {
            try {
                val resultPath: String? = withContext(Dispatchers.IO) {
                    val sourceFile = File(fileLocalPath)
                    if (!sourceFile.exists()) return@withContext null

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = context.contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                            put(
                                MediaStore.Audio.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_MUSIC + "/TGWrist"
                            )
                            if (title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, title)
                            if (performer.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, performer)
                            if (duration > 0) put(MediaStore.Audio.Media.DURATION, duration * 1000L)
                        }
                        val uri = resolver.insert(
                            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            contentValues
                        )
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { outputStream ->
                                sourceFile.inputStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            "${Environment.DIRECTORY_MUSIC}/TGWrist/$fileName"
                        } else {
                            null
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val musicDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                            "TGWrist"
                        )
                        if (!musicDir.exists()) musicDir.mkdirs()
                        val destFile = File(musicDir, fileName)
                        sourceFile.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (destFile.exists()) destFile.absolutePath else null
                    }
                }
                if (resultPath != null) {
                    isSaved = true
                    savedFilePath = resultPath
                    Toast.makeText(
                        context,
                        strFileSavedToPath.format(savedFilePath),
                        Toast.LENGTH_SHORT
                    ).show()
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

    // ========== 用外部应用打开 ==========
    fun openFile() {
        if (fileLocalPath.isBlank()) return
        try {
            val file = File(fileLocalPath)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val resolvedMimeType = mimeType.ifBlank { "audio/*" }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "audio/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (fallbackIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(fallbackIntent)
                } else {
                    Toast.makeText(context, strNoAppToOpen, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(context, strNoAppToOpen, Toast.LENGTH_SHORT).show()
        }
    }
    // ========== UI ==========
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()

    val playProgress = if (playbackDurationMs > 0) {
        (playbackPositionMs.toFloat() / playbackDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

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
            // ========== 专辑封面 + 标题 + 演唱者 ==========
            item(key = "audio_header") {
                ListHeader(
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding() * 0.2f,
                        bottom = 4.dp,
                        end = contentPadding.calculateEndPadding(LayoutDirection.Ltr) * 0.2f,
                        start = contentPadding.calculateStartPadding(LayoutDirection.Rtl) * 0.2f
                    ),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.transformedHeight(this, transformationSpec)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (albumCoverPath.isNotBlank()) {
                                val painter = rememberAsyncImagePainter(
                                    model = ImageRequest.Builder(context)
                                        .data(File(albumCoverPath))
                                        .size(Size.ORIGINAL)
                                        .build()
                                )
                                Image(
                                    painter = painter,
                                    contentDescription = displayTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Album,
                                        contentDescription = displayTitle,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = displayPerformer,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ========== 播放进度条 + 时长文字 ==========
            item(key = "playback_progress") {
                val totalDurationSec = (playbackDurationMs / 1000).takeIf { it > 0 } ?: duration
                val currentSec = (playbackPositionMs / 1000).coerceAtLeast(0)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { playProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${audioFormatDuration(currentSec)} / ${audioFormatDuration(totalDurationSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
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
                    item(key = "translation_results") {
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
            // ========== 下载进度 ==========
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
                            progress = downloadProgress,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
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
            if (fileLocalPath.isBlank() && !isDownloading) {
                item(key = "download_button") {
                    FilledTonalButton(
                        onClick = { startDownload() },
                        label = {
                            Text(
                                text = stringResource(R.string.download_audio),
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

            // ========== 播放/暂停按钮 ==========
            if (fileLocalPath.isNotBlank() && !isDownloading) {
                item(key = "play_button") {
                    FilledTonalButton(
                        onClick = { togglePlayback() },
                        label = {
                            Text(
                                text = if (isPlaying) stringResource(R.string.pause_audio)
                                else stringResource(R.string.play_audio),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play"
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
                        onDone = { translateCaption = it }
                    )
                }
            }

            // ========== 用外部应用打开 ==========
            if (fileLocalPath.isNotBlank() && !isDownloading) {
                item(key = "open_button") {
                    FilledTonalButton(
                        onClick = { openFile() },
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
            }

            // ========== 保存到 Music ==========
            if (fileLocalPath.isNotBlank() && !isDownloading) {
                item(key = "save_button") {
                    FilledTonalButton(
                        onClick = { saveToMusic() },
                        enabled = !isSaving && !isSaved,
                        label = {
                            Text(
                                text = if (isSaved) stringResource(R.string.file_saved)
                                else stringResource(R.string.save_audio_to_music),
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
            item {
                ReplyMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
            }

            // 删除按钮
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

            // ========== 信息卡片：标题 ==========
            if (title.isNotBlank()) {
                item(key = "title_card") {
                    TitleCard(
                        title = { Text(stringResource(R.string.audio_title)) },
                        onClick = { },
                        onLongClick = {
                            context.setClipboardText(title)
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    ) {
                        Text(title)
                    }
                }
            }

            // ========== 演唱者 ==========
            if (performer.isNotBlank()) {
                item(key = "performer_card") {
                    TitleCard(
                        title = { Text(stringResource(R.string.audio_performer)) },
                        onClick = { },
                        onLongClick = {
                            context.setClipboardText(performer)
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    ) {
                        Text(performer)
                    }
                }
            }

            // ========== 时长 ==========
            item(key = "duration_card") {
                val durationText = audioFormatDuration(duration)
                TitleCard(
                    title = { Text(stringResource(R.string.audio_duration_label)) },
                    onClick = { },
                    onLongClick = {
                        context.setClipboardText(durationText)
                        Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(durationText)
                }
            }

            // ========== 文件名 ==========
            if (audio.fileName.isNotBlank()) {
                item(key = "file_name_card") {
                    TitleCard(
                        title = { Text(stringResource(R.string.audio_file_name)) },
                        onClick = { },
                        onLongClick = {
                            context.setClipboardText(fileName)
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    ) {
                        Text(fileName)
                    }
                }
            }

            // ========== 文件大小 ==========
            item(key = "file_size") {
                TitleCard(
                    title = { Text(stringResource(R.string.file_size)) },
                    onClick = { },
                    onLongClick = {
                        context.setClipboardText(fileSizeText)
                        Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(fileSizeText)
                }
            }

            // ========== 文件类型 ==========
            item(key = "file_type") {
                TitleCard(
                    title = { Text(stringResource(R.string.file_type)) },
                    onClick = { },
                    onLongClick = {
                        context.setClipboardText(mimeType)
                        Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                ) {
                    Text(mimeType)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

