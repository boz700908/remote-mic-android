package com.boz700908.remotemic.inject

import android.view.KeyEvent

/**
 * 动作串里可以写名字的键，如 `key:CTRL+SHIFT+V`、`key:DPAD_UP@800`。
 *
 * 名字不分大小写，`-` 与 `_` 等价（`page-up` == `PAGE_UP`）。任何一个名字写错，
 * [codes] 就返回空表，调用方据此回落到默认转发——宁可不发，也不发半个组合键。
 *
 * 只收录 Android 公开的 `KeyEvent` 常量，避免编译期踩到 @hide。
 */
object KeyCatalog {

    val keys: Map<String, Int> = buildMap {
        for (i in 0..25) put(('A' + i).toString(), KeyEvent.KEYCODE_A + i)
        for (i in 0..9) put(i.toString(), KeyEvent.KEYCODE_0 + i)
        for (i in 0..11) put("F${i + 1}", KeyEvent.KEYCODE_F1 + i)

        // 确认 / 退出 / 导航
        put("ENTER", KeyEvent.KEYCODE_ENTER)
        put("OK", KeyEvent.KEYCODE_ENTER)
        put("RETURN", KeyEvent.KEYCODE_ENTER)
        put("DPAD_CENTER", KeyEvent.KEYCODE_DPAD_CENTER)
        put("ESC", KeyEvent.KEYCODE_ESCAPE)
        put("ESCAPE", KeyEvent.KEYCODE_ESCAPE)
        put("BACK", KeyEvent.KEYCODE_BACK)
        put("HOME", KeyEvent.KEYCODE_HOME)
        put("MENU", KeyEvent.KEYCODE_MENU)
        put("APP_SWITCH", KeyEvent.KEYCODE_APP_SWITCH)
        put("RECENTS", KeyEvent.KEYCODE_APP_SWITCH)
        put("TAB", KeyEvent.KEYCODE_TAB)
        put("SPACE", KeyEvent.KEYCODE_SPACE)
        put("UP", KeyEvent.KEYCODE_DPAD_UP)
        put("DPAD_UP", KeyEvent.KEYCODE_DPAD_UP)
        put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
        put("DPAD_DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
        put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
        put("DPAD_LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
        put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)
        put("DPAD_RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)

        // 编辑
        put("DEL", KeyEvent.KEYCODE_DEL)
        put("BACKSPACE", KeyEvent.KEYCODE_DEL)
        put("FORWARD_DEL", KeyEvent.KEYCODE_FORWARD_DEL)
        put("DELETE", KeyEvent.KEYCODE_FORWARD_DEL)
        put("INSERT", KeyEvent.KEYCODE_INSERT)
        put("CLEAR", KeyEvent.KEYCODE_CLEAR)
        put("MOVE_HOME", KeyEvent.KEYCODE_MOVE_HOME)
        put("MOVE_END", KeyEvent.KEYCODE_MOVE_END)
        put("END", KeyEvent.KEYCODE_MOVE_END)
        put("PAGE_UP", KeyEvent.KEYCODE_PAGE_UP)
        put("PGUP", KeyEvent.KEYCODE_PAGE_UP)
        put("PAGE_DOWN", KeyEvent.KEYCODE_PAGE_DOWN)
        put("PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
        put("CAPS_LOCK", KeyEvent.KEYCODE_CAPS_LOCK)
        put("NUM_LOCK", KeyEvent.KEYCODE_NUM_LOCK)
        put("SCROLL_LOCK", KeyEvent.KEYCODE_SCROLL_LOCK)
        put("SYSRQ", KeyEvent.KEYCODE_SYSRQ)
        put("BREAK", KeyEvent.KEYCODE_BREAK)

        // 系统
        put("POWER", KeyEvent.KEYCODE_POWER)
        put("SLEEP", KeyEvent.KEYCODE_SLEEP)
        put("WAKEUP", KeyEvent.KEYCODE_WAKEUP)
        put("CAMERA", KeyEvent.KEYCODE_CAMERA)
        put("SEARCH", KeyEvent.KEYCODE_SEARCH)
        put("SETTINGS", KeyEvent.KEYCODE_SETTINGS)
        put("REFRESH", KeyEvent.KEYCODE_REFRESH)
        put("FORWARD", KeyEvent.KEYCODE_FORWARD)
        put("ZOOM_IN", KeyEvent.KEYCODE_ZOOM_IN)
        put("ZOOM_OUT", KeyEvent.KEYCODE_ZOOM_OUT)
        put("HEADSETHOOK", KeyEvent.KEYCODE_HEADSETHOOK)
        put("BOOKMARK", KeyEvent.KEYCODE_BOOKMARK)
        put("CALCULATOR", KeyEvent.KEYCODE_CALCULATOR)
        put("CONTACTS", KeyEvent.KEYCODE_CONTACTS)
        put("MUSIC", KeyEvent.KEYCODE_MUSIC)
        put("GUIDE", KeyEvent.KEYCODE_GUIDE)
        put("TV", KeyEvent.KEYCODE_TV)

        // 音量与媒体
        put("VOLUME_UP", KeyEvent.KEYCODE_VOLUME_UP)
        put("VOL_UP", KeyEvent.KEYCODE_VOLUME_UP)
        put("VOLUME_DOWN", KeyEvent.KEYCODE_VOLUME_DOWN)
        put("VOL_DOWN", KeyEvent.KEYCODE_VOLUME_DOWN)
        put("VOLUME_MUTE", KeyEvent.KEYCODE_VOLUME_MUTE)
        put("MUTE", KeyEvent.KEYCODE_VOLUME_MUTE)
        put("MEDIA_PLAY", KeyEvent.KEYCODE_MEDIA_PLAY)
        put("MEDIA_PAUSE", KeyEvent.KEYCODE_MEDIA_PAUSE)
        put("MEDIA_PLAY_PAUSE", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        put("PLAY_PAUSE", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        put("MEDIA_STOP", KeyEvent.KEYCODE_MEDIA_STOP)
        put("MEDIA_NEXT", KeyEvent.KEYCODE_MEDIA_NEXT)
        put("NEXT", KeyEvent.KEYCODE_MEDIA_NEXT)
        put("MEDIA_PREVIOUS", KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        put("PREV", KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        put("MEDIA_FAST_FORWARD", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        put("MEDIA_REWIND", KeyEvent.KEYCODE_MEDIA_REWIND)
        put("CHANNEL_UP", KeyEvent.KEYCODE_CHANNEL_UP)
        put("CHANNEL_DOWN", KeyEvent.KEYCODE_CHANNEL_DOWN)
        put("BRIGHTNESS_UP", KeyEvent.KEYCODE_BRIGHTNESS_UP)
        put("BRIGHTNESS_DOWN", KeyEvent.KEYCODE_BRIGHTNESS_DOWN)

        // 修饰键
        put("CTRL", KeyEvent.KEYCODE_CTRL_LEFT)
        put("CONTROL", KeyEvent.KEYCODE_CTRL_LEFT)
        put("CTRL_LEFT", KeyEvent.KEYCODE_CTRL_LEFT)
        put("CTRL_RIGHT", KeyEvent.KEYCODE_CTRL_RIGHT)
        put("RCTRL", KeyEvent.KEYCODE_CTRL_RIGHT)
        put("SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT)
        put("SHIFT_LEFT", KeyEvent.KEYCODE_SHIFT_LEFT)
        put("SHIFT_RIGHT", KeyEvent.KEYCODE_SHIFT_RIGHT)
        put("RSHIFT", KeyEvent.KEYCODE_SHIFT_RIGHT)
        put("ALT", KeyEvent.KEYCODE_ALT_LEFT)
        put("ALT_LEFT", KeyEvent.KEYCODE_ALT_LEFT)
        put("ALT_RIGHT", KeyEvent.KEYCODE_ALT_RIGHT)
        put("RALT", KeyEvent.KEYCODE_ALT_RIGHT)
        put("META", KeyEvent.KEYCODE_META_LEFT)
        put("WIN", KeyEvent.KEYCODE_META_LEFT)
        put("SUPER", KeyEvent.KEYCODE_META_LEFT)
        put("CMD", KeyEvent.KEYCODE_META_LEFT)
        put("META_LEFT", KeyEvent.KEYCODE_META_LEFT)
        put("META_RIGHT", KeyEvent.KEYCODE_META_RIGHT)
        put("RMETA", KeyEvent.KEYCODE_META_RIGHT)

        // 标点
        put("GRAVE", KeyEvent.KEYCODE_GRAVE)
        put("BACKTICK", KeyEvent.KEYCODE_GRAVE)
        put("MINUS", KeyEvent.KEYCODE_MINUS)
        put("EQUALS", KeyEvent.KEYCODE_EQUALS)
        put("LEFT_BRACKET", KeyEvent.KEYCODE_LEFT_BRACKET)
        put("RIGHT_BRACKET", KeyEvent.KEYCODE_RIGHT_BRACKET)
        put("BACKSLASH", KeyEvent.KEYCODE_BACKSLASH)
        put("SEMICOLON", KeyEvent.KEYCODE_SEMICOLON)
        put("APOSTROPHE", KeyEvent.KEYCODE_APOSTROPHE)
        put("COMMA", KeyEvent.KEYCODE_COMMA)
        put("PERIOD", KeyEvent.KEYCODE_PERIOD)
        put("SLASH", KeyEvent.KEYCODE_SLASH)
    }

    /** `CTRL+SHIFT+V` → `[CTRL_LEFT, SHIFT_LEFT, V]`；任一名字不认识就返回空表。 */
    fun codes(spec: String): List<Int> {
        val parts = spec.split('+', ' ', ',')
            .map(::normalize)
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val out = ArrayList<Int>(parts.size)
        for (p in parts) out.add(keys[p] ?: return emptyList())
        return out
    }

    /** 反查，用于界面显示（优先短名字）。 */
    fun name(code: Int): String? = keys.entries.firstOrNull { it.value == code }?.key

    private fun normalize(raw: String): String = raw.trim().uppercase().replace('-', '_')
}
