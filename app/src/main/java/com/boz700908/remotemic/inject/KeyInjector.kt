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
 *  - [Channel.SHIZUKU_SVC]：Shizuku UserService 里直接 injectInputEvent，免 root 也能按住、
 *    真组合键与长按，能力与 uinput 同级（只是得多一次 binder 往返）；
 *  - [Channel.SHIZUKU]：Shizuku 提供 shell 身份但服务没绑上，只能 `input keyevent`；
 *  - [Channel.NONE]：都没有，只能退回无障碍的全局动作。
 *
 * 优先级 uinput > root > Shizuku 服务 > Shizuku 命令：能力从强到弱。
 * 探测要 fork 一次 `su`（uinput 还要拉起守护进程），结果缓存 30 秒。
 */
object KeyInjector {

    enum class Channel(val label: String) {
        UINPUT("root · uinput"),
        ROOT("root（su input）"),
        SHIZUKU_SVC("Shizuku 服务（真键盘）"),
        SHIZUKU("Shizuku（input 命令）"),
        NONE("不可用"),
    }

    @Volatile
    var channel: Channel = Channel.NONE
        private set

    @Volatile
    private var probedAt = 0L

    @Volatile
    private var shizukuNewProcess: java.lang.reflect.Method? = null

    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        UinputInjector.init(context)
        // 启动即预绑，等用户按键时通常早已连上。
        KeyServiceClient.prewarm(context)
    }

    fun refresh(force: Boolean = false): Channel {
        val now = System.currentTimeMillis()
        if (!force && now - probedAt < CACHE_MS) return channel
        probedAt = now
        val next = when {
            rootAvailable() -> if (UinputInjector.isReady()) Channel.UINPUT else Channel.ROOT
            shizukuGranted() -> if (KeyServiceClient.ready) Channel.SHIZUKU_SVC else Channel.SHIZUKU
            else -> Channel.NONE
        }
        if (next != channel) Log.i(TAG, "注入通道 ${channel.name} → ${next.name}")
        channel = next
        return next
    }

    val canInjectKeys: Boolean get() = refresh() != Channel.NONE

    /** 是否能真正「按住」——uinput 与 Shizuku 服务通道都可以。 */
    val canHold: Boolean get() = refresh().let { it == Channel.UINPUT || it == Channel.SHIZUKU_SVC }

    /** 单键点按。 */
    fun tap(keyCode: Int): Boolean {
        if (keyCode == 0) return false
        return when (refresh()) {
            Channel.UINPUT -> {
                val code = UinputInjector.linuxCode(keyCode)
                if (code != 0 && UinputInjector.tap(code)) true else commandTap(keyCode)
            }
            Channel.SHIZUKU_SVC -> withService { it.press(keyCode, 0) } || commandTap(keyCode)
            Channel.ROOT, Channel.SHIZUKU -> commandTap(keyCode)
            Channel.NONE -> false
        }
    }

    /** 按下不放（uinput 与 Shizuku 服务通道真按住；其它通道退化成点按）。 */
    fun down(keyCode: Int): Boolean {
        if (keyCode == 0) return false
        return when (refresh()) {
            Channel.UINPUT -> {
                val code = UinputInjector.linuxCode(keyCode)
                code != 0 && UinputInjector.down(code)
            }
            Channel.SHIZUKU_SVC -> withService { it.down(keyCode, 0) }
            else -> tap(keyCode)
        }
    }

    /** 抬起。 */
    fun up(keyCode: Int): Boolean {
        if (keyCode == 0) return false
        return when (refresh()) {
            Channel.UINPUT -> {
                val code = UinputInjector.linuxCode(keyCode)
                code != 0 && UinputInjector.up(code)
            }
            Channel.SHIZUKU_SVC -> withService { it.up(keyCode, 0) }
            else -> false
        }
    }

    /** 按住 [ms] 毫秒再抬起（会产生 repeat 事件，相当于真长按）。 */
    fun longPress(keyCode: Int, ms: Long = 600): Boolean {
        if (keyCode == 0) return false
        val hold = ms.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        return when (refresh()) {
            Channel.UINPUT -> {
                val code = UinputInjector.linuxCode(keyCode)
                if (code == 0 || !UinputInjector.down(code)) tap(keyCode)
                else {
                    sleep(hold)
                    UinputInjector.up(code)
                }
            }
            Channel.SHIZUKU_SVC -> withService { it.longPress(keyCode, 0, hold.toInt()) } || commandLongPress(keyCode)
            Channel.ROOT, Channel.SHIZUKU -> commandLongPress(keyCode)
            Channel.NONE -> tap(keyCode)
        }
    }

    /** 组合键同时按下（uinput 与 Shizuku 服务通道是真同时；其余通道用 input keycombination）。 */
    fun combination(vararg keyCodes: Int): Boolean {
        val keys = keyCodes.filter { it != 0 }
        if (keys.isEmpty()) return false
        return when (refresh()) {
            Channel.UINPUT -> {
                val codes = keys.map { UinputInjector.linuxCode(it) }.filter { it != 0 }
                if (codes.isNotEmpty() && UinputInjector.combination(*codes.toIntArray())) true
                else commandCombination(keys)
            }
            Channel.SHIZUKU_SVC -> withService { it.combination(keys.toIntArray(), COMBO_HOLD_MS) } ||
                commandCombination(keys)
            Channel.ROOT, Channel.SHIZUKU -> commandCombination(keys)
            Channel.NONE -> false
        }
    }

    /** 抬起所有按下的键，防止注入过程中断导致键卡住。 */
    fun releaseAll() {
        val service = KeyServiceClient.get() ?: return
        runCatching { service.releaseAll() }.onFailure { Log.w(TAG, "releaseAll 失败", it) }
    }

    /** 文字注入走无障碍：只有它知道焦点输入框和替换语义。 */
    /**
     * 在屏幕坐标上点一下；[holdMs] 大于 0 时按住不动（原地 `input swipe` 实现长按）。
     * 需要 root 或 Shizuku 通道，都没有时返回 false。
     */
    fun tapAt(x: Int, y: Int, holdMs: Long = 0L): Boolean {
        if (x < 0 || y < 0) return false
        val hold = holdMs.coerceIn(0L, MAX_HOLD_MS)
        val command = if (hold <= 0L) "input tap $x $y" else "input swipe $x $y $x $y $hold"
        return exec(command) != null
    }

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
        else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            // 授权已在，顺手把服务绑上。
            app?.let { KeyServiceClient.prewarm(it) }
            true
        } else {
            Shizuku.requestPermission(requestCode)
            true
        }
    }.getOrElse {
        Log.w(TAG, "请求 Shizuku 授权失败", it)
        false
    }

    private fun isPreV11(): Boolean = runCatching { Shizuku.isPreV11() }.getOrDefault(false)

    /* ------------------------------- 执行通道 ------------------------------- */

    private fun commandTap(keyCode: Int): Boolean = exec("input keyevent $keyCode") != null

    private fun commandLongPress(keyCode: Int): Boolean =
        exec("input keyevent --longpress $keyCode") != null || commandTap(keyCode)

    private fun commandCombination(keys: List<Int>): Boolean =
        exec("input keycombination ${keys.joinToString(" ")}") != null

    /** 调 Shizuku 服务；没就绪就返回 false 交给调用方回落。 */
    private inline fun withService(block: (com.boz700908.remotemic.IKeyService) -> Unit): Boolean {
        val context = app ?: return false
        val service = KeyServiceClient.ensure(context) ?: return false
        return runCatching {
            block(service)
            true
        }.onFailure { Log.w(TAG, "注入服务调用失败", it) }.getOrDefault(false)
    }

    internal fun exec(command: String): String? = when (refresh()) {
        Channel.UINPUT, Channel.ROOT -> runShell(arrayOf("su", "-c", command))
        Channel.SHIZUKU_SVC, Channel.SHIZUKU -> execShizuku(command)
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

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    const val REQ_SHIZUKU = 0x5A1

    private const val MIN_HOLD_MS = 20L
    private const val MAX_HOLD_MS = 30_000L
    private const val COMBO_HOLD_MS = 60
    private const val CACHE_MS = 30_000L
    private const val TAG = "KeyInjector"
}
