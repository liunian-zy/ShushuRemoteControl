package com.shushu.remote.webrtc

import android.util.Log
import com.google.gson.Gson
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * WebRTC 信令客户端
 * 复用现有的 WebSocket 连接进行信令交换
 */
class SignalingClient(
    private val sendMessage: (String) -> Unit
) {
    companion object {
        private const val TAG = "SignalingClient"

        // 信令消息类型
        const val TYPE_WEBRTC_OFFER = "webrtc.offer"
        const val TYPE_WEBRTC_ANSWER = "webrtc.answer"
        const val TYPE_WEBRTC_ICE = "webrtc.ice"
        const val TYPE_WEBRTC_READY = "webrtc.ready"
    }

    private val gson = Gson()

    // 回调接口
    var onOfferReceived: ((SessionDescription, String) -> Unit)? = null
    var onAnswerReceived: ((SessionDescription) -> Unit)? = null
    var onIceCandidateReceived: ((IceCandidate) -> Unit)? = null
    var onWebRTCReady: ((String) -> Unit)? = null  // 控制端准备好接收 WebRTC

    /**
     * 发送 WebRTC 准备就绪消息（设备端发送）
     */
    fun sendReady() {
        val message = mapOf(
            "type" to TYPE_WEBRTC_READY
        )
        sendMessage(gson.toJson(message))
        Log.d(TAG, "WebRTC ready sent")
    }

    /**
     * 发送 SDP Offer
     */
    fun sendOffer(sdp: SessionDescription, targetId: String) {
        val message = mapOf(
            "type" to TYPE_WEBRTC_OFFER,
            "targetId" to targetId,
            "sdp" to mapOf(
                "type" to sdp.type.canonicalForm(),
                "sdp" to sdp.description
            )
        )
        sendMessage(gson.toJson(message))
        Log.d(TAG, "Offer sent to $targetId")
    }

    /**
     * 发送 SDP Answer
     */
    fun sendAnswer(sdp: SessionDescription, targetId: String) {
        val message = mapOf(
            "type" to TYPE_WEBRTC_ANSWER,
            "targetId" to targetId,
            "sdp" to mapOf(
                "type" to sdp.type.canonicalForm(),
                "sdp" to sdp.description
            )
        )
        sendMessage(gson.toJson(message))
        Log.d(TAG, "Answer sent to $targetId")
    }

    /**
     * 发送 ICE Candidate
     */
    fun sendIceCandidate(candidate: IceCandidate, targetId: String) {
        val message = mapOf(
            "type" to TYPE_WEBRTC_ICE,
            "targetId" to targetId,
            "candidate" to mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate" to candidate.sdp
            )
        )
        sendMessage(gson.toJson(message))
        Log.d(TAG, "ICE candidate sent to $targetId")
    }

    /**
     * 处理收到的信令消息
     * @return true 如果消息被处理，false 如果不是信令消息
     */
    fun handleMessage(type: String, data: Map<*, *>): Boolean {
        return when (type) {
            TYPE_WEBRTC_READY -> {
                // 优先使用 fromId（服务器添加），其次使用 controllerId
                val controllerId = data["fromId"] as? String
                    ?: data["controllerId"] as? String
                    ?: ""
                Log.d(TAG, "WebRTC ready received from controller: $controllerId")
                onWebRTCReady?.invoke(controllerId)
                true
            }

            TYPE_WEBRTC_OFFER -> {
                val sdpData = data["sdp"] as? Map<*, *> ?: return false
                val fromId = data["fromId"] as? String ?: ""
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(sdpData["type"] as String),
                    sdpData["sdp"] as String
                )
                Log.d(TAG, "Offer received from $fromId")
                onOfferReceived?.invoke(sdp, fromId)
                true
            }

            TYPE_WEBRTC_ANSWER -> {
                val sdpData = data["sdp"] as? Map<*, *> ?: return false
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(sdpData["type"] as String),
                    sdpData["sdp"] as String
                )
                Log.d(TAG, "Answer received")
                onAnswerReceived?.invoke(sdp)
                true
            }

            TYPE_WEBRTC_ICE -> {
                val candidateData = data["candidate"] as? Map<*, *> ?: return false
                val candidate = IceCandidate(
                    candidateData["sdpMid"] as String,
                    (candidateData["sdpMLineIndex"] as Double).toInt(),
                    candidateData["candidate"] as String
                )
                Log.d(TAG, "ICE candidate received")
                onIceCandidateReceived?.invoke(candidate)
                true
            }

            else -> false
        }
    }
}
