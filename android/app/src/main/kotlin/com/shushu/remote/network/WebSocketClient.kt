package com.shushu.remote.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketClient(
    private val serverUrl: String,
    private val deviceId: String,
    private val deviceName: String,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val token: String,
    private val messageHandler: MessageHandler,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    companion object {
        private const val TAG = "WebSocketClient"
        private const val RECONNECT_DELAY = 3000L
        private const val HEARTBEAT_INTERVAL = 15000L // 15秒心跳
        private const val SEND_TIMEOUT = 5000L // 发送超时5秒
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private var shouldReconnect = true
    private val mainHandler = Handler(Looper.getMainLooper())

    // 帧发送回调
    var onFrameSentCallback: ((Int) -> Unit)? = null
    var onFrameDroppedCallback: (() -> Unit)? = null

    // 心跳
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isConnected.get()) {
                sendHeartbeat()
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL)
            }
        }
    }

    fun connect() {
        Log.d(TAG, "Connecting toserverUrl")
        shouldReconnect = true

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                isConnected.set(true)

                // 发送注册消息
                val registerMsg = mapOf(
                    "type" to "device.register",
                    "deviceId" to deviceId,
                    "deviceName" to deviceName,
                    "screenWidth" to screenWidth,
                    "screenHeight" to screenHeight,
                    "token" to token
                )
                webSocket.send(gson.toJson(registerMsg))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val msg = gson.fromJson(text, Map::class.java)
                    val type = msg["type"] as? String ?: return

                    when (type) {
                        "device.registered" -> {
                            Log.d(TAG, "Device registered successfully")
                            // 启动心跳
                            mainHandler.post(heartbeatRunnable)
                            onConnected()
                        }
                        else -> messageHandler.handleMessage(type, msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 设备端不接收二进制消息
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        isConnected.set(false)
        mainHandler.removeCallbacks(heartbeatRunnable)
        onDisconnected()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        Log.d(TAG, "Scheduling reconnect in ${RECONNECT_DELAY}ms")
        mainHandler.postDelayed({
            if (shouldReconnect && !isConnected.get()) {
                Log.d(TAG, "Attempting to reconnect...")
                connect()
            }
        }, RECONNECT_DELAY)
    }

    fun disconnect() {
        shouldReconnect = false
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected.set(false)
    }

    /**
     * 发送二进制帧数据，带超时检测
     */
    fun sendBinary(data: ByteArray) {
        if (!isConnected.get()) {
            onFrameDroppedCallback?.invoke()
            return
        }

        try {
            val success = webSocket?.send(ByteString.of(*data)) ?: false
            if (success) {
                onFrameSentCallback?.invoke(data.size)
            } else {
                Log.w(TAG, "Failed to send binary frame")
                onFrameDroppedCallback?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending binary", e)
            onFrameDroppedCallback?.invoke()
        }
    }

    fun sendClipboardUpdate(text: String) {
        if (isConnected.get()) {
            val msg = mapOf(
                "type" to "clipboard.update",
                "text" to text
            )
            webSocket?.send(gson.toJson(msg))
        }
    }

    fun sendHeartbeat() {
        if (isConnected.get()) {
            val msg = mapOf("type" to "device.heartbeat")
            webSocket?.send(gson.toJson(msg))
        }
    }

    /**
     * 发送原始 JSON 字符串（用于 WebRTC 信令）
     */
    fun sendRaw(message: String) {
        if (isConnected.get()) {
            webSocket?.send(message)
        }
    }

    fun isConnected(): Boolean = isConnected.get()
}
