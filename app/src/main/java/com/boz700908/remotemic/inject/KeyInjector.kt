package com.boz700908.remotemic.inject

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader

/**
 * 系统级按键注入。
 *
 * Android 没有 SendInput 这类接口，真实按键只能借「有 shell 身份的进程」执行 `input`：
 *  - [Channel.ROOT]：`su -c` 通道（已 root 的机器）；
 *  - [Channel.SHIZUKU]：Shizuku 通道（免 root，用户在 Shizuku 里授权 shell 身份）；
 *  - [Channel.NONE]：两者都没有，只能退回无障碍的全局动作。
 *
 * 优先级 root > Shizuku：root 不需要用户额外装应用，Shizuku 拿来兜没有 root 的机器。
 * 探测要 fork 一次 `su`，结果缓存 10 秒。
 */
object KeyInjector {

    enum class Channel(val label: String) {
        ROOT("root（su）"),
        SHIZUKU("Shizuku"),
        NONE("不可用"),
    }

    @Volatile
    var channel: Channel = Channel.NONE
        private set

    @Volatile
    private var probedAt = 0L

    @Volatile
    private var shizukuNewProcess: java.lang.reflect.Method? = null

    fun refresh(force: Boolean = false): Channel {
        val now = System.currentTimeMillis()
        if (!force && now - probedAt < 10_000) return channel
        probedAt = now
        val next = when {
            rootAvailable() -> Channel.ROOT
            shizukuGranted() -> Channel.SHIZUKU
            else -> Channel.NONE
        }
        if (next != channel) Log.i(TAG, "注入通道 ${channel.name} → ${next.name}")
        channel = next
        return next
    }

    val canInjectKeys: Boolean get() = refresh() != Channel.NONE

    /** 单键点按。 */
    fun tap(keyCode: Int): Boolean {
        if (keyCode == 0) return false
        if (refresh() == Channel.NONE) return false
        return exec("input keyevent $keyCode") != null
    }

    /** 组合键同时按下（Android 7.0+ 的 input keycombination）。 */
    fun combination(vararg keyCodes: Int): Boolean {
        val keys = keyCodes.filter { it != 0 }
        if (keys.isEmpty()) return false
        if (refresh() == Channel.NONE) return false
        return exec("input keycombination ${keys.joinToString(" ")}") != null
    }

    /** 文字注入走无障碍：只有它知道焦点输入框和替换语义。 */
    fun text(value: String, replace: Boolean): Boolean = Injector.typeText(value, replace)

    /* ----------------------------- Shizuku 授权 ----------------------------- */

    /** 设备上是否装了正在运行的 Shizuku。 */
    fun shizukuUsable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已经拿到 Shizuku 的 shell 授权。 */
    fun shizukuGranted(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        if (isPreV11()) true
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 弹 Shizuku 授权框；结果由调用方注册 Shizuku.addRequestPermissionResultListener 接收。 */
    fun requestShizuku(requestCode: Int = REQ_SHIZUKU): Boolean = runCatching {
        if (!Shizuku.pingBinder()) false
        else if (isPreV11()) true
        else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) true
        else {
            Shizuku.requestPermission(requestCode)
            true
        }
    }.getOrElse {
        Log.w(TAG, "请求 Shizuku 授权失败", it)
        false
    }

    private fun isPreV11(): Boolean = runCatching { Shizuku.isPreV11() }.getOrDefault(false)

    /* ------------------------------- 执行通道 ------------------------------- */

    internal fun exec(command: String): String? = when (refresh()) {
        Channel.ROOT -> runShell(arrayOf("su", "-c", command))
        Channel.SHIZUKU -> execShizuku(command)
        Channel.NONE -> null
    }

    private fun runShell(argv: Array<String>): String? = runCatching {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use(BufferedReader::readText).trim()
        val code = p.waitFor()
        if (code == 0) out else null.also { Log.w(TAG, "${argv.joinToString(" ")} 退出码 $code：$out") }
    }.getOrElse {
        Log.w(TAG, "执行失败：${argv.joinToString(" ")}", it)
        null
    }

    /** Shizuku 的 newProcess 是隐藏 API，按签名反射取用，取不到就降级。 */
    private fun execShizuku(command: String): String? = runCatching {
        val method = shizukuNewProcess ?: Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            .also {
                it.isAccessible = true
                shizukuNewProcess = it
            }
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val out = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
        val code = process.waitFor()
        if (code == 0) out else null.also { Log.w(TAG, "[shizuku] $command 退出码 $code：$out") }
    }.getOrElse {
        Log.w(TAG, "[shizuku] 执行失败：$command", it)
        null
    }

    private fun rootAvailable(): Boolean = runShell(arrayOf("su", "-c", "id"))?.contains("uid=0") == true

    const val REQ_SHIZUKU = 0x5A1

    private const val TAG = "KeyInjector"
}
