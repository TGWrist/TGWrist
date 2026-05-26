package com.tgwrist.app.runtime

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * 语音消息录制单例。
 *
 * 与 [VoiceRecordingService] 协同工作：
 *  - 单例存活于 Application 进程，UI 翻页 / 重组都不会丢状态；
 *  - 前台服务负责声明 `microphone` 类型 + WakeLock，让录音不被系统冻结；
 *  - 实际的 [MediaRecorder] 在这里持有，UI 直接读取振幅 / 波形。
 */
object VoiceRecordingState {
    private const val TAG = "VoiceRecordingState"

    /** 单次录音最大时长（秒）—— Telegram 服务端硬上限是 5 分钟左右，这里给到 60 分钟。 */
    private const val MAX_DURATION_MS = 60L * 60L * 1000L

    /** 振幅采样间隔（毫秒）—— 控制波形刷新频率。 */
    private const val SAMPLE_INTERVAL_MS = 50L

    /** 实时波形最大柱数（滑动窗口）。 */
    private const val LIVE_WAVEFORM_CAPACITY = 60

    /** 持久波形（发送给 TDLib 的）目标柱数。 */
    private const val FINAL_WAVEFORM_BARS = 100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 录音目标 chatId —— 切到别的聊天时仍然记着。 */
    @Volatile var chatId: Long = 0L
        private set

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startElapsedMs: Long = 0L
    private var pausedAccumulatedMs: Long = 0L
    private var pauseStartMs: Long = 0L
    private var amplitudeJob: Job? = null

    /** 整段录制过程采集到的所有原始振幅（[0,32767]）。停止后用来生成最终 5-bit 波形。 */
    private val allAmplitudes = ArrayList<Int>(2048)

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _isPreview = MutableStateFlow(false)
    val isPreview = _isPreview.asStateFlow()

    /** 录制时长（毫秒，会动态刷新）。 */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    /** 当前峰值（[0,1f]），用于驱动单一峰值条。 */
    private val _currentLevel = MutableStateFlow(0f)
    val currentLevel = _currentLevel.asStateFlow()

    /** 实时波形（滑动窗口，[0,31] 范围）。 */
    private val _liveWaveform = MutableStateFlow(IntArray(0))
    val liveWaveform = _liveWaveform.asStateFlow()

    /** 预览阶段使用的最终波形（[0,31] 范围，长度 ~ FINAL_WAVEFORM_BARS）。 */
    private val _finalWaveform = MutableStateFlow(IntArray(0))
    val finalWaveform = _finalWaveform.asStateFlow()

    /** 录制完成后的文件（停止录音后非空，发送 / 取消后置空）。 */
    private val _recordedFile = MutableStateFlow<File?>(null)
    val recordedFile = _recordedFile.asStateFlow()

    /** 是否使用 Opus（API 29+）—— 决定文件后缀和 mime 类型。 */
    @Volatile var isOpus: Boolean = true
        private set

    fun startRecording(context: Context, chatId: Long): Boolean {
        if (_isRecording.value || _isPreview.value) {
            Log.w(TAG, "startRecording ignored: already in recording/preview")
            return false
        }

        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val suffix = if (opus) "oga" else "m4a"
        val file = File(cacheDir, "TGWrist_voice_${System.currentTimeMillis()}.$suffix")

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                if (opus) {
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    setAudioSamplingRate(48000)
                    setAudioEncodingBitRate(32000)
                    setAudioChannels(1)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(64000)
                    setAudioChannels(1)
                }
                setOutputFile(file.absolutePath)
                setMaxDuration(MAX_DURATION_MS.toInt())
                prepare()
                start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "start recording failed", t)
            try { rec.release() } catch (_: Throwable) {}
            file.delete()
            return false
        }

        recorder = rec
        recordingFile = file
        this.chatId = chatId
        this.isOpus = opus
        startElapsedMs = SystemClock.elapsedRealtime()
        pausedAccumulatedMs = 0L
        pauseStartMs = 0L
        allAmplitudes.clear()
        _liveWaveform.value = IntArray(0)
        _finalWaveform.value = IntArray(0)
        _recordedFile.value = null
        _durationMs.value = 0L
        _currentLevel.value = 0f
        _isPaused.value = false
        _isPreview.value = false
        _isRecording.value = true

        // TDLib 录音状态指示
        sendChatActionRecording(chatId)

        amplitudeJob?.cancel()
        amplitudeJob = scope.launch { sampleLoop() }
        return true
    }

    fun pauseRecording() {
        val rec = recorder ?: return
        if (!_isRecording.value || _isPaused.value) return
        try {
            rec.pause()
            pauseStartMs = SystemClock.elapsedRealtime()
            _isPaused.value = true
        } catch (t: Throwable) {
            Log.w(TAG, "pause failed", t)
        }
    }

    fun resumeRecording() {
        val rec = recorder ?: return
        if (!_isRecording.value || !_isPaused.value) return
        try {
            rec.resume()
            if (pauseStartMs > 0L) {
                pausedAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartMs
                pauseStartMs = 0L
            }
            _isPaused.value = false
            sendChatActionRecording(chatId)
        } catch (t: Throwable) {
            Log.w(TAG, "resume failed", t)
        }
    }

    /**
     * 停止录音并进入预览模式（如果文件有效）。
     * 录音过短（<1024 字节）会直接走 [cancelAll]。
     */
    fun stopAndPreview(): Boolean {
        if (!_isRecording.value) return false
        amplitudeJob?.cancel()
        amplitudeJob = null

        val rec = recorder
        recorder = null
        try {
            rec?.stop()
        } catch (_: Throwable) {
            // 录音过短时 stop 会抛异常，忽略
        }
        try { rec?.release() } catch (_: Throwable) {}

        if (_isPaused.value && pauseStartMs > 0L) {
            pausedAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartMs
            pauseStartMs = 0L
        }
        _isPaused.value = false
        _isRecording.value = false
        sendChatActionCancel(chatId)

        val file = recordingFile
        val valid = file != null && file.exists() && file.length() > 1024
        if (!valid) {
            file?.delete()
            recordingFile = null
            _recordedFile.value = null
            _isPreview.value = false
            _durationMs.value = 0L
            _currentLevel.value = 0f
            _liveWaveform.value = IntArray(0)
            _finalWaveform.value = IntArray(0)
            return false
        }

        _finalWaveform.value = buildFinalWaveform(allAmplitudes, FINAL_WAVEFORM_BARS)
        _recordedFile.value = file
        _isPreview.value = true
        _currentLevel.value = 0f
        return true
    }

    /** 完全取消（录音中或预览中均可），删除文件、重置全部状态。 */
    fun cancelAll() {
        amplitudeJob?.cancel()
        amplitudeJob = null

        val rec = recorder
        recorder = null
        if (rec != null) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
        val targetChat = chatId
        recordingFile?.delete()
        recordingFile = null

        _isRecording.value = false
        _isPaused.value = false
        _isPreview.value = false
        _recordedFile.value = null
        _durationMs.value = 0L
        _currentLevel.value = 0f
        _liveWaveform.value = IntArray(0)
        _finalWaveform.value = IntArray(0)
        allAmplitudes.clear()

        if (targetChat != 0L) sendChatActionCancel(targetChat)
        chatId = 0L
    }

    /**
     * 标记发送完成 —— 跟 [cancelAll] 等价，但不删除文件（外部已经把它递给 TDLib）。
     */
    fun consumeAfterSend() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        recorder = null
        recordingFile = null
        chatId = 0L
        _isRecording.value = false
        _isPaused.value = false
        _isPreview.value = false
        _recordedFile.value = null
        _durationMs.value = 0L
        _currentLevel.value = 0f
        _liveWaveform.value = IntArray(0)
        _finalWaveform.value = IntArray(0)
        allAmplitudes.clear()
    }

    /** 当前录制总时长（毫秒），考虑暂停区间。 */
    fun currentDurationMs(): Long {
        if (!_isRecording.value) return _durationMs.value
        val now = SystemClock.elapsedRealtime()
        val rawElapsed = now - startElapsedMs
        val paused = pausedAccumulatedMs + (if (_isPaused.value && pauseStartMs > 0L) now - pauseStartMs else 0L)
        return (rawElapsed - paused).coerceAtLeast(0L)
    }

    private suspend fun sampleLoop() {
        val window = ArrayDeque<Int>(LIVE_WAVEFORM_CAPACITY)
        while (scope.isActive && _isRecording.value) {
            try {
                val rec = recorder
                val raw = if (rec != null && !_isPaused.value) {
                    try { rec.maxAmplitude } catch (_: Throwable) { 0 }
                } else 0
                val clamped = raw.coerceIn(0, 32767)
                if (!_isPaused.value) {
                    allAmplitudes.add(clamped)
                    val normalized = ((clamped / 32767f) * 31f).toInt().coerceIn(0, 31)
                    if (window.size >= LIVE_WAVEFORM_CAPACITY) window.removeFirst()
                    window.addLast(normalized)
                    _liveWaveform.value = window.toIntArray()
                    _currentLevel.value = (clamped / 32767f).coerceIn(0f, 1f)
                }
                _durationMs.value = currentDurationMs()
            } catch (_: Throwable) {
                // 忽略采样失败，继续循环
            }
            delay(SAMPLE_INTERVAL_MS.milliseconds)
        }
    }

    private fun sendChatActionRecording(chatId: Long) {
        if (chatId == 0L) return
        runCatching {
            TgClient.send(
                org.drinkless.tdlib.TdApi.SendChatAction(
                    chatId, null, null,
                    org.drinkless.tdlib.TdApi.ChatActionRecordingVoiceNote()
                )
            ) { /* 忽略结果 */ }
        }
    }

    private fun sendChatActionCancel(chatId: Long) {
        if (chatId == 0L) return
        runCatching {
            TgClient.send(
                org.drinkless.tdlib.TdApi.SendChatAction(
                    chatId, null, null, null
                )
            ) { /* 忽略结果 */ }
        }
    }
}

/**
 * 将原始 [0,32767] 振幅序列重采样并量化到 [0,31]，作为预览展示用波形。
 */
private fun buildFinalWaveform(samples: List<Int>, targetBars: Int): IntArray {
    if (samples.isEmpty() || targetBars <= 0) return IntArray(0)
    val n = samples.size
    if (n <= targetBars) {
        return IntArray(n) { i ->
            ((samples[i] / 32767f) * 31f).toInt().coerceIn(0, 31)
        }
    }
    val out = IntArray(targetBars)
    val step = n.toFloat() / targetBars
    var peakAcrossBars = 0
    for (i in 0 until targetBars) {
        val from = (i * step).toInt()
        val to = ((i + 1) * step).toInt().coerceAtMost(n)
        var peak = 0
        for (j in from until to) if (samples[j] > peak) peak = samples[j]
        out[i] = peak
        if (peak > peakAcrossBars) peakAcrossBars = peak
    }
    // 归一化到 [0,31]，避免整体偏低
    val divisor = if (peakAcrossBars > 0) peakAcrossBars else 32767
    for (i in out.indices) {
        out[i] = ((out[i].toFloat() / divisor) * 31f).toInt().coerceIn(0, 31)
    }
    return out
}

/**
 * 把 [0,31] 振幅数组按 5-bit 紧凑打包，与 TDLib `Voice.waveform` 解码逻辑互逆。
 * 该函数公开给页面调用：发送语音消息时需要把波形塞进 `InputMessageVoiceNote.waveform`。
 */
fun packWaveform5Bit(amplitudes: IntArray): ByteArray {
    if (amplitudes.isEmpty()) return ByteArray(0)
    val totalBits = amplitudes.size * 5
    val byteCount = (totalBits + 7) / 8
    val out = ByteArray(byteCount)
    for (i in amplitudes.indices) {
        val value = amplitudes[i].coerceIn(0, 31)
        val bitIndex = i * 5
        val byteIndex = bitIndex ushr 3
        val bitOffset = bitIndex and 0x07
        val low = (out[byteIndex].toInt() and 0xFF) or ((value shl bitOffset) and 0xFF)
        out[byteIndex] = low.toByte()
        if (bitOffset + 5 > 8 && byteIndex + 1 < byteCount) {
            val high = (out[byteIndex + 1].toInt() and 0xFF) or (value ushr (8 - bitOffset))
            out[byteIndex + 1] = high.toByte()
        }
    }
    return out
}
