package com.tgwrist.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.VoiceRecordingForegroundService
import com.tgwrist.app.data.MediaData
import com.tgwrist.app.data.MediaPickerRequest
import com.tgwrist.app.data.MediaPickerType
import com.tgwrist.app.data.SharedMessageInfoData
import com.tgwrist.app.data.SharedMessageInfoKey
import com.tgwrist.app.runtime.ChatMessagesRepository
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.Config.replyMessageFlow
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.runtime.VoiceRecordingState
import com.tgwrist.app.runtime.packWaveform5Bit
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.handleAllMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri

@Composable
fun Page1(chatId: Long, chatObject: TdApi.Chat?, mediaChose: SnapshotStateList<MediaData>, pagerState: PagerState) {
    val context = LocalContext.current
    val appState = LocalGlobalAppState.current
    val navController = appState.navController ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val coroutineScope = rememberCoroutineScope()
    val transformationSpec = rememberTransformationSpec()
    val interactionSource1 = remember { MutableInteractionSource() }
    val interactionSource2 = remember { MutableInteractionSource() }
    val replyMsg by replyMessageFlow.collectAsState()

    LaunchedEffect(lifecycleOwner, chatId) {
        ChatMessagesRepository.bindChat(lifecycleOwner, chatId)
    }

    val chatMessages by ChatMessagesRepository.getChatMessagesFlow(chatId)
        .collectAsStateWithLifecycle(
            initialValue = emptyList(),
            lifecycleOwner = lifecycleOwner,
            minActiveState = Lifecycle.State.CREATED
        )

    val chatMessagesById by remember(chatMessages) {
        derivedStateOf { chatMessages.associateBy(TdApi.Message::id) }
    }

    var text by remember { mutableStateOf("") }

    // 语音录制状态订阅
    val isVoiceRecording by VoiceRecordingState.isRecording.collectAsState()
    val isVoicePaused by VoiceRecordingState.isPaused.collectAsState()
    val isVoicePreview by VoiceRecordingState.isPreview.collectAsState()
    val voiceDurationMs by VoiceRecordingState.durationMs.collectAsState()
    val voiceCurrentLevel by VoiceRecordingState.currentLevel.collectAsState()
    val voiceLiveWaveform by VoiceRecordingState.liveWaveform.collectAsState()
    val voiceFinalWaveform by VoiceRecordingState.finalWaveform.collectAsState()
    val recordedVoiceFile by VoiceRecordingState.recordedFile.collectAsState()
    val recordMode = isVoiceRecording || isVoicePreview
    // 录制状态绑定的 chatId，用于隔离多聊天页
    val recordingTargetChatId = if (recordMode) VoiceRecordingState.chatId else 0L
    val isThisChatRecording = recordMode && recordingTargetChatId == chatId

    // 录音权限申请：RECORD_AUDIO 必须；POST_NOTIFICATIONS 仅 Android 13+ 用于前台通知
    val voicePermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val recordGranted = result[Manifest.permission.RECORD_AUDIO] == true
        if (recordGranted) {
            VoiceRecordingForegroundService.start(context)
            VoiceRecordingState.startRecording(context, chatId)
        } else {
            Log.w("Page1", "RECORD_AUDIO permission denied; cannot start voice recording")
        }
    }

    // 统一的“尝试启动录音”入口：先检查权限，没有则发起申请，拿到才会真正开录
    val tryStartVoiceRecording: () -> Unit = {
        val recordGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val needsNotifPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notifGranted = !needsNotifPermission || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (recordGranted && notifGranted) {
            VoiceRecordingForegroundService.start(context)
            VoiceRecordingState.startRecording(context, chatId)
        } else {
            val toRequest = buildList {
                if (!recordGranted) add(Manifest.permission.RECORD_AUDIO)
                if (needsNotifPermission && !notifGranted) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            voicePermissionLauncher.launch(toRequest)
        }
    }

    // 预览阶段使用的 ExoPlayer-lite —— 这里用 MediaPlayer 即可，避免引入额外播放器实例
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewPositionMs by remember { mutableLongStateOf(0L) }
    var previewDurationMs by remember { mutableLongStateOf(0L) }
    DisposableEffect(recordedVoiceFile) {
        // 预览文件变化（开始预览 / 取消 / 发送）时重置播放器
        previewPlayer?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        previewPlayer = null
        isPreviewPlaying = false
        previewPositionMs = 0L
        previewDurationMs = 0L
        val file = recordedVoiceFile
        if (file != null && isThisChatRecording && isVoicePreview) {
            try {
                previewPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    previewDurationMs = duration.toLong().coerceAtLeast(0L)
                    setOnCompletionListener {
                        isPreviewPlaying = false
                        previewPositionMs = 0L
                        try { seekTo(0) } catch (_: Throwable) {}
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                previewPlayer = null
            }
        }
        onDispose {
            previewPlayer?.let {
                try { it.stop() } catch (_: Throwable) {}
                it.release()
            }
            previewPlayer = null
        }
    }

    // 播放期间轮询当前播放位置
    LaunchedEffect(isPreviewPlaying, previewPlayer) {
        val player = previewPlayer
        if (isPreviewPlaying && player != null) {
            while (isPreviewPlaying) {
                try {
                    previewPositionMs = player.currentPosition.toLong().coerceAtLeast(0L)
                } catch (_: Throwable) {}
                kotlinx.coroutines.delay(50L.milliseconds)
            }
        }
    }

    // 录音/预览结束后清理本地播放状态
    LaunchedEffect(recordMode) {
        if (!recordMode) {
            previewPlayer?.let {
                try { it.stop() } catch (_: Throwable) {}
                it.release()
            }
            previewPlayer = null
            isPreviewPlaying = false
            previewPositionMs = 0L
            previewDurationMs = 0L
        }
    }

    DisposableEffect(Unit) {
        // 获取聊天草稿
        chatObject?.let { chat ->
            val draftMessage = chat.draftMessage?.inputMessageText
            if (draftMessage is TdApi.InputMessageText) {
                draftMessage.text?.let {
                    text = it.text
                }
            }
        }

        TgClient.subscribe(TdApi.UpdateChatDraftMessage::class.java, lifecycleOwner) { update ->
            if (chatId == update.chatId) {
                val draftMessage = update.draftMessage
                if (draftMessage == null) {
                    text = ""
                } else {
                    draftMessage.inputMessageText?.let { inputMessageText ->
                        if (inputMessageText is TdApi.InputMessageText) {
                            inputMessageText.text?.let {
                                text = it.text
                            }
                        }
                    }
                }
            }
        }

        onDispose {
            // 退出作用域时要执行的代码
            val draftMessage = TdApi.DraftMessage().apply {
                this.inputMessageText= TdApi.InputMessageText().apply {
                    this.text = TdApi.FormattedText(text, null)
                }
            }
            TgClient.send(TdApi.SetChatDraftMessage(chatId, null, draftMessage)) { result ->
                if (result.constructor == TdApi.Ok.CONSTRUCTOR) {
                    println("Setting draft message successfully, ChatId is $chatId")
                } else {
                    println("Failed to set draft message is $result")
                }
            }
        }
    }

    // 文本消息发送界面
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
            item {
                ListHeader {
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Send_message),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }

            // 回复消息显示
            replyMsg?.let {
                if (it.chatId == chatId || it.chatId == 0L || it.canBeRepliedInAnotherChat) {
                    item {
                        var replyMessage by remember { mutableStateOf<TdApi.Message?>(null) }
                        var replyLoaded by remember { mutableStateOf(false) }
                        val isSameChat = it.chatId == chatId || it.chatId == 0L

                        // 尝试从本地缓存获取，否则通过 TgClient 获取
                        LaunchedEffect(replyMsg) {
                            if (replyMsg != null) {
                                val targetChatId = if (replyMsg!!.chatId == 0L) chatId else replyMsg!!.chatId
                                // 先尝试从 ChatMessagesRepository 中获取
                                if (isSameChat) {
                                    val cached = chatMessagesById[replyMsg!!.messageId]
                                    if (cached != null) {
                                        replyMessage = cached
                                        replyLoaded = true
                                        return@LaunchedEffect
                                    }
                                }
                                // 否则通过网络获取
                                val msg = suspendCancellableCoroutine { cont ->
                                    TgClient.send(TdApi.GetMessage(targetChatId, replyMsg!!.messageId)) { res ->
                                        if (cont.isActive) { // 检查协程是否仍然活跃
                                            cont.resume(res as? TdApi.Message)
                                        }
                                    }
                                }
                                replyMessage = msg
                                replyLoaded = true
                            }
                        }

                        val replyPreviewText = remember(replyMessage) {
                            if (replyMessage != null) {
                                handleAllMessages(context, replyMessage, maxText = 48).text
                            } else null
                        }

                        TitleCard(
                            title = { Text(stringResource(R.string.Reply)) },
                            onClick = {
                                if (isSameChat && replyMessage != null) {
                                    // 打开回复消息的 MessageInfo
                                    val key = System.currentTimeMillis()
                                    appState.sharedMessageInfo[SharedMessageInfoKey(chatId, key)] =
                                        SharedMessageInfoData(listOf(replyMessage!!.id))
                                    navController.navigate(Destinations.messageInfo(chatId, key))
                                }
                            },
                            onLongClick = {
                                Config.replyMessage = null
                            }
                        ) {
                            Text(
                                text = if (replyLoaded) {
                                    replyPreviewText ?: stringResource(R.string.Message_deleted)
                                } else {
                                    "..."
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                if (isThisChatRecording) {
                    VoiceRecordingDisplay(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        isPaused = isVoicePaused,
                        isPreview = isVoicePreview,
                        durationMs = voiceDurationMs,
                        currentLevel = voiceCurrentLevel,
                        liveWaveform = voiceLiveWaveform,
                        finalWaveform = voiceFinalWaveform,
                        isPlayingPreview = isPreviewPlaying,
                        previewPositionMs = previewPositionMs,
                        previewTotalMs = if (previewDurationMs > 0L) previewDurationMs else voiceDurationMs,
                        onTogglePreview = {
                            val player = previewPlayer ?: return@VoiceRecordingDisplay
                            if (isPreviewPlaying) {
                                try { player.pause() } catch (_: Throwable) {}
                                isPreviewPlaying = false
                            } else {
                                try { player.start() } catch (_: Throwable) {}
                                isPreviewPlaying = true
                            }
                        }
                    )
                } else {
                    CustomTextInput(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        chatId = chatId,
                        text = text,
                        onTextChange = { text = it }
                    )
                }
            }

            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.transformedHeight(this, transformationSpec)
                ) {
                    if (isThisChatRecording) {
                        // 录音模式：暂停继续 / 停止 或 发送
                        ButtonGroup(Modifier.fillMaxWidth()) {
                            // 暂停 / 继续（仅录制阶段；预览阶段不展示）
                            if (isVoiceRecording) {
                                Button(
                                    onClick = {
                                        if (isVoicePaused) {
                                            VoiceRecordingState.resumeRecording()
                                        } else {
                                            VoiceRecordingState.pauseRecording()
                                        }
                                    },
                                    modifier = Modifier.animateWidth(interactionSource1),
                                    transformation = SurfaceTransformation(transformationSpec),
                                    interactionSource = interactionSource1,
                                ) {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (isVoicePaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                            modifier = Modifier.size(32.dp),
                                            contentDescription = if (isVoicePaused)
                                                stringResource(R.string.Voice_record_resume)
                                            else
                                                stringResource(R.string.Voice_record_pause)
                                        )
                                    }
                                }
                            }
                            // 停止 / 发送
                            Button(
                                onClick = {
                                    if (isVoiceRecording) {
                                        // 停止录制 → 进入预览
                                        val ok = VoiceRecordingState.stopAndPreview()
                                        if (!ok) {
                                            VoiceRecordingForegroundService.stop(context)
                                        }
                                    } else {
                                        // 发送语音消息
                                        val file = recordedVoiceFile ?: return@Button
                                        val durationSec = (voiceDurationMs / 1000L)
                                            .toInt().coerceAtLeast(1)
                                        val waveform = packWaveform5Bit(voiceFinalWaveform)
                                        val replyTo = replyMsg?.let { msg ->
                                            if (msg.chatId == chatId || msg.chatId == 0L) {
                                                TdApi.InputMessageReplyToMessage(
                                                    msg.messageId, msg.quote, 0, null
                                                )
                                            } else if (msg.canBeRepliedInAnotherChat) {
                                                TdApi.InputMessageReplyToExternalMessage(
                                                    msg.chatId, msg.messageId, msg.quote, 0, null
                                                )
                                            } else null
                                        }
                                        TgClient.send(
                                            TdApi.SendMessage(
                                                chatId,
                                                null,
                                                replyTo,
                                                null,
                                                null,
                                                TdApi.InputMessageVoiceNote(
                                                    TdApi.InputFileLocal(file.absolutePath),
                                                    durationSec,
                                                    waveform,
                                                    null,
                                                    null
                                                )
                                            )
                                        ) { result ->
                                            if (result is TdApi.Message) {
                                                Config.replyMessage = null
                                                VoiceRecordingState.consumeAfterSend()
                                                VoiceRecordingForegroundService.stop(context)
                                                coroutineScope.launch {
                                                    pagerState.scrollToPage(0)
                                                }
                                            } else {
                                                println("Failed to send voice message: $result")
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.animateWidth(interactionSource2),
                                transformation = SurfaceTransformation(transformationSpec),
                                interactionSource = interactionSource2,
                            ) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (isVoicePreview) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Stop,
                                        modifier = Modifier.size(32.dp),
                                        contentDescription = if (isVoicePreview)
                                            stringResource(R.string.Voice_record_send)
                                        else
                                            stringResource(R.string.Voice_record_stop)
                                    )
                                }
                            }
                        }
                    } else {
                    ButtonGroup(Modifier.fillMaxWidth()) {
                        // 按钮1 换行按钮
                        Button(
                            onClick = {
                                text += "\n"
                            },
                            modifier = Modifier.animateWidth(interactionSource1),
                            transformation = SurfaceTransformation(transformationSpec),
                            interactionSource = interactionSource1,
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.KeyboardReturn,
                                    modifier = Modifier.size(32.dp),
                                    contentDescription = "Enter"
                                )
                            }
                        }
                        // 按钮2 消息发送
                        Button(
                            onClick = {
                                // 定义回复部分
                                val replyTo = replyMsg?.let { msg ->
                                    if (msg.chatId == chatId || msg.chatId == 0L) {
                                        TdApi.InputMessageReplyToMessage(
                                            msg.messageId,
                                            msg.quote,
                                            0,
                                            null
                                        )
                                    } else {
                                        if (msg.canBeRepliedInAnotherChat) {
                                            TdApi.InputMessageReplyToExternalMessage(
                                                msg.chatId,
                                                msg.messageId,
                                                msg.quote,
                                                0,
                                                null
                                            )
                                        } else null
                                    }
                                }

                                val pendingMedia = mediaChose.toList()
                                val captionText = text
                                val onSent: () -> Unit = {
                                    text = ""
                                    Config.replyMessage = null
                                    mediaChose.clear()
                                    coroutineScope.launch {
                                        pagerState.scrollToPage(0)
                                    }
                                }

                                when {
                                    pendingMedia.isEmpty() -> {
                                        if (captionText.isBlank()) return@Button
                                        // 纯文本：保持原有行为
                                        TgClient.send(
                                            TdApi.SendMessage(
                                                chatId,
                                                null,
                                                replyTo,
                                                null,
                                                null,
                                                TdApi.InputMessageText().apply {
                                                    this.text = TdApi.FormattedText(captionText, null)
                                                    this.clearDraft = true
                                                }
                                            )
                                        ) {
                                            if (it is TdApi.Message) {
                                                println("Message sent successfully, ChatId is $chatId")
                                                onSent()
                                            } else {
                                                println("Failed to send message is $it")
                                            }
                                        }
                                    }
                                    else -> {
                                        // 媒体消息：URI → 本地路径 → InputMessagePhoto / InputMessageVideo
                                        coroutineScope.launch {
                                            val resolved = withContext(Dispatchers.IO) {
                                                pendingMedia.mapNotNull { item ->
                                                    val uri = runCatching { item.path.toUri() }.getOrNull()
                                                        ?: return@mapNotNull null
                                                    val path = resolveContentUriToFile(context, uri)
                                                        ?: return@mapNotNull null
                                                    item.type to path
                                                }
                                            }
                                            if (resolved.isEmpty()) {
                                                Log.w("Page1", "No usable media files after resolving URIs")
                                                return@launch
                                            }
                                            val caption = if (captionText.isBlank()) null
                                                else TdApi.FormattedText(captionText, null)

                                            if (resolved.size == 1) {
                                                val (type, path) = resolved[0]
                                                val content = buildMediaInputMessage(type, path, caption)
                                                TgClient.send(
                                                    TdApi.SendMessage(
                                                        chatId, null, replyTo, null, null, content
                                                    )
                                                ) { result ->
                                                    if (result is TdApi.Message) {
                                                        onSent()
                                                        // 清理草稿
                                                        TgClient.send(TdApi.SetChatDraftMessage(chatId, null, null))
                                                    } else {
                                                        println("Failed to send media message: $result")
                                                    }
                                                }
                                            } else {
                                                // 相册：caption 仅放在第一项；album 上限 10
                                                val capped = resolved.take(10)
                                                val contents = Array(capped.size) { idx ->
                                                    val (type, path) = capped[idx]
                                                    buildMediaInputMessage(
                                                        type = type,
                                                        path = path,
                                                        caption = if (idx == 0) caption else null,
                                                    )
                                                }
                                                TgClient.send(
                                                    TdApi.SendMessageAlbum(
                                                        chatId, null, replyTo, null, contents
                                                    )
                                                ) { result ->
                                                    if (result is TdApi.Messages) {
                                                        onSent()
                                                        // 清理草稿
                                                        TgClient.send(TdApi.SetChatDraftMessage(chatId, null, null))
                                                    } else {
                                                        println("Failed to send album: $result")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.animateWidth(interactionSource2),
                            transformation = SurfaceTransformation(transformationSpec),
                            interactionSource = interactionSource2,
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    modifier = Modifier.size(32.dp),
                                    contentDescription = "Send"
                                )
                            }
                        }
                    }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    // 电话
                    FilledIconButton(
                        onClick = {
                            TgCallManager.createCall(chatId)
                        },
                        modifier = Modifier.size(64.dp),
                        shapes = IconButtonDefaults.shapes(
                            shape = RoundedCornerShape(14.dp)
                        ),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF1D2B3A),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = "Call"
                        )
                    }

                    // 语音消息 / 录制中取消
                    FilledIconButton(
                        onClick = {
                            if (isThisChatRecording) {
                                // 录制/预览模式下点击取消
                                VoiceRecordingState.cancelAll()
                                VoiceRecordingForegroundService.stop(context)
                                return@FilledIconButton
                            }
                            tryStartVoiceRecording()
                        },
                        modifier = Modifier.size(64.dp),
                        shapes = IconButtonDefaults.shapes(
                            shape = RoundedCornerShape(14.dp)
                        ),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isThisChatRecording) Color(0xFFB00020) else Color(0xFF1D2B3A),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = if (isThisChatRecording) Icons.Rounded.Close else Icons.Rounded.KeyboardVoice,
                            contentDescription = if (isThisChatRecording)
                                stringResource(R.string.Voice_record_cancel)
                            else
                                stringResource(R.string.Voice_record_start)
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    // 图片选择
                    FilledIconButton(
                        onClick = {
                            val lastPickedImages = mediaChose.map { it.path.toUri() }
                            appState.mediaPickerRequest = MediaPickerRequest(
                                type = MediaPickerType.IMAGE_AND_VIDEO,
                                multiSelect = true,
                                maxCount = 10,
                                preselected = lastPickedImages,
                            ) { result ->
                                // 把选择结果写回 mediaChose
                                mediaChose.clear()
                                result.mapTo(mediaChose) { item ->
                                    MediaData(
                                        type = if (item.isVideo) "Video" else "Image",
                                        path = item.uri.toString(),
                                    )
                                }
                            }
                            navController.navigate(Destinations.MEDIA_PICKER)
                        },
                        modifier = Modifier.size(64.dp),
                        shapes = IconButtonDefaults.shapes(
                            shape = RoundedCornerShape(14.dp)
                        ),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF1D2B3A),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Photo,
                            contentDescription = "Photo"
                        )
                    }

                    // Emoji
                    FilledIconButton(
                        onClick = {
                            // TODO
                        },
                        modifier = Modifier.size(64.dp),
                        shapes = IconButtonDefaults.shapes(
                            shape = RoundedCornerShape(14.dp)
                        ),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF1D2B3A),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mood,
                            contentDescription = "Emoji"
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * 用 [type] 与本地文件路径构造 Telegram 媒体消息。
 *
 * - "Video" → [TdApi.InputMessageVideo]，启用 streaming，宽高/时长交给 TDLib 自行检测；
 * - 其它（默认 "Image"）→ [TdApi.InputMessagePhoto]。
 */
private fun buildMediaInputMessage(
    type: String,
    path: String,
    caption: TdApi.FormattedText?,
): TdApi.InputMessageContent {
    val inputFile = TdApi.InputFileLocal(path)
    return if (type == "Video") {
        TdApi.InputMessageVideo().apply {
            this.video = inputFile
            this.supportsStreaming = true
            this.caption = caption
        }
    } else {
        TdApi.InputMessagePhoto().apply {
            this.photo = inputFile
            this.caption = caption
        }
    }
}

/**
 * 把 MediaStore 的 content:// URI 解析成本地绝对路径。
 *
 * 1. 优先读 `MediaStore.MediaColumns.DATA`，命中真实文件则直接用；
 * 2. 否则把流拷贝到 cacheDir/picker_share 下临时文件返回（TDLib 需要本地路径）。
 *
 * 必须在 IO 调度器中调用。返回 null 表示无法访问。
 */
private fun resolveContentUriToFile(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path

    val resolver = context.contentResolver
    runCatching {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx >= 0) {
                    val data = cursor.getString(idx)
                    if (!data.isNullOrBlank() && File(data).exists()) {
                        return data
                    }
                }
            }
        }
    }

    // Fallback：读流到 cache
    return runCatching {
        val mime = resolver.getType(uri)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime ?: "")
            ?: queryDisplayName(resolver, uri)?.substringAfterLast('.', "")
            ?: ""
        val baseDir = File(context.cacheDir, "picker_share").apply { mkdirs() }
        val name = buildString {
            append("media_")
            append(System.currentTimeMillis())
            append('_')
            append(uri.lastPathSegment?.filter { it.isLetterOrDigit() } ?: "tmp")
            if (ext.isNotEmpty()) append('.').append(ext)
        }
        val target = File(baseDir, name)
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        target.absolutePath
    }.getOrNull()
}

private fun queryDisplayName(
    resolver: android.content.ContentResolver,
    uri: Uri,
): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else null
    }
}.getOrNull()

