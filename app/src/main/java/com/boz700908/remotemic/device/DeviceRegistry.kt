package com.boz700908.remotemic.device

import android.content.Context
import android.util.Log

/**
 * 描述符仓库与设备识别。
 *
 * Android 侧拿不到 USB/蓝牙 HID 的 VID/PID（没有 HID 主机 API），所以识别只能靠
 * 设备名与 GATT MODEL_NUMBER(0x2A24) 文本。策略是把描述符里的所有文本线索
 * （model / display-name / model-contains / name-contains / product / software-contains）
 * 归一化后去匹配「设备名 + 型号」串，取最长命中线索的机型。
 *
 * 一个都没命中但对方确实带 HID 服务时，回落到内置的通用描述符，保证按键不丢。
 */
object DeviceRegistry {

    private const val TAG = "DeviceRegistry"
    private const val DIR = "devices"

    @Volatile
    private var cache: List<DeviceDescriptor>? = null

    @Volatile
    private var genericCache: DeviceDescriptor? = null

    fun descriptors(context: Context): List<DeviceDescriptor> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val out = ArrayList<DeviceDescriptor>()
            try {
                val names = context.assets.list(DIR)?.sorted().orEmpty()
                for (n in names) {
                    if (!n.endsWith(".json", true)) continue
                    val text = context.assets.open("$DIR/$n").bufferedReader().use { it.readText() }
                    val d = DeviceDescriptor.fromJson(text)
                    if (d == null) {
                        Log.w(TAG, "描述符解析失败: $n")
                    } else {
                        out.add(d)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载描述符失败", e)
            }
            Log.i(TAG, "已加载 ${out.size} 个设备描述符")
            cache = out
            return out
        }
    }

    fun byId(context: Context, id: String): DeviceDescriptor? =
        descriptors(context).firstOrNull { it.id == id }

    /** 名称线索：只保留长度 >= 3 的片段，避免 "RC" 这类短串误判。 */
    private fun hints(d: DeviceDescriptor): List<String> {
        val raw = ArrayList<String?>()
        raw.add(d.model)
        raw.add(d.id.substringAfterLast('.'))
        for (id in d.identities) {
            raw.add(id["model-contains"])
            raw.add(id["name-contains"])
            raw.add(id["product"])
            raw.add(id["manufacturer-contains"])
            id["software-contains"]?.split('|')?.forEach { raw.add(it) }
        }
        return raw.mapNotNull { it?.trim() }
            .filter { norm(it).length >= 3 }
            .distinct()
    }

    private fun norm(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

    /**
     * @param hasHidService 这台设备是否暴露了 HID over GATT 服务（0x1812）
     */
    fun identify(
        context: Context,
        deviceName: String?,
        modelNumber: String?,
        hasHidService: Boolean,
    ): DeviceDescriptor? {
        val hay = norm(listOfNotNull(deviceName, modelNumber).joinToString("|"))
        var best: DeviceDescriptor? = null
        var bestScore = 0
        if (hay.isNotEmpty()) {
            for (d in descriptors(context)) {
                // 有线 HID 设备在 Android 蓝牙场景下不会出现
                if (d.identities.any { it["transport"] == "usb-hid" }) continue
                var score = 0
                for (h in hints(d)) {
                    val n = norm(h)
                    if (n.length >= 3 && hay.contains(n)) score = maxOf(score, n.length)
                }
                if (score > bestScore) {
                    bestScore = score
                    best = d
                }
            }
        }
        if (best != null) {
            Log.i(TAG, "识别为 ${best.id} (线索 ${bestScore} 字符)")
            return best
        }
        if (hasHidService) {
            Log.i(TAG, "未匹配到型号，使用通用描述符")
            return generic()
        }
        return null
    }

    /** 内置兜底描述符：按常见 usage 生成，报告形态取主流三种 + 消费页。 */
    fun generic(): DeviceDescriptor {
        genericCache?.let { return it }
        val kbPage = RemoteActions.PAGE_KEYBOARD.toHex()
        val csPage = RemoteActions.PAGE_CONSUMER.toHex()
        val reports = listOf(
            ReportSpec(
                name = "keyboard",
                reportId = 0x01, length = 7, encoding = ReportEncoding.LE_U16_SLOTS,
                usagePage = RemoteActions.PAGE_KEYBOARD, prefix = null, prefixNot = null,
                payloadOffset = 1, slotWidth = 2, slotCount = 3,
                stateOffset = null, stateMax = 1, stateGroup = "keyboard", attrs = emptyMap(),
            ),
            ReportSpec(
                name = "keyboard-no-id",
                reportId = null, length = 6, encoding = ReportEncoding.LE_U16_SLOTS,
                usagePage = RemoteActions.PAGE_KEYBOARD, prefix = null, prefixNot = null,
                payloadOffset = 0, slotWidth = 2, slotCount = 3,
                stateOffset = null, stateMax = 1, stateGroup = "keyboard", attrs = emptyMap(),
            ),
            ReportSpec(
                name = "keyboard-boot",
                reportId = null, length = 8, encoding = ReportEncoding.BOOT_KEYBOARD,
                usagePage = RemoteActions.PAGE_KEYBOARD, prefix = null, prefixNot = null,
                payloadOffset = 2, slotWidth = 1, slotCount = 6,
                stateOffset = null, stateMax = 1, stateGroup = "keyboard", attrs = emptyMap(),
            ),
            ReportSpec(
                name = "consumer",
                reportId = 0x03, length = 3, encoding = ReportEncoding.LE_U16_SINGLE,
                usagePage = RemoteActions.PAGE_CONSUMER, prefix = null, prefixNot = null,
                payloadOffset = 1, slotWidth = 2, slotCount = 1,
                stateOffset = null, stateMax = 1, stateGroup = null, attrs = emptyMap(),
            ),
        )
        val inputs = RemoteActions.catalog.mapIndexed { idx, u ->
            val sources = if (u.page == RemoteActions.PAGE_KEYBOARD) {
                listOf("keyboard", "keyboard-no-id", "keyboard-boot").map { rep ->
                    InputSource(
                        mapOf(
                            "report" to rep,
                            "page" to kbPage,
                            "usage" to "0x%04X".format(u.usage),
                        )
                    )
                }
            } else {
                listOf(
                    InputSource(
                        mapOf(
                            "report" to "consumer",
                            "page" to csPage,
                            "usage" to "0x%04X".format(u.usage),
                        )
                    )
                )
            }
            InputSpec(
                id = "generic.%02X.%04X".format(u.page, u.usage),
                label = u.label,
                kind = "button",
                legacyKey = null,
                original = false,
                pending = false,
                order = idx,
                icon = null,
                target = null,
                sources = sources,
            )
        }
        val d = DeviceDescriptor(
            kind = "remote",
            id = "generic",
            vendor = "generic",
            model = "Generic HID Remote",
            displayName = "通用 HID 遥控器（内置兜底）",
            identities = emptyList(),
            reports = reports,
            inputs = inputs,
        )
        genericCache = d
        return d
    }

    private fun Int.toHex(): String = "0x%02X".format(this)
}
