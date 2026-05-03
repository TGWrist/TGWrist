package com.tgwrist.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBox
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.MarkChatUnread
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Work
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.tgwrist.app.R
import com.tgwrist.app.utils.ChatsRepository
import com.tgwrist.app.utils.ChatsRepository.listKey
import com.tgwrist.app.utils.Config
import org.drinkless.tdlib.TdApi

/**
 * 将 TdApi.ChatFolderInfo 的 iconName 映射到对应的 Icons.Rounded 图标。
 * 未识别的名称返回 [Folder] 作为默认图标。
 */
@Composable
fun chatFolderIconVector(iconName: String): ImageVector = when (iconName) {
    "All"      -> Icons.AutoMirrored.Rounded.Chat
    "Unread"   -> Icons.Rounded.MarkChatUnread
    "Unmuted"  -> Icons.Rounded.Notifications
    "Bots"     -> Icons.Rounded.SmartToy
    "Channels" -> Icons.Rounded.Campaign
    "Groups"   -> Icons.Rounded.Group
    "Private"  -> Icons.Rounded.AccountBox
    "Custom"   -> Icons.Rounded.Folder
    "Setup"    -> Icons.Rounded.ContentPaste
    "Cat"      -> Icons.Rounded.Pets
    "Crown"    -> ImageVector.vectorResource(id = R.drawable.crown_24px)
    "Favorite" -> Icons.Rounded.Star
    "Flower"   -> Icons.Rounded.LocalFlorist
    "Game"     -> Icons.Rounded.SportsEsports
    "Home"     -> Icons.Rounded.Home
    "Love"     -> Icons.Rounded.Favorite
    "Mask"     -> Icons.Rounded.TheaterComedy
    "Party"    -> Icons.Rounded.Celebration
    "Sport"    -> Icons.Rounded.SportsBasketball
    "Study"    -> Icons.Rounded.School
    "Trade"    -> Icons.AutoMirrored.Rounded.TrendingUp
    "Travel"   -> Icons.Rounded.Flight
    "Work"     -> Icons.Rounded.Work
    "Airplane" -> Icons.AutoMirrored.Rounded.Send
    "Book"     -> Icons.Rounded.Book
    "Light"    -> Icons.Rounded.Lightbulb
    "Like"     -> Icons.Rounded.ThumbUp
    "Money"    -> Icons.Rounded.AttachMoney
    "Note"     -> Icons.Rounded.MusicNote
    "Palette"  -> Icons.Rounded.Brush
    else       -> Icons.Rounded.Folder
}

@Composable
internal fun Page0() {
    val chatFolderInfo by Config.chatFolderInfo.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()

    // 订阅 chatFolders 列表（消息文件夹列表）
    val chatFolders by ChatsRepository.chatFolders.collectAsState()

    // 订阅当前显示的 chat list
    val currentChatList by ChatsRepository.currentChatList.collectAsState()

    // 订阅 main chat list 位置
    val mainChatListPosition by  ChatsRepository.mainChatListPosition.collectAsState()

    // 确保存在All chats
    val safeMainPosition = mainChatListPosition.coerceIn(0, chatFolders.size)

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
                        text = stringResource(R.string.ChatFolders),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }

            // 渲染前段
            chatFolders.take(safeMainPosition).forEach { item ->
                item(key = "folder_${item.id}") {
                    val unreadCount = chatFolderInfo[item.id.toString()]?.unreadCount ?: 0
                    val unreadUnmutedCount = chatFolderInfo[item.id.toString()]?.unreadUnmutedCount ?: 0

                    val countText = if (unreadCount > 0) " $unreadCount" else ""
                    val countColor = if (unreadUnmutedCount > 0) colorResource(id = R.color.blue) else Color.Unspecified

                    RadioButton(
                        label = {
                            Text(
                                buildAnnotatedString {
                                    append(item.name.text.text)
                                    if (countText.isNotEmpty()) {
                                        withStyle(SpanStyle(color = countColor)) {
                                            append(countText)
                                        }
                                    }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        selected = listKey(TdApi.ChatListFolder(item.id)) == listKey(currentChatList),
                        onSelect = { ChatsRepository.setCurrentChatList(TdApi.ChatListFolder(item.id)) },
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = {
                            Icon(
                                imageVector = chatFolderIconVector(item.icon.name),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        enabled = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // 渲染All chats
            item(key = "main_all_chats") {
                val unreadCount = chatFolderInfo[MAIN_LIST]?.unreadCount ?: 0
                val unreadUnmutedCount = chatFolderInfo[MAIN_LIST]?.unreadUnmutedCount ?: 0

                val countText = if (unreadCount > 0) " $unreadCount" else ""
                val countColor =
                    if (unreadUnmutedCount > 0) colorResource(id = R.color.blue) else Color.Unspecified

                RadioButton(
                    label = {
                        Text(
                            buildAnnotatedString {
                                append(stringResource(R.string.All_Chats))
                                if (countText.isNotEmpty()) {
                                    withStyle(SpanStyle(color = countColor)) {
                                        append(countText)
                                    }
                                }
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = listKey(TdApi.ChatListMain()) == listKey(currentChatList),
                    onSelect = { ChatsRepository.setCurrentChatList(TdApi.ChatListMain()) },
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    enabled = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }

            // 渲染后段
            chatFolders.drop(safeMainPosition).forEach { item ->
                item(key = "folder_${item.id}") {
                    val unreadCount = chatFolderInfo[item.id.toString()]?.unreadCount ?: 0
                    val unreadUnmutedCount = chatFolderInfo[item.id.toString()]?.unreadUnmutedCount ?: 0

                    val countText = if (unreadCount > 0) " $unreadCount" else ""
                    val countColor = if (unreadUnmutedCount > 0) colorResource(id = R.color.blue) else Color.Unspecified

                    RadioButton(
                        label = {
                            Text(
                                buildAnnotatedString {
                                    append(item.name.text.text)
                                    if (countText.isNotEmpty()) {
                                        withStyle(SpanStyle(color = countColor)) {
                                            append(countText)
                                        }
                                    }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        selected = listKey(TdApi.ChatListFolder(item.id)) == listKey(currentChatList),
                        onSelect = { ChatsRepository.setCurrentChatList(TdApi.ChatListFolder(item.id)) },
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = {
                            Icon(
                                imageVector = chatFolderIconVector(item.icon.name),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        enabled = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }

            // 归档消息
            item {
                val unreadCount = chatFolderInfo[ARCHIVE_LIST]?.unreadCount ?: 0
                val unreadUnmutedCount = chatFolderInfo[ARCHIVE_LIST]?.unreadUnmutedCount ?: 0

                val countText = if (unreadCount > 0) " $unreadCount" else ""
                val countColor = if (unreadUnmutedCount > 0) colorResource(id = R.color.blue) else Color.Unspecified

                RadioButton(
                    label = {
                        Text(
                            buildAnnotatedString {
                                append(stringResource(R.string.Archived))
                                if (countText.isNotEmpty()) {
                                    withStyle(SpanStyle(color = countColor)) {
                                        append(countText)
                                    }
                                }
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = listKey(TdApi.ChatListArchive()) == listKey(currentChatList),
                    onSelect = { ChatsRepository.setCurrentChatList(TdApi.ChatListArchive()) },
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    enabled = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
