package com.tgwrist.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
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
import com.tgwrist.app.ui.main.ThumbnailChatPhoto
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.TgClient
import com.tgwrist.app.utils.dateTimeUserPref
import com.tgwrist.app.utils.setClipboardText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

@Composable
fun Page2(chatObject: TdApi.Chat?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val appState = LocalGlobalAppState.current
    val navController = appState.navController
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val transformationSpec = rememberTransformationSpec()
    val density = LocalDensity.current

    // 字符串资源（在 Composable 上下文中获取）
    val copiedClipboard = stringResource(id = R.string.Copied_clipboard)
    val strUnknown = stringResource(id = R.string.Unknown)
    val strPrivateChat = stringResource(id = R.string.Private_Chat)
    val strBot = stringResource(id = R.string.Bot)
    val strOnline = stringResource(id = R.string.Online)
    val strOffline = stringResource(id = R.string.Offline)
    val strLately = stringResource(id = R.string.Lately)
    val strLastWeek = stringResource(id = R.string.Last_week)
    val strLastMonth = stringResource(id = R.string.Last_month)
    val strLastSeen = stringResource(id = R.string.last_seen)
    val strGroupChat = stringResource(id = R.string.Group_Chat)
    val strSupergroupChat = stringResource(id = R.string.Supergroup_Chat)
    val strMember = stringResource(id = R.string.Member)
    val strSubscribers = stringResource(id = R.string.Subscribers)
    val strSecretChat = stringResource(id = R.string.Secret_Chat)

    val strActionTyping = stringResource(id = R.string.action_typing)
    val strActionRecordingVideo = stringResource(id = R.string.action_recording_video)
    val strActionUploadingVideo = stringResource(id = R.string.action_uploading_video)
    val strActionRecordingVoice = stringResource(id = R.string.action_recording_voice)
    val strActionUploadingVoice = stringResource(id = R.string.action_uploading_voice)
    val strActionUploadingPhoto = stringResource(id = R.string.action_uploading_photo)
    val strActionUploadingDocument = stringResource(id = R.string.action_uploading_document)
    val strActionChoosingSticker = stringResource(id = R.string.action_choosing_sticker)
    val strActionChoosingLocation = stringResource(id = R.string.action_choosing_location)
    val strActionChoosingContact = stringResource(id = R.string.action_choosing_contact)
    val strActionPlayingGame = stringResource(id = R.string.action_playing_game)
    val strActionRecordingVideoNote = stringResource(id = R.string.action_recording_video_note)
    val strActionUploadingVideoNote = stringResource(id = R.string.action_uploading_video_note)
    val strActionWatchingAnimation = stringResource(id = R.string.action_watching_animation)

    // 状态：副标题（在线状态 / 成员数等）
    var subtitle by remember { mutableStateOf("") }

    // 状态：用户 / 群组信息
    var userInfo by remember { mutableStateOf<TdApi.User?>(null) }
    var userFullInfo by remember { mutableStateOf<TdApi.UserFullInfo?>(null) }
    var groupDescription by remember { mutableStateOf<String?>(null) }
    var inviteLink by remember { mutableStateOf<String?>(null) }
    var publicLink by remember { mutableStateOf<String?>(null) }
    var onlineMemberCount by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf("") }
    var chatPhoto by remember { mutableStateOf(chatObject?.photo) }

    // 订阅用户在线状态更新
    LaunchedEffect(Unit) {
        TgClient.subscribe(TdApi.UpdateUserStatus::class.java, lifecycleOwner) { update ->
            val targetUserId = when (val chatType = chatObject?.type) {
                is TdApi.ChatTypePrivate -> chatType.userId
                is TdApi.ChatTypeSecret -> chatType.userId
                else -> null
            }
            if (targetUserId != null && update.userId == targetUserId) {
                subtitle = when (val status = update.status) {
                    is TdApi.UserStatusOnline -> strOnline
                    is TdApi.UserStatusRecently -> strLately
                    is TdApi.UserStatusLastWeek -> strLastWeek
                    is TdApi.UserStatusLastMonth -> strLastMonth
                    is TdApi.UserStatusOffline -> {
                        if (status.wasOnline > 0) {
                            "$strLastSeen ${dateTimeUserPref(context, status.wasOnline.toLong() * 1000)}"
                        } else {
                            strOffline
                        }
                    }
                    else -> strUnknown
                }
            }
        }

        // 订阅用户信息更新
        TgClient.subscribe(TdApi.UpdateUser::class.java, lifecycleOwner) { update ->
            val targetUserId = when (val chatType = chatObject?.type) {
                is TdApi.ChatTypePrivate -> chatType.userId
                is TdApi.ChatTypeSecret -> chatType.userId
                else -> null
            }
            if (targetUserId != null && update.user.id == targetUserId) {
                userInfo = update.user
            }
        }

        // 订阅用户详细信息更新
        TgClient.subscribe(TdApi.UpdateUserFullInfo::class.java, lifecycleOwner) { update ->
            val targetUserId = when (val chatType = chatObject?.type) {
                is TdApi.ChatTypePrivate -> chatType.userId
                is TdApi.ChatTypeSecret -> chatType.userId
                else -> null
            }
            if (targetUserId != null && update.userId == targetUserId) {
                userFullInfo = update.userFullInfo
            }
        }

        // 订阅普通群组信息更新
        TgClient.subscribe(TdApi.UpdateBasicGroup::class.java, lifecycleOwner) { update ->
            val chatType = chatObject?.type
            if (chatType is TdApi.ChatTypeBasicGroup && update.basicGroup.id == chatType.basicGroupId) {
                subtitle = "${update.basicGroup.memberCount} $strMember"
            }
        }

        // 订阅普通群组详细信息更新
        TgClient.subscribe(TdApi.UpdateBasicGroupFullInfo::class.java, lifecycleOwner) { update ->
            val chatType = chatObject?.type
            if (chatType is TdApi.ChatTypeBasicGroup && update.basicGroupId == chatType.basicGroupId) {
                update.basicGroupFullInfo.inviteLink?.inviteLink?.takeIf { it.isNotBlank() }?.let {
                    inviteLink = it
                }
                update.basicGroupFullInfo.description.takeIf { it.isNotBlank() }?.let {
                    groupDescription = it
                }
            }
        }

        // 订阅超级群组信息更新
        TgClient.subscribe(TdApi.UpdateSupergroup::class.java, lifecycleOwner) { update ->
            val chatType = chatObject?.type
            if (chatType is TdApi.ChatTypeSupergroup && update.supergroup.id == chatType.supergroupId) {
                subtitle = if (chatType.isChannel) {
                    "${update.supergroup.memberCount} $strSubscribers"
                } else {
                    "${update.supergroup.memberCount} $strMember"
                }
                update.supergroup.usernames?.activeUsernames?.firstOrNull()?.let { username ->
                    publicLink = "https://t.me/$username"
                }
            }
        }

        // 订阅超级群组详细信息更新
        TgClient.subscribe(TdApi.UpdateSupergroupFullInfo::class.java, lifecycleOwner) { update ->
            val chatType = chatObject?.type
            if (chatType is TdApi.ChatTypeSupergroup && update.supergroupId == chatType.supergroupId) {
                update.supergroupFullInfo.description.takeIf { it.isNotBlank() }?.let {
                    groupDescription = it
                }
                if (publicLink == null) {
                    update.supergroupFullInfo.inviteLink?.inviteLink?.takeIf { it.isNotBlank() }?.let {
                        inviteLink = it
                    }
                }
            }
        }

        // 订阅聊天动作更新
        TgClient.subscribe(TdApi.UpdateChatAction::class.java, lifecycleOwner) { update ->
            if (update.chatId == chatObject?.id) {
                val actionText = when (update.action) {
                    is TdApi.ChatActionTyping -> strActionTyping
                    is TdApi.ChatActionRecordingVideo -> strActionRecordingVideo
                    is TdApi.ChatActionUploadingVideo -> strActionUploadingVideo
                    is TdApi.ChatActionRecordingVoiceNote -> strActionRecordingVoice
                    is TdApi.ChatActionUploadingVoiceNote -> strActionUploadingVoice
                    is TdApi.ChatActionUploadingPhoto -> strActionUploadingPhoto
                    is TdApi.ChatActionUploadingDocument -> strActionUploadingDocument
                    is TdApi.ChatActionChoosingSticker -> strActionChoosingSticker
                    is TdApi.ChatActionChoosingLocation -> strActionChoosingLocation
                    is TdApi.ChatActionChoosingContact -> strActionChoosingContact
                    is TdApi.ChatActionStartPlayingGame -> strActionPlayingGame
                    is TdApi.ChatActionRecordingVideoNote -> strActionRecordingVideoNote
                    is TdApi.ChatActionUploadingVideoNote -> strActionUploadingVideoNote
                    is TdApi.ChatActionWatchingAnimations -> strActionWatchingAnimation
                    is TdApi.ChatActionCancel -> ""
                    else -> ""
                }

                if (actionText.isEmpty()) {
                    action = ""
                    return@subscribe
                }

                // 私人聊天（一对一）不需要显示发送者名称
                val isPrivateChat = chatObject.type is TdApi.ChatTypePrivate ||
                        chatObject.type is TdApi.ChatTypeSecret
                if (isPrivateChat) {
                    action = actionText
                } else {
                    // 先显示动作文本，再异步加载发送者名称
                    action = actionText
                    CoroutineScope(Dispatchers.Main).launch {
                        val senderName: String? = when (val sender = update.senderId) {
                            is TdApi.MessageSenderUser -> {
                                suspendCancellableCoroutine { cont ->
                                    TgClient.send(TdApi.GetUser(sender.userId)) { res ->
                                        if (!cont.isActive) return@send
                                        val name = if (res is TdApi.User) {
                                            listOfNotNull(
                                                res.firstName.takeIf { it.isNotBlank() },
                                                res.lastName.takeIf { it.isNotBlank() }
                                            ).joinToString(" ").ifEmpty { null }
                                        } else null
                                        cont.resume(name)
                                    }
                                }
                            }
                            is TdApi.MessageSenderChat -> {
                                suspendCancellableCoroutine { cont ->
                                    TgClient.send(TdApi.GetChat(sender.chatId)) { res ->
                                        if (!cont.isActive) return@send
                                        cont.resume((res as? TdApi.Chat)?.title)
                                    }
                                }
                            }
                            else -> null
                        }
                        // 仅在 action 仍然是当前动作时才更新（避免过期覆盖）
                        if (action == actionText) {
                            action = if (!senderName.isNullOrBlank()) {
                                "$senderName $actionText"
                            } else {
                                actionText
                            }
                        }
                    }
                }
            }
        }

        // 订阅聊天头像更新
        TgClient.subscribe(TdApi.UpdateChatPhoto::class.java, lifecycleOwner) { update ->
            if (update.chatId == chatObject?.id) {
                chatPhoto = update.photo
            }
        }

        // 订阅在线成员数更新
        TgClient.subscribe(TdApi.UpdateChatOnlineMemberCount::class.java, lifecycleOwner) { update ->
            if (update.chatId == chatObject?.id) {
                onlineMemberCount = update.onlineMemberCount
            }
        }
    }

    // 异步获取对方用户 / 群组详细信息
    LaunchedEffect(chatObject?.id) {
        if (chatObject == null) return@LaunchedEffect

        when (val chatType = chatObject.type) {
            // ============ 私人聊天 ============
            is TdApi.ChatTypePrivate -> {
                subtitle = strPrivateChat

                // 获取用户信息
                val user = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetUser(chatType.userId)) { res ->
                        cont.resume(res as? TdApi.User)
                    }
                }
                user?.let { u ->
                    userInfo = u

                    // 判断是否为机器人
                    if (u.type is TdApi.UserTypeBot) {
                        subtitle = strBot
                    } else {
                        // 用户在线状态
                        subtitle = when (val status = u.status) {
                            is TdApi.UserStatusOnline -> strOnline
                            is TdApi.UserStatusRecently -> strLately
                            is TdApi.UserStatusLastWeek -> strLastWeek
                            is TdApi.UserStatusLastMonth -> strLastMonth
                            is TdApi.UserStatusOffline -> {
                                if (status.wasOnline > 0) {
                                    "$strLastSeen ${dateTimeUserPref(context, status.wasOnline.toLong() * 1000)}"
                                } else {
                                    strOffline
                                }
                            }
                            else -> strUnknown
                        }
                    }
                }

                // 获取用户详细信息
                val fullInfo = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetUserFullInfo(chatType.userId)) { res ->
                        cont.resume(res as? TdApi.UserFullInfo)
                    }
                }
                fullInfo?.let { userFullInfo = it }
            }

            // ============ 私密聊天 ============
            is TdApi.ChatTypeSecret -> {
                subtitle = strSecretChat

                val user = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetUser(chatType.userId)) { res ->
                        cont.resume(res as? TdApi.User)
                    }
                }
                user?.let { u ->
                    userInfo = u
                    subtitle = when (val status = u.status) {
                        is TdApi.UserStatusOnline -> strOnline
                        is TdApi.UserStatusRecently -> strLately
                        is TdApi.UserStatusLastWeek -> strLastWeek
                        is TdApi.UserStatusLastMonth -> strLastMonth
                        is TdApi.UserStatusOffline -> {
                            if (status.wasOnline > 0) {
                                "$strLastSeen ${dateTimeUserPref(context, status.wasOnline.toLong() * 1000)}"
                            } else {
                                strOffline
                            }
                        }
                        else -> strUnknown
                    }
                }

                val fullInfo = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetUserFullInfo(chatType.userId)) { res ->
                        cont.resume(res as? TdApi.UserFullInfo)
                    }
                }
                fullInfo?.let { userFullInfo = it }
            }

            // ============ 普通群组 ============
            is TdApi.ChatTypeBasicGroup -> {
                subtitle = strGroupChat

                val groupInfo = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetBasicGroup(chatType.basicGroupId)) { res ->
                        cont.resume(res as? TdApi.BasicGroup)
                    }
                }
                groupInfo?.let {
                    subtitle = "${it.memberCount} $strMember"
                }

                val groupFullInfo = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetBasicGroupFullInfo(chatType.basicGroupId)) { res ->
                        cont.resume(res as? TdApi.BasicGroupFullInfo)
                    }
                }
                groupFullInfo?.let { fi ->
                    fi.inviteLink?.inviteLink?.takeIf { it.isNotBlank() }?.let {
                        inviteLink = it
                    }
                    fi.description.takeIf { it.isNotBlank() }?.let {
                        groupDescription = it
                    }
                }
            }

            // ============ 超级群组 / 频道 ============
            is TdApi.ChatTypeSupergroup -> {
                subtitle = strSupergroupChat

                val supergroupInfo = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetSupergroup(chatType.supergroupId)) { res ->
                        cont.resume(res as? TdApi.Supergroup)
                    }
                }
                supergroupInfo?.let { sg ->
                    subtitle = if (chatType.isChannel) {
                        "${sg.memberCount} $strSubscribers"
                    } else {
                        "${sg.memberCount} $strMember"
                    }

                    // 公开链接
                    sg.usernames?.activeUsernames?.firstOrNull()?.let { username ->
                        publicLink = "https://t.me/$username"
                    }
                }

                val supergroupFullInfo = suspendCancellableCoroutine { cont ->
                    TgClient.send(TdApi.GetSupergroupFullInfo(chatType.supergroupId)) { res ->
                        cont.resume(res as? TdApi.SupergroupFullInfo)
                    }
                }
                supergroupFullInfo?.let { fi ->
                    fi.description.takeIf { it.isNotBlank() }?.let {
                        groupDescription = it
                    }
                    if (publicLink == null) {
                        fi.inviteLink?.inviteLink?.takeIf { it.isNotBlank() }?.let {
                            inviteLink = it
                        }
                    }
                }
            }
        }
    }

    // ============ UI ============
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
            // ---- 头部：头像 + 名称 + 副标题 ----
            item {
                ListHeader {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ThumbnailChatPhoto(
                            thumbnail = chatPhoto?.big ?: chatPhoto?.small,
                            title = chatObject?.title ?: "",
                            accentColorId = chatObject?.accentColorId ?: 0,
                            contentDescription = "Chat Photo",
                            onClick = {
                                navController?.navigate(Destinations.imgView(it))
                            },
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(with(density) { 160.toDp() })
                                    .clip(CircleShape)
                                    .wrapContentSize(align = Alignment.Center)
                        )

                        Text(
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            text = chatObject?.title ?: "",
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = Color(0xFFC9C3CF),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 在线成员数（仅群组 / 频道，且有数据时才显示）
                        val isGroup = chatObject?.type is TdApi.ChatTypeBasicGroup ||
                                chatObject?.type is TdApi.ChatTypeSupergroup
                        if (isGroup && onlineMemberCount > 0) {
                            Text(
                                text = "$onlineMemberCount $strOnline",
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                color = Color(0xFF4FC3F7),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // action显示处
                        if (action.isNotBlank()) {
                            Text(
                                text = action,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = colorResource(id = R.color.blue),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ---- 电话号码（仅私人聊天） ----
            if (!userInfo?.phoneNumber.isNullOrBlank()) {
                item {
                    TitleCard(
                        title = {
                            Text(stringResource(id = R.string.Phone_number), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                        },
                        onClick = {},
                        onLongClick = {
                            context.setClipboardText("+${userInfo?.phoneNumber}", "phoneNumber")
                            
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 5.dp)
                    ) {
                        Text(
                            text = "+${userInfo?.phoneNumber}",
                            fontSize = 18.sp,
                            color = Color.White,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ---- 用户名（私人聊天） ----
            val activeUsername = userInfo?.usernames?.activeUsernames?.firstOrNull()
            if (!activeUsername.isNullOrBlank()) {
                item {
                    TitleCard(
                        title = {
                            Text(stringResource(id = R.string.Username), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                        },
                        onClick = {},
                        onLongClick = {
                            context.setClipboardText("@$activeUsername", "Username")
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 5.dp)
                    ) {
                        Text(
                            text = "@$activeUsername",
                            fontSize = 18.sp,
                            color = Color.White,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ---- 个人简介 / Bio（私人聊天） ----
            val bioText = userFullInfo?.bio?.text
            if (!bioText.isNullOrBlank()) {
                item {
                    TitleCard(
                        title = {
                            Text(stringResource(id = R.string.Bio), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                        },
                        onClick = {
                            userFullInfo?.bio?.let {
                                val textId = System.currentTimeMillis()
                                appState.tgTextIdMap[textId] = it
                                navController?.navigate(Destinations.textView(textId))
                            }
                        },
                        onLongClick = {
                            context.setClipboardText(bioText, "Bio")
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 5.dp)
                    ) {
                        Text(
                            text = bioText,
                            fontSize = 18.sp,
                            color = Color.White,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ---- 公开链接（超级群组 / 频道） ----
            if (!publicLink.isNullOrBlank()) {
                item {
                    TitleCard(
                        title = {
                            Text(stringResource(id = R.string.Link), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                        },
                        onClick = {},
                        onLongClick = {
                            context.setClipboardText(publicLink ?: "", "Link")
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 5.dp)
                    ) {
                        Text(
                            text = publicLink ?: "",
                            fontSize = 14.sp,
                            color = Color.White,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ---- 邀请链接（群组） ----
            if (!inviteLink.isNullOrBlank()) {
                item {
                    TitleCard(
                        title = {
                            Text(stringResource(id = R.string.Invite_Link), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                        },
                        onClick = {},
                        onLongClick = {
                            context.setClipboardText(inviteLink ?: "", "InviteLink")
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 5.dp)
                    ) {
                        Text(
                            text = inviteLink ?: "",
                            fontSize = 14.sp,
                            color = Color.White,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ---- 群组 / 频道简介 ----
            if (!groupDescription.isNullOrBlank()) {
                item {
                    TitleCard(
                        title = {
                            Text(stringResource(id = R.string.Description), fontSize = 12.sp, color = Color(0xFFC9C3CF))
                        },
                        onClick = {},
                        onLongClick = {
                            context.setClipboardText(groupDescription ?: "", "Description")
                            Toast.makeText(context, copiedClipboard, Toast.LENGTH_SHORT).show()
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                            .padding(horizontal = 5.dp)
                    ) {
                        Text(
                            text = groupDescription ?: "",
                            fontSize = 18.sp,
                            color = Color.White,
                            overflow = TextOverflow.Ellipsis
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
