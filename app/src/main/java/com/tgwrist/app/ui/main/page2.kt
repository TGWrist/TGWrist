package com.tgwrist.app.ui.main

import android.widget.Toast
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
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
import com.tgwrist.app.ui.Destinations
import com.tgwrist.app.runtime.Config
import com.tgwrist.app.runtime.Config.connectionState
import com.tgwrist.app.ui.ThumbnailChatPhoto
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.setClipboardText

@Composable
internal fun Page2() {
    // 获取context
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val density = LocalDensity.current
    val appState = LocalGlobalAppState.current
    val navController = appState.navController

    val connectionState by connectionState.collectAsState()
    // 存 StringRes ID，适配多语言
    val statusResId = when (connectionState) {
        Config.ConnectionState.Ready -> R.string.Online
        Config.ConnectionState.Connecting -> R.string.Connecting
        Config.ConnectionState.ConnectingToProxy -> R.string.Connecting_proxy
        Config.ConnectionState.Updating -> R.string.Update
        Config.ConnectionState.WaitingForNetwork -> R.string.Offline
        else -> R.string.Offline
    }
    val connectState = stringResource(id = statusResId)

    // 订阅当前用户信息
    val currentUser by Config.currentUser.collectAsState()
    val currentUserFullInfo by Config.currentUserFullInfo.collectAsState()

    // 字符串变量
    val copiedClipboard = stringResource(id = R.string.Copied_clipboard)

    ScreenScaffold(
        scrollState = listState,
        overscrollEffect = overscroll,
        edgeButton = {
            EdgeButton(
                onClick = {
                    navController?.navigate(Destinations.settings(0))
                },
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDDFFD)),
                modifier =
                    // 如果用户开始从EdgeButton滚动
                    Modifier.scrollable(
                        listState,
                        orientation = Orientation.Vertical,
                        reverseDirection = true,
                        // 应对EdgeButton应用超滚动效果以适当调整滚动行为
                        overscrollEffect = overscroll,
                    ),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            overscrollEffect = overscroll,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (currentUser != null) {
                item {
                    ListHeader {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally  // 水平居中
                        ) {
                            ThumbnailChatPhoto(
                                thumbnail = currentUser?.profilePhoto?.big
                                    ?: currentUser?.profilePhoto?.small,
                                title = "${currentUser?.firstName} ${currentUser?.lastName}",
                                accentColorId = currentUser!!.accentColorId,
                                contentDescription = "Chat Photo",
                                onClick = {
                                    navController?.navigate(Destinations.imgView(it))
                                },
                                modifier =
                                    Modifier
                                        .size(with(density) { 160.toDp() })
                                        .clip(CircleShape)
                                        .wrapContentSize(align = Alignment.Center)
                            )

                            SelectionContainer {
                                Text(
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center,
                                    text = currentUser?.firstName + if (currentUser?.lastName.isNullOrBlank()) "" else " ${currentUser?.lastName}",
                                    color = Color.White,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                )
                            }

                            Text(
                                text = connectState,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = Color(0xFFC9C3CF),
                                modifier = Modifier
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
                if (!currentUser?.phoneNumber.isNullOrBlank()) {
                    item {
                        TitleCard(
                            title = {
                                Text(stringResource(id = R.string.Phone_number), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                            },
                            onClick = {},
                            onLongClick = {
                                // 复制文本
                                context.setClipboardText("+${currentUser?.phoneNumber}", "phoneNumber")

                                Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                            },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                                .padding(horizontal = 5.dp)
                        ) {
                            Text(
                                text = "+${currentUser?.phoneNumber}",
                                fontSize = 18.sp,
                                color = Color.White,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (!currentUser?.usernames?.editableUsername.isNullOrBlank()) {
                    item {
                        TitleCard(
                            title = {
                                Text(stringResource(id = R.string.Username), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                            },
                            onClick = {},
                            onLongClick = {
                                // 复制文本
                                context.setClipboardText("@${currentUser?.usernames?.editableUsername}", "Username")

                                Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                            },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                                .padding(horizontal = 5.dp)
                        ) {
                            Text(
                                text = "@${currentUser?.usernames?.editableUsername}",
                                fontSize = 18.sp,
                                color = Color.White,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (currentUserFullInfo?.bio != null) {
                    item {
                        TitleCard(
                            title = {
                                Text(stringResource(id = R.string.Bio), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                            },
                            onClick = {
                                currentUserFullInfo?.bio?.let {
                                    val textId = System.currentTimeMillis()
                                    appState.tgTextIdMap[textId] = it
                                    navController?.navigate(Destinations.textView(textId))
                                }
                            },
                            onLongClick = {
                                // 复制文本
                                context.setClipboardText(currentUserFullInfo?.bio?.text ?: "", "Bio")

                                Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                            },
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier.fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                                .padding(horizontal = 5.dp)
                        ) {
                            Text(
                                text = currentUserFullInfo?.bio?.text ?: "",
                                fontSize = 18.sp,
                                color = Color.White,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
