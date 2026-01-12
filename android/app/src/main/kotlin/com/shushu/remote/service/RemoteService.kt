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
import kotlinx.coroutines.*

class RemoteService : Service() {

    companion object {
        private const val TAG = "RemoteService"
        private const val NOTIFICATION_ID = 1
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var screenCapture: ScreenCapture? = null
    private var webSocketClient: WebSocketClient? = null
    private var inputInjector: InputInjector? = null
    private var clipboardSync: ClipboardSync? = null
    private var messageHandler: MessageHandler? = null

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
                }
            }

            // 初始化屏幕采集（优先使用系统权限，回退到 MediaProjection）
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

            // 设置屏幕帧回调
            screenCapture?.setFrameCallback { frameData ->
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

            // 连接服务器
            serviceScope.launch {
                webSocketClient?.connect()
            }
        }

        return START_STICKY
    }

    private fun onWebSocketConnected() {
        Log.d(TAG, "WebSocket connected")
        clipboardSync?.startListening()
    }

    private fun onWebSocketDisconnected() {
        Log.d(TAG, "WebSocket disconnected")
        screenCapture?.stopCapture()
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
        clipboardSync?.stopListening()
        mediaProjection?.stop()
    }
}
