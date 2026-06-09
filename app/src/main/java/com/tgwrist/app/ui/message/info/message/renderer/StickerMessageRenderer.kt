package com.tgwrist.app.ui.message.info.message.renderer

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.tgwrist.app.R
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.ForwardMessageButton
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.zip.GZIPInputStream

private fun stickerProgress(downloadedSize: Long, size: Long, expectedSize: Long): Float {
    val total = size.takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 } ?: 1L
    return (downloadedSize.toFloat() / total).coerceIn(0f, 1f)
}

private suspend fun readStickerTgsAsJson(path: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        GZIPInputStream(File(path).inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}

@Composable
private fun StickerPlaceholder(
    thumbnailFileUrl: String,
    progress: Float,
    contentDescription: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
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
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize()
            )
        }
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.size(40.dp)
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun StickerWebmPlayer(
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
fun StickerMessageRenderer(
    content: TdApi.MessageSticker,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val sticker = remember(content.sticker) { content.sticker }
    val emoji = remember(sticker?.emoji) { sticker?.emoji.orEmpty() }
    val isPremium = remember(content.isPremium) { content.isPremium }

    // 缩略图（WEBP/JPEG，可能为空）
    val thumbnail = remember(sticker?.thumbnail) { sticker?.thumbnail }
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
                        thumbnailDownloadProgress = stickerProgress(
                            result.local.downloadedSize, result.size, result.expectedSize
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

    // 主贴纸文件
    val stickerFile = remember(sticker?.sticker) { sticker?.sticker }
    var stickerFileUrl by remember { mutableStateOf("") }
    var stickerDownloadProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(stickerFile?.id) {
        stickerFile?.let {
            if (it.local.isDownloadingCompleted) {
                stickerFileUrl = it.local.path
                stickerDownloadProgress = 1f
            } else {
                TgClient.send(TdApi.DownloadFile(it.id, 32, 0, 0, false)) { result ->
                    if (result is TdApi.File) {
                        stickerDownloadProgress = stickerProgress(
                            result.local.downloadedSize, result.size, result.expectedSize
                        )
                        if (result.local.isDownloadingCompleted) {
                            stickerFileUrl = result.local.path
                            stickerDownloadProgress = 1f
                        }
                    }
                }
            }
        }
    }

    // 统一订阅 UpdateFile 拿到增量
    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateFile::class.java, lifecycleOwner) { update ->
            when (update.file.id) {
                thumbnail?.file?.id -> {
                    thumbnailDownloadProgress = stickerProgress(
                        update.file.local.downloadedSize,
                        update.file.size,
                        update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingCompleted) {
                        thumbnailFileUrl = update.file.local.path
                        thumbnailDownloadProgress = 1f
                    }
                }

                stickerFile?.id -> {
                    stickerDownloadProgress = stickerProgress(
                        update.file.local.downloadedSize,
                        update.file.size,
                        update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingCompleted) {
                        stickerFileUrl = update.file.local.path
                        stickerDownloadProgress = 1f
                    }
                }
            }
        }
    }

    val isTgs = remember(sticker?.format) { sticker?.format is TdApi.StickerFormatTgs }
    val isWebm = remember(sticker?.format) { sticker?.format is TdApi.StickerFormatWebm }

    var tgsJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stickerFileUrl, isTgs) {
        if (isTgs && stickerFileUrl.isNotBlank()) {
            tgsJson = readStickerTgsAsJson(stickerFileUrl)
        }
    }

    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val isScrollable = scrollState.maxValue > 0
    val stickerSize = 160.dp
    val stickerContentDescription = stringResource(R.string.sticker_content_description)

    ScreenScaffold(
        scrollState = scrollState,
        scrollIndicator = { if (isScrollable) ScrollIndicator(state = scrollState) },
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .requestFocusOnHierarchyActive()
                .rotaryScrollable(
                    RotaryScrollableDefaults.behavior(scrollableState = scrollState),
                    focusRequester
                )
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 20.dp)
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    sticker == null -> {
                        SelectionContainer {
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    isTgs -> {
                        val json = tgsJson
                        if (json != null) {
                            val composition by rememberLottieComposition(
                                LottieCompositionSpec.JsonString(json)
                            )
                            val progress by animateLottieCompositionAsState(
                                composition = composition,
                                iterations = LottieConstants.IterateForever,
                                isPlaying = true,
                                restartOnPlay = false
                            )
                            Box(
                                modifier = Modifier.size(stickerSize),
                                contentAlignment = Alignment.Center
                            ) {
                                LottieAnimation(
                                    composition = composition,
                                    progress = { progress },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            StickerPlaceholder(
                                thumbnailFileUrl = thumbnailFileUrl,
                                progress = if (stickerFileUrl.isBlank()) {
                                    stickerDownloadProgress
                                } else {
                                    thumbnailDownloadProgress
                                },
                                contentDescription = stickerContentDescription
                            )
                        }
                    }

                    isWebm -> {
                        if (stickerFileUrl.isNotBlank()) {
                            StickerWebmPlayer(
                                filePath = stickerFileUrl,
                                modifier = Modifier.size(stickerSize)
                            )
                        } else {
                            StickerPlaceholder(
                                thumbnailFileUrl = thumbnailFileUrl,
                                progress = stickerDownloadProgress,
                                contentDescription = stickerContentDescription
                            )
                        }
                    }

                    else -> {
                        // WEBP / 其它静态格式
                        if (stickerFileUrl.isNotBlank()) {
                            Image(
                                painter = rememberAsyncImagePainter(
                                    model = ImageRequest.Builder(context)
                                        .data(stickerFileUrl)
                                        .size(Size.ORIGINAL)
                                        .build()
                                ),
                                contentDescription = stickerContentDescription,
                                modifier = Modifier.size(stickerSize)
                            )
                        } else {
                            StickerPlaceholder(
                                thumbnailFileUrl = thumbnailFileUrl,
                                progress = stickerDownloadProgress,
                                contentDescription = stickerContentDescription
                            )
                        }
                    }
                }
            }

            if (isPremium) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sticker_premium_label),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 回复按钮
            ReplyMessageButton(
                modifier = Modifier.padding(top = 4.dp),
                properties = messageRenderContext.properties,
                message = messageRenderContext.message
            )

            // 转发按钮
            ForwardMessageButton(
                modifier = Modifier.padding(top = 8.dp),
                properties = messageRenderContext.properties,
                message = messageRenderContext.message
            )

            // 删除按钮
            if (messageRenderContext.chat != null) {
                DeleteMessageButton(
                    modifier = Modifier.padding(top = 8.dp),
                    chat = messageRenderContext.chat,
                    messageId = messageRenderContext.messageId,
                    properties = messageRenderContext.properties,
                    useDialog = messageRenderContext.useDialog
                )
            }
        }
    }
}

