package com.shushu.remote.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shushu.remote.BuildConfig
import com.shushu.remote.RemoteApplication
import com.shushu.remote.service.RemoteService
import com.shushu.remote.util.DeviceIdProvider

/**
 * 跨应用服务控制接收器
 *
 * 其他应用可以通过发送广播来控制远程服务：
 *
 * 1. 启动服务:
 *    val intent = Intent("com.shushu.remote.ACTION_START_SERVICE")
 *    intent.setPackage("com.shushu.remote")
 *    sendBroadcast(intent)
 *
 * 2. 停止服务:
 *    val intent = Intent("com.shushu.remote.ACTION_STOP_SERVICE")
 *    intent.setPackage("com.shushu.remote")
 *    sendBroadcast(intent)
 *
 * 3. 查询服务状态:
 *    val intent = Intent("com.shushu.remote.ACTION_QUERY_STATUS")
 *    intent.setPackage("com.shushu.remote")
 *    sendBroadcast(intent)
 *
 *    // 监听状态回复
 *    registerReceiver(object : BroadcastReceiver() {
 *        override fun onReceive(context: Context, intent: Intent) {
 *            val isRunning = intent.getBooleanExtra("is_running", false)
 *        }
 *    }, IntentFilter("com.shushu.remote.ACTION_STATUS_RESPONSE"))
 */
class ServiceControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceControlReceiver"
        private const val PREFS_NAME = "remote_config"

        // 接收的 Action
        const val ACTION_START_SERVICE = "com.shushu.remote.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.shushu.remote.ACTION_STOP_SERVICE"
        const val ACTION_QUERY_STATUS = "com.shushu.remote.ACTION_QUERY_STATUS"

        // 发送的 Action
        const val ACTION_STATUS_RESPONSE = "com.shushu.remote.ACTION_STATUS_RESPONSE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            ACTION_START_SERVICE -> startService(context)
            ACTION_STOP_SERVICE -> stopService(context)
            ACTION_QUERY_STATUS -> queryStatus(context)
        }
    }

    private fun startService(context: Context) {
        Log.d(TAG, "Starting RemoteService from external request")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val serverUrl = prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL) ?: return
        val deviceId = DeviceIdProvider.getDeviceId(context)
        val deviceName = prefs.getString("device_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        val token = prefs.getString("token", "Vh2Zzjtb3NIUk1X6rbfzKAEsFGk/ASX3") ?: return

        val serviceIntent = Intent(context, RemoteService::class.java).apply {
            putExtra("server_url", serverUrl)
            putExtra("device_id", deviceId)
            putExtra("device_name", deviceName)
            putExtra("token", token)
        }

        context.startForegroundService(serviceIntent)
        Log.d(TAG, "RemoteService started")

        // 发送状态回复
        sendStatusResponse(context, true)
    }

    private fun stopService(context: Context) {
        Log.d(TAG, "Stopping RemoteService from external request")

        val serviceIntent = Intent(context, RemoteService::class.java)
        context.stopService(serviceIntent)

        // 发送状态回复
        sendStatusResponse(context, false)
    }

    private fun queryStatus(context: Context) {
        Log.d(TAG, "Querying RemoteService status")
        sendStatusResponse(context, RemoteApplication.isServiceRunning)
    }

    private fun sendStatusResponse(context: Context, isRunning: Boolean) {
        val responseIntent = Intent(ACTION_STATUS_RESPONSE).apply {
            putExtra("is_running", isRunning)
            // 添加包名以便其他应用过滤
            putExtra("package", "com.shushu.remote")
        }
        context.sendBroadcast(responseIntent)
        Log.d(TAG, "Status response sent: isRunning=$isRunning")
    }
}
