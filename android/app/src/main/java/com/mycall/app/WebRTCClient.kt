package com.mycall.app

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.webrtc.*
import java.util.concurrent.Executors

/**
 * WebRTCClient - Handles PeerConnection, audio tracks, and level visualization.
 * Matches the exact flow from the web version (initiator sends offer first).
 */
class WebRTCClient(
    private val context: Context,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionStateChange: (PeerConnection.PeerConnectionState) -> Unit,
    private val onRemoteStreamReady: () -> Unit,
    private val onLocalOfferReady: (targetId: String, sdp: SessionDescription) -> Unit,
    private val onLocalAnswerReady: (targetId: String, sdp: SessionDescription) -> Unit
) {
    companion object {
        private const val TAG = "WebRTCClient"
    }

    private val executor = Executors.newSingleThreadExecutor()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localStream: MediaStream? = null

    private var remoteAudioTrack: AudioTrack? = null

    private var audioManager: AudioManager? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphone: Boolean = false

    // For local mic level (we use a simple periodic RMS via AudioRecord side channel)
    @Volatile
    var localAudioLevel: Int = 0   // 0-100

    // For remote level we poll getStats (inbound-rtp audioLevel)
    @Volatile
    var remoteAudioLevel: Int = 0  // 0-100

    private var isMuted = false
    private var remoteVolume: Float = 1.0f

    private var remoteId: String? = null

    private val statsTimer = java.util.Timer()

    fun initialize() {
        executor.execute {
            initializePeerConnectionFactory()
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            saveAudioState()
        }
    }

    private fun initializePeerConnectionFactory() {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        audioDeviceModule.release()
    }

    private fun createPeerConnection(remoteUserId: String): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                // Not used in Unified Plan
            }

            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "onAddTrack: remote track received")
                val track = receiver?.track()
                if (track is AudioTrack) {
                    remoteAudioTrack = track
                    track.setEnabled(true)
                    track.setVolume(remoteVolume.toDouble())
                    onRemoteStreamReady()
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "PeerConnection state: $newState")
                onConnectionStateChange(newState)
            }
        }

        return peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
    }

    fun startCallAsInitiator(targetId: String) {
        remoteId = targetId
        executor.execute {
            setupLocalAudio()
            peerConnection = createPeerConnection(targetId)
            peerConnection?.let { pc ->
                // Add local track
                localAudioTrack?.let { track ->
                    pc.addTrack(track, listOf("stream1"))
                }
                createAndSendOffer(targetId)
            }
            startRemoteStatsPolling()
        }
    }

    fun handleIncomingCallAndCreateAnswer(callerId: String) {
        remoteId = callerId
        executor.execute {
            setupLocalAudio()
            peerConnection = createPeerConnection(callerId)
            peerConnection?.let { pc ->
                localAudioTrack?.let { track ->
                    pc.addTrack(track, listOf("stream1"))
                }
            }
            startRemoteStatsPolling()
        }
    }

    private fun setupLocalAudio() {
        // Audio constraints with echo cancellation etc (match web)
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        localAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", localAudioSource)
        localAudioTrack?.setEnabled(true)

        // Start local level meter (simple RMS)
        startLocalLevelMeter()
    }

    private fun createAndSendOffer(targetId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        onLocalOfferReady(targetId, sdp)
                    }
                    override fun onSetFailure(error: String?) { Log.e(TAG, "setLocalDesc failed: $error") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { Log.e(TAG, "createOffer failed: $error") }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createAnswerAndSend(targetId: String, offerSdp: String) {
        executor.execute {
            val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    }
                    peerConnection?.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription) {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    onLocalAnswerReady(targetId, sdp)
                                }
                                override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal (answer) failed: $e") }
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onCreateFailure(p0: String?) {}
                            }, sdp)
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(e: String?) { Log.e(TAG, "createAnswer failed: $e") }
                        override fun onSetFailure(e: String?) {}
                    }, constraints)
                }
                override fun onSetFailure(e: String?) { Log.e(TAG, "setRemote (offer) failed: $e") }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, offer)
        }
    }

    fun setRemoteAnswer(answerSdp: String) {
        executor.execute {
            val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { Log.d(TAG, "Remote answer set successfully") }
                override fun onSetFailure(e: String?) { Log.e(TAG, "setRemote (answer) failed: $e") }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, answer)
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun setMute(muted: Boolean) {
        isMuted = muted
        localAudioTrack?.setEnabled(!muted)
    }

    fun setRemoteVolume(volume: Float) {
        remoteVolume = volume.coerceIn(0f, 1f)
        remoteAudioTrack?.setVolume(remoteVolume.toDouble())
    }

    private fun startLocalLevelMeter() {
        // We run a simple background thread that records short PCM chunks and computes RMS.
        // This gives us responsive "YOU" meter without needing full visualizer complexity.
        Thread {
            try {
                val sampleRate = 8000
                val bufferSize = 1024
                val recorder = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
                if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord init failed for level meter")
                    return@Thread
                }
                val buffer = ShortArray(bufferSize)
                recorder.startRecording()

                while (recorder.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    val read = recorder.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += (buffer[i] * buffer[i]).toDouble()
                        }
                        val rms = kotlin.math.sqrt(sum / read)
                        // Map roughly to 0-100 (tuned empirically for voice)
                        val level = (rms / 1200.0 * 100).toInt().coerceIn(0, 100)
                        localAudioLevel = level
                    }
                    Thread.sleep(80)
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "Local level meter stopped: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startRemoteStatsPolling() {
        statsTimer.schedule(object : java.util.TimerTask() {
            override fun run() {
                peerConnection?.getStats { report ->
                    var maxAudioLevel = 0.0
                    report.statsMap.values.forEach { stat ->
                        if (stat.type == "inbound-rtp" || stat.type == "track") {
                            val level = stat.members["audioLevel"] as? Double ?: 0.0
                            if (level > maxAudioLevel) maxAudioLevel = level
                        }
                    }
                    // audioLevel from WebRTC is 0.0 - 1.0 roughly
                    remoteAudioLevel = (maxAudioLevel * 100).toInt().coerceIn(0, 100)
                }
            }
        }, 120, 120)
    }

    fun setSpeakerphone(on: Boolean) {
        audioManager?.let { am ->
            am.isSpeakerphoneOn = on
        }
    }

    private fun saveAudioState() {
        audioManager?.let { am ->
            savedAudioMode = am.mode
            savedSpeakerphone = am.isSpeakerphoneOn
        }
    }

    fun setInCallAudioMode() {
        audioManager?.let { am ->
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false   // default to earpiece / headphones like a phone call
        }
    }

    fun restoreAudioState() {
        audioManager?.let { am ->
            am.mode = savedAudioMode
            am.isSpeakerphoneOn = savedSpeakerphone
        }
    }

    fun cleanup() {
        executor.execute {
            statsTimer.cancel()

            try { peerConnection?.close() } catch (_: Exception) {}
            peerConnection = null

            try { localAudioTrack?.dispose() } catch (_: Exception) {}
            try { localAudioSource?.dispose() } catch (_: Exception) {}

            localAudioTrack = null
            localAudioSource = null
            remoteAudioTrack = null

            try { peerConnectionFactory?.dispose() } catch (_: Exception) {}
            peerConnectionFactory = null

            restoreAudioState()
            localAudioLevel = 0
            remoteAudioLevel = 0
        }
    }

    fun getPeerConnection(): PeerConnection? = peerConnection
}