package com.shushu.remote.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*

/**
 * WebRTC 客户端 - 负责屏幕共享的 WebRTC 连接管理
 */
class WebRTCClient(
    private val context: Context
) {
    companion object {
        private const val TAG = "WebRTCClient"

        // STUN/TURN 服务器配置
        private val ICE_SERVERS = listOf(
            // 公共 STUN 服务器
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.stunprotocol.org:3478").createIceServer()
        )
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var mediaProjectionPermissionResultData: Intent? = null

    // 回调
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onIceConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * 初始化 WebRTC
     */
    fun initialize() {
        Log.d(TAG, "Initializing WebRTC")

        // 初始化 EGL 上下文（用于硬件编码）
        eglBase = EglBase.create()

        // 初始化 PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // 创建编码器工厂（优先硬件编码）
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,  // 启用硬件编码
            true   // 启用 H.264 高配置
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC initialized successfully")
    }

    /**
     * 设置 MediaProjection 权限数据
     */
    fun setMediaProjectionPermission(resultData: Intent) {
        mediaProjectionPermissionResultData = resultData
    }

    /**
     * 创建 PeerConnection
     */
    fun createPeerConnection(): Boolean {
        if (peerConnectionFactory == null) {
            Log.e(TAG, "PeerConnectionFactory is null, reinitializing...")
            initialize()
        }

        if (peerConnectionFactory == null) {
            Log.e(TAG, "Failed to initialize PeerConnectionFactory")
            return false
        }

        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // 启用 TCP 候选（更稳定）
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            // 允许所有传输类型
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            // 启用 ICE 候选池
            iceCandidatePoolSize = 10
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE candidate: ${candidate.sdpMid}")
                onIceCandidate?.invoke(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE connection state: $state")
                onIceConnectionStateChange?.invoke(state)

                when (state) {
                    PeerConnection.IceConnectionState.FAILED -> {
                        onError?.invoke("ICE connection failed")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "ICE disconnected, may reconnect...")
                    }
                    else -> {}
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE receiving: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "Stream added")
            }

            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "Stream removed")
            }

            override fun onDataChannel(channel: DataChannel) {
                Log.d(TAG, "Data channel: ${channel.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                Log.d(TAG, "Track added")
            }
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)

        return peerConnection != null
    }

    /**
     * 开始屏幕捕获并添加视频轨道
     */
    fun startScreenCapture(width: Int, height: Int, fps: Int): Boolean {
        if (mediaProjectionPermissionResultData == null) {
            Log.e(TAG, "MediaProjection permission not granted")
            onError?.invoke("MediaProjection permission not granted")
            return false
        }

        try {
            // 创建屏幕捕获器
            videoCapturer = ScreenCapturerAndroid(
                mediaProjectionPermissionResultData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection stopped")
                    }
                }
            )

            // 创建 SurfaceTextureHelper
            surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                eglBase!!.eglBaseContext
            )

            // 创建视频源
            videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

            // 开始捕获
            videoCapturer?.startCapture(width, height, fps)

            // 创建视频轨道
            localVideoTrack = peerConnectionFactory?.createVideoTrack("screen_track", videoSource)
            localVideoTrack?.setEnabled(true)

            // 添加到 PeerConnection
            peerConnection?.addTrack(localVideoTrack, listOf("screen_stream"))

            Log.d(TAG, "Screen capture started: ${width}x${height}@${fps}fps")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            onError?.invoke("Failed to start screen capture: ${e.message}")
            return false
        }
    }

    /**
     * 动态调整视频参数（自适应码率）
     */
    fun updateVideoParameters(maxBitrateBps: Int, maxFps: Int) {
        peerConnection?.senders?.forEach { sender ->
            if (sender.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                val parameters = sender.parameters
                parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED

                parameters.encodings.forEach { encoding ->
                    encoding.maxBitrateBps = maxBitrateBps
                    encoding.maxFramerate = maxFps
                }

                sender.parameters = parameters
                Log.d(TAG, "Updated video parameters: maxBitrate=$maxBitrateBps, maxFps=$maxFps")
            }
        }
    }

    /**
     * 创建 Offer（作为发起方）
     */
    fun createOffer(callback: (SessionDescription?) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Offer created successfully")
                // 设置本地描述
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set")
                        callback(sdp)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Set local description failed: $error")
                        callback(null)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create offer failed: $error")
                callback(null)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    /**
     * 设置远端 Answer
     */
    fun setRemoteAnswer(sdp: SessionDescription, callback: (Boolean) -> Unit) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote answer set successfully")
                callback(true)
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Set remote answer failed: $error")
                callback(false)
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    /**
     * 添加 ICE Candidate
     */
    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
        Log.d(TAG, "ICE candidate added: ${candidate.sdpMid}")
    }

    /**
     * 获取连接状态
     */
    fun getConnectionState(): PeerConnection.IceConnectionState? {
        return peerConnection?.iceConnectionState()
    }

    /**
     * 获取连接统计信息
     */
    fun getStats(callback: (RTCStatsReport?) -> Unit) {
        peerConnection?.getStats { report ->
            callback(report)
        }
    }

    /**
     * 停止并释放资源
     */
    fun release() {
        Log.d(TAG, "Releasing WebRTC resources")

        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer", e)
        }
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.dispose()
        localVideoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        peerConnection?.close()
        peerConnection = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        eglBase?.release()
        eglBase = null

        Log.d(TAG, "WebRTC resources released")
    }
}
