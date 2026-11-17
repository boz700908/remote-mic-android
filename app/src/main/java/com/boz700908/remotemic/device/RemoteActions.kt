package com.boz700908.remotemic.device

import android.view.KeyEvent

/** HID usage → Android KeyEvent 键码，以及供 UI/日志用的中文名。 */
object RemoteActions {

    const val PAGE_KEYBOARD = 0x07
    const val PAGE_CONSUMER = 0x0C

    data class Usage(val page: Int, val usage: Int, val label: String)

    /**
     * 常见遥控器键位目录。两张用途：
     *  1) 未知型号走内置兜底描述符时，按这些 usage 生成通用按键；
     *  2) UI 里给「虚拟按键」挑选默认动作。
     */
    val catalog: List<Usage> = listOf(
        Usage(PAGE_KEYBOARD, 0x52, "方向上"),
        Usage(PAGE_KEYBOARD, 0x51, "方向下"),
        Usage(PAGE_KEYBOARD, 0x50, "方向左"),
        Usage(PAGE_KEYBOARD, 0x4F, "方向右"),
        Usage(PAGE_KEYBOARD, 0x28, "确认"),
        Usage(PAGE_KEYBOARD, 0x29, "返回(Esc)"),
        Usage(PAGE_KEYBOARD, 0x66, "电源"),
        Usage(PAGE_KEYBOARD, 0x4A, "主页"),
        Usage(PAGE_KEYBOARD, 0x65, "菜单"),
        Usage(PAGE_KEYBOARD, 0x35, "TV(反引号)"),
        Usage(PAGE_KEYBOARD, 0x80, "音量+"),
        Usage(PAGE_KEYBOARD, 0x81, "音量−"),
        Usage(PAGE_KEYBOARD, 0x7F, "静音"),
        Usage(PAGE_KEYBOARD, 0x4B, "上一页"),
        Usage(PAGE_KEYBOARD, 0x4E, "下一页"),
        Usage(PAGE_KEYBOARD, 0x3E, "F5"),
        Usage(PAGE_KEYBOARD, 0x2C, "空格"),
        Usage(PAGE_KEYBOARD, 0x2B, "Tab"),
        Usage(PAGE_KEYBOARD, 0x2A, "退格"),
        Usage(PAGE_KEYBOARD, 0x4C, "删除"),
        Usage(PAGE_KEYBOARD, 0xE0, "左 Ctrl"),
        Usage(PAGE_KEYBOARD, 0xE1, "左 Shift"),
        Usage(PAGE_KEYBOARD, 0xE2, "左 Alt"),
        Usage(PAGE_KEYBOARD, 0xE3, "左 Win/Cmd"),
        Usage(PAGE_CONSUMER, 0x41, "确认(消费页)"),
        Usage(PAGE_CONSUMER, 0x223, "主页(消费页)"),
        Usage(PAGE_CONSUMER, 0x224, "返回(消费页)"),
        Usage(PAGE_CONSUMER, 0xE9, "音量+(消费页)"),
        Usage(PAGE_CONSUMER, 0xEA, "音量−(消费页)"),
        Usage(PAGE_CONSUMER, 0xE2, "静音(消费页)"),
        Usage(PAGE_CONSUMER, 0xB5, "下一曲"),
        Usage(PAGE_CONSUMER, 0xB6, "上一曲"),
        Usage(PAGE_CONSUMER, 0xCD, "播放/暂停"),
        Usage(PAGE_CONSUMER, 0xB3, "快进"),
        Usage(PAGE_CONSUMER, 0xB4, "快退"),
        Usage(PAGE_CONSUMER, 0xFC, "频道+"),
        Usage(PAGE_CONSUMER, 0xFD, "频道−"),
        Usage(PAGE_CONSUMER, 0x30, "电源(消费页)"),
    )

    private val keyboard: Map<Int, Int> = buildMap {
        // 字母 a..z = 0x04..0x1D
        for (i in 0..25) put(0x04 + i, KeyEvent.KEYCODE_A + i)
        // 数字 1..9 与 0
        for (i in 1..9) put(0x1D + i, KeyEvent.KEYCODE_1 + (i - 1))
        put(0x27, KeyEvent.KEYCODE_0)
        put(0x28, KeyEvent.KEYCODE_ENTER)
        put(0x29, KeyEvent.KEYCODE_ESCAPE)
        put(0x2A, KeyEvent.KEYCODE_DEL)
        put(0x2B, KeyEvent.KEYCODE_TAB)
        put(0x2C, KeyEvent.KEYCODE_SPACE)
        put(0x2D, KeyEvent.KEYCODE_MINUS)
        put(0x2E, KeyEvent.KEYCODE_EQUALS)
        put(0x2F, KeyEvent.KEYCODE_LEFT_BRACKET)
        put(0x30, KeyEvent.KEYCODE_RIGHT_BRACKET)
        put(0x31, KeyEvent.KEYCODE_BACKSLASH)
        put(0x33, KeyEvent.KEYCODE_SEMICOLON)
        put(0x34, KeyEvent.KEYCODE_APOSTROPHE)
        put(0x35, KeyEvent.KEYCODE_GRAVE)
        put(0x36, KeyEvent.KEYCODE_COMMA)
        put(0x37, KeyEvent.KEYCODE_PERIOD)
        put(0x38, KeyEvent.KEYCODE_SLASH)
        put(0x39, KeyEvent.KEYCODE_CAPS_LOCK)
        for (i in 0..11) put(0x3A + i, KeyEvent.KEYCODE_F1 + i)
        put(0x46, KeyEvent.KEYCODE_SYSRQ)
        put(0x47, KeyEvent.KEYCODE_SCROLL_LOCK)
        put(0x48, KeyEvent.KEYCODE_BREAK)
        put(0x49, KeyEvent.KEYCODE_INSERT)
        put(0x4A, KeyEvent.KEYCODE_HOME)
        put(0x4B, KeyEvent.KEYCODE_PAGE_UP)
        put(0x4C, KeyEvent.KEYCODE_FORWARD_DEL)
        put(0x4D, KeyEvent.KEYCODE_MOVE_END)
        put(0x4E, KeyEvent.KEYCODE_PAGE_DOWN)
        put(0x4F, KeyEvent.KEYCODE_DPAD_RIGHT)
        put(0x50, KeyEvent.KEYCODE_DPAD_LEFT)
        put(0x51, KeyEvent.KEYCODE_DPAD_DOWN)
        put(0x52, KeyEvent.KEYCODE_DPAD_UP)
        put(0x53, KeyEvent.KEYCODE_NUM_LOCK)
        put(0x65, KeyEvent.KEYCODE_MENU)
        put(0x66, KeyEvent.KEYCODE_POWER)
        put(0x7F, KeyEvent.KEYCODE_VOLUME_MUTE)
        put(0x80, KeyEvent.KEYCODE_VOLUME_UP)
        put(0x81, KeyEvent.KEYCODE_VOLUME_DOWN)
        put(0xE0, KeyEvent.KEYCODE_CTRL_LEFT)
        put(0xE1, KeyEvent.KEYCODE_SHIFT_LEFT)
        put(0xE2, KeyEvent.KEYCODE_ALT_LEFT)
        put(0xE3, KeyEvent.KEYCODE_META_LEFT)
        put(0xE4, KeyEvent.KEYCODE_CTRL_RIGHT)
        put(0xE5, KeyEvent.KEYCODE_SHIFT_RIGHT)
        put(0xE6, KeyEvent.KEYCODE_ALT_RIGHT)
        put(0xE7, KeyEvent.KEYCODE_META_RIGHT)
    }

    private val consumer: Map<Int, Int> = mapOf(
        0x30 to KeyEvent.KEYCODE_POWER,
        0x41 to KeyEvent.KEYCODE_ENTER,
        0x223 to KeyEvent.KEYCODE_HOME,
        0x224 to KeyEvent.KEYCODE_BACK,
        0x225 to KeyEvent.KEYCODE_FORWARD,
        0x226 to KeyEvent.KEYCODE_REFRESH,
        0xB0 to KeyEvent.KEYCODE_MEDIA_PLAY,
        0xB1 to KeyEvent.KEYCODE_MEDIA_PAUSE,
        0xB2 to KeyEvent.KEYCODE_MEDIA_RECORD,
        0xB3 to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        0xB4 to KeyEvent.KEYCODE_MEDIA_REWIND,
        0xB5 to KeyEvent.KEYCODE_MEDIA_NEXT,
        0xB6 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        0xB7 to KeyEvent.KEYCODE_MEDIA_STOP,
        0xCD to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        0xE2 to KeyEvent.KEYCODE_VOLUME_MUTE,
        0xE9 to KeyEvent.KEYCODE_VOLUME_UP,
        0xEA to KeyEvent.KEYCODE_VOLUME_DOWN,
        0xEB to KeyEvent.KEYCODE_GUIDE,
        0xFC to KeyEvent.KEYCODE_CHANNEL_UP,
        0xFD to KeyEvent.KEYCODE_CHANNEL_DOWN,
        0x6F to KeyEvent.KEYCODE_BRIGHTNESS_UP,
        0x70 to KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
    )

    /** 没有直接对应时返回 0。 */
    fun keyCodeFor(page: Int, usage: Int): Int = when (page) {
        PAGE_KEYBOARD -> keyboard[usage] ?: 0
        PAGE_CONSUMER -> consumer[usage] ?: 0
        else -> 0
    }

    fun labelFor(page: Int, usage: Int): String =
        catalog.firstOrNull { it.page == page && it.usage == usage }?.label
            ?: "0x%02X/0x%04X".format(page, usage)

    /** 反查（用于配置界面的「当前是这个键」提示）。 */
    fun usageFor(page: Int, keyCode: Int): Int? = when (page) {
        PAGE_KEYBOARD -> keyboard.entries.firstOrNull { it.value == keyCode }?.key
        PAGE_CONSUMER -> consumer.entries.firstOrNull { it.value == keyCode }?.key
        else -> null
    }
}
