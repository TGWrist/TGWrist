package com.tgwrist.app.runtime

import android.content.Context.AUDIO_SERVICE
import android.media.AudioManager
import android.util.Log
import com.tgwrist.app.TGWrist
import com.tgwrist.app.utils.getNetworkType
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.thunderdog.challegram.voip.ConnectionStateListener
import org.thunderdog.challegram.voip.NetworkStats
import org.thunderdog.challegram.voip.VoIP
import org.thunderdog.challegram.voip.VoIPInstance
import org.thunderdog.challegram.voip.annotation.AudioState
import org.thunderdog.challegram.voip.annotation.CallState
import org.thunderdog.challegram.voip.annotation.VideoState

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
    /** 接听/挂断/发起正在 TDLib 往返，临时锁定按钮避免重复发送 */
    val actionLocked: Boolean = false,
)

object TgCallManager {
    private const val TAG = "TgCallManager"

    private lateinit var audioManager: AudioManager
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

    // ===========================
    // 初始化 / 订阅 TdLib 更新
    // ===========================
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        audioManager = TGWrist.context.getSystemService(AUDIO_SERVICE) as AudioManager
        _uiState.update { it.copy(isMute = audioManager.isMicrophoneMute) }
        subscribeAll()
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
                _uiState.update { it.copy(actionLocked = false) }
            }
            // UpdateCall(CallStateDiscarded) 会把状态重置
        }
    }

    private fun resetCallState() {
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
            isMute = if (::audioManager.isInitialized) audioManager.isMicrophoneMute else false
        )
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
        setMute(false)

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
                delay(1000L)
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
