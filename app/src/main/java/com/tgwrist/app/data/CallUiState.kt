package com.tgwrist.app.data

import com.tgwrist.app.runtime.CALL_STATE_NONE
import com.tgwrist.app.runtime.CALL_STATUS_NONE
import org.drinkless.tdlib.TdApi

/** CallScreen 绑定的完整 UI 状态，来源唯一：TgCallManager */
data class CallUiState(
    val callState: Int = CALL_STATE_NONE,
    val callStatus: Int = CALL_STATUS_NONE,
    val isOutgoing: Boolean = false,
    val peerUserId: Long = 0L,
    val peerName: String = "",
    val peerPhoto: TdApi.File? = null,
    val peerAccentColorId: Int = 0,
    val emojis: List<String> = emptyList(),
    val callDurationMillis: Long = 0L,
    val isMute: Boolean = false,
    /** 当前通话音量 (STREAM_VOICE_CALL) */
    val callVolume: Int = 0,
    /** 通话音量最大值 (STREAM_VOICE_CALL) */
    val maxCallVolume: Int = 0,
    /** 当前通话音频输出路由（手表扬声器 / 蓝牙耳机 / 有线耳机 / 听筒等） */
    val audioOutput: AudioOutputDevice = AudioOutputDevice.SPEAKER,
    /** 接听/挂断/发起正在 TDLib 往返，临时锁定按钮避免重复发送 */
    val actionLocked: Boolean = false,
)

/** CallScreen 关心的音频输出设备分类，用于驱动音量对话框里的图标和文案 */
enum class AudioOutputDevice {
    SPEAKER,
    EARPIECE,
    WIRED_HEADSET,
    BLUETOOTH,
}
