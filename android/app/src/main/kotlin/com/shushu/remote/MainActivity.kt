package com.shushu.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shushu.remote.service.RemoteService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val PREFS_NAME = "remote_config"
    }

    private lateinit var etServerUrl: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var etToken: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var mediaProjectionManager: MediaProjectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadConfig()

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 自动启动连接（静默模式）
        autoStartService()
    }

    private fun initViews() {
        etServerUrl = findViewById(R.id.et_server_url)
        etDeviceId = findViewById(R.id.et_device_id)
        etDeviceName = findViewById(R.id.et_device_name)
        etToken = findViewById(R.id.et_token)
        tvStatus = findViewById(R.id.tv_status)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        btnStart.setOnClickListener { startServiceWithProjection() }
        btnStop.setOnClickListener { stopService() }
    }

    private fun generateDeviceId(): String {
        // 优先使用 Android ID
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        Log.d(TAG, "Android ID: $androidId")
        if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
            return androidId
        }
        // 回退到设备型号 + 随机后缀
        val fallbackId = "DEVICE_${android.os.Build.MODEL.replace(" ", "_")}_${System.currentTimeMillis() % 10000}"
        Log.d(TAG, "Fallback ID: $fallbackId")
        return fallbackId
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDeviceId = prefs.getString("device_id", null)
        Log.d(TAG, "Saved device_id from prefs: $savedDeviceId")

        val deviceId = savedDeviceId ?: generateDeviceId()
        Log.d(TAG, "Final device_id: $deviceId")

        etServerUrl.setText(prefs.getString("server_url", "ws://192.168.50.174:9222/ws/device"))
        etDeviceId.setText(deviceId)
        etDeviceName.setText(prefs.getString("device_name", "工业设备-${android.os.Build.MODEL}"))
        etToken.setText(prefs.getString("token", "shushu123"))
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("server_url", etServerUrl.text.toString())
            putString("device_id", etDeviceId.text.toString())
            putString("device_name", etDeviceName.text.toString())
            putString("token", etToken.text.toString())
            apply()
        }
    }

    /**
     * 自动启动服务 - 静默模式（使用系统权限，无需用户授权）
     */
    private fun autoStartService() {
        saveConfig()
        Log.d(TAG, "Auto starting service in silent mode...")

        val serviceIntent = Intent(this, RemoteService::class.java).apply {
            putExtra("server_url", etServerUrl.text.toString())
            putExtra("device_id", etDeviceId.text.toString())
            putExtra("device_name", etDeviceName.text.toString())
            putExtra("token", etToken.text.toString())
            // 不传 result_code 和 result_data，让服务使用系统权限模式
        }

        startForegroundService(serviceIntent)
        tvStatus.text = "状态: 运行中"
    }

    /**
     * 手动启动服务（请求 MediaProjection 授权，作为备用）
     */
    private fun startServiceWithProjection() {
        saveConfig()

        // 请求屏幕录制权限
        mediaProjectionManager?.createScreenCaptureIntent()?.let { intent ->
            startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
        }
    }

    private fun stopService() {
        val intent = Intent(this, RemoteService::class.java)
        stopService(intent)
        tvStatus.text = "状态: 已停止"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // 启动服务（带 MediaProjection 授权）
                val serviceIntent = Intent(this, RemoteService::class.java).apply {
                    putExtra("server_url", etServerUrl.text.toString())
                    putExtra("device_id", etDeviceId.text.toString())
                    putExtra("device_name", etDeviceName.text.toString())
                    putExtra("token", etToken.text.toString())
                    putExtra("result_code", resultCode)
                    putExtra("result_data", data)
                }

                startForegroundService(serviceIntent)
                tvStatus.text = "状态: 运行中（MediaProjection）"
            } else {
                Toast.makeText(this, "需要屏幕录制权限", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
