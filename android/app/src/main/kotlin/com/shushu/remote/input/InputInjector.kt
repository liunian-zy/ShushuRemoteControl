package com.shushu.remote.input

import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import kotlin.concurrent.thread

/**
 * 输入注入器
 * 优先使用 InputManager API，失败时回退到 shell 命令
 */
class InputInjector {

    companion object {
        private const val TAG = "InputInjector"
    }

    private val inputManagerWrapper = InputManagerWrapper()
    private val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    private val useInputManager = inputManagerWrapper.isAvailable()

    init {
        Log.d(TAG, "InputInjector initialized, useInputManager=$useInputManager")
    }

    /**
     * 注入点击事件
     */
    fun injectTap(x: Int, y: Int) {
        Log.d(TAG, "injectTap: x=$x, y=$y")
        thread {
            if (useInputManager) {
                val success = inputManagerWrapper.injectTap(x.toFloat(), y.toFloat())
                Log.d(TAG, "InputManager tap result: $success")
                if (!success) {
                    executeShellCommand("input tap $x $y")
                }
            } else {
                executeShellCommand("input tap $x $y")
            }
        }
    }

    /**
     * 注入滑动事件
     */
    fun injectSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Int = 300) {
        Log.d(TAG, "injectSwipe: ($startX,$startY) -> ($endX,$endY)")
        thread {
            if (useInputManager) {
                val success = inputManagerWrapper.injectSwipe(
                    startX.toFloat(), startY.toFloat(),
                    endX.toFloat(), endY.toFloat(),
                    duration
                )
                Log.d(TAG, "InputManager swipe result: $success")
                if (!success) {
                    executeShellCommand("input swipe $startX $startY $endX $endY $duration")
                }
            } else {
                executeShellCommand("input swipe $startX $startY $endX $endY $duration")
            }
        }
    }

    /**
     * 注入长按事件
     */
    fun injectLongPress(x: Int, y: Int, duration: Int = 800) {
        Log.d(TAG, "injectLongPress: x=$x, y=$y, duration=$duration")
        thread {
            if (useInputManager) {
                val success = inputManagerWrapper.injectLongPress(x.toFloat(), y.toFloat(), duration)
                Log.d(TAG, "InputManager longpress result: $success")
                if (!success) {
                    // 长按用 swipe 模拟：起点终点相同，持续时间长
                    executeShellCommand("input swipe $x $y $x $y $duration")
                }
            } else {
                executeShellCommand("input swipe $x $y $x $y $duration")
            }
        }
    }

    /**
     * 注入滚轮事件
     */
    fun injectScroll(x: Int, y: Int, hScroll: Float, vScroll: Float) {
        Log.d(TAG, "injectScroll: x=$x, y=$y, hScroll=$hScroll, vScroll=$vScroll")
        thread {
            if (useInputManager) {
                val success = inputManagerWrapper.injectScroll(x.toFloat(), y.toFloat(), hScroll, vScroll)
                Log.d(TAG, "InputManager scroll result: $success")
                if (!success) {
                    // 回退：用滑动模拟滚动
                    val distance = (vScroll * 100).toInt()
                    executeShellCommand("input swipe $x $y $x ${y - distance} 100")
                }
            } else {
                // 用滑动模拟滚动
                val distance = (vScroll * 100).toInt()
                executeShellCommand("input swipe $x $y $x ${y - distance} 100")
            }
        }
    }

    /**
     * 注入粘贴操作 (Ctrl+V)
     */
    fun injectPaste() {
        Log.d(TAG, "injectPaste")
        thread {
            // Android 的粘贴快捷键是 KEYCODE_PASTE (279) 或 Ctrl+V
            // 先尝试 KEYCODE_PASTE
            executeShellCommand("input keyevent 279")
        }
    }

    /**
     * 隐藏软键盘
     */
    fun hideKeyboard() {
        Log.d(TAG, "hideKeyboard")
        thread {
            // 使用 ime 命令隐藏输入法
            executeShellCommand("input keyevent 111") // KEYCODE_ESCAPE 有时能隐藏键盘
            // 或者使用更可靠的方式
            executeShellCommand("cmd statusbar collapse") // 收起状态栏和键盘
        }
    }

    /**
     * 注入按键事件
     */
    fun injectKey(keyCode: Int, action: String) {
        Log.d(TAG, "injectKey: keyCode=$keyCode, action=$action")
        thread {
            if (action == "down") {
                if (useInputManager) {
                    val success = inputManagerWrapper.injectKeyPress(keyCode)
                    Log.d(TAG, "InputManager key result: $success")
                    if (!success) {
                        // 回退到 shell 命令
                        executeShellCommand("input keyevent $keyCode")
                    }
                } else {
                    executeShellCommand("input keyevent $keyCode")
                }
            }
        }
    }

    /**
     * 注入文本 - 逐字符处理
     */
    fun injectText(text: String) {
        Log.d(TAG, "injectText: '$text'")
        thread {
            for (char in text) {
                injectChar(char)
                // 小延迟确保字符顺序正确
                Thread.sleep(10)
            }
        }
    }

    /**
     * 注入单个字符
     */
    private fun injectChar(char: Char) {
        // 方式1: 使用 InputManager + KeyCharacterMap
        if (useInputManager) {
            val events = charMap.getEvents(charArrayOf(char))
            if (events != null && events.isNotEmpty()) {
                var success = true
                for (event in events) {
                    if (!inputManagerWrapper.injectKeyEvent(event.action, event.keyCode, event.metaState)) {
                        success = false
                        break
                    }
                }
                if (success) {
                    Log.d(TAG, "Injected char '$char' via InputManager")
                    return
                }
            }
        }

        // 方式2: 使用 keyevent 命令
        val keyCode = charToKeyCode(char)
        if (keyCode != null) {
            val needShift = char.isUpperCase() || isShiftChar(char)
            if (needShift) {
                // 需要 Shift 组合键
                executeShellCommand("input keyevent --press KEYCODE_SHIFT_LEFT $keyCode")
            } else {
                executeShellCommand("input keyevent $keyCode")
            }
            Log.d(TAG, "Injected char '$char' via keyevent $keyCode")
            return
        }

        // 方式3: 使用 input text 命令
        val escaped = escapeForShell(char)
        executeShellCommand("input text $escaped")
        Log.d(TAG, "Injected char '$char' via input text")
    }

    /**
     * 判断是否需要 Shift 键
     */
    private fun isShiftChar(char: Char): Boolean {
        return char in "~!@#\$%^&*()_+{}|:\"<>?"
    }

    /**
     * 转义字符用于 shell 命令
     */
    private fun escapeForShell(char: Char): String {
        return when (char) {
            ' ' -> "' '"
            '\'' -> "\"'\""
            '"' -> "'\"'"
            '\\' -> "'\\\\'"
            '`' -> "'\\`'"
            '$' -> "'\\$'"
            '!' -> "'!'"
            '&' -> "'&'"
            '|' -> "'|'"
            ';' -> "';'"
            '(' -> "'('"
            ')' -> "')'"
            '<' -> "'<'"
            '>' -> "'>'"
            '*' -> "'*'"
            '?' -> "'?'"
            '[' -> "'['"
            ']' -> "']'"
            '{' -> "'{'"
            '}' -> "'}'"
            '#' -> "'#'"
            '~' -> "'~'"
            '^' -> "'^'"
            else -> "'$char'"
        }
    }

    /**
     * 字符转 Android KeyCode
     */
    private fun charToKeyCode(char: Char): Int? {
        val lowerChar = char.lowercaseChar()
        return when (lowerChar) {
            // 字母 a-z
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (lowerChar - 'a')
            // 数字 0-9
            '0' -> KeyEvent.KEYCODE_0
            '1' -> KeyEvent.KEYCODE_1
            '2' -> KeyEvent.KEYCODE_2
            '3' -> KeyEvent.KEYCODE_3
            '4' -> KeyEvent.KEYCODE_4
            '5' -> KeyEvent.KEYCODE_5
            '6' -> KeyEvent.KEYCODE_6
            '7' -> KeyEvent.KEYCODE_7
            '8' -> KeyEvent.KEYCODE_8
            '9' -> KeyEvent.KEYCODE_9
            // 常用符号
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\n' -> KeyEvent.KEYCODE_ENTER
            '\t' -> KeyEvent.KEYCODE_TAB
            '.' -> KeyEvent.KEYCODE_PERIOD
            ',' -> KeyEvent.KEYCODE_COMMA
            '-', '_' -> KeyEvent.KEYCODE_MINUS
            '=', '+' -> KeyEvent.KEYCODE_EQUALS
            '[', '{' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']', '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            ';', ':' -> KeyEvent.KEYCODE_SEMICOLON
            '\'', '"' -> KeyEvent.KEYCODE_APOSTROPHE
            '/', '?' -> KeyEvent.KEYCODE_SLASH
            '\\', '|' -> KeyEvent.KEYCODE_BACKSLASH
            '`', '~' -> KeyEvent.KEYCODE_GRAVE
            else -> null
        }
    }

    /**
     * 执行 shell 命令
     */
    private fun executeShellCommand(command: String) {
        try {
            Log.d(TAG, "Executing: $command")
            val startTime = System.currentTimeMillis()
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()
            val duration = System.currentTimeMillis() - startTime

            if (exitCode == 0) {
                Log.d(TAG, "Command success (${duration}ms)")
            } else {
                val error = process.errorStream.bufferedReader().readText()
                Log.e(TAG, "Command failed: exitCode=$exitCode, error=$error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing command: $command", e)
        }
    }
}
