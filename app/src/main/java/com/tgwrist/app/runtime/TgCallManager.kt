package com.tgwrist.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tgwrist.app.CallForegroundService
import com.tgwrist.app.IncomingCallNotificationService
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import com.tgwrist.app.data.AudioOutputDevice
import com.tgwrist.app.data.CallUiState
import com.tgwrist.app.utils.getNetworkType
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.thunderdog.challegram.voip.ConnectionStateListener
import org.thunderdog.challegram.voip.NetworkStats
import org.thunderdog.challegram.voip.VoIP
import org.thunderdog.challegram.voip.VoIPInstance
import org.thunderdog.challegram.voip.annotation.AudioState
import org.thunderdog.challegram.voip.annotation.CallState
import org.thunderdog.challegram.voip.annotation.VideoState
import kotlin.time.Duration.Companion.milliseconds

const val CALL_STATE_NONE = 0
const val CALL_STATE_PENDING = 1
const val CALL_STATE_INCOMING = 2
const val CALL_STATE_CALLING = 3
const val OPEN_CALL_PAGE = "open_call_page"

const val CALL_STATUS_NONE = 0
const val CALL_STATUS_REQUESTING = 1
const val CALL_STATUS_WAITING = 2
const val CALL_STATUS_RINGING = 3
const val CALL_STATUS_CALLING_YOU = 4
const val CALL_STATUS_EXCHANGING_KEYS = 5
const val CALL_STATUS_USER_PRIVACY_RESTRICTED = 6
const val CALL_STATUS_ERROR = 7

object TgCallManager {
    private const val TAG = "TgCallManager"
    /** 音频输出切换的防抖窗口:窗口内的连点会被忽略,避免反复折腾 SCO 链路 */
    private const val SWITCH_DEBOUNCE_MS = 600L
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var voipItem: VoIPInstance? = null
    private var durationJob: Job? = null
    private var callId = 0
    /** 正在查询的 peerUserId，避免同一个 call 重复发 GetUser */
    private var loadedPeerUserId: Long = 0L

    /** 上次音频输出切换被接受的时间戳,用于点击防抖 */
    private var lastAudioOutputSwitchAt: Long = 0L
    /** 切换后的兜底 refresh 任务,新切换发起时会取消旧的,避免旧值盖掉新乐观状态 */
    private var pendingAudioRefreshJob: Job? = null

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState = _uiState.asStateFlow()

    /** 旧调用点只关心 int 状态，这里用 map 派生一个兼容 flow */
    val callState = _uiState
        .map { it.callState }
        .stateIn(scope, SharingStarted.Eagerly, CALL_STATE_NONE)

    /** 切换静音（同时更新 AudioManager / VoIP / UI state） */
    fun toggleMute() {
        setMute(!_uiState.value.isMute)
    }

    fun setMute(value: Boolean) {
        if (!::audioManager.isInitialized) return
        audioManager.isMicrophoneMute = value
        voipItem?.setMicDisabled(value)
        _uiState.update { it.copy(isMute = value) }
    }

    /** 读取一次系统通话音量并写入 UI state */
    fun refreshCallVolume() {
        if (!::audioManager.isInitialized) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        _uiState.update { it.copy(callVolume = cur, maxCallVolume = max) }
    }

    /** 设置通话音量（语音通话流），值会被 coerce 到 [0, max] 区间 */
    fun setCallVolume(value: Int) {
        if (!::audioManager.isInitialized) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val target = value.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
        _uiState.update { it.copy(callVolume = target, maxCallVolume = max) }
    }

    /**
     * 读取当前通话音频输出路由并写入 UI state。
     *
     * API 31+ 优先使用 [AudioManager.getCommunicationDevice]，老设备退回到
     * 历史 API（[AudioManager.isBluetoothScoOn]、[AudioManager.isWiredHeadsetOn]
     * 与 [AudioManager.isSpeakerphoneOn]）。
     *
     * 顺手把通话音量一并刷新:STREAM_VOICE_CALL 在不同物理输出上各自维护一份音量,
     * 系统切完路由后返回的已经是新设备对应的音量了,UI 必须同步,否则会出现"切到
     * 蓝牙耳机后音量条还停留在扬声器档位"的错位。
     */
    fun refreshAudioOutput() {
        if (!::audioManager.isInitialized) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        _uiState.update {
            it.copy(
                audioOutput = currentAudioOutput(),
                callVolume = cur,
                maxCallVolume = max,
            )
        }
    }

    private fun currentAudioOutput(): AudioOutputDevice {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { return it.type.toOutputDevice() }
        }
        @Suppress("DEPRECATION")
        return when {
            audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn -> AudioOutputDevice.BLUETOOTH
            audioManager.isWiredHeadsetOn -> AudioOutputDevice.WIRED_HEADSET
            audioManager.isSpeakerphoneOn -> AudioOutputDevice.SPEAKER
            else -> AudioOutputDevice.EARPIECE
        }
    }

    private fun Int.toOutputDevice(): AudioOutputDevice = when (this) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioOutputDevice.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioOutputDevice.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutputDevice.EARPIECE
        else -> AudioOutputDevice.SPEAKER
    }

    /**
     * 让用户切换通话音频输出。
     *
     * 物理路由切换是异步的（蓝牙 SCO 链路建立尤其慢），切完立刻 `refreshAudioOutput()`
     * 拿到的多半还是旧值。所以这里做"乐观更新"：先按预测的目标 UI 状态推一次,真正
     * 的最终态由 [communicationDeviceListener] / [audioDeviceCallback] / [scoReceiver]
     * 异步回调时再 [refreshAudioOutput] 校正。
     *
     * 同时做两层抖动消除:
     *  1. 短时间(< [SWITCH_DEBOUNCE_MS]ms)内的连点直接忽略。频繁切换会让蓝牙 SCO 反复
     *     建立 / 断开,系统底层很容易卡住或出现奇怪中间态。
     *  2. 每次切换会启动一个 [pendingAudioRefreshJob] 在 1.5s 后兜底 refresh,新切换
     *     来时取消旧 job。否则旧 job 会拿着旧路由值盖掉新乐观状态,UI 又跳回去。
     */
    fun requestSwitchAudioOutput() {
        if (!::audioManager.isInitialized) return

        val now = System.currentTimeMillis()
        if (now - lastAudioOutputSwitchAt < SWITCH_DEBOUNCE_MS) {
            Log.d(TAG, "audio output switch ignored (debounce)")
            return
        }

        // 在还没进入通话音频模式时,setCommunicationDevice / startBluetoothSco 大概率会被
        // 系统忽略。这里兜底把 mode 调过去,保证用户在等待接听阶段也能切到蓝牙耳机。
        ensureCommunicationMode()

        val current = currentAudioOutput()
        val predicted = if (current == AudioOutputDevice.SPEAKER) {
            switchToNonSpeaker()
        } else {
            switchToSpeaker()
        }

        if (predicted != null) {
            lastAudioOutputSwitchAt = now
            // 乐观更新:不等系统 callback,UI 立刻反映目标设备
            _uiState.update { it.copy(audioOutput = predicted) }

            // 兜底:个别机型 callback 不触发,延迟读一次实际状态。新切换来时取消旧的,
            // 避免旧 job 用旧路由覆盖新乐观状态。
            pendingAudioRefreshJob?.cancel()
            pendingAudioRefreshJob = scope.launch {
                delay(1500L.milliseconds)
                refreshAudioOutput()
            }
        } else {
            // 立即失败,把状态拉回当前实际值
            refreshAudioOutput()
        }
    }

    /**
     * 切到扬声器,返回最终预期的 UI 设备类型;若底层立刻拒绝则返回 null。
     */
    private fun switchToSpeaker(): AudioOutputDevice? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager
                .availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                val ok = audioManager.setCommunicationDevice(speaker)
                if (!ok) {
                    Log.w(TAG, "setCommunicationDevice(SPEAKER) rejected by system")
                    return null
                }
                return AudioOutputDevice.SPEAKER
            }
            return null
        }
        @Suppress("DEPRECATION")
        run {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = true
        }
        return AudioOutputDevice.SPEAKER
    }

    /**
     * 切到非扬声器(蓝牙 / 有线 / 听筒中按优先级第一个可用项),返回该 UI 设备类型;
     * 若没有可用目标或被系统拒绝则返回 null。
     */
    private fun switchToNonSpeaker(): AudioOutputDevice? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 优先蓝牙 -> 有线耳机 -> 听筒
            val priority = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            )
            val available = audioManager.availableCommunicationDevices
            val target = priority
                .firstNotNullOfOrNull { type -> available.firstOrNull { it.type == type } }
            if (target != null) {
                val ok = audioManager.setCommunicationDevice(target)
                if (!ok) {
                    Log.w(TAG, "setCommunicationDevice rejected: type=${target.type}")
                    return null
                }
                return target.type.toOutputDevice()
            }
            // 没有非扬声器目标:清掉路由让系统自选,保持 UI 同步实际状态
            audioManager.clearCommunicationDevice()
            return null
        }
        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = false
            // 若有蓝牙耳机,启动 SCO 让通话路由过去
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                return AudioOutputDevice.BLUETOOTH
            }
        }
        // 没蓝牙就交给系统(可能是有线耳机或听筒),路由变化后由 callback 回填
        return null
    }

    // ===========================
    // 初始化 / 订阅 TdLib 更新
    // ===========================
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        audioManager = TGWrist.context.getSystemService(AUDIO_SERVICE) as AudioManager
        _uiState.update {
            it.copy(
                isMute = audioManager.isMicrophoneMute,
                callVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL),
                maxCallVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                audioOutput = currentAudioOutput(),
            )
        }
        registerAudioRouteCallbacks()
        subscribeAll()
        observeForegroundService()
    }

    /**
     * 注册输出设备 / 路由变化监听，确保用户在系统切换器（或外部按键）改了输出后，
     * UI 能第一时间更新，不必等用户再次打开音量对话框。
     */
    private fun registerAudioRouteCallbacks() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnCommunicationDeviceChangedListener(
                TGWrist.context.mainExecutor,
                communicationDeviceListener!!,
            )
        } else {
            // API 31+ 已用 OnCommunicationDeviceChangedListener,只在更老的设备上注册
            // ACTION_SCO_AUDIO_STATE_UPDATED:监听 SCO 链路真正建立 / 断开,弥补
            // AudioDeviceCallback 监听不到 SCO 状态变化的问题
            @Suppress("DEPRECATION")
            TGWrist.context.registerReceiver(
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            )
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioOutput()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioOutput()
        }
    }

    private val communicationDeviceListener by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener { refreshAudioOutput() }
        } else null
    }

    private val scoReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            val state = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR,
            )
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED ||
                state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
            ) {
                refreshAudioOutput()
            }
        }
    }

    /**
     * 确保 [AudioManager] 处于 [AudioManager.MODE_IN_COMMUNICATION]。
     *
     * 这是 `setCommunicationDevice` / `startBluetoothSco` 真正生效的前提:在 `MODE_NORMAL`
     * 下,系统会忽略来自非系统电话栈的通话路由请求,UI 上看就是"切了等于没切"。
     */
    private fun ensureCommunicationMode() {
        if (!::audioManager.isInitialized) return
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    /**
     * 监听通话状态变化，控制前台服务的生命周期与通知文案。
     *
     * 之所以放在 manager 内部驱动而不是让 UI 层去 start/stop，是因为：
     *  1. UI 是临时的（Compose 屏幕可能销毁），通话却要在锁屏 / 后台继续；
     *  2. 多客户端入口（手动发起、来电推送、点击通话条目）都会经过 manager，集中处理避免遗漏。
     */
    private fun observeForegroundService() {
        val ctx = TGWrist.context
        scope.launch {
            _uiState
                .map { state ->
                    Triple(
                        state.callState,
                        // 通知里展示的标题：优先对端名字
                        state.peerName.ifBlank { ctx.getString(R.string.Call_notification_default_title) },
                        notificationStatusText(ctx, state)
                    )
                }
                .distinctUntilChanged()
                .collect { (callState, title, text) ->
                    when (callState) {
                        CALL_STATE_NONE -> {
                            // 通话结束，两个服务都停掉
                            IncomingCallNotificationService.stop(ctx)
                            CallForegroundService.stop(ctx)
                        }
                        CALL_STATE_INCOMING -> {
                            // 来电使用用轻量服务（无麦克风权限）
                            CallForegroundService.stop(ctx)
                            IncomingCallNotificationService.start(ctx, title, text)
                        }
                        CALL_STATE_CALLING -> {
                            // 用户已接听：必须切换到有麦克风权限的服务，否则没有声音
                            IncomingCallNotificationService.stop(ctx)
                            CallForegroundService.start(ctx, title, text)
                        }
                        else -> {
                            // CALL_STATE_PENDING 等其他状态：保持当前服务不变，只更新文案
                            if (Config.isMainActivityOnFront) {
                                CallForegroundService.start(ctx, title, text)
                            } else {
                                // 后台发起的电话（PENDING）也用轻量服务占位
                                IncomingCallNotificationService.start(ctx, title, text)
                            }
                        }
                    }
                }
        }
    }

    private fun notificationStatusText(ctx: Context, state: CallUiState): String {
        return when (state.callState) {
            CALL_STATE_INCOMING -> ctx.getString(R.string.Call_status_calling_you)
            CALL_STATE_PENDING -> when (state.callStatus) {
                CALL_STATUS_REQUESTING -> ctx.getString(R.string.Call_status_requesting)
                CALL_STATUS_WAITING -> ctx.getString(R.string.Call_status_waiting)
                CALL_STATUS_RINGING -> ctx.getString(R.string.Call_status_ringing)
                CALL_STATUS_EXCHANGING_KEYS -> ctx.getString(R.string.Call_status_exchanging_keys)
                else -> ctx.getString(R.string.Tap_to_return_to_call)
            }
            CALL_STATE_CALLING -> formatNotificationDuration(state.callDurationMillis)
            else -> ctx.getString(R.string.Tap_to_return_to_call)
        }
    }

    private fun formatNotificationDuration(durationMillis: Long): String {
        val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val mm = if (minutes < 10L) "0$minutes" else "$minutes"
        val ss = if (seconds < 10L) "0$seconds" else "$seconds"
        return if (hours > 0L) "$hours:$mm:$ss" else "$mm:$ss"
    }

    // 发起电话
    fun createCall(userId: Long) {
        val current = _uiState.value
        if (current.callState != CALL_STATE_NONE || current.actionLocked) {
            Log.d(TAG, "createCall ignored: state=${current.callState}, locked=${current.actionLocked}")
            return
        }
        // 立即锁定并改为 PENDING，避免用户连点发出多次 CreateCall
        _uiState.update {
            it.copy(
                callState = CALL_STATE_PENDING,
                callStatus = CALL_STATUS_REQUESTING,
                isOutgoing = true,
                peerUserId = userId,
                emojis = emptyList(),
                callDurationMillis = 0L,
                actionLocked = true,
            )
        }
        // 提前进入通话音频模式,等待对方接听阶段也能切蓝牙 / 有线耳机
        ensureCommunicationMode()
        loadPeerUser(userId)
        TgClient.send(TdApi.CreateCall(userId, VoIP.getProtocol(), false)) { result ->
            if (result is TdApi.CallId) {
                callId = result.id
                _uiState.update { it.copy(actionLocked = false) }
                GlobalEventBus.send(OPEN_CALL_PAGE)
            } else {
                // 失败（TdApi.Error 等）：回滚到 NONE
                Log.w(TAG, "CreateCall failed: $result")
                resetCallState()
            }
        }
    }

    // 接听电话
    fun acceptCall() {
        val current = _uiState.value
        if (current.callState != CALL_STATE_INCOMING || current.actionLocked) return
        _uiState.update { it.copy(actionLocked = true) }
        TgClient.send(TdApi.AcceptCall(callId, VoIP.getProtocol())) { result ->
            if (result is TdApi.Error) {
                Log.w(TAG, "AcceptCall failed: $result")
                _uiState.update { it.copy(actionLocked = false) }
            }
            // 成功情况下等 UpdateCall 到来后自然推进状态并解锁
        }
    }

    // 挂断 / 拒绝
    fun discardCall(isDisconnected: Boolean = false) {
        val current = _uiState.value
        if (current.callState == CALL_STATE_NONE || current.actionLocked) return
        _uiState.update { it.copy(actionLocked = true) }
        TgClient.send(
            TdApi.DiscardCall(
                callId,
                isDisconnected,
                "",
                voipItem?.callDuration?.toInt() ?: 0,
                false,
                voipItem?.connectionId ?: 0
            )
        ) { result ->
            if (result is TdApi.Error) {
                Log.w(TAG, "DiscardCall failed: $result")
                //_uiState.update { it.copy(actionLocked = false) }
            }
            resetCallState()
        }
    }

    private fun resetCallState(callStatus: Int = CALL_STATUS_NONE) {
        callId = 0
        loadedPeerUserId = 0L
        durationJob?.cancel()
        durationJob = null
        // 通话结束,取消可能还在等待的兜底 refresh,避免触发对已重置 UI 的写入
        pendingAudioRefreshJob?.cancel()
        pendingAudioRefreshJob = null
        lastAudioOutputSwitchAt = 0L
        voipItem?.performDestroy()
        voipItem = null
        if (::audioManager.isInitialized) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        _uiState.value = CallUiState(
            isMute = if (::audioManager.isInitialized) audioManager.isMicrophoneMute else false,
            callVolume = if (::audioManager.isInitialized)
                audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) else 0,
            maxCallVolume = if (::audioManager.isInitialized)
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) else 0,
            audioOutput = if (::audioManager.isInitialized) currentAudioOutput() else AudioOutputDevice.SPEAKER,
            callStatus = callStatus
        )
        runBlocking {
            delay(10000L.milliseconds)
            if (!Config.isMainActivityAlive && callState.value == CALL_STATE_NONE) TgClient.close()
        }
    }

    private fun loadPeerUser(userId: Long) {
        if (userId == 0L || userId == loadedPeerUserId) return
        loadedPeerUserId = userId
        TgClient.send(TdApi.GetUser(userId)) { res ->
            if (res is TdApi.User && _uiState.value.peerUserId == userId) {
                val name = listOfNotNull(res.firstName, res.lastName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                _uiState.update {
                    it.copy(
                        peerName = name,
                        peerPhoto = res.profilePhoto?.small,
                        peerAccentColorId = res.accentColorId,
                    )
                }
            }
        }
    }

    private fun subscribeAll() {
        TgClient.subscribe(TdApi.UpdateNewCallSignalingData::class.java) { update ->
            val data = update.data
            Log.d(TAG, "Receive signaling packet, length ${data.size}")
            voipItem?.handleIncomingSignalingData(data)
        }

        TgClient.subscribe(TdApi.UpdateCall::class.java) { update ->
            val call = update.call
            callId = call.id
            // 任何 UpdateCall 都确认一下对端用户信息
            if (call.userId != 0L) {
                if (_uiState.value.peerUserId != call.userId) {
                    _uiState.update { it.copy(peerUserId = call.userId) }
                }
                loadPeerUser(call.userId)
            }

            when (val state = call.state) {
                is TdApi.CallStateReady -> handleCallReady(call, state)
                is TdApi.CallStatePending -> handleCallPending(call, state)
                is TdApi.CallStateExchangingKeys -> {
                    Log.d(TAG, "Exchanging keys")
                    _uiState.update {
                        it.copy(
                            callState = CALL_STATE_PENDING,
                            callStatus = CALL_STATUS_EXCHANGING_KEYS,
                            peerUserId = if (call.userId != 0L) call.userId else it.peerUserId,
                            actionLocked = false,
                        )
                    }
                }
                is TdApi.CallStateHangingUp, is TdApi.CallStateDiscarded -> {
                    Log.d(TAG, "Call is hanging up or discarded")
                    resetCallState()
                }
                is TdApi.CallStateError -> {
                    Log.d(TAG, "Call is in error state: ${state.error.message}")
                    resetCallState(if (state.error.code == 403) CALL_STATUS_USER_PRIVACY_RESTRICTED else CALL_STATUS_ERROR)
                }
                else -> {
                    Log.d(TAG, "Call state: $state")
                }
            }
        }
    }

    private fun handleCallPending(call: TdApi.Call, state: TdApi.CallStatePending) {
        Log.d(TAG, "Call is pending: isCreated=${state.isCreated}, isReceived=${state.isReceived}")
        val current = _uiState.value
        if (current.callState == CALL_STATE_NONE) {
            // 来电:在 ringing 阶段就把 audio mode 切到通话,这样用户在响铃时点蓝牙耳机
            // 切换按钮才有反应(MODE_NORMAL 下系统会忽略 setCommunicationDevice)
            ensureCommunicationMode()
            _uiState.update {
                it.copy(
                    callState = CALL_STATE_INCOMING,
                    callStatus = CALL_STATUS_CALLING_YOU,
                    isOutgoing = false,
                    peerUserId = if (call.userId != 0L) call.userId else it.peerUserId,
                    emojis = emptyList(),
                    callDurationMillis = 0L,
                    actionLocked = false,
                )
            }
            GlobalEventBus.send(OPEN_CALL_PAGE)
            return
        }

        val pendingStatus = when {
            state.isReceived -> CALL_STATUS_RINGING
            state.isCreated -> CALL_STATUS_WAITING
            else -> CALL_STATUS_REQUESTING
        }
        _uiState.update {
            it.copy(
                callState = if (it.isOutgoing) CALL_STATE_PENDING else CALL_STATE_INCOMING,
                callStatus = if (it.isOutgoing) pendingStatus else CALL_STATUS_CALLING_YOU,
                peerUserId = if (call.userId != 0L) call.userId else it.peerUserId,
                actionLocked = false,
            )
        }
    }

    private fun handleCallReady(call: TdApi.Call, state: TdApi.CallStateReady) {
        Log.d(TAG, "Call is ready")

        val stateListener = object : ConnectionStateListener {
            override fun onSignallingDataEmitted(data: ByteArray?) {
                if (data == null) return
                Log.d(TAG, "Send signaling packet, length ${data.size}")
                TgClient.send(TdApi.SendCallSignalingData(call.id, data)) { result ->
                    Log.d(TAG, "SendCallSignalingData: $result")
                }
            }

            override fun onConnectionStateChanged(context: VoIPInstance, @CallState newState: Int) {
                Log.d(TAG, "VoIP connection status changes: $newState")
                if (newState == CallState.ESTABLISHED) {
                    updateCallDuration()
                }
            }

            override fun onRemoteMediaStateChanged(
                context: VoIPInstance,
                @AudioState audioState: Int,
                @VideoState videoState: Int
            ) {
                Log.d(TAG, "Remote media status: audio=$audioState, video=$videoState")
            }

            override fun onStopped(
                releasedContext: VoIPInstance,
                finalStats: NetworkStats,
                debugLog: String?
            ) {
                Log.d(TAG, "VoIP stopped: $finalStats")
                debugLog?.let { Log.d("VoIP", "VoIP debug log: $it") }
            }
        }

        // 进入通话模式
        ensureCommunicationMode()
        // 不强制取消静音；保留用户在等待阶段的静音选择
        // 若需要把当前 isMute 同步到 VoIP / AudioManager：
        setMute(_uiState.value.isMute)

        voipItem = VoIP.instantiateAndConnect(
            call,
            state,
            stateListener,
            false,
            null,
            getNetworkType(TGWrist.context),
            true,
            1,
            false
        )

        val emojis = state.emojis?.take(4)?.toList().orEmpty()
        _uiState.update {
            it.copy(
                callState = CALL_STATE_CALLING,
                callStatus = CALL_STATUS_NONE,
                emojis = emojis,
                callDurationMillis = 0L,
                actionLocked = false,
            )
        }
        startDurationUpdates()
    }

    private fun startDurationUpdates() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive) {
                updateCallDuration()
                delay(1000L.milliseconds)
            }
        }
    }

    private fun updateCallDuration() {
        val duration = voipItem?.callDuration ?: VoIPInstance.DURATION_UNKNOWN
        if (duration < 0L) return
        _uiState.update {
            if (it.callState == CALL_STATE_CALLING) {
                it.copy(callDurationMillis = duration)
            } else {
                it
            }
        }
    }
}
