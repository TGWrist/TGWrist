package com.tgwrist.app.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.tgwrist.app.data.MediaPickerRequest
import com.tgwrist.app.data.MediaPickerType
import com.tgwrist.app.utils.LocalGlobalAppState

// 文件级 state：跨 composition 销毁、跨 navigation 都能存活（只在进程被杀时丢失）。
// 用于演示 MediaPicker 的 preselected：上次多选的结果，下次进入页面时默认勾选。
private var lastPickedImages by mutableStateOf<List<Uri>>(emptyList())
private var lastPickedMixed by mutableStateOf<List<Uri>>(emptyList())

@Composable
fun TestScreen() {
    // 进入方法：关于页面点击6次软件图标，再长按软件图标

    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val context = LocalContext.current
    val appState = LocalGlobalAppState.current
    val navController = appState.navController

    var selected1 by remember { mutableStateOf(false) }
    var selected2 by remember { mutableStateOf(false) }
    var selected3 by remember { mutableStateOf(false) }

    val selecting = selected1 || selected2 || selected3

    AppScaffold(timeText = { StatusTimeText() }) {
        ScreenScaffold(
            overscrollEffect = overscroll,
            scrollState = listState,
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
                    Text(
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        text = "Test Page",
                        color = Color(0xFFAAAAAA),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }

                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        onClick = {
                            if (selecting) {
                                selected1 = !selected1
                            } else {
                                // 普通点击逻辑
                            }
                        },
                        onLongClick = {
                            selected1 = true
                        },
                        onLongClickLabel = "Select",
                        appName = {
                            Text("TG Wrist")
                        },
                        title = {
                            Text("Test title 1")
                        },
                        content = {
                            Text("Long press this card to select it.")
                        },
                        appImage = {
                            // 依然保留这个 Box 作为“定海神针”，锁死布局尺寸
                            AnimatedVisibility(
                                visible = selected1,
                                enter = fadeIn(animationSpec = spring()),
                                exit = fadeOut(animationSpec = spring()),
                                label = "avatar_selection_animation"
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Selected",
                                    modifier = Modifier.size(CardDefaults.AppImageSize)
                                )
                            }
                        },
                        time = {
                            Text("12:30")
                        },
                        colors = if (selected1) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                appNameColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                timeColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            CardDefaults.cardColors()
                        },
                        border = if (selected1) {
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            null
                        }
                    )
                }

                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        onClick = {
                            if (selecting) {
                                selected2 = !selected2
                            } else {
                                // 普通点击逻辑
                            }
                        },
                        onLongClick = {
                            selected2 = true
                        },
                        onLongClickLabel = "Select",
                        appName = {
                            Text("TG Wrist")
                        },
                        title = {
                            Text("Test title 2")
                        },
                        content = {
                            Text("When selecting, tap this card to toggle it.")
                        },
                        appImage = {
                            AnimatedContent(
                                targetState = selected2,
                                transitionSpec = {
                                    fadeIn(animationSpec = spring()) togetherWith
                                            fadeOut(animationSpec = spring())
                                },
                                label = "avatar_selection_animation"
                            ) { selected ->
                                Icon(
                                    imageVector = if (selected) {
                                        Icons.Rounded.CheckCircle
                                    } else {
                                        Icons.AutoMirrored.Rounded.Chat
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(CardDefaults.AppImageSize)
                                )
                            }
                        },
                        time = {
                            Text("12:31")
                        },
                        colors = if (selected2) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                appNameColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                timeColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            CardDefaults.cardColors()
                        },
                        border = if (selected2) {
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            null
                        }
                    )
                }

                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        onClick = {
                            if (selecting) {
                                selected3 = !selected3
                            } else {
                                // 普通点击逻辑
                            }
                        },
                        onLongClick = {
                            selected3 = true
                        },
                        onLongClickLabel = "Select",
                        appName = {
                            Text("TG Wrist")
                        },
                        title = {
                            Text("Test title 3")
                        },
                        content = {
                            Text("If all cards are unselected, selection mode exits automatically.")
                        },
                        appImage = {
                            AnimatedContent(
                                targetState = selected3,
                                transitionSpec = {
                                    fadeIn(animationSpec = spring()) togetherWith
                                            fadeOut(animationSpec = spring())
                                },
                                label = "avatar_selection_animation"
                            ) { selected ->
                                Icon(
                                    imageVector = if (selected) {
                                        Icons.Rounded.CheckCircle
                                    } else {
                                        Icons.AutoMirrored.Rounded.Chat
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(CardDefaults.AppImageSize)
                                )
                            }
                        },
                        time = {
                            Text("12:32")
                        },
                        colors = if (selected3) {
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                appNameColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                timeColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            CardDefaults.cardColors()
                        },
                        border = if (selected3) {
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            null
                        }
                    )
                }

                item {
                    CompactButton(
                        onClick = { /* Do something */ },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = "Favorite icon",
                                modifier = Modifier.size(ButtonDefaults.ExtraSmallIconSize),
                            )

                        },
                        modifier = Modifier,
                    ) {
                        Text("Compact Button", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                item {
                    ChildButton(
                        onClick = { /* Do something */ },
                        label = { Text("Child Button") },
                        secondaryLabel = { Text("Secondary label") },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = "Favorite icon",
                                modifier = Modifier.size(ButtonDefaults.ExtraSmallIconSize),
                            )
                        },
                        modifier = Modifier,
                    )
                }

                item {
                    TextButton(
                        onClick = { /* Do something for onClick*/ },
                        onLongClick = { /* Do something for onLongClick*/ },
                        onLongClickLabel = "Long click",
                    ) {
                        Text(text = "ABC")
                    }
                }

                item {
                    FilledIconButton(
                        onClick = {

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
                            imageVector = Icons.Rounded.MicOff,
                            contentDescription = "MicOff"
                        )
                    }
                }

                item {
                    ImagePickerScreen()
                }

                // ===== MediaPicker 调用示例 =====
                item {
                    // 单选图片
                    Button(onClick = {
                        appState.mediaPickerRequest = MediaPickerRequest(
                            type = MediaPickerType.IMAGE_ONLY,
                            multiSelect = false,
                        ) { result ->
                            val uri = result.firstOrNull()?.uri

                            android.widget.Toast.makeText(
                                context,
                                "Picked image: $uri",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        navController?.navigate(Destinations.MEDIA_PICKER)
                    }) {
                        Text("Pick 1 image")
                    }
                }

                item {
                    // 多选图片（最多 9 张），第二次打开会带上次选择的结果作为默认勾选
                    Button(onClick = {
                        appState.mediaPickerRequest = MediaPickerRequest(
                            type = MediaPickerType.IMAGE_ONLY,
                            multiSelect = true,
                            maxCount = 9,
                            preselected = lastPickedImages,
                        ) { result ->
                            lastPickedImages = result.map { it.uri }
                            android.widget.Toast.makeText(
                                context,
                                "Picked ${result.size} images",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        navController?.navigate(Destinations.MEDIA_PICKER)
                    }) {
                        Text("Pick N images")
                    }
                }

                item {
                    // 单选视频
                    Button(onClick = {
                        appState.mediaPickerRequest = MediaPickerRequest(
                            type = MediaPickerType.VIDEO_ONLY,
                            multiSelect = false,
                        ) { result ->
                            val item = result.firstOrNull()
                            android.widget.Toast.makeText(
                                context,
                                "Picked video: ${item?.uri} (${item?.durationMs}ms)",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        navController?.navigate(Destinations.MEDIA_PICKER)
                    }) {
                        Text("Pick 1 video")
                    }
                }

                item {
                    // 多选图片 + 视频（不限数量），同样演示 preselected
                    Button(onClick = {
                        appState.mediaPickerRequest = MediaPickerRequest(
                            type = MediaPickerType.IMAGE_AND_VIDEO,
                            multiSelect = true,
                            maxCount = 0,
                            preselected = lastPickedMixed,
                        ) { result ->
                            lastPickedMixed = result.map { it.uri }
                            val imgs = result.count { !it.isVideo }
                            val vids = result.count { it.isVideo }
                            android.widget.Toast.makeText(
                                context,
                                "Picked $imgs images, $vids videos",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        navController?.navigate(Destinations.MEDIA_PICKER)
                    }) {
                        Text("Pick mixed media")
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun ImagePickerScreen() {
    // 注册 Photo Picker 的 ActivityResultLauncher
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            println("成功获取图片 URI: $uri")
            // 在此处将 URI 转换为 Bitmap 或直接加载（例如使用 Coil）
        } else {
            println("用户未选择任何媒体")
        }
    }

    Button(onClick = {
        try {
            // 启动选择器，并限制只选择图片
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: ActivityNotFoundException) {
            // 必须进行异常捕获：处理部分 Wear OS 设备被阉割了文件系统的情况
            println( "当前设备不支持图片选择: $e")
        }
    }) {
        Text("选择相册图片")
    }
}
