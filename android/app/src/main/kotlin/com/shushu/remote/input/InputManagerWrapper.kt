package com.shushu.remote.input

import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * InputManager 包装器，使用反射调用系统 API 注入输入事件
 * 参考 scrcpy 实现
 */
class InputManagerWrapper {

    companion object {
        private const val TAG = "InputManagerWrapper"

        // 注入模式
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
    }

    private var inputManager: InputManager? = null
    private var injectInputEventMethod: Method? = null
    private var isAvailable = false

    init {
        try {
            // 获取 InputManager 实例
            val getInstanceMethod = InputManager::class.java.getDeclaredMethod("getInstance")
            inputManager = getInstanceMethod.invoke(null) as InputManager

            // 获取 injectInputEvent 方法
            injectInputEventMethod = InputManager::class.java.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )

            isAvailable = true
            Log.d(TAG, "InputManager wrapper initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize InputManager wrapper", e)
            isAvailable = false
        }
    }

    fun isAvailable(): Boolean = isAvailable

    /**
     * 注入输入事件
     */
    fun injectInputEvent(event: InputEvent, mode: Int = INJECT_INPUT_EVENT_MODE_ASYNC): Boolean {
        if (!isAvailable) return false

        return try {
            val result = injectInputEventMethod?.invoke(inputManager, event, mode) as? Boolean
            result ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input event", e)
            false
        }
    }

    /**
     * 注入按键事件
     */
    fun injectKeyEvent(action: Int, keyCode: Int, metaState: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now,                              // downTime
            now,                              // eventTime
            action,                           // action
            keyCode,                          // code
            0,                                // repeat
            metaState,                        // metaState
            KeyCharacterMap.VIRTUAL_KEYBOARD, // deviceId
            0,                                // scancode
            KeyEvent.FLAG_FROM_SYSTEM,        // flags
            InputDevice.SOURCE_KEYBOARD       // source
        )
        return injectInputEvent(event)
    }

    /**
     * 注入按键按下和释放
     */
    fun injectKeyPress(keyCode: Int, metaState: Int = 0): Boolean {
        val down = injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, metaState)
        val up = injectKeyEvent(KeyEvent.ACTION_UP, keyCode, metaState)
        return down && up
    }

    /**
     * 注入触摸事件
     */
    fun injectTouchEvent(action: Int, x: Float, y: Float, displayId: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()

        val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })

        val event = MotionEvent.obtain(
            now,                           // downTime
            now,                           // eventTime
            action,                        // action
            1,                             // pointerCount
            pointerProperties,             // pointerProperties
            pointerCoords,                 // pointerCoords
            0,                             // metaState
            0,                             // buttonState
            1f,                            // xPrecision
            1f,                            // yPrecision
            0,                             // deviceId
            0,                             // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN,// source
            0                              // flags
        )

        val result = injectInputEvent(event)
        event.recycle()
        return result
    }

    /**
     * 注入滚轮事件
     */
    fun injectScroll(x: Float, y: Float, hScroll: Float, vScroll: Float): Boolean {
        if (!isAvailable) return false

        val now = SystemClock.uptimeMillis()

        val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })

        val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        })

        val event = MotionEvent.obtain(
            now,                           // downTime
            now,                           // eventTime
            MotionEvent.ACTION_SCROLL,     // action
            1,                             // pointerCount
            pointerProperties,             // pointerProperties
            pointerCoords,                 // pointerCoords
            0,                             // metaState
            0,                             // buttonState
            1f,                            // xPrecision
            1f,                            // yPrecision
            0,                             // deviceId
            0,                             // edgeFlags
            InputDevice.SOURCE_MOUSE,      // source
            0                              // flags
        )

        val result = injectInputEvent(event)
        event.recycle()
        return result
    }

    /**
     * 注入点击
     */
    fun injectTap(x: Float, y: Float): Boolean {
        val down = injectTouchEvent(MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(50)
        val up = injectTouchEvent(MotionEvent.ACTION_UP, x, y)
        return down && up
    }

    /**
     * 注入长按
     */
    fun injectLongPress(x: Float, y: Float, duration: Int = 800): Boolean {
        val down = injectTouchEvent(MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(duration.toLong())
        val up = injectTouchEvent(MotionEvent.ACTION_UP, x, y)
        return down && up
    }

    /**
     * 注入滑动
     */
    fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Int = 300): Boolean {
        val steps = 20
        val stepDuration = duration / steps
        val dx = (endX - startX) / steps
        val dy = (endY - startY) / steps

        var success = injectTouchEvent(MotionEvent.ACTION_DOWN, startX, startY)
        if (!success) return false

        for (i in 1..steps) {
            Thread.sleep(stepDuration.toLong())
            val x = startX + dx * i
            val y = startY + dy * i
            success = injectTouchEvent(MotionEvent.ACTION_MOVE, x, y)
            if (!success) break
        }

        Thread.sleep(stepDuration.toLong())
        success = injectTouchEvent(MotionEvent.ACTION_UP, endX, endY)
        return success
    }
}
