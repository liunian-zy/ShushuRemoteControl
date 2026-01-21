package com.shushu.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shushu.remote.service.RemoteService
import com.shushu.remote.util.DeviceIdProvider

class RemoteApplication : Application() {

    companion object {
        const val CHANNEL_ID = "remote_service_channel"
        private const val TAG = "RemoteApplication"
        private const val PREFS_NAME = "remote_config"

        @Volatile
        var isServiceRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 应用启动时自动启动服务（persistent 应用开机自动启动）
        startRemoteService()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "远程控制服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "远程控制服务运行中"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startRemoteService() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val serverUrl = prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL) ?: return
        val deviceId = DeviceIdProvider.getDeviceId(this)
        val deviceName = prefs.getString("device_name", Build.MODEL) ?: Build.MODEL
        val token = prefs.getString("token", "Vh2Zzjtb3NIUk1X6rbfzKAEsFGk/ASX3") ?: return

        Log.d(TAG, "Starting RemoteService with deviceId: $deviceId")

        val serviceIntent = Intent(this, RemoteService::class.java).apply {
            putExtra("server_url", serverUrl)
            putExtra("device_id", deviceId)
            putExtra("device_name", deviceName)
            putExtra("token", token)
        }

        startForegroundService(serviceIntent)
    }

}
