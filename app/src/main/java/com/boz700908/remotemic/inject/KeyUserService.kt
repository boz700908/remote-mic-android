package com.boz700908.remotemic.inject

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.boz700908.remotemic.IKeyService

/**
 * 运行在 Shizuku 提供的 shell 身份下的按键注入服务（免 root 的真键盘模拟）。
 *
 * 为什么需要它：普通应用（含无障碍服务）拿不到 `INJECT_EVENTS` 权限，`input keyevent`
 * 只能整按、不能按住也不能真组合同步按下，也无法把任意键送到没有输入框的应用。
 * Shizuku 以 uid 2000 启动本服务，shell 持有 `INJECT_EVENTS`，于是可以直接调隐藏 API
 * `InputManager.injectInputEvent()`，把任意 `KeyEvent` 注入输入分发链。
 * Shizuku 明确保证 user service 进程**不受 non-SDK 接口限制**，这是本方案成立的前提。
 *
 * 本进程不是合法 Android 应用进程：没有 Application、没有 DataStore、多数 Context API
 * 不可用。因此这里只做纯注入，映射解析全部留在 App 进程完成后再把具体键码传进来。
 */
class KeyUserService : Service() {

    /** 同一批按下事件的公共按下时刻（组合键/长按要共用）。 */
    private var downTime = 0L

    /** 当前仍处于按下状态的键，用于释放兜底与去重。 */
    private val pressed = LinkedHashSet<Int>()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { releaseAllNow() }
        super.onDestroy()
    }

    private val binder = object : IKeyService.Stub() {

        override fun press(keyCode: Int, metaState: Int) {
            doDown(keyCode, metaState)
            SystemClock.sleep(PRESS_MS.toLong())
            doUp(keyCode, metaState)
        }

        override fun down(keyCode: Int, metaState: Int) = doDown(keyCode, metaState)

        override fun up(keyCode: Int, metaState: Int) = doUp(keyCode, metaState)

        override fun longPress(keyCode: Int, metaState: Int, ms: Int) {
            val hold = ms.coerceIn(PRESS_MS, MAX_HOLD_MS)
            doDown(keyCode, metaState)
            var waited = 0
            var repeat = 0
            while (waited + REPEAT_MS <= hold) {
                SystemClock.sleep(REPEAT_MS.toLong())
                waited += REPEAT_MS
                repeat++
                doRepeat(keyCode, metaState, repeat)
            }
            val rest = hold - waited
            if (rest > 0) SystemClock.sleep(rest.toLong())
            doUp(keyCode, metaState)
        }

        override fun combination(keyCodes: IntArray, holdMs: Int) {
            val codes = keyCodes.filter { it != 0 }.distinct()
            if (codes.isEmpty()) return
            // 修饰键先按住，其余键随后，抬起时逆序，与真实键盘一致。
            val (mods, mains) = codes.partition { it in MODIFIER_CODES }
            val meta = metaBits(codes)
            for (code in mods) doDown(code, meta)
            for (code in mains) doDown(code, meta)
            SystemClock.sleep(holdMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS).toLong())
            for (code in mains.asReversed()) doUp(code, meta)
            for (code in mods.asReversed()) doUp(code, meta)
        }

        override fun releaseAll() = releaseAllNow()

        override fun destroy() {
            releaseAllNow()
            stopSelf()
        }
    }

    /* ------------------------------- 事件构造 ------------------------------- */

    private fun doDown(keyCode: Int, metaState: Int, repeat: Int = 0) {
        if (keyCode == 0) return
        val now = SystemClock.uptimeMillis()
        if (repeat == 0 && pressed.isEmpty()) downTime = now
        if (send(if (downTime == 0L) now else downTime, now, KeyEvent.ACTION_DOWN, keyCode, repeat, metaState)) {
            pressed.add(keyCode)
        }
    }

    private fun doRepeat(keyCode: Int, metaState: Int, repeat: Int) {
        if (keyCode == 0) return
        val now = SystemClock.uptimeMillis()
        send(if (downTime == 0L) now else downTime, now, KeyEvent.ACTION_DOWN, keyCode, repeat, metaState)
    }

    private fun doUp(keyCode: Int, metaState: Int) {
        if (keyCode == 0) return
        val now = SystemClock.uptimeMillis()
        send(if (downTime == 0L) now - 1 else downTime, now, KeyEvent.ACTION_UP, keyCode, 0, metaState)
        pressed.remove(keyCode)
        if (pressed.isEmpty()) downTime = 0L
    }

    private fun releaseAllNow() {
        val codes = pressed.toList()
        if (codes.isEmpty()) {
            downTime = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        val start = if (downTime == 0L) now - 1 else downTime
        pressed.clear()
        for (code in codes.asReversed()) {
            send(start, now, KeyEvent.ACTION_UP, code, 0, 0)
        }
        downTime = 0L
    }

    /**
     * 十参构造器是公开 SDK API，最后一个参数是 source；六参版本 source 为 0，
     * 注入后可能被输入分发链忽略，所以这里显式指定 SOURCE_KEYBOARD。
     */
    private fun send(
        start: Long,
        now: Long,
        action: Int,
        keyCode: Int,
        repeat: Int,
        metaState: Int,
    ): Boolean {
        val event = KeyEvent(
            start,
            now,
            action,
            keyCode,
            repeat,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
        val manager = inputManager ?: return false
        val method = injectMethod ?: return false
        return runCatching { method.invoke(manager, event, MODE_ASYNC) as? Boolean ?: false }
            .onFailure { Log.w(TAG, "注入失败 code=$keyCode action=$action", it) }
            .getOrDefault(false)
    }

    /* ------------------------------ 隐藏 API 反射 ------------------------------ */

    private val inputManager: Any? by lazy {
        runCatching {
            Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance")
                .invoke(null)
        }.onFailure { Log.w(TAG, "取 InputManager 失败", it) }.getOrNull()
    }

    private val injectMethod: java.lang.reflect.Method? by lazy {
        val manager = inputManager ?: return@lazy null
        runCatching {
            manager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType!!,
            )
        }.onFailure { Log.w(TAG, "取 injectInputEvent 失败", it) }.getOrNull()
    }

    /* --------------------------------- 工具 --------------------------------- */

    private fun metaBits(codes: List<Int>): Int {
        var meta = 0
        for (code in codes) {
            meta = meta or when (code) {
                KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
                KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
                KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
                KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
                else -> 0
            }
        }
        return meta
    }

    private companion object {
        const val TAG = "KeyUserService"

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val MODE_ASYNC = 0

        const val PRESS_MS = 40
        const val REPEAT_MS = 50
        const val MIN_HOLD_MS = 20
        const val MAX_HOLD_MS = 30_000

        val MODIFIER_CODES = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
        )
    }
}
