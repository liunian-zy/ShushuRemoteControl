package com.shushu.remote.privacy

import android.content.Context
import android.util.Log

/**
 * 隐私屏幕管理器 - 已禁用
 */
class PrivacyScreenManager(private val context: Context) {

    companion object {
        private const val TAG = "PrivacyScreenManager"
    }

    var onPrivacyModeChanged: ((Boolean) -> Unit)? = null

    fun enable() {
        Log.d(TAG, "Privacy mode is disabled")
    }

    fun disable() {
        Log.d(TAG, "Privacy mode is disabled")
    }

    fun toggle() {
        Log.d(TAG, "Privacy mode is disabled")
    }

    fun isEnabled(): Boolean = false

    fun enablePrivacyMode() = enable()
    fun disablePrivacyMode() = disable()
    fun togglePrivacyMode(): Boolean = false

    fun startFrameCapture(onFrame: (ByteArray) -> Unit) {}
    fun stopFrameCapture() {}
    fun release() {}
}
