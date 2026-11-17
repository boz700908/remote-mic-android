package com.boz700908.remotemic.inject

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader

/**
 * 系统级按键注入。
 *
 * Android 没有 SendInput 这类接口，真实按键只能借「有 shell 身份的进程」：
 *  - [Channel.UINPUT]：root 下写 /dev/uinput 的虚拟键盘，能按住与真组合键（延迟最低）；
 *  - [Channel.ROOT]：root 下 `input keyevent`，只有整按（uinput 不可用时的退路）；
 *  - [Channel.SHIZUKU]：Shizuku 提供 shell 身份，同样走 `input keyevent`；
 *  - [Channel.NONE]：都没有，只能退回无障碍的全局动作。
 *
 * 优先级 uinput > root > Shizuku：root 不需要用户额外装应用，Shizuku 兜没有 root 的机器。
 * 探测要 fork 一次 `su`（uinput 还要拉起守护进程），结果缓存 30 秒。
 */
object KeyInjector {

    enum class Channel(val label: String) {
        UINPUT("root · uinput"),
        ROOT("root（su input）"),
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

    fun init(context: Context) {
        UinputInjector.init(context)
    }

    fun refresh(force: Boolean = false): Channel {
        val now = System.currentTimeMillis()
        if (!force && now - probedAt < CACHE_MS) return channel
        probedAt = now
        val next = when {
            rootAvailable() -> if (UinputInjector.isReady()) Channel.UINPUT else Channel.ROOT
            shizukuGranted() -> Channel.SHIZUKU
            else -> Channel.NONE
        }
        if (next != channel) Log.i(TAG, "注入通道 ${channel.name} → ${next.name}")
        channel = next
        return next
    }

    val canInjectKeys: Boolean get() = refresh() != Channel.NONE

    /** 是否能发「按住」——只有 uinput 通道能。 */
    val canHold: Boolean get() = refresh() == Channel.UINPUT

    /** 单键点按。 */
    fun tap(keyCode: Int): Boolean {
        if (keyCode == 0) return false
        return when (refresh()) {
            Channel.UINPUT -> {
                val code = UinputInjector.linuxCode(keyCode)
                if (code != 0 && UinputInjector.tap(code)) true
                else exec("input keyevent $keyCode") != null
            }
            Channel.ROOT, Channel.SHIZUKU -> exec("input keyevent $keyCode") != null
            Channel.NONE -> false
        }
    }

    /** 按下不放（仅 uinput；其它通道退化成点按）。 */
    fun down(keyCode: Int): Boolean {
        if (keyCode == 0) return false
        if (refresh() != Channel.UINPUT) return tap(keyCode)
        val code = UinputInjector.linuxCode(keyCode)
        return code != 0 && UinputInjector.down(code)
    }

    /** 抬起。 */
    fun up(keyCode: Int): Boolean {
        if (keyCode == 0) return false
        if (refresh() != Channel.UINPUT) return false
        val code = UinputInjector.linuxCode(keyCode)
        return code != 0 && UinputInjector.up(code)
    }

    /** 按住 [ms] 毫秒再抬起；非 uinput 通道下等价于点按。 */
    fun longPress(keyCode: Int, ms: Long = 600): Boolean {
        if (keyCode == 0) return false
        if (refresh() != Channel.UINPUT) return tap(keyCode)
        val code = UinputInjector.linuxCode(keyCode)
        if (code == 0) return tap(keyCode)
        if (!UinputInjector.down(code)) return tap(keyCode)
        try {
            Thread.sleep(ms.coerceIn(50, 10_000))
        } catch (_: InterruptedException) {
        }
        return UinputInjector.up(code)
    }

    /** 组合键同时按下（uinput 通道下是真同时；其余通道用 Android 7.0+ 的 input keycombination）。 */
    fun combination(vararg keyCodes: Int): Boolean {
        val keys = keyCodes.filter { it != 0 }
        if (keys.isEmpty()) return false
        return when (refresh()) {
            Channel.UINPUT -> {
                val codes = keys.map { UinputInjector.linuxCode(it) }.filter { it != 0 }
                if (codes.isNotEmpty() && UinputInjector.combination(*codes.toIntArray())) true
                else exec("input keycombination ${keys.joinToString(" ")}") != null
            }
            Channel.ROOT, Channel.SHIZUKU -> exec("input keycombination ${keys.joinToString(" ")}") != null
            Channel.NONE -> false
        }
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
        Channel.UINPUT, Channel.ROOT -> runShell(arrayOf("su", "-c", command))
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

    private const val CACHE_MS = 30_000L
    private const val TAG = "KeyInjector"
}
