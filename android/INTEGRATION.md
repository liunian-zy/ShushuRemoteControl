# 远程控制服务集成文档

## 基本信息

| 项目 | 值 |
|------|-----|
| 包名 | `com.shushu.remote` |
| 服务类名 | `com.shushu.remote.service.RemoteService` |

---

## 服务特性

本应用使用系统签名，配置了 `android:persistent="true"` 属性：

- 系统启动时自动启动（无需定昌 API 的开机自启动）
- 被杀后系统自动重启
- 内存不足时优先级最高，最后被杀
- 不占用定昌 API 的开机自启动名额

---

## 跨应用控制接口

### Action 常量

| Action | 说明 |
|--------|------|
| `com.shushu.remote.ACTION_START_SERVICE` | 启动远程服务 |
| `com.shushu.remote.ACTION_STOP_SERVICE` | 停止远程服务 |
| `com.shushu.remote.ACTION_QUERY_STATUS` | 查询服务状态 |
| `com.shushu.remote.ACTION_STATUS_RESPONSE` | 状态回复广播 |

---

## 使用方法

### 1. 启动服务

```kotlin
val intent = Intent("com.shushu.remote.ACTION_START_SERVICE")
intent.setPackage("com.shushu.remote")
sendBroadcast(intent)
```

### 2. 停止服务

```kotlin
val intent = Intent("com.shushu.remote.ACTION_STOP_SERVICE")
intent.setPackage("com.shushu.remote")
sendBroadcast(intent)
```

### 3. 查询服务状态

```kotlin
// 1. 注册状态回复接收器
val statusReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isRunning = intent.getBooleanExtra("is_running", false)
        Log.d("RemoteStatus", "远程服务运行状态: $isRunning")
    }
}
registerReceiver(statusReceiver, IntentFilter("com.shushu.remote.ACTION_STATUS_RESPONSE"))

// 2. 发送查询请求
val intent = Intent("com.shushu.remote.ACTION_QUERY_STATUS")
intent.setPackage("com.shushu.remote")
sendBroadcast(intent)

// 3. 使用完毕后注销接收器
unregisterReceiver(statusReceiver)
```

---

## 检测应用是否安装

```kotlin
fun isRemoteAppInstalled(context: Context): Boolean {
    return try {
        context.packageManager.getPackageInfo("com.shushu.remote", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
```

---

## 静默安装（使用定昌 API）

```kotlin
// 需要导入 ZtlApi.jar
ZtlManager.GetInstance().setContext(context)
ZtlManager.GetInstance().InstallApp("/sdcard/shushu_remote.apk")
```

---

## 完整工具类

```kotlin
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class RemoteServiceHelper(private val context: Context) {

    companion object {
        private const val TAG = "RemoteServiceHelper"
        private const val REMOTE_PACKAGE = "com.shushu.remote"

        private const val ACTION_START = "com.shushu.remote.ACTION_START_SERVICE"
        private const val ACTION_STOP = "com.shushu.remote.ACTION_STOP_SERVICE"
        private const val ACTION_QUERY = "com.shushu.remote.ACTION_QUERY_STATUS"
        private const val ACTION_RESPONSE = "com.shushu.remote.ACTION_STATUS_RESPONSE"
    }

    private var statusReceiver: BroadcastReceiver? = null

    /**
     * 检测远程应用是否已安装
     */
    fun isInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(REMOTE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 启动远程服务
     */
    fun startService() {
        val intent = Intent(ACTION_START).apply {
            setPackage(REMOTE_PACKAGE)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Start service broadcast sent")
    }

    /**
     * 停止远程服务
     */
    fun stopService() {
        val intent = Intent(ACTION_STOP).apply {
            setPackage(REMOTE_PACKAGE)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Stop service broadcast sent")
    }

    /**
     * 查询服务状态
     * @param callback 状态回调，true=运行中，false=未运行
     */
    fun queryStatus(callback: (Boolean) -> Unit) {
        // 注销旧的接收器
        unregisterReceiver()

        // 注册新的接收器
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val isRunning = intent.getBooleanExtra("is_running", false)
                Log.d(TAG, "Service status: $isRunning")
                callback(isRunning)
                unregisterReceiver()
            }
        }

        context.registerReceiver(statusReceiver, IntentFilter(ACTION_RESPONSE))

        // 发送查询
        val intent = Intent(ACTION_QUERY).apply {
            setPackage(REMOTE_PACKAGE)
        }
        context.sendBroadcast(intent)

        // 超时处理（2秒无响应视为未运行）
        Handler(Looper.getMainLooper()).postDelayed({
            if (statusReceiver != null) {
                Log.d(TAG, "Query timeout, assuming service not running")
                callback(false)
                unregisterReceiver()
            }
        }, 2000)
    }

    /**
     * 确保远程服务运行
     * @param callback 结果回调，true=服务已运行，false=启动失败
     */
    fun ensureRunning(callback: (Boolean) -> Unit) {
        if (!isInstalled()) {
            Log.e(TAG, "Remote app not installed")
            callback(false)
            return
        }

        queryStatus { isRunning ->
            if (isRunning) {
                Log.d(TAG, "Service already running")
                callback(true)
            } else {
                Log.d(TAG, "Service not running, starting...")
                startService()

                // 等待启动后再次确认
                Handler(Looper.getMainLooper()).postDelayed({
                    queryStatus(callback)
                }, 2000)
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        unregisterReceiver()
    }

    private fun unregisterReceiver() {
        statusReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // 忽略
            }
        }
        statusReceiver = null
    }
}
```

---

## 使用示例

```kotlin
class YourActivity : AppCompatActivity() {

    private lateinit var remoteHelper: RemoteServiceHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remoteHelper = RemoteServiceHelper(this)

        // 确保远程服务运行
        remoteHelper.ensureRunning { isRunning ->
            if (isRunning) {
                Log.d("YourApp", "远程服务已就绪")
            } else {
                Log.e("YourApp", "远程服务启动失败")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        remoteHelper.release()
    }
}
```

---

## Root 命令方式（备用）

### 检测应用是否安装

```bash
pm path com.shushu.remote
```

### 检测服务是否运行

```bash
dumpsys activity services com.shushu.remote/.service.RemoteService | grep "app="
```

或：

```bash
ps -A | grep com.shushu.remote
```

### 启动服务

```bash
am start-foreground-service -n com.shushu.remote/.service.RemoteService \
    --es server_url "wss://rc.photo.sqaigc.com/ws/device" \
    --es device_id "YOUR_DEVICE_ID" \
    --es device_name "YOUR_DEVICE_NAME" \
    --es token "YOUR_TOKEN"
```

### 停止服务

```bash
am stopservice -n com.shushu.remote/.service.RemoteService
```

### 静默安装

```bash
pm install -r /sdcard/shushu_remote.apk
```
