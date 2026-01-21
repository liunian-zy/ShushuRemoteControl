package com.shushu.remote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shushu.remote.BuildConfig
import com.shushu.remote.service.RemoteService
import com.shushu.remote.util.DeviceIdProvider

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

        val serverUrl = prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL) ?: return
        val deviceId = DeviceIdProvider.getDeviceId(context)
        val deviceName = prefs.getString("device_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        val token = prefs.getString("token", "Vh2Zzjtb3NIUk1X6rbfzKAEsFGk/ASX3") ?: return
        Log.d(TAG, "Starting service with deviceId: $deviceId")

        val serviceIntent = Intent(context, RemoteService::class.java).apply {
            putExtra("server_url", serverUrl)
            putExtra("device_id", deviceId)
            putExtra("device_name", deviceName)
            putExtra("token", token)
        }

        context.startForegroundService(serviceIntent)
    }
}
