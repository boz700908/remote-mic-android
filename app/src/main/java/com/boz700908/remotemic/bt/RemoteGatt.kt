package com.boz700908.remotemic.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.boz700908.remotemic.device.DescriptorEngine
import com.boz700908.remotemic.device.DeviceDescriptor
import com.boz700908.remotemic.device.DeviceRegistry
import com.boz700908.remotemic.protocol.AtvvCapabilities
import com.boz700908.remotemic.protocol.AtvvProtocol
import com.boz700908.remotemic.protocol.FrameAccumulator
import com.boz700908.remotemic.protocol.HidReportParser
import com.boz700908.remotemic.protocol.ImaAdpcmDecoder
import com.boz700908.remotemic.protocol.PcmPostprocessor
import java.util.UUID

/**
 * 小米蓝牙遥控器连接：一条 GATT 连接同时承载 ATVV 语音与 HID 按键。
 *
 * 流程：扫描 → 连接 → 发现服务 → 订阅 0003/0004（语音）与 HID Report →
 * 向 0002 写能力协商 → 收到 0x0B → READY → 遥控器按键触发 0x04 开流 → 音频分片解码成 PCM。
 */
@SuppressLint("MissingPermission")
class RemoteGatt(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(status: Status, detail: String)
        fun onDeviceFound(name: String, address: String, rssi: Int)
        fun onCapabilities(caps: AtvvCapabilities)
        fun onModelNumber(model: String)
        fun onBattery(level: Int?)
        fun onVoiceStart(sessionId: Int)
        fun onVoiceStop(timeout: Boolean)
        fun onPcm(samples: ShortArray)
        fun onHidUsages(usages: List<Int>)

        /** 识别出的机型描述符（机型名与按键表都来自它）。识别不出时不回调。 */
        fun onDescriptor(descriptor: DeviceDescriptor) {}

        /** 按描述符解出的按键状态；pressed 是当前按住的输入 id 列表。 */
        fun onHidDecoded(decoded: DescriptorEngine.Decoded) {}
    }

    enum class Status { IDLE, SCANNING, CONNECTING, DISCOVERING, READY, DISCONNECTED, ERROR }

    private val handler = Handler(Looper.getMainLooper())
    private val decoder = ImaAdpcmDecoder()
    private val accumulator = FrameAccumulator()

    private var manager: BluetoothManager? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var autoConnect = true

    private var transmit: BluetoothGattCharacteristic? = null
    private var subscribedAudio = false
    private var subscribedControl = false
    private var capsRequested = false

    private var descriptor: DeviceDescriptor? = null
    private var modelNumber = ""
    private var hasHidService = false

    private var caps: AtvvCapabilities? = null
    private var streaming = false
    private var sessionId = 0
    private var lastStopAt = 0L
    private var pendingSync: Pair<Int, Int>? = null

    val isReady: Boolean get() = caps != null
    val isStreaming: Boolean get() = streaming

    fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun adapter(): BluetoothAdapter? {
        if (manager == null) {
            manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        }
        return manager?.adapter
    }

    // ---------------- 扫描 ----------------

    fun startScan(autoConnectFirst: Boolean = true) {
        if (!hasPermissions()) {
            listener.onStatus(Status.ERROR, "缺少蓝牙权限")
            return
        }
        val adapter = adapter() ?: run {
            listener.onStatus(Status.ERROR, "本机不支持蓝牙")
            return
        }
        if (!adapter.isEnabled) {
            listener.onStatus(Status.ERROR, "请先打开蓝牙")
            return
        }
        stopScan()
        autoConnect = autoConnectFirst
        val scanner = adapter.bluetoothLeScanner ?: run {
            listener.onStatus(Status.ERROR, "蓝牙扫描器不可用")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        scanning = true
        listener.onStatus(Status.SCANNING, "正在搜索遥控器…")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = runCatching { device.name }.getOrNull() ?: result.scanRecord?.deviceName
            val advService = result.scanRecord?.serviceUuids
                ?.any { it.uuid == AtvvProtocol.SERVICE } == true
            val knownName = name?.let { isKnownName(it) } == true
            if (name != null) listener.onDeviceFound(name, device.address, result.rssi)
            if (autoConnect && (advService || knownName)) {
                autoConnect = false
                stopScan()
                connect(device.address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onStatus(Status.ERROR, "扫描失败（错误码 $errorCode）")
        }
    }

    private fun isKnownName(name: String): Boolean {
        val n = name.uppercase()
        return n.contains("MI RC") || name.contains("语音遥控器") || name.contains("蓝牙遥控器") ||
            n.contains("RC003") || n.contains("RC001") || n.contains("MI TV")
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ---------------- 连接 ----------------

    fun connect(address: String) {
        if (!hasPermissions()) {
            listener.onStatus(Status.ERROR, "缺少蓝牙权限")
            return
        }
        val adapter = adapter() ?: run {
            listener.onStatus(Status.ERROR, "本机不支持蓝牙")
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: run {
            listener.onStatus(Status.ERROR, "地址无效：$address")
            return
        }
        disconnect()
        val shown = runCatching { device.name }.getOrNull() ?: address
        listener.onStatus(Status.CONNECTING, "正在连接 $shown")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onStatus(Status.DISCOVERING, "已连接，正在发现服务…")
                handler.postDelayed({ runCatching { g.discoverServices() } }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                resetSession()
                listener.onStatus(Status.DISCONNECTED, "连接已断开")
                runCatching { g.close() }
                if (gatt === g) gatt = null
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus(Status.ERROR, "连接错误 status=$status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus(Status.ERROR, "服务发现失败 status=$status")
                return
            }
            // 先把 HID 通道接好：没有语音服务的机型也要能转发按键。
            hasHidService = g.getService(HID_SERVICE) != null
            g.getService(HID_SERVICE)?.characteristics?.forEach { ch ->
                if (ch.uuid == HID_REPORT && supportsNotify(ch)) enableNotify(g, ch)
            }
            g.getService(DEVICE_INFO_SERVICE)?.getCharacteristic(MODEL_NUMBER)?.let { ch ->
                runCatching { g.readCharacteristic(ch) }
            }
            identify(g)

            val atvv = g.getService(AtvvProtocol.SERVICE) ?: run {
                listener.onStatus(Status.ERROR, "这台设备没有 ATVV 语音服务（按键转发不受影响）")
                return
            }
            val tx = atvv.getCharacteristic(AtvvProtocol.TRANSMIT)
            val audio = atvv.getCharacteristic(AtvvProtocol.AUDIO)
            val control = atvv.getCharacteristic(AtvvProtocol.CONTROL)
            if (tx == null || audio == null || control == null) {
                listener.onStatus(Status.ERROR, "ATVV 特征不完整")
                return
            }
            transmit = tx
            enableNotify(g, audio)
            enableNotify(g, control)

            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { ch ->
                runCatching { g.readCharacteristic(ch) }
                if (supportsNotify(ch)) enableNotify(g, ch)
            }
            runCatching { g.requestMtu(517) }
            handler.postDelayed({ requestCapabilitiesIfReady() }, 1200)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            requestCapabilitiesIfReady()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val ch = descriptor.characteristic ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "订阅失败 ${ch.uuid} status=$status")
            }
            when (ch.uuid) {
                AtvvProtocol.AUDIO -> subscribedAudio = true
                AtvvProtocol.CONTROL -> subscribedControl = true
            }
            requestCapabilitiesIfReady()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(ch.uuid, value, status)
        }

        @Deprecated("API < 33 回调")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val value = runCatching { ch.value }.getOrNull() ?: return
            handleRead(ch.uuid, value, status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(ch.uuid, value)
        }

        @Deprecated("API < 33 回调")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val value = runCatching { ch.value }.getOrNull() ?: return
            handleNotify(ch.uuid, value)
        }
    }

    private fun handleRead(uuid: UUID, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        when (uuid) {
            BATTERY_LEVEL -> listener.onBattery(value.firstOrNull()?.toInt()?.and(0xFF))
            MODEL_NUMBER -> {
                val model = runCatching { String(value, Charsets.UTF_8).trim() }.getOrDefault("")
                if (model.isNotEmpty()) {
                    modelNumber = model
                    if (model.uppercase().contains("ARN9")) decoder.lowNibbleFirst = true
                    listener.onModelNumber(model)
                    gatt?.let { identify(it) }
                }
            }
        }
    }

    /** 用「设备名 + 型号」识别机型；型号通常晚于服务发现才读到，所以会调两次。 */
    private fun identify(g: BluetoothGatt) {
        val name = runCatching { g.device.name }.getOrNull()
        val d = DeviceRegistry.identify(context, name, modelNumber, hasHidService) ?: return
        if (d.id == descriptor?.id) return
        descriptor = d
        listener.onDescriptor(d)
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            AtvvProtocol.CONTROL -> handleControl(value)
            AtvvProtocol.AUDIO -> handleAudio(value)
            HID_REPORT -> {
                val d = descriptor
                val decoded = if (d != null) DescriptorEngine.decode(d, value) else null
                if (decoded != null && decoded.report != null) {
                    listener.onHidDecoded(decoded)
                } else {
                    // 描述符没覆盖这种形态：退回通用 usage 解析
                    val usages = HidReportParser.usages(value)
                    if (usages.isNotEmpty()) listener.onHidUsages(usages)
                }
            }
        }
    }

    // ---------------- 控制消息 ----------------

    private fun handleControl(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt() and 0xFF) {
            0x0B -> {
                val parsed = AtvvCapabilities.parse(data)
                if (parsed == null) {
                    listener.onStatus(Status.ERROR, "能力响应无效")
                    return
                }
                caps = parsed
                listener.onCapabilities(parsed)
                if (!AtvvProtocol.supportsAudio(parsed.sampleRate)) {
                    listener.onStatus(Status.ERROR, "遥控器未提供 16 kHz 语音编码")
                    return
                }
                listener.onStatus(
                    Status.READY,
                    "已就绪（codec=${parsed.selectedCodec} 帧=${parsed.frameSize} 字节）",
                )
            }

            0x08 -> {
                val c = caps ?: return
                write(AtvvProtocol.microphoneOpen(c.version, c.selectedCodec))
            }

            0x04 -> {
                val c = caps ?: return
                val codec = if (data.size >= 3) data[2].toInt() and 0xFF else c.selectedCodec
                if (codec != 0x02) {
                    listener.onStatus(Status.ERROR, "遥控器切到了 8 kHz 编码，不支持")
                    return
                }
                sessionId = if (data.size >= 4) data[3].toInt() and 0xFF else 0
                startStreaming()
            }

            0x00 -> {
                val timeout = data.size >= 2 && (data[1].toInt() and 0xFF) == 0x08
                stopStreaming(timeout)
            }

            0x0A -> {
                if (data.size < 7) return
                val predictor = (((data[4].toInt() and 0xFF) shl 8) or
                    (data[5].toInt() and 0xFF)).toShort().toInt()
                pendingSync = predictor to (data[6].toInt() and 0xFF)
                accumulator.reset()
            }
        }
    }

    private fun startStreaming() {
        accumulator.reset()
        decoder.reset()
        pendingSync = null
        lastStopAt = 0L
        if (!streaming) {
            streaming = true
            listener.onVoiceStart(sessionId)
        }
    }

    private fun stopStreaming(timeout: Boolean) {
        if (!streaming) return
        streaming = false
        accumulator.reset()
        pendingSync = null
        lastStopAt = System.currentTimeMillis()
        listener.onVoiceStop(timeout)
    }

    // ---------------- 音频 ----------------

    private fun handleAudio(data: ByteArray) {
        val c = caps ?: return
        if (!streaming) {
            val now = System.currentTimeMillis()
            if (lastStopAt != 0L && now - lastStopAt < 300) return
            startStreaming()
        }
        for (frame in accumulator.append(data, c.frameSize)) {
            pendingSync?.let { (pred, idx) ->
                decoder.reset(pred, idx)
                pendingSync = null
            }
            listener.onPcm(PcmPostprocessor.process(decoder.decode(frame), 0.0))
        }
    }

    // ---------------- 写入 ----------------

    private fun requestCapabilitiesIfReady() {
        if (capsRequested) return
        if (!subscribedAudio || !subscribedControl) return
        capsRequested = true
        val ok = write(AtvvProtocol.GET_CAPABILITIES_V10)
        if (!ok) listener.onStatus(Status.ERROR, "能力协商写入失败")
    }

    fun openMicrophone(): Boolean {
        val c = caps ?: return false
        return write(AtvvProtocol.microphoneOpen(c.version, c.selectedCodec))
    }

    fun closeMicrophone(): Boolean {
        val c = caps ?: return false
        return write(AtvvProtocol.microphoneClose(c.version, sessionId))
    }

    private fun write(payload: ByteArray): Boolean {
        val g = gatt ?: return false
        val tx = transmit ?: return false
        val type =
            if (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ 返回 BluetoothStatusCodes 状态码，不是 Boolean
                g.writeCharacteristic(tx, payload, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                tx.writeType = type
                @Suppress("DEPRECATION")
                tx.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(tx)
            }
        }.getOrElse { false }
    }

    // ---------------- 工具 ----------------

    private fun supportsNotify(ch: BluetoothGattCharacteristic): Boolean =
        ch.properties and (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        runCatching {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    private fun resetSession() {
        caps = null
        streaming = false
        capsRequested = false
        subscribedAudio = false
        subscribedControl = false
        transmit = null
        descriptor = null
        hasHidService = false
        accumulator.reset()
        decoder.reset()
        pendingSync = null
        sessionId = 0
    }

    fun disconnect() {
        stopScan()
        resetSession()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        private const val TAG = "RemoteGatt"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val HID_SERVICE: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        val HID_REPORT: UUID = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
    }
}
