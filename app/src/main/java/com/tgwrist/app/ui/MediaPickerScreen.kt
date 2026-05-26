package com.tgwrist.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tgwrist.app.R
import com.tgwrist.app.data.MediaPickerItem
import com.tgwrist.app.data.MediaPickerType
import com.tgwrist.app.utils.LocalGlobalAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GRID_COLUMNS = 2

@Composable
fun MediaPickerScreen() {
    val appState = LocalGlobalAppState.current
    val navController = appState.navController
    val request = appState.mediaPickerRequest
    val context = LocalContext.current

    // 没有请求体（被刷新或直接打开）：直接退出
    if (request == null) {
        LaunchedEffect(Unit) { navController?.popBackStack() }
        return
    }

    var loading by remember { mutableStateOf(true) }
    val items = remember { mutableStateListOf<MediaPickerItem>() }
    // 多选模式下用 preselected 预置已选项；单选模式忽略
    val selected = remember {
        mutableStateListOf<Uri>().apply {
            if (request.multiSelect && request.preselected.isNotEmpty()) {
                val unique = request.preselected.distinct()
                val capped = if (request.maxCount > 0) unique.take(request.maxCount) else unique
                addAll(capped)
            }
        }
    }
    var maxReachedHint by remember { mutableStateOf(false) }

    // 权限处理
    val requiredPermissions = remember(request.type) {
        requiredPermissionsFor(request.type)
    }
    var permissionGranted by remember(requiredPermissions) {
        mutableStateOf(hasAllPermissions(context, requiredPermissions))
    }
    val permissionDeniedToast = stringResource(R.string.media_picker_permission_denied)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 注意：API < 33 上 READ_MEDIA_* 权限恒为 false，需要兜底再 check 一次实际状态
        val granted = requiredPermissions.all { perm ->
            result[perm] == true ||
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            permissionGranted = true
        } else {
            Toast.makeText(context, permissionDeniedToast, Toast.LENGTH_SHORT).show()
            appState.mediaPickerRequest = null
            request.onResult(emptyList())
            navController?.popBackStack()
        }
    }

    // 进入页面时如果没权限，立刻申请
    LaunchedEffect(requiredPermissions) {
        if (!permissionGranted && requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // 加载媒体库（仅在已获权限时）
    LaunchedEffect(request.type, permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        loading = true
        val result = withContext(Dispatchers.IO) {
            queryMedia(context, request.type)
        }
        items.clear()
        items.addAll(result)
        // 预选项中已经不在媒体库的条目剔除掉，避免计数对不上
        if (selected.isNotEmpty()) {
            val available = result.mapTo(HashSet()) { it.uri }
            selected.retainAll(available)
        }
        loading = false
    }

    val titleRes = when (request.type) {
        MediaPickerType.IMAGE_ONLY -> R.string.media_picker_title_image
        MediaPickerType.VIDEO_ONLY -> R.string.media_picker_title_video
        MediaPickerType.IMAGE_AND_VIDEO -> R.string.media_picker_title_media
    }

    val finishWith: (List<MediaPickerItem>) -> Unit = { result ->
        // 先回调，再清空请求并退出，避免回调里再次跳转时被自动 pop 干扰
        request.onResult(result)
        appState.mediaPickerRequest = null
    }

    val toggle: (MediaPickerItem) -> Unit = { item ->
        if (request.multiSelect) {
            val exists = selected.remove(item.uri)
            if (!exists) {
                if (request.maxCount > 0 && selected.size >= request.maxCount) {
                    maxReachedHint = true
                } else {
                    selected.add(item.uri)
                    maxReachedHint = false
                }
            }
        } else {
            finishWith(listOf(item))
        }
    }

    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()

    AppScaffold(timeText = { StatusTimeText() }) {
        ScreenScaffold(
            overscrollEffect = overscroll,
            scrollState = listState,
            modifier = Modifier.fillMaxSize(),
        ) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                overscrollEffect = overscroll,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )
                }

                if (request.multiSelect) {
                    item {
                        val hint = if (maxReachedHint && request.maxCount > 0) {
                            stringResource(R.string.media_picker_max_reached, request.maxCount)
                        } else {
                            stringResource(R.string.media_picker_selected_count, selected.size)
                        }
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (maxReachedHint) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        )
                    }
                }

                if (request.multiSelect) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                val out = items.filter { it.uri in selected }
                                finishWith(out)
                            },
                            enabled = true,
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.SmallIconSize),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.media_picker_done))
                        }
                    }
                    item {}
                }

                if (loading) {
                    item {
                        LoadingPlaceholder()
                    }
                } else if (items.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.media_picker_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    }
                } else {
                    val rows = (items.size + GRID_COLUMNS - 1) / GRID_COLUMNS
                    items(rows) { rowIndex ->
                        MediaRow(
                            items = items,
                            rowIndex = rowIndex,
                            selected = selected,
                            onClick = toggle,
                        )
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// PLACEHOLDER_HELPERS

@Composable
private fun MediaRow(
    items: List<MediaPickerItem>,
    rowIndex: Int,
    selected: List<Uri>,
    onClick: (MediaPickerItem) -> Unit,
) {
    val start = rowIndex * GRID_COLUMNS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (col in 0 until GRID_COLUMNS) {
            val idx = start + col
            if (idx < items.size) {
                val item = items[idx]
                MediaCell(
                    item = item,
                    selected = item.uri in selected,
                    onClick = { onClick(item) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MediaCell(
    item: MediaPickerItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        if (item.isVideo) {
            VideoThumbnail(
                uri = item.uri,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.uri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (item.isVideo) {
            // 左下角播放图标 + 时长
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp),
                )
                if (item.durationMs > 0) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = formatDuration(item.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            loadVideoFrame(context, uri)
        }
    }
    Box(modifier = modifier.background(Color(0xFF111111))) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun loadVideoFrame(context: Context, uri: Uri): Bitmap? {
    // 优先用系统缩略图 API（API 29+），命中 MediaStore 缓存，速度快、兼容性好
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            return context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        }
    }
    // 兜底：用 MediaMetadataRetriever 抽帧。覆盖 API 26-28 以及系统缩略图缺失的情况
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
private fun LoadingPlaceholder() {
    Text(
        text = "…",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFFAAAAAA),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

private fun requiredPermissionsFor(type: MediaPickerType): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // API 33+：按媒体类型分桶
        when (type) {
            MediaPickerType.IMAGE_ONLY -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            MediaPickerType.VIDEO_ONLY -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            MediaPickerType.IMAGE_AND_VIDEO -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        }
    } else {
        // API <= 32：统一一个旧权限
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasAllPermissions(context: Context, permissions: Array<String>): Boolean {
    return permissions.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}

private fun queryMedia(context: Context, type: MediaPickerType): List<MediaPickerItem> {
    return when (type) {
        MediaPickerType.IMAGE_ONLY -> queryImages(context)
        MediaPickerType.VIDEO_ONLY -> queryVideos(context)
        MediaPickerType.IMAGE_AND_VIDEO -> queryImages(context) + queryVideos(context)
    }.sortedByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }
}

private fun queryImages(context: Context): List<MediaPickerItem> {
    val result = mutableListOf<MediaPickerItem>()
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
    )
    val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    runCatching {
        context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                result.add(MediaPickerItem(uri = uri, isVideo = false))
            }
        }
    }
    return result
}

private fun queryVideos(context: Context): List<MediaPickerItem> {
    val result = mutableListOf<MediaPickerItem>()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DURATION,
    )
    val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
    runCatching {
        context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val duration = if (durCol >= 0) cursor.getLong(durCol) else 0L
                result.add(MediaPickerItem(uri = uri, isVideo = true, durationMs = duration))
            }
        }
    }
    return result
}
