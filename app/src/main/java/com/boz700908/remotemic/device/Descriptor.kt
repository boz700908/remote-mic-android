package com.boz700908.remotemic.device

import org.json.JSONObject

/** 报告编码。与描述符 JSON 的 encoding 属性一一对应。 */
enum class ReportEncoding {
    BOOT_KEYBOARD, LE_U16_SLOTS, LE_U16_SINGLE, BIT_FIELD, VENDOR_STATE, GATT_STREAM, UNKNOWN;

    companion object {
        fun of(v: String?): ReportEncoding = when (v?.trim()?.lowercase()) {
            "boot-keyboard" -> BOOT_KEYBOARD
            "le-u16-slots" -> LE_U16_SLOTS
            "le-u16-single" -> LE_U16_SINGLE
            "bit-field" -> BIT_FIELD
            "vendor-state" -> VENDOR_STATE
            "gatt-stream" -> GATT_STREAM
            else -> UNKNOWN
        }
    }
}

internal fun parseHexInt(v: String?): Int? {
    val s = v?.trim().orEmpty()
    if (s.isEmpty()) return null
    return try {
        if (s.startsWith("0x", true)) s.substring(2).toInt(16) else s.toInt()
    } catch (e: NumberFormatException) {
        null
    }
}

internal fun parseHexBytes(v: String?): ByteArray? {
    val s = v?.trim().orEmpty()
    if (s.isEmpty()) return null
    val parts = s.split(' ', ',', '\t').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val out = ByteArray(parts.size)
    for (i in parts.indices) {
        val n = parseHexInt(parts[i]) ?: return null
        out[i] = n.toByte()
    }
    return out
}

internal fun attrMap(o: JSONObject): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val it = o.keys()
    while (it.hasNext()) {
        val k = it.next()
        out[k] = o.optString(k, "")
    }
    return out
}

/** 一条 HID 输入报告的形状（同一设备可有多条，用 state-group 合并成同一按键状态池）。 */
class ReportSpec(
    val name: String,
    val reportId: Int?,
    val length: Int?,
    val encoding: ReportEncoding,
    val usagePage: Int,
    val prefix: ByteArray?,
    val prefixNot: ByteArray?,
    val payloadOffset: Int,
    val slotWidth: Int,
    val slotCount: Int,
    val stateOffset: Int?,
    val stateMax: Int,
    val stateGroup: String?,
    val attrs: Map<String, String>,
) {
    /** 报告开头带 report-id 时，payload 起点通常紧跟其后，但描述符已显式给出 payload-offset，此处不推断。 */
    override fun toString(): String = "ReportSpec($name enc=$encoding id=$reportId len=$length)"

    companion object {
        fun from(o: JSONObject): ReportSpec? {
            val a = o.optJSONObject("attrs") ?: o
            val m = attrMap(a)
            val name = m["id"] ?: return null
            return ReportSpec(
                name = name,
                reportId = parseHexInt(m["report-id"]),
                length = parseHexInt(m["length"]),
                encoding = ReportEncoding.of(m["encoding"]),
                usagePage = parseHexInt(m["usage-page"]) ?: 0,
                prefix = parseHexBytes(m["prefix"]),
                prefixNot = parseHexBytes(m["prefix-not"]),
                payloadOffset = parseHexInt(m["payload-offset"]) ?: 0,
                slotWidth = parseHexInt(m["slot-width"]) ?: 1,
                slotCount = parseHexInt(m["slot-count"]) ?: 0,
                stateOffset = parseHexInt(m["state-offset"]),
                stateMax = parseHexInt(m["state-max"]) ?: 1,
                stateGroup = m["state-group"]?.takeIf { it.isNotBlank() },
                attrs = m,
            )
        }
    }
}

/** 一个按键/轴的一个取值来源：报告 + 页 + usage，或报告 + 位域/偏移。 */
class InputSource(val attrs: Map<String, String>) {
    val report: String? get() = attrs["report"]
    val page: Int? get() = parseHexInt(attrs["page"])
    val usage: Int? get() = parseHexInt(attrs["usage"])
    val offset: Int? get() = parseHexInt(attrs["offset"])
    val bit: Int get() = parseHexInt(attrs["bit"]) ?: 0
    val width: Int get() = parseHexInt(attrs["width"]) ?: 0
    val field: String? get() = attrs["field"]
    val engine: String? get() = attrs["engine"]
    val equalsValue: Int? get() = parseHexInt(attrs["equals"])
    val direction: String? get() = attrs["direction"]

    fun double(key: String): Double? = attrs[key]?.trim()?.toDoubleOrNull()
    fun int(key: String, def: Int = 0): Int = parseHexInt(attrs[key]) ?: def

    override fun toString(): String =
        "Source(rep=$report page=${page?.let { "0x%02X".format(it) }} usage=${usage?.let { "0x%04X".format(it) }} off=$offset)"
}

/** 描述符声明的一个逻辑按键。kind = button / axis / trigger / hat。 */
class InputSpec(
    val id: String,
    val label: String,
    val kind: String,
    val legacyKey: String?,
    val original: Boolean,
    val pending: Boolean,
    val order: Int,
    val icon: String?,
    val target: String?,
    val sources: List<InputSource>,
) {
    val isButton: Boolean get() = kind == "button"

    companion object {
        fun from(o: JSONObject): InputSpec? {
            val a = o.optJSONObject("attrs") ?: o
            val m = attrMap(a)
            val id = m["id"] ?: return null
            val arr = o.optJSONArray("sources")
            val sources = ArrayList<InputSource>()
            if (arr != null && arr.length() > 0) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val sa = s.optJSONObject("attrs") ?: s
                    sources.add(InputSource(attrMap(sa)))
                }
            } else if (m["report"] != null) {
                // input 上内联的单 source 写法
                sources.add(InputSource(m))
            }
            return InputSpec(
                id = id,
                label = m["label"] ?: id,
                kind = (m["kind"] ?: "button").lowercase(),
                legacyKey = m["legacy-key"]?.takeIf { it.isNotBlank() },
                original = m["original"] == "true",
                pending = m["binding"] == "pending",
                order = parseHexInt(m["order"]) ?: 0,
                icon = m["icon"],
                target = m["target"],
                sources = sources,
            )
        }
    }
}

/** 一台遥控器的完整描述符。identities 保留原始属性表，识别逻辑在 DeviceRegistry。 */
class DeviceDescriptor(
    val kind: String,
    val id: String,
    val vendor: String,
    val model: String,
    val displayName: String,
    val identities: List<Map<String, String>>,
    val reports: List<ReportSpec>,
    val inputs: List<InputSpec>,
) {
    val reportByName: Map<String, ReportSpec> = reports.associateBy { it.name }

    fun input(id: String): InputSpec? = inputs.firstOrNull { it.id == id }

    /** 报告里出现过的全部 usage（page, usage），用于通用兜底与调试显示。 */
    val allUsages: List<Pair<Int, Int>>
        get() = inputs.flatMap { i -> i.sources.mapNotNull { s -> val u = s.usage; if (u != null) (s.page ?: 7) to u else null } }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))

    override fun toString(): String = "DeviceDescriptor($id $displayName inputs=${inputs.size})"

    companion object {
        fun fromJson(text: String): DeviceDescriptor? = try {
            val o = JSONObject(text)
            val reports = ArrayList<ReportSpec>()
            val ra = o.optJSONArray("reports")
            if (ra != null) for (i in 0 until ra.length()) {
                ra.optJSONObject(i)?.let { ReportSpec.from(it)?.let(reports::add) }
            }
            val inputs = ArrayList<InputSpec>()
            val ia = o.optJSONArray("inputs")
            if (ia != null) for (i in 0 until ia.length()) {
                ia.optJSONObject(i)?.let { InputSpec.from(it)?.let(inputs::add) }
            }
            val identities = ArrayList<Map<String, String>>()
            val ma = o.optJSONArray("identities")
            if (ma != null) for (i in 0 until ma.length()) {
                ma.optJSONObject(i)?.let { identities.add(attrMap(it)) }
            }
            DeviceDescriptor(
                kind = o.optString("kind"),
                id = o.optString("id"),
                vendor = o.optString("vendor"),
                model = o.optString("model"),
                displayName = o.optString("displayName", o.optString("model")),
                identities = identities,
                reports = reports,
                inputs = inputs,
            )
        } catch (e: Exception) {
            null
        }
    }
}
