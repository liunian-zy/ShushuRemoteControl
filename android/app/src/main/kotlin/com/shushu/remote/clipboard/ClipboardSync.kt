package com.shushu.remote.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class ClipboardSync(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardSync"
    }

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onClipboardChangeListener: ((String) -> Unit)? = null
    private var lastClipboardText: String = ""
    private var isListening = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = getCurrentText()
        if (text.isNotEmpty() && text != lastClipboardText) {
            lastClipboardText = text
            Log.d(TAG, "Clipboard changed: ${text.take(50)}...")
            onClipboardChangeListener?.invoke(text)
        }
    }

    fun setOnClipboardChangeListener(listener: (String) -> Unit) {
        onClipboardChangeListener = listener
    }

    fun startListening() {
        if (isListening) return
        isListening = true

        mainHandler.post {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
            lastClipboardText = getCurrentText()
        }
        Log.d(TAG, "Started listening to clipboard")
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false

        mainHandler.post {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        }
        Log.d(TAG, "Stopped listening to clipboard")
    }

    fun getCurrentText(): String {
        return try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get clipboard text", e)
            ""
        }
    }

    fun setText(text: String) {
        mainHandler.post {
            try {
                lastClipboardText = text // 防止触发回调
                val clip = ClipData.newPlainText("remote", text)
                clipboardManager.setPrimaryClip(clip)
                Log.d(TAG, "Clipboard set: ${text.take(50)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set clipboard text", e)
            }
        }
    }
}
