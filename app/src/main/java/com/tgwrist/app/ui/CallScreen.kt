package com.tgwrist.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.StepperLevelIndicator
import androidx.wear.compose.material3.Text
import com.tgwrist.app.R
import com.tgwrist.app.data.AudioOutputDevice
import com.tgwrist.app.runtime.CALL_STATE_CALLING
import com.tgwrist.app.runtime.CALL_STATE_INCOMING
import com.tgwrist.app.runtime.CALL_STATE_NONE
import com.tgwrist.app.runtime.CALL_STATE_PENDING
import com.tgwrist.app.runtime.CALL_STATUS_CALLING_YOU
import com.tgwrist.app.runtime.CALL_STATUS_ERROR
import com.tgwrist.app.runtime.CALL_STATUS_EXCHANGING_KEYS
import com.tgwrist.app.runtime.CALL_STATUS_NONE
import com.tgwrist.app.runtime.CALL_STATUS_REQUESTING
import com.tgwrist.app.runtime.CALL_STATUS_RINGING
import com.tgwrist.app.runtime.CALL_STATUS_USER_PRIVACY_RESTRICTED
import com.tgwrist.app.runtime.CALL_STATUS_WAITING
import com.tgwrist.app.runtime.TgCallManager
import com.tgwrist.app.utils.LocalGlobalAppState
import com.tgwrist.app.utils.RoundedPolygonShape
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun CallScreen() {
    val context = LocalContext.current
    val appState = LocalGlobalAppState.current
    val navController = appState.navController

    val uiState by TgCallManager.uiState.collectAsState()
    val callState = uiState.callState

    var dialogShow by remember { mutableStateOf(false) }

    // 打开音量对话框时同步一次系统通话音量，避免被外部按键改动后显示不一致
    LaunchedEffect(dialogShow) {
        if (dialogShow) {
            TgCallManager.refreshCallVolume()
            TgCallManager.refreshAudioOutput()
        }
    }

    val needMicPermission = stringResource(R.string.Need_mic_permission)

    /** 来电震动；离开 INCOMING 状态或页面销毁时取消 */
    val ringtoneVibrator = remember(context) {
        ContextCompat.getSystemService(context, Vibrator::class.java)
    }
    val incomingCallVibrationPattern = remember {
        longArrayOf(0L, 450L, 350L, 450L, 1200L)
    }
    val incomingCallVibrationAttributes = remember {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    LaunchedEffect(callState) {
        if (callState == CALL_STATE_NONE) {
            // 等待 3 秒后再确认一次：期间如果有新通话进来就不 pop
            delay(3000.milliseconds)
            if (TgCallManager.uiState.value.callState == CALL_STATE_NONE) {
                navController?.popBackStack()
            }
        }
    }

    DisposableEffect(callState, ringtoneVibrator) {
        if (callState == CALL_STATE_INCOMING) {
            ringtoneVibrator?.vibrate(
                VibrationEffect.createWaveform(incomingCallVibrationPattern, 0),
                incomingCallVibrationAttributes
            )
        }
        onDispose {
            if (callState == CALL_STATE_INCOMING) {
                ringtoneVibrator?.cancel()
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
        if (hasMicPermission != isGranted) hasMicPermission = isGranted
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
        CALL_STATUS_USER_PRIVACY_RESTRICTED -> stringResource(id = R.string.Call_status_private_restricted)
        CALL_STATUS_ERROR -> stringResource(id = R.string.Call_status_error)
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
        Dialog(
            visible = dialogShow,
            onDismissRequest = { dialogShow = false },
        ) {
            VolumeDialogContent(
                volume = uiState.callVolume,
                maxVolume = uiState.maxCallVolume,
                audioOutput = uiState.audioOutput,
                onVolumeChange = { TgCallManager.setCallVolume(it) },
                onToggleAudioOutput = { TgCallManager.requestSwitchAudioOutput() },
            )
        }
        ScreenScaffold(Modifier.fillMaxSize()) { contentPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .requestFocusOnHierarchyActive()
                    .padding(contentPadding)
            ) {
                val screenWidth = maxWidth
                val screenHeight = maxHeight

                // 小屏断点：约 192dp 的圆表盘上文字会跟中间控件重叠，这里收紧字号和控件尺寸
                val isCompact = screenWidth < 200.dp

                val titleStyle = if (isCompact) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.titleMedium
                }
                val subtitleStyle = if (isCompact) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.titleSmall
                }
                val subtitleMaxLines = if (isCompact) 1 else 2
                val avatarSizeFraction = if (isCompact) 0.26f else 0.32f
                val iconButtonSize = if (isCompact) 40.dp else 52.dp
                val iconSize = if (isCompact) 20.dp else 24.dp
                val topTextPadding = if (isCompact) screenHeight * 0.01f else screenHeight * 0.02f

                Box(modifier = Modifier.fillMaxSize()) {
                    // 顶部文字
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = topTextPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = peerTitle,
                            style = titleStyle,
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
                                style = subtitleStyle,
                                textAlign = TextAlign.Center,
                                maxLines = subtitleMaxLines,
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
                                modifier = Modifier.size(iconButtonSize),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (leftIsReject) Color(0xFFF58B81) else Color(0xFF1D2B3A),
                                    contentColor = Color.White
                                )
                            ) {
                                when {
                                    leftIsReject -> Icon(
                                        imageVector = Icons.Rounded.CallEnd,
                                        contentDescription = "CallEnd",
                                        modifier = Modifier.size(iconSize)
                                    )
                                    uiState.isMute -> Icon(
                                        imageVector = Icons.Rounded.MicOff,
                                        contentDescription = "MicOff",
                                        modifier = Modifier.size(iconSize)
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = "Mic",
                                        modifier = Modifier.size(iconSize)
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
                                        .size(screenWidth * avatarSizeFraction)
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
                                    dialogShow = true
                                },
                                modifier = Modifier.size(iconButtonSize),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color(0xFF1D2B3A),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreHoriz,
                                    contentDescription = "MoreHoriz",
                                    modifier = Modifier.size(iconSize)
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

/**
 * 通话音量调整对话框内容。直接驱动 [TgCallManager.setCallVolume]，
 * 由 manager 负责把变更写回 AudioManager.STREAM_VOICE_CALL。
 *
 * 中央 [ChildButton] 显示当前音频输出设备（手表扬声器 / 蓝牙 / 有线耳机 / 听筒），
 * 点击会调用 [onToggleAudioOutput]，在支持的系统上打开“媒体输出切换器”，
 * 否则回退到手动两态切换。
 *
 * 左侧的 [StepperLevelIndicator] 显示当前音量在 [0..maxVolume] 内的位置。
 */
@Composable
private fun VolumeDialogContent(
    volume: Int,
    maxVolume: Int,
    audioOutput: AudioOutputDevice,
    onVolumeChange: (Int) -> Unit,
    onToggleAudioOutput: () -> Unit,
) {
    // maxVolume 在系统未就绪时可能为 0，这里兜底成 1 步避免 require(steps >= 0) 失败
    val safeMax = maxVolume.coerceAtLeast(1)
    val safeValue = volume.coerceIn(0, safeMax).toFloat()
    // Stepper 的 steps 表示最小/最大之间的离散点个数，所以是 max - 1
    val stepCount = (safeMax - 1).coerceAtLeast(0)
    val valueRange = 0f..safeMax.toFloat()

    val outputIcon = when (audioOutput) {
        AudioOutputDevice.SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp
        AudioOutputDevice.EARPIECE -> Icons.Rounded.PhoneInTalk
        AudioOutputDevice.WIRED_HEADSET -> Icons.Rounded.Headphones
        AudioOutputDevice.BLUETOOTH -> Icons.Rounded.Bluetooth
    }
    val outputLabelRes = when (audioOutput) {
        AudioOutputDevice.SPEAKER -> R.string.Call_audio_output_speaker
        AudioOutputDevice.EARPIECE -> R.string.Call_audio_output_earpiece
        AudioOutputDevice.WIRED_HEADSET -> R.string.Call_audio_output_wired
        AudioOutputDevice.BLUETOOTH -> R.string.Call_audio_output_bluetooth
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        StepperLevelIndicator(
            value = { safeValue },
            valueRange = valueRange,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Stepper(
            value = safeValue,
            onValueChange = { newValue ->
                val intValue = newValue.toInt().coerceIn(0, safeMax)
                if (intValue != volume) onVolumeChange(intValue)
            },
            steps = stepCount,
            valueRange = valueRange,
            decreaseIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeDown,
                    contentDescription = "VolumeDown",
                    modifier = Modifier.size(24.dp)
                )
            },
            increaseIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "VolumeUp",
                    modifier = Modifier.size(24.dp)
                )
            },
        ) {
            ChildButton(
                onClick = onToggleAudioOutput,
                label = {
                    Text(
                        text = stringResource(id = R.string.Call_volume),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                secondaryLabel = {
                    Text(
                        text = stringResource(id = outputLabelRes),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                icon = {
                    Icon(
                        imageVector = outputIcon,
                        contentDescription = "AudioOutput",
                        modifier = Modifier.size(ButtonDefaults.ExtraLargeIconSize),
                    )
                }
            )
        }
    }
}
