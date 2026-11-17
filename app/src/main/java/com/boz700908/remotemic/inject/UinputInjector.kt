package com.boz700908.remotemic.inject

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import java.io.File

/**
 * root 通道的 uinput 实现：由 `su` 拉起 [libuinputd]，用 /dev/uinput 造一个虚拟键盘。
 *
 * 相比 `input keyevent`，它能做真正的按下/抬起分离与组合键，延迟也更低
 * （一次写入而不是每键 fork 一个 Java 进程）。
 *
 * 注意这里的码是 Linux `KEY_*`，不是 Android `KeyEvent` 码，入口负责换算。
 */
object UinputInjector {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var ready = false

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    private var lastLine: String = ""

    private var binary: String = ""

    fun init(context: Context) {
        binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME).absolutePath
    }

    @Synchronized
    fun isReady(): Boolean {
        if (ready && process?.isAlive == true) return true
        return start()
    }

    /** Linux KEY 码点按。 */
    fun tap(code: Int): Boolean = send("t $code")

    fun down(code: Int): Boolean = send("d $code")

    fun up(code: Int): Boolean = send("u $code")

    /** 多个 Linux KEY 码同时按下（组合键）。 */
    fun combination(vararg codes: Int): Boolean {
        val list = codes.filter { it > 0 }.take(8)
        if (list.isEmpty()) return false
        return send("k ${list.joinToString(" ")}")
    }

    @Synchronized
    fun send(command: String): Boolean {
        val p = process
        if (!ready || p == null || !p.isAlive) {
            if (!start()) return false
        }
        return try {
            val stdin = process?.outputStream ?: return false
            stdin.write((command + "\n").toByteArray())
            stdin.flush()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "写入失败：$command", t)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        val p = process ?: return
        runCatching {
            p.outputStream.write("q\n".toByteArray())
            p.outputStream.flush()
        }
        runCatching { p.waitFor() }
        runCatching { p.destroy() }
        process = null
        ready = false
    }

    @Synchronized
    private fun start(): Boolean {
        if (binary.isEmpty()) {
            lastError = "未初始化"
            return false
        }
        if (!File(binary).exists()) {
            lastError = "缺少 $binary"
            Log.w(TAG, lastError)
            return false
        }
        if (process?.isAlive == true) stop()
        ready = false
        lastLine = ""
        val p = try {
            ProcessBuilder("su", "-c", binary).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            lastError = "启动失败：${t.message}"
            Log.w(TAG, lastError, t)
            return false
        }
        process = p
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    lastLine = line
                    when {
                        line == "ready" -> ready = true
                        line != "ok" && line != "err" -> {
                            lastError = line
                            Log.w(TAG, "uinputd: $line")
                        }
                    }
                }
            }
        }, "uinput-reader").apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < deadline) {
            if (ready) return true
            if (!p.isAlive) break
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
        }
        lastError = if (lastError.isNotEmpty()) lastError else "uinputd 未在 1 秒内就绪 ${lastLine}"
        Log.w(TAG, lastError)
        stop()
        return false
    }

    /* --------------------- Android KeyEvent 码 → Linux KEY 码 --------------------- */

    private val SPECIAL: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to 103,      // KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN to 108,    // KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT to 105,    // KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT to 106,   // KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER to 28,   // KEY_ENTER
        KeyEvent.KEYCODE_ENTER to 28,
        KeyEvent.KEYCODE_SPACE to 57,
        KeyEvent.KEYCODE_BACK to 158,         // KEY_BACK
        KeyEvent.KEYCODE_HOME to 102,         // KEY_HOME
        KeyEvent.KEYCODE_MENU to 139,         // KEY_MENU
        KeyEvent.KEYCODE_APP_SWITCH to 127,   // KEY_COMPOSE
        KeyEvent.KEYCODE_DEL to 14,           // KEY_BACKSPACE
        KeyEvent.KEYCODE_TAB to 15,
        KeyEvent.KEYCODE_ESCAPE to 1,
        KeyEvent.KEYCODE_VOLUME_UP to 115,
        KeyEvent.KEYCODE_VOLUME_DOWN to 114,
        KeyEvent.KEYCODE_VOLUME_MUTE to 113,
        KeyEvent.KEYCODE_POWER to 116,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 164,
        KeyEvent.KEYCODE_MEDIA_PLAY to 207,
        KeyEvent.KEYCODE_MEDIA_PAUSE to 119,
        KeyEvent.KEYCODE_MEDIA_STOP to 166,
        KeyEvent.KEYCODE_MEDIA_NEXT to 163,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to 165,
        KeyEvent.KEYCODE_MEDIA_REWIND to 168,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to 208,
        KeyEvent.KEYCODE_CHANNEL_UP to 402,   // KEY_CHANNELUP
        KeyEvent.KEYCODE_CHANNEL_DOWN to 403,
        KeyEvent.KEYCODE_TV to 377,           // KEY_TV
        KeyEvent.KEYCODE_GUIDE to 362,        // KEY_PROGRAM
        KeyEvent.KEYCODE_INFO to 358,
        KeyEvent.KEYCODE_SETTINGS to 172,     // KEY_HOMEPAGE
        KeyEvent.KEYCODE_SEARCH to 217,
        KeyEvent.KEYCODE_ASSIST to 217,
        KeyEvent.KEYCODE_CAMERA to 212,       // KEY_CAMERA
        KeyEvent.KEYCODE_BRIGHTNESS_UP to 225,
        KeyEvent.KEYCODE_BRIGHTNESS_DOWN to 224,
        KeyEvent.KEYCODE_MUTE to 113,
        KeyEvent.KEYCODE_SLEEP to 142,
        KeyEvent.KEYCODE_WAKEUP to 143,
        KeyEvent.KEYCODE_CAPS_LOCK to 58,
        KeyEvent.KEYCODE_SHIFT_LEFT to 42,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 54,
        KeyEvent.KEYCODE_CTRL_LEFT to 29,
        KeyEvent.KEYCODE_CTRL_RIGHT to 97,
        KeyEvent.KEYCODE_ALT_LEFT to 56,
        KeyEvent.KEYCODE_ALT_RIGHT to 100,
        KeyEvent.KEYCODE_MOVE_HOME to 102,
        KeyEvent.KEYCODE_MOVE_END to 107,
        KeyEvent.KEYCODE_PAGE_UP to 104,
        KeyEvent.KEYCODE_PAGE_DOWN to 109,
        KeyEvent.KEYCODE_INSERT to 110,
        KeyEvent.KEYCODE_FORWARD_DEL to 111,
    )

    /** Android 的 A..Z（29..54）在 Linux 上不是连续的。 */
    private val LETTERS = intArrayOf(
        30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
        49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44,
    )

    /** Android 的 0..9（7..16）；Linux 的 1..9、0 是 2..11。 */
    private val DIGITS = intArrayOf(11, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    fun linuxCode(androidKeyCode: Int): Int {
        SPECIAL[androidKeyCode]?.let { return it }
        if (androidKeyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return LETTERS[androidKeyCode - KeyEvent.KEYCODE_A]
        }
        if (androidKeyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return DIGITS[androidKeyCode - KeyEvent.KEYCODE_0]
        }
        if (androidKeyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            val index = androidKeyCode - KeyEvent.KEYCODE_F1
            return if (index < 10) 59 + index else 87 + (index - 10)
        }
        return 0
    }

    private const val BINARY_NAME = "libuinputd.so"
    private const val TAG = "UinputInjector"
}
