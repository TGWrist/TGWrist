package com.tgwrist.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.runtime.CALL_STATE_CALLING
import com.tgwrist.app.runtime.CALL_STATE_INCOMING
import com.tgwrist.app.runtime.CALL_STATE_NONE
import com.tgwrist.app.runtime.CALL_STATE_PENDING
import com.tgwrist.app.runtime.CALL_STATUS_CALLING_YOU
import com.tgwrist.app.runtime.CALL_STATUS_EXCHANGING_KEYS
import com.tgwrist.app.runtime.CALL_STATUS_NONE
import com.tgwrist.app.runtime.CALL_STATUS_REQUESTING
import com.tgwrist.app.runtime.CALL_STATUS_RINGING
import com.tgwrist.app.runtime.CALL_STATUS_WAITING
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.ui.main.ThumbnailChatPhoto
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.RoundedPolygonShape
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun CallScreen() {
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val overscroll = rememberOverscrollEffect()
    val appState = LocalGlobalAppState.current
    val navController = appState.navController

    val uiState by TgCallManager.uiState.collectAsState()
    val callState = uiState.callState

    val needMicPermission = stringResource(R.string.Need_mic_permission)

    LaunchedEffect(callState) {
        if (callState == CALL_STATE_NONE) {
            // 等待 3 秒后再确认一次：期间如果有新通话进来就不 pop
            delay(3000.milliseconds)
            if (TgCallManager.uiState.value.callState == CALL_STATE_NONE) {
                navController?.popBackStack()
            }
        }
    }

    // 录音权限申请
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasMicPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, needMicPermission, Toast.LENGTH_SHORT).show()
            TgCallManager.discardCall()
            navController?.popBackStack()
        }
    }

    LaunchedEffect(hasMicPermission) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 头像扇贝形状
    val m3Scallop = remember {
        RoundedPolygon.star(
            numVerticesPerRadius = 7, // 标准 7 角波浪
            innerRadius = 0.77f,      // 控制凹陷深度，0.82 左右微凹，保持整体的圆形感
            // radius 设置为 0.5f (官方库会自动将其限制在完美的相切弧度)
            // smoothing = 1f 让曲线完美贴合，彻底消灭所有直线和尖角
            rounding = CornerRounding(radius = 0.5f, smoothing = 1f),
            innerRounding = CornerRounding(radius = 0.5f, smoothing = 1f)
        )
    }
    val avatarShape = remember(m3Scallop) { RoundedPolygonShape(m3Scallop) }

    val peerTitle = uiState.peerName.ifBlank { stringResource(id = R.string.Call) }
    val statusText = when (uiState.callStatus) {
        CALL_STATUS_CALLING_YOU -> stringResource(id = R.string.Call_status_calling_you)
        CALL_STATUS_REQUESTING -> stringResource(id = R.string.Call_status_requesting)
        CALL_STATUS_WAITING -> stringResource(id = R.string.Call_status_waiting)
        CALL_STATUS_RINGING -> stringResource(id = R.string.Call_status_ringing)
        CALL_STATUS_EXCHANGING_KEYS -> stringResource(id = R.string.Call_status_exchanging_keys)
        CALL_STATUS_NONE -> ""
        else -> ""
    }
    val subtitle = when {
        callState == CALL_STATE_CALLING -> {
            val durationText = formatCallDuration(uiState.callDurationMillis)
            val emojis = uiState.emojis.joinToString(" ")
            if (emojis.isNotEmpty()) {
                "$durationText\n$emojis"
            } else {
                durationText
            }
        }
        statusText.isNotEmpty() -> statusText
        else -> ""
    }

    AppScaffold(timeText = { StatusTimeText() }) {
        ScreenScaffold(Modifier.fillMaxSize()) { contentPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .requestFocusOnHierarchyActive()
                    .padding(contentPadding)
            ) {
                val screenWidth = maxWidth
                val screenHeight = maxHeight

                Box(modifier = Modifier.fillMaxSize()) {
                    // 顶部文字
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = peerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = screenWidth * 0.08f)
                        )

                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = screenWidth * 0.08f,
                                        top = screenHeight * 0.01f,
                                        end = screenWidth * 0.08f
                                    )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左：来电时拒接；其余状态切换静音
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val leftIsReject = callState == CALL_STATE_INCOMING
                            FilledIconButton(
                                onClick = {
                                    if (uiState.actionLocked) return@FilledIconButton
                                    if (leftIsReject) {
                                        TgCallManager.discardCall()
                                    } else {
                                        TgCallManager.toggleMute()
                                    }
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (leftIsReject) Color(0xFFF58B81) else Color(0xFF1D2B3A),
                                    contentColor = Color.White
                                )
                            ) {
                                when {
                                    leftIsReject -> Icon(
                                        imageVector = Icons.Rounded.CallEnd,
                                        contentDescription = "CallEnd"
                                    )
                                    uiState.isMute -> Icon(
                                        imageVector = Icons.Rounded.MicOff,
                                        contentDescription = "MicOff"
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = "Mic"
                                    )
                                }
                            }
                        }

                        // 中：头像
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (callState != CALL_STATE_NONE) {
                                ThumbnailChatPhoto(
                                    thumbnail = uiState.peerPhoto,
                                    title = uiState.peerName,
                                    accentColorId = uiState.peerAccentColorId,
                                    contentDescription = "Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(screenWidth * 0.32f)
                                        .clip(avatarShape)
                                        .background(Color.DarkGray)
                                )
                            }
                        }

                        // 右：更多功能
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            FilledIconButton(
                                onClick = {
                                    // TODO
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color(0xFF1D2B3A),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreHoriz,
                                    contentDescription = "MoreHoriz"
                                )
                            }
                        }
                    }
                }
            }

            Box(Modifier.align(Alignment.BottomCenter)) {
                EdgeButton(
                    onClick = {
                        if (uiState.actionLocked) return@EdgeButton
                        when (callState) {
                            CALL_STATE_INCOMING -> TgCallManager.acceptCall()
                            CALL_STATE_PENDING, CALL_STATE_CALLING -> TgCallManager.discardCall()
                        }
                    },
                    buttonSize = EdgeButtonSize.Medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (callState == CALL_STATE_INCOMING)
                            Color(0xFF71E3B9) else Color(0xFFF58B81)
                    ),
                ) {
                    if (callState == CALL_STATE_INCOMING) {
                        Icon(Icons.Rounded.Call, contentDescription = "Call")
                    } else {
                        Icon(Icons.Rounded.CallEnd, contentDescription = "CallEnd")
                    }
                }
            }
        }
    }
}

private fun formatCallDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        "${hours}:${minutes.twoDigits()}:${seconds.twoDigits()}"
    } else {
        "${minutes.twoDigits()}:${seconds.twoDigits()}"
    }
}

private fun Long.twoDigits(): String = if (this < 10L) "0$this" else toString()
