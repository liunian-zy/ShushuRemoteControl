package com.shushu.remote.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.shushu.remote.MainActivity
import com.shushu.remote.RemoteApplication
import com.shushu.remote.R
import com.shushu.remote.capture.ScreenCapture
import com.shushu.remote.clipboard.ClipboardSync
import com.shushu.remote.input.InputInjector
import com.shushu.remote.network.WebSocketClient
import com.shushu.remote.network.MessageHandler
import com.shushu.remote.webrtc.WebRTCClient
import com.shushu.remote.webrtc.SignalingClient
import kotlinx.coroutines.*
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class RemoteService : Service() {

    companion object {
        private const val TAG = "RemoteService"
        private const val NOTIFICATION_ID = 1

        // 流模式
        const val STREAM_MODE_WEBRTC = "webrtc"
        const val STREAM_MODE_MJPEG = "mjpeg"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var screenCapture: ScreenCapture? = null
    private var webSocketClient: WebSocketClient? = null
    private var inputInjector: InputInjector? = null
    private var clipboardSync: ClipboardSync? = null
    private var messageHandler: MessageHandler? = null

    // WebRTC 相关
    private var webRTCClient: WebRTCClient? = null
    private var signalingClient: SignalingClient? = null
    private var streamMode = STREAM_MODE_WEBRTC  // 默认使用 WebRTC
    private var currentControllerId: String? = null
    private var mediaProjectionIntent: Intent? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // 获取屏幕参数
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // 初始化组件
        inputInjector = InputInjector()
        clipboardSync = ClipboardSync(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting")

        startForeground(NOTIFICATION_ID, createNotification())

        intent?.let {
            val serverUrl = it.getStringExtra("server_url") ?: return@let
            val deviceId = it.getStringExtra("device_id") ?: return@let
            val deviceName = it.getStringExtra("device_name") ?: return@let
            val token = it.getStringExtra("token") ?: return@let

            // 清理旧连接（防止重复连接）
            cleanupOldConnection()

            // 尝试获取 MediaProjection（如果有授权数据）
            val resultCode = it.getIntExtra("result_code", -1)
            if (resultCode != -1) {
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    it.getParcelableExtra("result_data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelableExtra("result_data")
                }
                if (resultData != null) {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                    mediaProjectionIntent = resultData
                }
            }

            // 初始化屏幕采集（MJPEG 模式备用）
            screenCapture = ScreenCapture(
                this,
                mediaProjection,
                screenWidth,
                screenHeight,
                screenDensity
            )

            // 初始化消息处理器
            messageHandler = MessageHandler(
                inputInjector!!,
                clipboardSync!!,
                screenCapture!!
            )

            // 设置 WebRTC 信令处理
            messageHandler?.setWebRTCSignalingHandler { type, data ->
                handleWebRTCSignaling(type, data)
            }

            // 初始化 WebSocket
            webSocketClient = WebSocketClient(
                serverUrl = serverUrl,
                deviceId = deviceId,
                deviceName = deviceName,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                token = token,
                messageHandler = messageHandler!!,
                onConnected = { onWebSocketConnected() },
                onDisconnected = { onWebSocketDisconnected() }
            )

            // 设置屏幕帧回调（MJPEG 和 H264 模式都使用）
            screenCapture?.setFrameCallback { frameData ->
                // H264 模式：frameData 已经包含类型头 [Type][Flags][Payload]
                // MJPEG 模式：frameData 是原始 JPEG 数据
                webSocketClient?.sendBinary(frameData)
            }

            // 设置帧发送状态回调（用于自适应码率）
            webSocketClient?.onFrameSentCallback = { frameSize ->
                screenCapture?.onFrameSent(frameSize)
            }
            webSocketClient?.onFrameDroppedCallback = {
                screenCapture?.onFrameDropped()
            }

            // 设置剪贴板变化回调
            clipboardSync?.setOnClipboardChangeListener { text ->
                webSocketClient?.sendClipboardUpdate(text)
            }

            // 初始化 WebRTC
            initWebRTC()

            // 连接服务器
            serviceScope.launch {
                webSocketClient?.connect()
            }
        }

        return START_STICKY
    }

    /**
     * 初始化 WebRTC
     */
    private fun initWebRTC() {
        Log.d(TAG, "Initializing WebRTC")

        // 创建信令客户端
        signalingClient = SignalingClient { message ->
            webSocketClient?.sendRaw(message)
        }

        // 设置信令回调
        signalingClient?.onWebRTCReady = { controllerId ->
            Log.d(TAG, "Controller ready for WebRTC: $controllerId")
            currentControllerId = controllerId
            startWebRTCStream(controllerId)
        }

        signalingClient?.onAnswerReceived = { sdp ->
            Log.d(TAG, "Received WebRTC answer")
            webRTCClient?.setRemoteAnswer(sdp) { success ->
                if (success) {
                    Log.d(TAG, "WebRTC answer set successfully")
                } else {
                    Log.e(TAG, "Failed to set WebRTC answer, falling back to MJPEG")
                    fallbackToMJPEG()
                }
            }
        }

        signalingClient?.onIceCandidateReceived = { candidate ->
            webRTCClient?.addIceCandidate(candidate)
        }

        // 创建 WebRTC 客户端
        webRTCClient = WebRTCClient(this).apply {
            initialize()

            // 设置 MediaProjection 权限
            mediaProjectionIntent?.let { setMediaProjectionPermission(it) }

            // 设置回调
            onIceCandidate = { candidate ->
                currentControllerId?.let { controllerId ->
                    signalingClient?.sendIceCandidate(candidate, controllerId)
                }
            }

            onIceConnectionStateChange = { state ->
                Log.d(TAG, "WebRTC ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        Log.d(TAG, "WebRTC connected successfully")
                        streamMode = STREAM_MODE_WEBRTC
                        // 停止 MJPEG 推流
                        screenCapture?.stopCapture()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.e(TAG, "WebRTC connection failed")
                        fallbackToMJPEG()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "WebRTC disconnected")
                        // 可能会自动恢复，等待一段时间
                    }
                    else -> {}
                }
            }

            onError = { error ->
                Log.e(TAG, "WebRTC error: $error")
                fallbackToMJPEG()
            }
        }
    }

    /**
     * 处理 WebRTC 信令消息
     */
    private fun handleWebRTCSignaling(type: String, data: Map<*, *>) {
        signalingClient?.handleMessage(type, data)
    }

    /**
     * 开始 WebRTC 推流
     */
    private fun startWebRTCStream(controllerId: String) {
        Log.d(TAG, "Starting WebRTC stream to $controllerId")

        // 设置 WebRTC 连接超时（10秒）
        serviceScope.launch {
            delay(10000)
            if (streamMode != STREAM_MODE_WEBRTC) {
                Log.w(TAG, "WebRTC connection timeout, falling back to MJPEG")
                fallbackToMJPEG()
            }
        }

        serviceScope.launch(Dispatchers.Main) {
            try {
                // 创建 PeerConnection
                if (webRTCClient?.createPeerConnection() != true) {
                    Log.e(TAG, "Failed to create PeerConnection")
                    fallbackToMJPEG()
                    return@launch
                }

                // 开始屏幕捕获
                // 使用较低的初始参数，WebRTC 会自动调整
                val captureWidth = (screenWidth * 0.75).toInt()
                val captureHeight = (screenHeight * 0.75).toInt()
                val captureFps = 30

                if (webRTCClient?.startScreenCapture(captureWidth, captureHeight, captureFps) != true) {
                    Log.e(TAG, "Failed to start screen capture for WebRTC")
                    fallbackToMJPEG()
                    return@launch
                }

                // 创建并发送 Offer
                webRTCClient?.createOffer { sdp ->
                    if (sdp != null) {
                        signalingClient?.sendOffer(sdp, controllerId)
                        Log.d(TAG, "WebRTC offer sent")
                    } else {
                        Log.e(TAG, "Failed to create WebRTC offer")
                        fallbackToMJPEG()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error starting WebRTC stream", e)
                fallbackToMJPEG()
            }
        }
    }

    /**
     * 回退到 MJPEG 模式
     */
    private fun fallbackToMJPEG() {
        if (streamMode == STREAM_MODE_MJPEG) {
            Log.d(TAG, "Already in MJPEG mode")
            return
        }

        Log.w(TAG, "Falling back to MJPEG mode")
        streamMode = STREAM_MODE_MJPEG

        // 释放 WebRTC 资源（但不释放 factory，以便后续重试）
        serviceScope.launch(Dispatchers.Main) {
            webRTCClient?.release()
        }

        // 启动 MJPEG 推流
        val params = when {
            screenWidth > 1080 -> Pair(60, 20)  // 高分辨率用中等质量
            else -> Pair(70, 25)
        }
        screenCapture?.startCapture(params.first, params.second)
    }

    private fun onWebSocketConnected() {
        Log.d(TAG, "WebSocket connected")
        clipboardSync?.startListening()

        // 通知控制端 WebRTC 已准备好
        signalingClient?.sendReady()
    }

    private fun onWebSocketDisconnected() {
        Log.d(TAG, "WebSocket disconnected")
        screenCapture?.stopCapture()
        webRTCClient?.release()
        currentControllerId = null
    }

    /**
     * 清理旧连接（在重新连接前调用）
     */
    private fun cleanupOldConnection() {
        Log.d(TAG, "Cleaning up old connection")

        // 断开旧的 WebSocket 连接（会停止重连）
        webSocketClient?.disconnect()
        webSocketClient = null

        // 停止屏幕采集
        screenCapture?.stopCapture()
        screenCapture = null

        // 释放 WebRTC
        webRTCClient?.release()
        webRTCClient = null
        signalingClient = null

        // 停止剪贴板监听
        clipboardSync?.stopListening()

        currentControllerId = null
        streamMode = STREAM_MODE_WEBRTC
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RemoteApplication.CHANNEL_ID)
            .setContentTitle("远程控制服务")
            .setContentText("服务运行中...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        serviceScope.cancel()
        screenCapture?.stopCapture()
        webSocketClient?.disconnect()
        webRTCClient?.release()
        clipboardSync?.stopListening()
        mediaProjection?.stop()
    }
}
