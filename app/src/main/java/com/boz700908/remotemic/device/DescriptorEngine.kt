package com.boz700908.remotemic.device

/**
 * HID 报告解码引擎。
 *
 * 与旧实现（只认 report-id=0x01 的 7 字节报告）相比，这里按描述符逐条匹配报告形态：
 * 长度是硬约束，prefix / prefix-not 区分同长度同 ID 的多种形态，state-group 把同一逻辑
 * 按键状态池里的多条报告合并（任一形态到达都算数），因此同一台遥控器发出的任意一种
 * 报告形态都能被正确识别。
 *
 * 语义要点：
 *  - 一次通知只解释成「一份」报告（strength 最高的那条），同 state-group 的其它报告
 *    只用来决定某个 input 是否参与本次求值；
 *  - 参与求值的 input，其值完全由本次报告决定（usage 不在报告里即为 0，松开能归零）；
 *  - 不参与求值的 input 不出现在结果里，调用方保留其上一次状态。
 */
object DescriptorEngine {

    data class Decoded(
        val values: Map<String, Double>,
        val pressed: List<String>,
        val report: String?,
        val usages: Set<Int>,
        val hex: String,
    )

    /** 报告与这段字节相符吗。长度给了就必须精确相等。 */
    fun matches(r: ReportSpec, b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        // GATT 流是应用层不透明数据，只交给它自己的解码器，永不当作按键报告。
        if (r.encoding == ReportEncoding.GATT_STREAM) return false
        val dir = r.attrs["direction"]
        if (!dir.isNullOrBlank() && dir != "input") return false
        if (r.reportId != null && (b[0].toInt() and 0xFF) != r.reportId) return false
        if (r.length != null && b.size != r.length) return false
        val p = r.prefix
        if (p != null && !startsWith(b, p)) return false
        val pn = r.prefixNot
        if (pn != null && startsWith(b, pn)) return false
        return true
    }

    /** 匹配强度：prefix 最具体，prefix-not 次之，长度精确再次之，report-id 次之。 */
    fun strength(r: ReportSpec, b: ByteArray): Int {
        var s = 0
        if (r.reportId != null) s += 1
        if (r.prefix != null) s += 4
        if (r.prefixNot != null) s += 3
        if (r.length != null && r.length == b.size) s += 2
        if (r.encoding != ReportEncoding.UNKNOWN) s += 1
        return s
    }

    fun pick(d: DeviceDescriptor, b: ByteArray): ReportSpec? =
        d.reports.filter { matches(it, b) }.maxByOrNull { strength(it, b) }

    /** 该报告里带出来的 usage 集合（键盘页或消费页，按报告声明）。 */
    fun usages(r: ReportSpec, b: ByteArray): Set<Int> {
        val out = LinkedHashSet<Int>()
        when (r.encoding) {
            ReportEncoding.BOOT_KEYBOARD -> {
                val count = if (r.slotCount > 0) r.slotCount else 6
                for (i in 0 until count) {
                    val idx = r.payloadOffset + i
                    if (idx < 0 || idx >= b.size) continue
                    val u = b[idx].toInt() and 0xFF
                    if (u != 0) out.add(u)
                }
            }

            ReportEncoding.LE_U16_SLOTS -> {
                val w = if (r.slotWidth > 0) r.slotWidth else 2
                val count = if (r.slotCount > 0) r.slotCount else 3
                for (i in 0 until count) {
                    val u = leU16(b, r.payloadOffset + i * w) ?: continue
                    if (u != 0) out.add(u)
                }
            }

            ReportEncoding.LE_U16_SINGLE -> {
                leU16(b, r.payloadOffset)?.let { if (it != 0) out.add(it) }
            }

            else -> Unit
        }
        return out
    }

    fun decode(d: DeviceDescriptor, b: ByteArray): Decoded {
        val best = pick(d, b)
            ?: return Decoded(emptyMap(), emptyList(), null, emptySet(), hex(b))
        val active = usages(best, b)
        val values = LinkedHashMap<String, Double>()
        for (input in d.inputs) {
            var acc: Double? = null
            for (src in input.sources) {
                val rep = src.report?.let { d.reportByName[it] } ?: continue
                if (rep.encoding == ReportEncoding.GATT_STREAM) continue
                val speaks = rep.name == best.name ||
                    (best.stateGroup != null && rep.stateGroup == best.stateGroup)
                if (!speaks) continue
                val v = valueOf(input, src, rep, b, active)
                if (v != null) acc = if (acc == null) v else maxOf(acc, v)
            }
            if (acc != null) values[input.id] = acc
        }
        val pressed = values.entries
            .filter { pressed(d, it.key, it.value, false) }
            .map { it.key }
        return Decoded(values, pressed, best.name, active, hex(b))
    }

    /**
     * 是否算按下。默认 0.55 按下、0.35 松开（滞回，避免抖动），
     * 描述符可用 threshold / release-threshold 覆盖，语义同 engine.rs 的 pressed()。
     */
    fun pressed(d: DeviceDescriptor, inputId: String, value: Double, wasPressed: Boolean): Boolean {
        var press: Double? = null
        var release: Double? = null
        val input = d.inputs.firstOrNull { it.id == inputId }
        if (input != null) {
            for (src in input.sources) {
                val t = src.double("threshold")
                val r = src.double("release-threshold")
                if (t != null || r != null) { press = t; release = r; break }
            }
        }
        val threshold = (press ?: 0.55).coerceIn(0.0, 1.0)
        val releaseThreshold = (release ?: 0.35).coerceIn(0.0, threshold)
        return value >= if (wasPressed) releaseThreshold else threshold
    }

    private fun valueOf(
        input: InputSpec,
        src: InputSource,
        rep: ReportSpec,
        b: ByteArray,
        active: Set<Int>,
    ): Double? {
        // 厂商状态字段（示例：语音键按下/松开边沿）
        val field = src.field
        if (!field.isNullOrEmpty()) {
            val off = rep.stateOffset ?: return null
            if (off < 0 || off >= b.size) return null
            val raw = b[off].toInt() and 0xFF
            val eq = src.equalsValue
            return if (eq != null) (if (raw == eq) 1.0 else 0.0)
            else raw.toDouble() / rep.stateMax.coerceAtLeast(1).toDouble()
        }
        return when (input.kind) {
            "axis", "trigger" -> axisValue(src, b)
            "hat" -> hatValue(src, b)
            else -> buttonValue(src, rep, b, active)
        }
    }

    private fun buttonValue(
        src: InputSource,
        rep: ReportSpec,
        b: ByteArray,
        active: Set<Int>,
    ): Double? {
        val usage = src.usage
        if (usage != null) {
            val page = src.page
            if (page != null && rep.usagePage != 0 && page != rep.usagePage) return 0.0
            return if (active.contains(usage)) 1.0 else 0.0
        }
        val off = src.offset ?: return null
        val width = if (src.width > 0) src.width else 1
        val bits = readBits(b, off, src.bit, width) ?: run {
            if (off < 0 || off >= b.size) return null
            (b[off].toLong() and 0xFF)
        }
        val eq = src.equalsValue?.toLong()
        return if (eq != null) (if (bits == eq) 1.0 else 0.0) else (if (bits != 0L) 1.0 else 0.0)
    }

    private fun axisValue(src: InputSource, b: ByteArray): Double? {
        val off = src.offset ?: return null
        val raw = leU16(b, off) ?: return null
        val min = src.double("min") ?: 0.0
        val max = src.double("max") ?: 65535.0
        var n = if (max > min) (raw - min) / (max - min) else 0.0
        n = n.coerceIn(0.0, 1.0)
        val dz = src.double("deadzone") ?: 0.0
        return if (n < dz) 0.0 else n
    }

    private fun hatValue(src: InputSource, b: ByteArray): Double? {
        val off = src.offset ?: return null
        if (off < 0 || off >= b.size) return null
        val raw = b[off].toInt() and 0xFF
        if (raw == src.int("neutral", 0)) return 0.0
        val map = src.attrs["direction-map"] ?: return null
        val dir = src.direction ?: return null
        val allowed = directionValues(map, dir) ?: return null
        return if (allowed.contains(raw)) 1.0 else 0.0
    }

    /** "up:8,1,2;right:2,3,4" 取某方向的值列表；不带冒号时整串就是一个方向的值。 */
    fun directionValues(map: String, direction: String): Set<Int>? {
        val text = map.trim()
        if (text.isEmpty()) return null
        val body: String
        if (text.contains(':')) {
            var found: String? = null
            for (part in text.split(';')) {
                val i = part.indexOf(':')
                if (i <= 0) continue
                if (part.substring(0, i).trim() == direction) {
                    found = part.substring(i + 1)
                    break
                }
            }
            if (found == null) return null
            body = found
        } else {
            body = text
        }
        val out = LinkedHashSet<Int>()
        for (tok in body.split(',', '|', ' ')) {
            if (tok.isBlank()) continue
            parseHexInt(tok)?.let { out.add(it) }
        }
        return if (out.isEmpty()) null else out
    }

    fun readBits(b: ByteArray, offset: Int, bit: Int, width: Int): Long? {
        if (width <= 0 || width > 64) return null
        var value = 0L
        for (i in 0 until width) {
            val pos = bit + i
            val idx = offset + pos / 8
            if (idx < 0 || idx >= b.size) return null
            val one = (b[idx].toInt() shr (pos % 8)) and 1
            value = value or (one.toLong() shl i)
        }
        return value
    }

    fun leU16(b: ByteArray, idx: Int): Int? {
        if (idx < 0 || idx + 1 >= b.size) return null
        return (b[idx].toInt() and 0xFF) or ((b[idx + 1].toInt() and 0xFF) shl 8)
    }

    private fun startsWith(b: ByteArray, p: ByteArray): Boolean {
        if (b.size < p.size) return false
        for (i in p.indices) if (b[i] != p[i]) return false
        return true
    }

    fun hex(b: ByteArray): String {
        if (b.isEmpty()) return ""
        val sb = StringBuilder(b.size * 3)
        for (i in b.indices) {
            if (i > 0) sb.append(' ')
            val v = b[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v).uppercase())
        }
        return sb.toString()
    }
}
