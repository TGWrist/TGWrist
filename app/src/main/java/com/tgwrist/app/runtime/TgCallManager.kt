package com.tgwrist.app.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.Intent
import android.content.pm.ApplicationInfo
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

    // Horologist OutputSwitcher 用的常量；AndroidX 没有公开符号，这里硬编码
    private const val MEDIA_OUTPUT_PANEL_ACTION =
        "com.android.settings.panel.action.MEDIA_OUTPUT"
    private const val MEDIA_OUTPUT_PANEL_EXTRA_PACKAGE_NAME =
        "com.android.settings.panel.extra.PACKAGE_NAME"

    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var voipItem: VoIPInstance? = null
    private var durationJob: Job? = null
    private var callId = 0
    /** 正在查询的 peerUserId，避免同一个 call 重复发 GetUser */
    private var loadedPeerUserId: Long = 0L

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
     */
    fun refreshAudioOutput() {
        if (!::audioManager.isInitialized) return
        _uiState.update { it.copy(audioOutput = currentAudioOutput()) }
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
     * - Wear OS 5 / Android 14+ 等支持系统“媒体输出切换器”的机型：解析系统级
     *   `com.android.settings.panel.action.MEDIA_OUTPUT` 面板组件并显式启动，
     *   这是官方推荐的输出选择 UI（参考 Horologist `OutputSwitcher`）。
     * - 系统未提供该面板：回退到手动两态切换（扬声器 ↔ 蓝牙 / 有线 / 听筒）。
     */
    fun requestSwitchAudioOutput(context: Context) {
        if (!::audioManager.isInitialized) return
        if (launchSystemMediaOutputSwitcher(context)) {
            // 系统面板会异步切换路由，这里立刻刷一次只是同步当前值；真正的更新由
            // AudioDeviceCallback / OnCommunicationDeviceChangedListener 推送。
            refreshAudioOutput()
            return
        }
        toggleAudioOutputManually()
        refreshAudioOutput()
    }

    /**
     * 尝试启动系统媒体输出切换器面板。返回 true 表示已成功 startActivity。
     *
     * 只接受系统应用 / 系统更新过的应用提供的面板，避免被第三方 App 抢响应。
     */
    private fun launchSystemMediaOutputSwitcher(context: Context): Boolean {
        val intent = Intent(MEDIA_OUTPUT_PANEL_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MEDIA_OUTPUT_PANEL_EXTRA_PACKAGE_NAME, context.packageName)
        val component = resolveSystemComponent(context, intent) ?: return false
        intent.component = component
        return try {
            context.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Launch system media output switcher failed", e)
            false
        }
    }

    private fun resolveSystemComponent(context: Context, intent: Intent): ComponentName? {
        val pm = context.packageManager
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        for (info in resolveInfos) {
            val activity = info.activityInfo ?: continue
            val app = activity.applicationInfo ?: continue
            if (app.flags and systemFlags != 0) {
                return ComponentName(activity.packageName, activity.name)
            }
        }
        return null
    }

    /**
     * 在“扬声器”和“非扬声器”之间手动切换：当前是扬声器就切到耳机/蓝牙/听筒；
     * 当前不是扬声器就切回扬声器。手表上没有听筒，绝大多数设备只在两者之间切换。
     */
    private fun toggleAudioOutputManually() {
        val current = currentAudioOutput()
        if (current == AudioOutputDevice.SPEAKER) {
            switchToNonSpeaker()
        } else {
            switchToSpeaker()
        }
    }

    private fun switchToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager
                .availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
                return
            }
        }
        @Suppress("DEPRECATION")
        run {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun switchToNonSpeaker() {
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
                audioManager.setCommunicationDevice(target)
                return
            }
            // 没有可用的非扬声器目标：清掉路由让系统选默认
            audioManager.clearCommunicationDevice()
            return
        }
        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = false
            // 若有蓝牙耳机，启动 SCO 让通话路由过去
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
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
     * 我们的 UI 能第一时间更新，不必等用户再次打开音量对话框。
     */
    private fun registerAudioRouteCallbacks() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnCommunicationDeviceChangedListener(
                TGWrist.context.mainExecutor,
                communicationDeviceListener,
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

    private val communicationDeviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { refreshAudioOutput() }

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
                            if (Config.isMainActivityOnFront) {
                                // Activity 在前台，可以直接用有麦克风权限的服务
                                IncomingCallNotificationService.stop(ctx)
                                CallForegroundService.start(ctx, title, text)
                            } else {
                                // 后台收到来电：用轻量服务（无麦克风权限，FCM 高优先级豆免内合法）
                                CallForegroundService.stop(ctx)
                                IncomingCallNotificationService.start(ctx, title, text)
                            }
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
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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
