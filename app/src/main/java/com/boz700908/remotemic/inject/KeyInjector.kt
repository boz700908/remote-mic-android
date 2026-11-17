package com.boz700908.remotemic.inject

import android.util.Log
import java.io.BufferedReader

/**
 * 系统级按键注入。
 *
 * Android 没有 SendInput 这类接口，真实按键只能借 shell 身份执行 `input`：
 *  - [Channel.ROOT]：`su -c` 通道，`input keyevent` / `input keycombination` 全可用；
 *  - [Channel.NONE]：既没 root 也没 Shizuku 授权，只能退回无障碍的全局动作。
 *
 * Shizuku 通道（免 root 拿 shell 身份）尚未接入，见 README 待办。
 */
object KeyInjector {

    enum class Channel { ROOT, NONE }

    @Volatile
    var channel: Channel = Channel.NONE
        private set

    @Volatile
    private var probedAt = 0L

    /** 探测可用通道。root 探测要 fork 一次 su，结果缓存 10 秒。 */
    fun refresh(force: Boolean = false): Channel {
        val now = System.currentTimeMillis()
        if (!force && now - probedAt < 10_000) return channel
        probedAt = now
        channel = if (rootAvailable()) Channel.ROOT else Channel.NONE
        Log.i(TAG, "注入通道 ${channel.name}")
        return channel
    }

    val canInjectKeys: Boolean get() = channel != Channel.NONE

    /** 单键点按。 */
    fun tap(keyCode: Int): Boolean {
        if (keyCode == 0) return false
        if (refresh() == Channel.NONE) return false
        return exec("input keyevent $keyCode") != null
    }

    /** 组合键同时按下（Android 12+ 的 input keycombination）。 */
    fun combination(vararg keyCodes: Int): Boolean {
        val keys = keyCodes.filter { it != 0 }
        if (keys.isEmpty()) return false
        if (refresh() == Channel.NONE) return false
        return exec("input keycombination ${keys.joinToString(" ")}") != null
    }

    /** 文字注入走无障碍：只有它知道焦点输入框和替换语义。 */
    fun text(value: String, replace: Boolean): Boolean = Injector.typeText(value, replace)

    internal fun exec(command: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use(BufferedReader::readText).trim()
        val code = p.waitFor()
        if (code == 0) out else null.also { Log.w(TAG, "$command 退出码 $code：$out") }
    }.getOrElse {
        Log.w(TAG, "执行失败：$command", it)
        null
    }

    private fun rootAvailable(): Boolean = exec("id")?.contains("uid=0") == true

    private const val TAG = "KeyInjector"
}
