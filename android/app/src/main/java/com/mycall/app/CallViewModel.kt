package com.mycall.app

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

data class UiState(
    val serverUrl: String = "https://mycall-wji0.onrender.com",
    val myCallId: String? = null,
    val isRegistered: Boolean = false,
    val onlineUsers: List<String> = emptyList(),
    val connectionStatus: String = "Disconnected",
    val isInCall: Boolean = false,
    val peerId: String? = null,
    val callTimer: String = "00:00",
    val isRinging: Boolean = false,
    val isMuted: Boolean = false,
    val remoteVolume: Float = 1.0f,
    val localLevel: Int = 0,
    val remoteLevel: Int = 0,
    val statusMessage: String? = null,
    val incomingCallFrom: String? = null,
    val isCallPanelVisible: Boolean = false
)

class CallViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CallViewModel"
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // For easy access from UI
    var serverUrl by mutableStateOf("https://mycall-wji0.onrender.com")
        private set
    var myCallId by mutableStateOf<String?>(null)
        private set
    var isRegistered by mutableStateOf(false)
        private set
    var onlineUsers by mutableStateOf<List<String>>(emptyList())
        private set
    var connectionStatus by mutableStateOf("Disconnected")
        private set
    var peerId by mutableStateOf<String?>(null)
        private set
    var callTimer by mutableStateOf("00:00")
        private set
    var localLevel by mutableStateOf(0)
        private set
    var remoteLevel by mutableStateOf(0)
        private set
    var isMuted by mutableStateOf(false)
        private set
    var remoteVolume by mutableStateOf(1.0f)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var incomingCallFrom by mutableStateOf<String?>(null)
        private set
    var isCallPanelVisible by mutableStateOf(false)
        private set
    var isInCall by mutableStateOf(false)
        private set

    private var signaling: SignalingClient? = null
    private var webrtc: WebRTCClient? = null

    private var timerJob: kotlinx.coroutines.Job? = null
    private var levelJob: kotlinx.coroutines.Job? = null
    private var isInitiator = false
    private var currentPeerId: String? = null

    fun updateServerUrl(url: String) {
        serverUrl = url.trim()
        _uiState.value = _uiState.value.copy(serverUrl = serverUrl)
    }

    fun connectSignaling() {
        disconnectSignaling()

        signaling = SignalingClient(
            serverUrl = serverUrl,
            onEvent = ::handleSignalingEvent,
            onConnected = {
                connectionStatus = "Connected"
                _uiState.value = _uiState.value.copy(connectionStatus = "Connected")
                // Refresh online users after connect
                if (isRegistered) {
                    signaling?.getOnlineUsers { users -> updateOnlineUsers(users) }
                }
            },
            onDisconnected = {
                connectionStatus = "Disconnected"
                _uiState.value = _uiState.value.copy(connectionStatus = "Disconnected")
            }
        )
        signaling?.connect()
    }

    private fun disconnectSignaling() {
        signaling?.disconnect()
        signaling = null
    }

    fun registerCallId(callId: String) {
        if (callId.length < 2) {
            showStatus("Call ID must be at least 2 characters")
            return
        }

        signaling?.register(callId) { success, error, registeredId ->
            if (success && registeredId != null) {
                myCallId = registeredId
                isRegistered = true
                _uiState.value = _uiState.value.copy(
                    myCallId = registeredId,
                    isRegistered = true
                )
                showStatus("You are now reachable as \"$registeredId\"")

                // Get latest online list
                signaling?.getOnlineUsers { users ->
                    updateOnlineUsers(users)
                }
            } else {
                showStatus(error ?: "Registration failed")
            }
        }
    }

    private fun updateOnlineUsers(users: List<String>) {
        val filtered = users.filter { it != myCallId }
        onlineUsers = filtered
        _uiState.value = _uiState.value.copy(onlineUsers = filtered)
    }

    fun startCall(targetId: String) {
        if (!isRegistered || myCallId == null) {
            showStatus("Set your Call ID first")
            return
        }
        if (targetId.isBlank() || targetId == myCallId) {
            showStatus("Enter a valid different Call ID")
            return
        }

        val app = getApplication<Application>()

        // Create WebRTC client
        webrtc = WebRTCClient(
            context = app,
            onIceCandidate = { candidate ->
                signaling?.sendIceCandidate(
                    targetId = targetId,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    fromId = myCallId!!
                )
            },
            onConnectionStateChange = { state ->
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    showStatus(null)
                    startCallTimer()
                }
                if (state == PeerConnection.PeerConnectionState.FAILED ||
                    state == PeerConnection.PeerConnectionState.DISCONNECTED ||
                    state == PeerConnection.PeerConnectionState.CLOSED) {
                    if (isInCall) endCall()
                }
            },
            onRemoteStreamReady = {
                startCallTimer()
                showStatus(null)
            },
            onLocalOfferReady = { tid, sdp -> onOfferCreated(tid, sdp) },
            onLocalAnswerReady = { tid, sdp -> onAnswerCreated(tid, sdp) }
        ).apply { initialize() }

        // Request mic + start call signaling
        webrtc?.setInCallAudioMode()

        currentPeerId = targetId
        isInitiator = true
        peerId = targetId
        isCallPanelVisible = true
        isInCall = true

        _uiState.value = _uiState.value.copy(
            peerId = targetId,
            isCallPanelVisible = true,
            isInCall = true,
            isRinging = true
        )

        signaling?.callUser(targetId, myCallId!!)
        showStatus("Calling $targetId...")

        // Start local level updates
        startLevelUpdates()
    }

    // Called from WebRTCClient when it creates local offer (via activity bridge)
    fun onOfferCreated(targetId: String, sdp: SessionDescription) {
        signaling?.sendOffer(targetId, sdp.description, myCallId ?: "")
    }

    fun onAnswerCreated(targetId: String, sdp: SessionDescription) {
        signaling?.sendAnswer(targetId, sdp.description, myCallId ?: "")
    }

    private fun handleSignalingEvent(event: String, data: org.json.JSONObject?) {
        when (event) {
            "online-users" -> {
                val users = mutableListOf<String>()
                data?.optJSONArray("users")?.let { arr ->
                    for (i in 0 until arr.length()) users.add(arr.optString(i))
                }
                updateOnlineUsers(users)
            }

            "incoming-call" -> {
                val from = data?.optString("fromId") ?: return
                if (isInCall) {
                    // Busy - auto reject like web
                    signaling?.answerCall(from, false, myCallId ?: "")
                    return
                }
                incomingCallFrom = from
                _uiState.value = _uiState.value.copy(incomingCallFrom = from)
            }

            "call-initiated" -> {
                // We are ringing the other side
            }

            "call-accepted" -> {
                val other = data?.optString("fromId") ?: data?.optString("targetId") ?: return
                isCallPanelVisible = true
                peerId = other
                currentPeerId = other
                _uiState.value = _uiState.value.copy(isRinging = false, peerId = other)

                if (isInitiator) {
                    // Only initiator creates and sends the offer
                    webrtc?.startCallAsInitiator(other)
                }
            }

            "call-rejected" -> {
                showStatus("Call declined")
                cleanupCall()
            }

            "call-error" -> {
                val msg = data?.optString("message") ?: "Call error"
                showStatus(msg)
                cleanupCall()
            }

            "offer" -> {
                val fromId = data?.optString("fromId") ?: return
                val offer = data.optJSONObject("offer") ?: return
                val sdp = offer.optString("sdp")

                if (webrtc == null) {
                    // We are the callee - create WebRTC now
                    val app = getApplication<Application>()
                    webrtc = WebRTCClient(
                        context = app,
                        onIceCandidate = { cand ->
                            signaling?.sendIceCandidate(fromId, cand.sdp, cand.sdpMid, cand.sdpMLineIndex, myCallId!!)
                        },
                        onConnectionStateChange = { st ->
                            if (st == PeerConnection.PeerConnectionState.CONNECTED) startCallTimer()
                        },
                        onRemoteStreamReady = { startCallTimer() },
                        onLocalOfferReady = { _, _ -> /* callee should not create offer */ },
                        onLocalAnswerReady = { tid, sdp -> onAnswerCreated(tid, sdp) }
                    ).apply { initialize() }
                    webrtc?.handleIncomingCallAndCreateAnswer(fromId)
                    webrtc?.setInCallAudioMode()
                }

                webrtc?.createAnswerAndSend(fromId, sdp)
            }

            "answer" -> {
                val answer = data?.optJSONObject("answer") ?: return
                val sdp = answer.optString("sdp")
                webrtc?.setRemoteAnswer(sdp)
            }

            "ice-candidate" -> {
                val candObj = data?.optJSONObject("candidate") ?: return
                val sdp = candObj.optString("candidate")
                val sdpMid = candObj.optString("sdpMid")
                val sdpMLineIndex = candObj.optInt("sdpMLineIndex")

                val ice = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                webrtc?.addIceCandidate(ice)
            }

            "hangup" -> {
                showStatus("Call ended by other party")
                cleanupCall()
            }

            "user-left" -> {
                val leftId = data?.optString("callId")
                if (leftId == currentPeerId && isInCall) {
                    showStatus("Peer left. Call ended.")
                    cleanupCall()
                }
            }

            "error" -> {
                val msg = data?.optString("message") ?: "Signaling error"
                showStatus(msg)
            }
        }
    }

    fun acceptIncomingCall() {
        val from = incomingCallFrom ?: return
        incomingCallFrom = null
        _uiState.value = _uiState.value.copy(incomingCallFrom = null)

        currentPeerId = from
        peerId = from
        isInitiator = false
        isCallPanelVisible = true
        isInCall = true

        _uiState.value = _uiState.value.copy(
            peerId = from,
            isCallPanelVisible = true,
            isInCall = true,
            isRinging = false
        )

        // Tell server we accept
        signaling?.answerCall(from, true, myCallId ?: "")

        // WebRTC client will be created when we receive the offer (see "offer" handler)
        startLevelUpdates()
    }

    fun rejectIncomingCall() {
        val from = incomingCallFrom ?: return
        signaling?.answerCall(from, false, myCallId ?: "")
        incomingCallFrom = null
        _uiState.value = _uiState.value.copy(incomingCallFrom = null)
    }

    fun toggleMute() {
        isMuted = !isMuted
        _uiState.value = _uiState.value.copy(isMuted = isMuted)
        webrtc?.setMute(isMuted)
    }

    fun setRemoteVolume(volume: Float) {
        remoteVolume = volume
        _uiState.value = _uiState.value.copy(remoteVolume = volume)
        webrtc?.setRemoteVolume(volume)
    }

    fun endCall() {
        currentPeerId?.let { id ->
            signaling?.hangup(id)
        }
        cleanupCall()
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        val start = System.currentTimeMillis()

        timerJob = viewModelScope.launch {
            while (isInCall) {
                val elapsed = (System.currentTimeMillis() - start) / 1000
                val min = (elapsed / 60).toString().padStart(2, '0')
                val sec = (elapsed % 60).toString().padStart(2, '0')
                callTimer = "$min:$sec"
                _uiState.value = _uiState.value.copy(callTimer = callTimer)
                delay(1000)
            }
        }
    }

    private fun startLevelUpdates() {
        levelJob?.cancel()
        levelJob = viewModelScope.launch {
            while (isInCall) {
                webrtc?.let { w ->
                    localLevel = w.localAudioLevel
                    remoteLevel = w.remoteAudioLevel
                    _uiState.value = _uiState.value.copy(
                        localLevel = localLevel,
                        remoteLevel = remoteLevel
                    )
                }
                delay(90)
            }
        }
    }

    private fun showStatus(message: String?) {
        statusMessage = message
        _uiState.value = _uiState.value.copy(statusMessage = message)
        if (message != null) {
            viewModelScope.launch {
                delay(2200)
                if (statusMessage == message) {
                    statusMessage = null
                    _uiState.value = _uiState.value.copy(statusMessage = null)
                }
            }
        }
    }

    fun clearStatus() {
        statusMessage = null
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    private fun cleanupCall() {
        timerJob?.cancel()
        levelJob?.cancel()

        webrtc?.cleanup()
        webrtc = null

        isInCall = false
        isCallPanelVisible = false
        peerId = null
        currentPeerId = null
        isMuted = false
        callTimer = "00:00"
        localLevel = 0
        remoteLevel = 0

        _uiState.value = _uiState.value.copy(
            isInCall = false,
            isCallPanelVisible = false,
            peerId = null,
            isRinging = false,
            isMuted = false,
            callTimer = "00:00",
            localLevel = 0,
            remoteLevel = 0
        )

        // Refresh online list
        signaling?.getOnlineUsers { users -> updateOnlineUsers(users) }
    }

    fun refreshOnlineUsers() {
        signaling?.getOnlineUsers { users -> updateOnlineUsers(users) }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        levelJob?.cancel()
        webrtc?.cleanup()
        signaling?.disconnect()
    }
}