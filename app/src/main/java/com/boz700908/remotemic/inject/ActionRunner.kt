package com.boz700908.remotemic.inject

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import com.boz700908.remotemic.core.KeymapStore

/**
 * 把 [KeymapStore] 里的动作串变成真动作。
 *
 * 原则：**先试真按键，再试无障碍，都不行就把按键还给原来的转发逻辑**。
 * 所以 [run] 返回 handled=false 时，调用方应当照旧执行默认处理，而不是丢掉这个键。
 */
object ActionRunner {

    /** handled=false 表示「没配 / 没做成」，调用方应继续走默认转发。 */
    data class Result(val handled: Boolean, val isVoice: Boolean = false)

    private val HANDLED = Result(true)
    private val SKIP = Result(false)

    /** 动作串里 act: 之后可以写的系统动作。 */
    val actNames: List<String> = listOf(
        "back", "home", "recents", "menu",
        "vol_up", "vol_down", "mute",
        "media_play_pause", "media_next", "media_prev",
        "scroll_up", "scroll_down", "paste", "voice", "none",
    )

    /**
     * 执行一个按键绑定的动作。
     *
     * @param action [KeymapStore] 里存的动作串。
     * @param defaultKeyCode 遥控器那个键原本映射的 Android 键码，`act:repeat`/`key:` 解析失败时兜底用。
     */
    fun run(context: Context, action: String?, defaultKeyCode: Int = 0): Result {
        val spec = action?.trim().orEmpty()
        if (spec.isEmpty()) return SKIP

        val head = spec.substringBefore(':').lowercase()
        val body = if (spec.contains(':')) spec.substringAfter(':').trim() else ""

        val ok = when (head) {
            "key" -> sendKeys(body)
            "text" -> typeText(body)
            "app" -> openApp(context, body)
            "url" -> openUrl(context, body)
            "act" -> act(context, body.lowercase())
            "none" -> true
            else -> sendKeys(spec) // 直接写 `CTRL+V` 也认
        }
        return if (ok) HANDLED else SKIP
    }

    /** 是否是需要抢麦克风语音的绑定（由 BridgeService 自己处理）。 */
    fun isVoice(action: String?): Boolean =
        action?.trim()?.lowercase()?.let { it == "act:voice" || it == "voice" } == true

    // ---- 具体动作 ----

    private fun sendKeys(spec: String): Boolean {
        if (spec.isEmpty()) return false
        val holdMs = spec.substringAfterLast('@', "").toLongOrNull() ?: 0L
        val keysPart = if (holdMs > 0L) spec.substringBeforeLast('@') else spec
        val codes = KeyCatalog.codes(keysPart)
        if (codes.isEmpty()) return false
        return when {
            codes.size == 1 && holdMs > 0L -> KeyInjector.longPress(codes[0], holdMs)
            codes.size == 1 -> KeyInjector.tap(codes[0])
            holdMs > 0L -> KeyInjector.combination(*codes.toIntArray())
            else -> KeyInjector.combination(*codes.toIntArray())
        }
    }

    private fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val payload = text.replace("\\n", "\n")
        return KeyInjector.text(payload, replace = false)
    }

    private fun openApp(context: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "启动应用失败：$pkg", it)
            false
        }
    }

    private fun openUrl(context: Context, url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse {
            Log.w(TAG, "打开网址失败：$u", it)
            false
        }
    }

    private fun act(context: Context, name: String): Boolean = when (name) {
        "back" -> KeyInjector.tap(KeyEvent.KEYCODE_BACK) || Injector.globalBack()
        "home" -> KeyInjector.tap(KeyEvent.KEYCODE_HOME) || Injector.globalHome()
        "recents" -> KeyInjector.tap(KeyEvent.KEYCODE_APP_SWITCH) || Injector.globalRecents()
        "menu" -> KeyInjector.tap(KeyEvent.KEYCODE_MENU)
        "vol_up" -> volume(context, AudioManager.ADJUST_RAISE)
        "vol_down" -> volume(context, AudioManager.ADJUST_LOWER)
        "mute" -> volume(context, AudioManager.ADJUST_TOGGLE_MUTE)
        "media_play_pause" -> KeyInjector.tap(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        "media_next" -> KeyInjector.tap(KeyEvent.KEYCODE_MEDIA_NEXT)
        "media_prev" -> KeyInjector.tap(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        "scroll_up" -> Injector.scroll(false)
        "scroll_down" -> Injector.scroll(true)
        "paste" -> KeyInjector.combination(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V)
        // voice 由 BridgeService 处理，这里只当作「已认领」，避免回落成普通按键
        "voice" -> true
        "none" -> true
        else -> false
    }

    private fun volume(context: Context, direction: Int): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        true
    }.getOrDefault(false)

    private const val TAG = "RemoteActionRun"
}
