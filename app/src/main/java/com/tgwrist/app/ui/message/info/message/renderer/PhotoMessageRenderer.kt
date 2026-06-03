package com.tgwrist.app.ui.message.info.message.renderer

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.tgwrist.app.R
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.ui.message.info.DeleteMessageButton
import com.tgwrist.app.ui.message.info.MessageTextView
import com.tgwrist.app.ui.message.info.TranslationButton
import com.tgwrist.app.ui.message.info.message.factory.MessageRenderContext
import com.tgwrist.app.runtime.TgClient
import com.tgwrist.app.ui.message.info.ForwardMessageButton
import com.tgwrist.app.ui.message.info.ReplyMessageButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

@Composable
fun PhotoMessageRenderer(
    content: TdApi.MessagePhoto,
    messageRenderContext: MessageRenderContext,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = messageRenderContext.navController
    val coroutineScope = rememberCoroutineScope()

    val showText = remember(content.caption) { content.caption }
    var translatedText by remember { mutableStateOf<TdApi.FormattedText?>(null) }

    // 字符串
    val strSavedToMediaLibrary = stringResource(R.string.saved_to_media_library)
    val strFileSaveFailed = stringResource(R.string.file_save_failed)

    // 缩略图下载
    var thumbnailDownloadProgress by remember { mutableFloatStateOf(0f) }
    val thumbnail = remember(content.photo.sizes) {
        content.photo.sizes.minByOrNull { it.width * it.height }
    }
    var thumbnailFileUrl by remember { mutableStateOf("") }

    LaunchedEffect(thumbnail?.photo?.id) {
        thumbnail?.let {
            if (!thumbnail.photo.local.isDownloadingCompleted) {
                TgClient.send(TdApi.DownloadFile(thumbnail.photo.id, 30, 0, 0, false)) { result ->
                    if (result is TdApi.File) {
                        val downloadedSize = result.local.downloadedSize.toFloat()
                        thumbnailDownloadProgress =
                            (downloadedSize / (thumbnail.photo.size.takeIf { it > 0 } ?: 1)).coerceIn(0f, 1f)
                        if (result.local.isDownloadingCompleted) thumbnailFileUrl = result.local.path
                    }
                }
            } else {
                thumbnailFileUrl = thumbnail.photo.local.path
            }
        }
    }

    // 原图下载
    var photoDownloadProgress by remember { mutableFloatStateOf(0f) }
    val photo = remember(content.photo.sizes) {
        content.photo.sizes.maxByOrNull { it.width * it.height }
    }
    var photoFileUrl by remember { mutableStateOf("") }

    LaunchedEffect(photo?.photo?.id) {
        photo?.let {
            if (!photo.photo.local.isDownloadingCompleted) {
                TgClient.send(TdApi.DownloadFile(photo.photo.id, 28, 0, 0, false)) { result ->
                    if (result is TdApi.File) {
                        val downloadedSize = result.local.downloadedSize.toFloat()
                        photoDownloadProgress =
                            (downloadedSize / (photo.photo.size.takeIf { it > 0 } ?: 1)).coerceIn(0f, 1f)
                        if (result.local.isDownloadingCompleted) photoFileUrl = result.local.path
                    }
                }
            } else {
                photoFileUrl = photo.photo.local.path
            }
        }
    }

    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateFile::class.java, lifecycleOwner) { update ->
            when (update.file.id) {
                thumbnail?.photo?.id -> {
                    val downloadedSize = update.file.local.downloadedSize.toFloat()
                    thumbnailDownloadProgress =
                        (downloadedSize / (thumbnail.photo.size.takeIf { it > 0 } ?: 1)).coerceIn(0f, 1f)
                    if (update.file.local.isDownloadingCompleted) thumbnailFileUrl = update.file.local.path
                }
                photo?.photo?.id -> {
                    val downloadedSize = update.file.local.downloadedSize.toFloat()
                    photoDownloadProgress =
                        (downloadedSize / (photo.photo.size.takeIf { it > 0 } ?: 1)).coerceIn(0f, 1f)
                    if (update.file.local.isDownloadingCompleted) photoFileUrl = update.file.local.path
                }
            }
        }
    }

    // 保存到媒体库状态
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }

    // 检查相册中是否已存在该图片
    LaunchedEffect(photoFileUrl) {
        if (photoFileUrl.isBlank()) return@LaunchedEffect
        val fileName = File(photoFileUrl).name
        if (fileName.isBlank()) return@LaunchedEffect
        val alreadyExists = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf(fileName, "%TGWrist%")
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, selectionArgs, null
                )?.use { it.count > 0 } ?: false
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "TGWrist"
                )
                File(picturesDir, fileName).exists()
            }
        }
        if (alreadyExists) isSaved = true
    }

    // ========== 保存到系统媒体库 ==========
    fun saveToMediaLibrary() {
        if (photoFileUrl.isBlank() || isSaving) return
        isSaving = true
        coroutineScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    val sourceFile = File(photoFileUrl)
                    if (!sourceFile.exists()) return@withContext false

                    val fileName = sourceFile.name.ifBlank { "photo_${System.currentTimeMillis()}.jpg" }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = context.contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TGWrist")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                            ?: return@withContext false
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            sourceFile.inputStream().use { it.copyTo(outputStream) }
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                        true
                    } else {
                        @Suppress("DEPRECATION")
                        val picturesDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "TGWrist"
                        )
                        if (!picturesDir.exists()) picturesDir.mkdirs()
                        val destFile = File(picturesDir, fileName)
                        sourceFile.inputStream().use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
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

    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val isScrollable = scrollState.maxValue > 0
    ScreenScaffold(
        scrollState = scrollState,
        scrollIndicator = {
            if (isScrollable) {
                ScrollIndicator(state = scrollState)
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .requestFocusOnHierarchyActive()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = scrollState), focusRequester)
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 20.dp)
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 显示图片
            if (photoFileUrl.isBlank()) {
                if (thumbnailFileUrl.isBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(progress = thumbnailDownloadProgress)
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(thumbnailFileUrl)
                                    .size(Size.ORIGINAL)
                                    .build()
                            ),
                            contentDescription = "Message_IMG",
                            modifier = Modifier.aspectRatio(1f)
                        )
                        CircularProgressIndicator(progress = photoDownloadProgress)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            navController.navigate(Destinations.imgView(photoFileUrl))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(photoFileUrl)
                                .size(Size.ORIGINAL)
                                .build()
                        ),
                        contentDescription = "Message_IMG",
                        modifier = Modifier.aspectRatio(1f)
                    )
                }
            }

            // 显示文字
            if (!showText?.text.isNullOrBlank()) {
                MessageTextView(
                    text = showText.text,
                    entities = showText.entities,
                    modifier = Modifier,
                    navController = navController,
                )

                translatedText?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.Translation_results),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    MessageTextView(
                        text = it.text,
                        entities = it.entities,
                        modifier = Modifier,
                        navController = navController,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                TranslationButton(
                    text = showText,
                    onDone = {
                        translatedText = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 保存到媒体库按钮
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
                modifier = Modifier.fillMaxWidth()
            )

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

            // 删除消息按钮
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
