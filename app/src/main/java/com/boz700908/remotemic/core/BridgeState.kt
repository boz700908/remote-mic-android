package com.boz700908.remotemic.core

import com.boz700908.remotemic.protocol.AtvvCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

data class FoundDevice(val name: String, val address: String, val rssi: Int)

data class Transcript(val text: String, val at: Long, val durationMs: Long)

/** 服务与界面共享的状态（单进程内存态，够用）。 */
object BridgeState {
    val status = MutableStateFlow("未连接")
    val connected = MutableStateFlow(false)
    val deviceName = MutableStateFlow<String?>(null)
    val deviceAddress = MutableStateFlow<String?>(null)
    val battery = MutableStateFlow<Int?>(null)
    val modelNumber = MutableStateFlow<String?>(null)
    val caps = MutableStateFlow<AtvvCapabilities?>(null)
    val capsText = MutableStateFlow("")

    val recording = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val coverage = MutableStateFlow(0.0)
    val sessionMs = MutableStateFlow(0L)

    val transcript = MutableStateFlow("")
    val history = MutableStateFlow<List<Transcript>>(emptyList())
    val lastError = MutableStateFlow<String?>(null)

    val asrReady = MutableStateFlow(false)
    val asrBusy = MutableStateFlow(false)
    val asrStatus = MutableStateFlow("语音模型未加载")

    val modelId = MutableStateFlow("sense_voice")
    val modelReady = MutableStateFlow(false)
    val downloadProgress = MutableStateFlow(-1f)
    val downloadText = MutableStateFlow("")

    val devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val a11yEnabled = MutableStateFlow(false)
    val lastKey = MutableStateFlow("")

    fun reset() {
        caps.value = null
        capsText.value = ""
        recording.value = false
        level.value = 0f
        coverage.value = 0.0
        sessionMs.value = 0L
    }
}
