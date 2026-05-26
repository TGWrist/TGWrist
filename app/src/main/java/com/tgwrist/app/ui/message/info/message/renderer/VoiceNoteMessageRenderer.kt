package com.tgwrist.app.ui.message.info.message.renderer

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
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
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * 把 5-bit 紧凑波形数据解包成 [0,31] 的振幅数组。
 * Telegram 用 5 位记录每个采样点，按位流连续存储。
 */
private fun decodeWaveform(packed: ByteArray?): IntArray {
    if (packed == null || packed.isEmpty()) return IntArray(0)
    val sampleCount = packed.size * 8 / 5
    val out = IntArray(sampleCount)
    for (i in 0 until sampleCount) {
        val bitIndex = i * 5
        val byteIndex = bitIndex ushr 3
        val bitOffset = bitIndex and 0x07
        // 取 16-bit 视窗，避免跨字节
        val low = packed[byteIndex].toInt() and 0xFF
        val high = if (byteIndex + 1 < packed.size) packed[byteIndex + 1].toInt() and 0xFF else 0
        val value = (low or (high shl 8)) ushr bitOffset
        out[i] = value and 0x1F
    }
    return out
}

/**
 * 安全计算下载进度
 */
private fun calculateProgress(downloadedSize: Long, size: Long, expectedSize: Long): Float {
    val total = size.takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 } ?: 1L
    return (downloadedSize.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 把秒数格式化为 mm:ss
 */
private fun formatDuration(totalSeconds: Int): String {
    val seconds = max(totalSeconds, 0)
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}

@Composable
fun VoiceNoteMessageRenderer(
    content: TdApi.MessageVoiceNote,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = messageRenderContext.navController
    val coroutineScope = rememberCoroutineScope()

    val voiceNote = content.voiceNote
    val duration = remember(voiceNote) { voiceNote.duration }
    val mimeType = remember(voiceNote) { voiceNote.mimeType.ifBlank { "audio/ogg" } }
    val waveformBars = remember(voiceNote) { decodeWaveform(voiceNote.waveform) }
    val recognizedText: TdApi.SpeechRecognitionResult? = voiceNote.speechRecognitionResult

    val caption = remember(content.caption) { content.caption }
    var translateCaption by remember { mutableStateOf<TdApi.FormattedText?>(null) }

    // 字符串资源
    val copiedClipboard = stringResource(R.string.Copied_clipboard)
    val strPlayFailed = stringResource(R.string.voice_play_failed)

    // 文件状态
    val fileId by remember { mutableIntStateOf(voiceNote.voice.id) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var fileLocalPath by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }

    // 播放状态
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableIntStateOf(0) }
    var playbackDurationMs by remember { mutableIntStateOf(duration * 1000) }

    // ========== 初始化文件状态 ==========
    LaunchedEffect(Unit) {
        val initialFile = voiceNote.voice
        if (initialFile.local.isDownloadingCompleted) {
            fileLocalPath = initialFile.local.path
            downloadProgress = 1f
        } else if (initialFile.local.isDownloadingActive) {
            isDownloading = true
            downloadProgress = calculateProgress(
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
                    downloadProgress = calculateProgress(
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

    // ========== 订阅文件下载更新 ==========
    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateFile::class.java, lifecycleOwner) { update ->
            if (update.file.id == fileId) {
                downloadProgress = calculateProgress(
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
            delay(100L.milliseconds)
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
        TgClient.send(TdApi.DownloadFile(fileId, 30, 0, 0, false)) { result ->
            if (result is TdApi.File) {
                downloadProgress = calculateProgress(
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
        // 全新创建
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
                // 播放完成
                isPlaying = false
                playbackPositionMs = 0
                runCatching { it.seekTo(0) }
                // 发送语音被阅读标记
                TgClient.send(TdApi.OpenMessageContent(messageRenderContext.chatId, messageRenderContext.messageId))
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

    // ========== UI ==========
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()

    val playProgress = if (playbackDurationMs > 0) {
        (playbackPositionMs.toFloat() / playbackDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val playedColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

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
            // ========== 麦克风图标 + 波形 + 时长进度文字 ==========
            item(key = "voice") {
                val totalDurationSec = (playbackDurationMs / 1000).takeIf { it > 0 } ?: duration
                val currentSec = (playbackPositionMs / 1000).coerceAtLeast(0)
                ListHeader(
                    contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() * 0.2f, bottom = 4.dp, end = contentPadding.calculateEndPadding(
                        LayoutDirection.Ltr) * 0.2f, start = contentPadding.calculateStartPadding(LayoutDirection.Rtl) * 0.2f),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = stringResource(R.string.voice_note),
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        if (waveformBars.isEmpty()) {
                            Text(
                                text = stringResource(R.string.voice_no_waveform),
                                style = MaterialTheme.typography.bodySmall,
                                color = idleColor
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val barCount = waveformBars.size
                                    val totalWidth = size.width
                                    val totalHeight = size.height
                                    val barWidth = totalWidth / barCount * 0.6f
                                    val gap = totalWidth / barCount * 0.4f
                                    val playedBoundary = totalWidth * playProgress
                                    for (i in 0 until barCount) {
                                        val amp = waveformBars[i] / 31f
                                        val barHeight = (totalHeight * amp).coerceAtLeast(2f)
                                        val x = i * (barWidth + gap) + barWidth / 2f
                                        val y = (totalHeight - barHeight) / 2f
                                        val color = if (x <= playedBoundary) playedColor else idleColor
                                        drawLine(
                                            color = color,
                                            start = Offset(x, y),
                                            end = Offset(x, y + barHeight),
                                            strokeWidth = barWidth.coerceAtLeast(1f),
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${formatDuration(currentSec)} / ${formatDuration(totalDurationSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
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

            // ========== 语音转文字（若 TDLib 已识别） ==========
            (recognizedText as? TdApi.SpeechRecognitionResultText)?.let { result ->
                if (result.text.isNotBlank()) {
                    item(key = "speech_text_title") {
                        Text(
                            text = stringResource(R.string.voice_recognized_text),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    item(key = "speech_text") {
                        Text(
                            text = result.text,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                                .padding(horizontal = 10.dp)
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
                                text = stringResource(R.string.download_voice),
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
                                text = if (isPlaying) stringResource(R.string.pause_voice)
                                else stringResource(R.string.play_voice),
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

            // ========== 时长信息 ==========
            item(key = "duration_card") {
                val durationText = formatDuration(duration)
                TitleCard(
                    title = { Text(stringResource(R.string.voice_duration)) },
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
