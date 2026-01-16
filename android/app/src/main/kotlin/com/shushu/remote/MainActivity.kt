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

        // 更新服务状态显示
        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun initViews() {
        etServerUrl = findViewById(R.id.et_server_url)
        etDeviceId = findViewById(R.id.et_device_id)
        etDeviceName = findViewById(R.id.et_device_name)
        etToken = findViewById(R.id.et_token)
        tvStatus = findViewById(R.id.tv_status)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)

        btnStart.setOnClickListener { startService() }
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

        etServerUrl.setText(prefs.getString("server_url", "wss://rc.photo.sqaigc.com/ws/device"))
        etDeviceId.setText(deviceId)
        etDeviceName.setText(prefs.getString("device_name", "${android.os.Build.MODEL}"))
        etToken.setText(prefs.getString("token", "Vh2Zzjtb3NIUk1X6rbfzKAEsFGk/ASX3"))
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

    private fun updateServiceStatus() {
        tvStatus.text = if (RemoteApplication.isServiceRunning) {
            "状态: 运行中"
        } else {
            "状态: 已停止"
        }
    }

    /**
     * 启动服务（静默模式，使用系统权限）
     */
    private fun startService() {
        saveConfig()
        Log.d(TAG, "Starting service in silent mode...")

        val serviceIntent = Intent(this, RemoteService::class.java).apply {
            putExtra("server_url", etServerUrl.text.toString())
            putExtra("device_id", etDeviceId.text.toString())
            putExtra("device_name", etDeviceName.text.toString())
            putExtra("token", etToken.text.toString())
        }

        startForegroundService(serviceIntent)
        tvStatus.text = "状态: 运行中"
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopService() {
        val intent = Intent(this, RemoteService::class.java)
        stopService(intent)
        tvStatus.text = "状态: 已停止"
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
    }
}
