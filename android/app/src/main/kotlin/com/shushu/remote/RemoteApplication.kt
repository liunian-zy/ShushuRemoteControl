package com.shushu.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class RemoteApplication : Application() {

    companion object {
        const val CHANNEL_ID = "remote_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
}
