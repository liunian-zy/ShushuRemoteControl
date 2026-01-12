package com.shushu.remote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, consider auto-starting service")
            // 注意：自动启动需要用户先手动授权过 MediaProjection
            // 这里可以启动一个 Activity 来引导用户
        }
    }
}
