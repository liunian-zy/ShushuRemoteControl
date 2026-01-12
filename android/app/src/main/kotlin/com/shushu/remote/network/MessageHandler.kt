package com.shushu.remote.network

import android.util.Log
import com.shushu.remote.capture.ScreenCapture
import com.shushu.remote.clipboard.ClipboardSync
import com.shushu.remote.input.InputInjector

class MessageHandler(
    private val inputInjector: InputInjector,
    private val clipboardSync: ClipboardSync,
    private val screenCapture: ScreenCapture
) {
    companion object {
        private const val TAG = "MessageHandler"
    }

    fun handleMessage(type: String, msg: Map<*, *>) {
        Log.d(TAG, "Received message type: $type")
        when (type) {
            "stream.start" -> handleStreamStart(msg)
            "stream.stop" -> handleStreamStop()
            "input.touch" -> handleTouch(msg)
            "input.key" -> handleKey(msg)
            "input.text" -> handleText(msg)
            "input.command" -> handleCommand(msg)
            "clipboard.set" -> handleClipboardSet(msg)
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }

    private fun handleStreamStart(msg: Map<*, *>) {
        val quality = (msg["quality"] as? Double)?.toInt() ?: 80
        val maxFps = (msg["maxFps"] as? Double)?.toInt() ?: 30
        Log.d(TAG, "Starting stream: quality=$quality, maxFps=$maxFps")
        screenCapture.startCapture(quality, maxFps)
    }

    private fun handleStreamStop() {
        Log.d(TAG, "Stopping stream")
        screenCapture.stopCapture()
    }

    private fun handleTouch(msg: Map<*, *>) {
        val action = msg["action"] as? String ?: return

        Log.d(TAG, "Touch event: action=$action")

        when (action) {
            "tap" -> {
                val x = (msg["x"] as? Double)?.toInt() ?: return
                val y = (msg["y"] as? Double)?.toInt() ?: return
                Log.d(TAG, "Tap: x=$x, y=$y")
                inputInjector.injectTap(x, y)
            }
            "longpress" -> {
                val x = (msg["x"] as? Double)?.toInt() ?: return
                val y = (msg["y"] as? Double)?.toInt() ?: return
                Log.d(TAG, "LongPress: x=$x, y=$y")
                inputInjector.injectLongPress(x, y)
            }
            "swipe" -> {
                val startX = (msg["startX"] as? Double)?.toInt() ?: return
                val startY = (msg["startY"] as? Double)?.toInt() ?: return
                val endX = (msg["endX"] as? Double)?.toInt() ?: return
                val endY = (msg["endY"] as? Double)?.toInt() ?: return
                val duration = (msg["duration"] as? Double)?.toInt() ?: 300
                Log.d(TAG, "Swipe: ($startX,$startY) -> ($endX,$endY), duration=$duration")
                inputInjector.injectSwipe(startX, startY, endX, endY, duration)
            }
            "scroll" -> {
                val x = (msg["x"] as? Double)?.toInt() ?: return
                val y = (msg["y"] as? Double)?.toInt() ?: return
                val hScroll = (msg["hScroll"] as? Double)?.toFloat() ?: 0f
                val vScroll = (msg["vScroll"] as? Double)?.toFloat() ?: 0f
                Log.d(TAG, "Scroll: x=$x, y=$y, hScroll=$hScroll, vScroll=$vScroll")
                inputInjector.injectScroll(x, y, hScroll, vScroll)
            }
        }
    }

    private fun handleKey(msg: Map<*, *>) {
        val keyCode = (msg["keyCode"] as? Double)?.toInt() ?: return
        val action = msg["action"] as? String ?: return

        Log.d(TAG, "Key event: keyCode=$keyCode, action=$action")
        inputInjector.injectKey(keyCode, action)
    }

    private fun handleClipboardSet(msg: Map<*, *>) {
        val text = msg["text"] as? String ?: return
        val autoPaste = msg["autoPaste"] as? Boolean ?: true
        Log.d(TAG, "Setting clipboard: ${text.take(50)}..., autoPaste=$autoPaste")

        clipboardSync.setText(text)

        // 自动粘贴 - 使用真正的粘贴操作支持中文
        if (autoPaste) {
            Thread.sleep(150) // 等待剪贴板设置完成
            inputInjector.injectPaste()
        }
    }

    private fun handleText(msg: Map<*, *>) {
        val text = msg["text"] as? String ?: return
        Log.d(TAG, "Input text: $text")
        inputInjector.injectText(text)
    }

    private fun handleCommand(msg: Map<*, *>) {
        val command = msg["command"] as? String ?: return
        Log.d(TAG, "Command: $command")

        when (command) {
            "hide_keyboard" -> inputInjector.hideKeyboard()
        }
    }
}
