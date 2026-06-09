package com.tgwrist.app.ui.message.info.message.renderer

import android.media.MediaPlayer
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
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
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.EditMessageTextButton
import com.tgwrist.app.ui.message.info.ForwardMessageButton
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.message.EditTextModelView
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.zip.GZIPInputStream

private fun animatedEmojiProgress(downloadedSize: Long, size: Long, expectedSize: Long): Float {
    val total = size.takeIf { it > 0 } ?: expectedSize.takeIf { it > 0 } ?: 1L
    return (downloadedSize.toFloat() / total).coerceIn(0f, 1f)
}

private suspend fun readTgsAsJson(path: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        GZIPInputStream(File(path).inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}

@Composable
private fun AnimatedEmojiPlaceholder(
    thumbnailFileUrl: String,
    progress: Float,
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
                contentDescription = "AnimatedEmoji_Thumbnail",
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
private fun WebmStickerPlayer(
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
fun AnimatedEmojiMessageRenderer(
    content: TdApi.MessageAnimatedEmoji,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val isEditing = remember { mutableStateOf(false) }
    val editText = remember { mutableStateOf("") }

    val animatedEmoji = remember(content.animatedEmoji) { content.animatedEmoji }
    val sticker = remember(animatedEmoji) { animatedEmoji?.sticker }
    val fallbackEmoji = remember(content.emoji) { content.emoji.orEmpty() }

    val thumbnail = remember(sticker?.thumbnail) { sticker?.thumbnail }
    var thumbnailFileUrl by remember { mutableStateOf("") }
    var thumbnailDownloadProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(thumbnail?.file?.id) {
        thumbnail?.let {
            if (it.file.local.isDownloadingCompleted) {
                thumbnailFileUrl = it.file.local.path
            } else {
                TgClient.send(TdApi.DownloadFile(it.file.id, 30, 0, 0, false)) { result ->
                    if (result is TdApi.File) {
                        thumbnailDownloadProgress = animatedEmojiProgress(
                            result.local.downloadedSize, result.size, result.expectedSize
                        )
                        if (result.local.isDownloadingCompleted) {
                            thumbnailFileUrl = result.local.path
                        }
                    }
                }
            }
        }
    }

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
                        stickerDownloadProgress = animatedEmojiProgress(
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

    val soundFile = remember(animatedEmoji?.sound) { animatedEmoji?.sound }
    var soundFileUrl by remember { mutableStateOf("") }

    LaunchedEffect(soundFile?.id) {
        soundFile?.let {
            if (it.local.isDownloadingCompleted) {
                soundFileUrl = it.local.path
            } else {
                TgClient.send(TdApi.DownloadFile(it.id, 30, 0, 0, false)) { result ->
                    if (result is TdApi.File && result.local.isDownloadingCompleted) {
                        soundFileUrl = result.local.path
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateFile::class.java, lifecycleOwner) { update ->
            when (update.file.id) {
                thumbnail?.file?.id -> {
                    thumbnailDownloadProgress = animatedEmojiProgress(
                        update.file.local.downloadedSize, update.file.size, update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingCompleted) {
                        thumbnailFileUrl = update.file.local.path
                    }
                }

                stickerFile?.id -> {
                    stickerDownloadProgress = animatedEmojiProgress(
                        update.file.local.downloadedSize, update.file.size, update.file.expectedSize
                    )
                    if (update.file.local.isDownloadingCompleted) {
                        stickerFileUrl = update.file.local.path
                        stickerDownloadProgress = 1f
                    }
                }

                soundFile?.id -> {
                    if (update.file.local.isDownloadingCompleted) {
                        soundFileUrl = update.file.local.path
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
            tgsJson = readTgsAsJson(stickerFileUrl)
        }
    }

    fun playSound() {
        if (soundFileUrl.isBlank()) return
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(soundFileUrl)
                        setOnCompletionListener { release() }
                        setOnErrorListener { mp, _, _ -> mp.release(); true }
                        prepare()
                        start()
                    }
                }
            }
        }
    }

    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val isScrollable = scrollState.maxValue > 0
    val stickerSize = 160.dp

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
            if (isEditing.value) {
                EditTextModelView(editText, isEditing, messageRenderContext)
            } else {
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
                                    text = fallbackEmoji.ifBlank { "❌" },
                                    style = MaterialTheme.typography.displayLarge,
                                    textAlign = TextAlign.Center
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
                                    modifier = Modifier
                                        .size(stickerSize)
                                        .clickable { playSound() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    LottieAnimation(
                                        composition = composition,
                                        progress = { progress },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else {
                                AnimatedEmojiPlaceholder(
                                    thumbnailFileUrl = thumbnailFileUrl,
                                    progress = if (stickerFileUrl.isBlank()) stickerDownloadProgress else thumbnailDownloadProgress
                                )
                            }
                        }

                        isWebm -> {
                            if (stickerFileUrl.isNotBlank()) {
                                WebmStickerPlayer(
                                    filePath = stickerFileUrl,
                                    modifier = Modifier
                                        .size(stickerSize)
                                        .clickable { playSound() }
                                )
                            } else {
                                AnimatedEmojiPlaceholder(
                                    thumbnailFileUrl = thumbnailFileUrl,
                                    progress = stickerDownloadProgress
                                )
                            }
                        }

                        else -> {
                            if (stickerFileUrl.isNotBlank()) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        model = ImageRequest.Builder(context)
                                            .data(stickerFileUrl)
                                            .size(Size.ORIGINAL)
                                            .build()
                                    ),
                                    contentDescription = "AnimatedEmoji",
                                    modifier = Modifier
                                        .size(stickerSize)
                                        .clickable { playSound() }
                                )
                            } else {
                                AnimatedEmojiPlaceholder(
                                    thumbnailFileUrl = thumbnailFileUrl,
                                    progress = stickerDownloadProgress
                                )
                            }
                        }
                    }
                }
            }

            // 编辑按钮
            if (messageRenderContext.properties?.canBeEdited == true) {
                EditMessageTextButton(
                    Modifier.padding(top = 4.dp),
                    properties = messageRenderContext.properties,
                    isEditing = isEditing,
                    onClick = {
                        if (isEditing.value) {
                            isEditing.value = false
                            editText.value = ""
                        } else {
                            editText.value = content.emoji
                            isEditing.value = true
                        }
                    }
                )
            }

            // 回复按钮
            ReplyMessageButton(
                modifier = Modifier.padding(top = 8.dp),
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
