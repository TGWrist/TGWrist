package com.tgwrist.app.ui.message.info.message.renderer

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
import com.tgwrist.app.TGWrist
import com.tgwrist.app.data.MediaPickerRequest
import com.tgwrist.app.data.MediaPickerType
import com.tgwrist.app.runtime.ChatMessagesRepository
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.chat.buildMediaInputMessage
import com.tgwrist.app.ui.chat.resolveContentUriToFile
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.EditMessageMediaButton
import com.tgwrist.app.ui.message.info.EditMessageTextButton
import com.tgwrist.app.ui.message.info.ForwardMessageButton
import com.tgwrist.app.ui.message.info.MessageTextView
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import com.tgwrist.app.ui.message.info.TranslationButton
import com.tgwrist.app.ui.message.info.message.EditTextModelView
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.setClipboardText
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
fun DocumentMessageRenderer(
    content: TdApi.MessageDocument,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = messageRenderContext.navController
    val coroutineScope = rememberCoroutineScope()
    val appState = LocalGlobalAppState.current

    val isEditing = remember { mutableStateOf(false) }
    val editText = remember { mutableStateOf("") }

    val document = content.document
    val fileName = remember(document) { document.fileName.ifBlank { "unknown" } }
    val mimeType = remember(document) { document.mimeType.ifBlank { "application/octet-stream" } }
    val fileSize = remember(document) { document.document.size }
    val fileSizeText = remember(fileSize) { formatFileSize(fileSize) }

    val caption = remember(content.caption) { content.caption }
    var translateCaption by remember { mutableStateOf<TdApi.FormattedText?>(null) }

    // 字符串变量
    val copiedClipboard = stringResource(R.string.Copied_clipboard)
    val strFileSavedToPath = stringResource(R.string.file_saved_to_path)
    val strFileSaveFailed = stringResource(R.string.file_save_failed)
    val strNoAppToOpen = stringResource(R.string.no_app_to_open)

    // 文件状态
    var fileId by remember { mutableIntStateOf(document.document.id) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var fileLocalPath by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }

    // 保存状态
    var isSaved by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // 保存成功弹窗
    var savedFilePath by remember { mutableStateOf("") }

    // ========== 页面打开时，先用消息自带信息初始化，再用 GetFile 刷新 ==========
    LaunchedEffect(Unit) {
        val initialFile = document.document
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

        // 异步通过 GetFile 获取最新文件状态
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
                    // 文件既没下载完也没在下载中，重置状态
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

                // 文件开始下载 → 切换到进度条
                if (update.file.local.isDownloadingActive) {
                    isDownloading = true
                }

                // 文件下载完成 → 切换到保存按钮
                if (update.file.local.isDownloadingCompleted) {
                    fileLocalPath = update.file.local.path
                    isDownloading = false
                    downloadProgress = 1f
                }
            }
        }
    }

    // ========== 开始下载文件 ==========
    fun startDownload() {
        isDownloading = true
        TgClient.send(
            TdApi.DownloadFile(fileId, 28, 0, 0, false)
        ) { result ->
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

    // ========== 取消下载文件 ==========
    fun cancelDownload() {
        TgClient.send(TdApi.CancelDownloadFile(fileId, false)) { result ->
            if (result is TdApi.Ok) {
                isDownloading = false
                downloadProgress = 0f
            }
        }
    }

    // ========== 保存到下载目录 ==========
    fun saveToDownloads() {
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
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, mimeType)
                            put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS + "/TGWrist"
                            )
                        }
                        val uri = resolver.insert(
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            contentValues
                        )
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { outputStream ->
                                sourceFile.inputStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            "${Environment.DIRECTORY_DOWNLOADS}/TGWrist/$fileName"
                        } else {
                            null
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val downloadsDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "TGWrist"
                        )
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val destFile = File(downloadsDir, fileName)
                        sourceFile.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (destFile.exists()) destFile.absolutePath else null
                    }
                }

                // 回到主线程更新 UI
                if (resultPath != null) {
                    isSaved = true
                    savedFilePath = resultPath
                    Toast.makeText(context, strFileSavedToPath.format(savedFilePath), Toast.LENGTH_SHORT).show()
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

    // ========== 用外部应用打开文件 ==========
    fun openFile() {
        if (fileLocalPath.isBlank()) return
        try {
            val file = File(fileLocalPath)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val resolvedMimeType = mimeType.let {
                if (it.isBlank() || it == "application/octet-stream") "*/*" else it
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Fallback with */*
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
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
            // ========== 文件图标 + 文件名 ==========
            item(key = "file_icon") {
                ListHeader(
                    contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() * 0.2f, bottom = 4.dp, end = contentPadding.calculateEndPadding(
                        LayoutDirection.Ltr), start = contentPadding.calculateStartPadding(LayoutDirection.Rtl)),
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = "File",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // ========== 说明文字 ==========
            if (isEditing.value) {
                item(key = "edit_text") {
                    EditTextModelView(editText, isEditing, messageRenderContext)
                }
            } else {
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
                            progress = downloadProgress,
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
            if (fileLocalPath.isBlank() && !isDownloading) {
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

            // ========== 编辑按钮 ==========
            if (messageRenderContext.properties?.canBeEdited == true) {
                item(key = "edit_text_button") {
                    EditMessageTextButton(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        surfaceTransformation = SurfaceTransformation(transformationSpec),
                        properties = messageRenderContext.properties,
                        isEditing = isEditing,
                        onClick = {
                            if (isEditing.value) {
                                isEditing.value = false
                                editText.value = ""
                            } else {
                                editText.value = caption?.text ?: ""
                                isEditing.value = true
                            }
                        }
                    )
                }
            }

            // ========== 替换消息资源 ==========
            if (messageRenderContext.properties?.canEditMedia == true) {
                item(key = "edit_media_button") {
                    EditMessageMediaButton(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        surfaceTransformation = SurfaceTransformation(transformationSpec),
                        properties = messageRenderContext.properties,
                        onClick = {
                            appState.mediaPickerRequest = MediaPickerRequest(
                                type = MediaPickerType.IMAGE_AND_VIDEO,
                                multiSelect = false,
                            ) { result ->
                                val item = result.firstOrNull() ?: return@MediaPickerRequest
                                // 使用应用级作用域：回调触发时本 Composable 可能已离开组合，
                                // coroutineScope（rememberCoroutineScope）已取消，launch 内代码不会执行
                                TGWrist.applicationScope.launch {
                                    val path = withContext(Dispatchers.IO) {
                                        resolveContentUriToFile(context, item.uri)
                                    }
                                    if (path == null) {
                                        Toast.makeText(context, strFileSaveFailed, Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    // 保留原有图片说明文字
                                    val caption = content.caption.takeIf { !it.text.isNullOrBlank() }
                                    val newContent = buildMediaInputMessage(
                                        type = if (item.isVideo) "Video" else "Image",
                                        path = path,
                                        caption = caption,
                                    )
                                    TgClient.send(
                                        TdApi.EditMessageMedia(
                                            messageRenderContext.chatId,
                                            messageRenderContext.messageId,
                                            null,
                                            newContent
                                        )
                                    ) { editResult ->
                                        if (editResult is TdApi.Message) {
                                            ChatMessagesRepository.publicAddOrReplaceMessage(editResult)
                                        } else {
                                            println("Failed to edit message media: $editResult")
                                        }
                                    }
                                }
                            }
                            navController.navigate(Destinations.MEDIA_PICKER)
                        }
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

            // ========== 用外部应用打开文件 ==========
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

            // ========== 保存到下载目录按钮 ==========
            if (fileLocalPath.isNotBlank() && !isDownloading) {
                item(key = "save_button") {
                    FilledTonalButton(
                        onClick = { saveToDownloads() },
                        enabled = !isSaving && !isSaved,
                        label = {
                            Text(
                                text = if (isSaved) stringResource(R.string.file_saved)
                                else stringResource(R.string.save_to_downloads),
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

            // 转发按钮
            item {
                ForwardMessageButton(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    surfaceTransformation = SurfaceTransformation(transformationSpec),
                    properties = messageRenderContext.properties,
                    message = messageRenderContext.message
                )
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

            // ========== 文件大小 ==========
            item(key = "file_size") {
                TitleCard(
                    title = { Text(stringResource(R.string.file_size)) },
                    onClick = { /* Not do something */ },
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
                    onClick = { /* Not do something */ },
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
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
