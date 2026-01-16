package com.shushu.remote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shushu.remote.service.RemoteService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "remote_config"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, starting RemoteService in background")
            startServiceSilently(context)
        }
    }

    private fun startServiceSilently(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val serverUrl = prefs.getString("server_url", "wss://rc.photo.sqaigc.com/ws/device") ?: return
        val deviceId = prefs.getString("device_id", null)
        val deviceName = prefs.getString("device_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        val token = prefs.getString("token", "Vh2Zzjtb3NIUk1X6rbfzKAEsFGk/ASX3") ?: return

        // 如果没有 deviceId，生成一个
        val finalDeviceId = deviceId ?: run {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
       if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                "DEVICE_${android.os.Build.MODEL.replace(" ", "_")}_${System.currentTimeMillis() % 10000}"
            }
        }

        Log.d(TAG, "Starting service with deviceId: $finalDeviceId")

        val serviceIntent = Intent(context, RemoteService::class.java).apply {
            putExtra("server_url", serverUrl)
            putExtra("device_id", finalDeviceId)
            putExtra("device_name", deviceName)
            putExtra("token", token)
        }

        context.startForegroundService(serviceIntent)
    }
}
