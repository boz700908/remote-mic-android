package com.boz700908.remotemic.protocol

/**
 * RC003 原始 HID 报告解析。
 *
 * 报告 ID 1 的负载是「2 字节小端 usage 列表」，非零 usage 即为当前按住的按键。
 * 参考实现见 RemoteButtons.swift 的 RemoteHIDReportParser。
 */
object HidReportParser {

    fun usages(payload: ByteArray): List<Int> {
        var bytes = payload
        if (bytes.size == 7 && bytes[0] == 1.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        if (bytes.isEmpty() || bytes.size % 2 != 0) return emptyList()
        val result = ArrayList<Int>(bytes.size / 2)
        var i = 0
        while (i < bytes.size) {
            val usage = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            if (usage != 0) result.add(usage)
            i += 2
        }
        return result
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    /** 键盘报告（boot 协议 8 字节：修饰键 + 保留 + 6 个 usage）里的按键。 */
    fun keyboardUsages(payload: ByteArray): List<Int> {
        if (payload.size < 8) return emptyList()
        return (2 until 8).map { payload[it].toInt() and 0xFF }.filter { it != 0 }
    }
}
