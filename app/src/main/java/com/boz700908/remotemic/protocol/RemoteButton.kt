package com.boz700908.remotemic.protocol

/**
 * 小米 RC001 / RC003 实体按键，HID usage 与参考实现完全一致。
 * usage 空间为该遥控器厂商集合内上报的键盘页 usage。
 */
enum class RemoteButton(val usage: Int, val label: String) {
    POWER(0x66, "电源"),
    UP(0x52, "上"),
    LEFT(0x50, "左"),
    OK(0x28, "OK"),
    RIGHT(0x4F, "右"),
    DOWN(0x51, "下"),
    BACK(0xF1, "返回"),
    VOLUME_UP(0x80, "音量+"),
    HOME(0x4A, "主页"),
    VOLUME_DOWN(0x81, "音量−"),
    MENU(0x65, "菜单"),
    TV(0x35, "TV");

    /** 语音键在遥控器的键盘接口上是 F5（HID usage 0x3E）。 */
    companion object {
        const val VOICE_KEY_F5 = 0x3E

        private val byUsage = entries.associateBy { it.usage }

        fun from(usage: Int): RemoteButton? = byUsage[usage]
    }
}
