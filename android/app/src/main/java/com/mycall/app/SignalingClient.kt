package com.mycall.app

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

/**
 * SignalingClient - Socket.IO wrapper that mirrors the web app's signaling protocol exactly.
 * Events must match server.js + public/index.html 1:1 for cross-platform (Android <-> Web) calling to work.
 */
class SignalingClient(
    private val serverUrl: String,
    private val onEvent: (String, JSONObject?) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var socket: Socket? = null
    var isConnected = false
        private set

    fun connect() {
        try {
            val opts = IO.Options().apply {
                reconnection = true
                reconnectionDelay = 1500
                reconnectionDelayMax = 5000
                transports = arrayOf("websocket", "polling")
            }
            socket = IO.socket(URI.create(serverUrl), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                isConnected = true
                onConnected()
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                isConnected = false
                onDisconnected()
            }

            // Core events from server (must match web client names)
            socket?.on("online-users") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("online-users", data)
            }

            socket?.on("user-left") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("user-left", data)
            }

            socket?.on("incoming-call") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("incoming-call", data)
            }

            socket?.on("call-initiated") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("call-initiated", data)
            }

            socket?.on("call-accepted") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("call-accepted", data)
            }

            socket?.on("call-rejected") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("call-rejected", data)
            }

            socket?.on("call-error") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("call-error", data)
            }

            socket?.on("offer") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("offer", data)
            }

            socket?.on("answer") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("answer", data)
            }

            socket?.on("ice-candidate") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("ice-candidate", data)
            }

            socket?.on("hangup") { args ->
                val data = args.firstOrNull() as? JSONObject
                onEvent("hangup", data)
            }

            socket?.connect()
        } catch (e: Exception) {
            onEvent("error", JSONObject().put("message", e.message ?: "Connection failed"))
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        isConnected = false
    }

    // Register with Call ID (uses ack callback like the web app)
    fun register(callId: String, onResult: (success: Boolean, error: String?, callId: String?) -> Unit) {
        val data = JSONObject().put("callId", callId)
        socket?.emit("register", callId) { args ->
            val response = args.firstOrNull() as? JSONObject
            if (response != null) {
                val success = response.optBoolean("success", false)
                if (success) {
                    onResult(true, null, response.optString("callId"))
                } else {
                    onResult(false, response.optString("error", "Registration failed"), null)
                }
            } else {
                onResult(false, "No response from server", null)
            }
        }
    }

    fun getOnlineUsers(onResult: (List<String>) -> Unit) {
        socket?.emit("get-online-users") { args ->
            val response = args.firstOrNull() as? JSONObject
            val users = mutableListOf<String>()
            response?.optJSONArray("users")?.let { arr ->
                for (i in 0 until arr.length()) {
                    users.add(arr.optString(i))
                }
            }
            onResult(users)
        }
    }

    fun callUser(targetId: String, fromId: String) {
        val payload = JSONObject()
            .put("targetId", targetId)
            .put("fromId", fromId)
        socket?.emit("call-user", payload)
    }

    fun answerCall(targetId: String, accept: Boolean, fromId: String) {
        val payload = JSONObject()
            .put("targetId", targetId)
            .put("accept", accept)
            .put("fromId", fromId)
        socket?.emit("answer-call", payload)
    }

    fun sendOffer(targetId: String, sdp: String, fromId: String) {
        val offer = JSONObject()
            .put("type", "offer")
            .put("sdp", sdp)
        val payload = JSONObject()
            .put("targetId", targetId)
            .put("offer", offer)
            .put("fromId", fromId)
        socket?.emit("offer", payload)
    }

    fun sendAnswer(targetId: String, sdp: String, fromId: String) {
        val answer = JSONObject()
            .put("type", "answer")
            .put("sdp", sdp)
        val payload = JSONObject()
            .put("targetId", targetId)
            .put("answer", answer)
            .put("fromId", fromId)
        socket?.emit("answer", payload)
    }

    fun sendIceCandidate(targetId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int, fromId: String) {
        val cand = JSONObject()
            .put("candidate", candidate)
            .put("sdpMid", sdpMid)
            .put("sdpMLineIndex", sdpMLineIndex)
        val payload = JSONObject()
            .put("targetId", targetId)
            .put("candidate", cand)
            .put("fromId", fromId)
        socket?.emit("ice-candidate", payload)
    }

    fun hangup(targetId: String) {
        val payload = JSONObject().put("targetId", targetId)
        socket?.emit("hangup", payload)
    }
}