package com.boz700908.remotemic.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.boz700908.remotemic.MainActivity
import com.boz700908.remotemic.RemoteMicApp
import com.boz700908.remotemic.R
import com.boz700908.remotemic.asr.AsrModelSpec
import com.boz700908.remotemic.asr.AsrModels
import com.boz700908.remotemic.asr.ModelDownloader
import com.boz700908.remotemic.asr.OfflineAsr
import com.boz700908.remotemic.bt.RemoteGatt
import com.boz700908.remotemic.core.AppSettings
import com.boz700908.remotemic.core.BridgeState
import com.boz700908.remotemic.core.FoundDevice
import com.boz700908.remotemic.core.PcmPlayer
import com.boz700908.remotemic.core.SettingsStore
import com.boz700908.remotemic.core.Transcript
import com.boz700908.remotemic.core.VolumeController
import com.boz700908.remotemic.device.DescriptorEngine
import com.boz700908.remotemic.device.DeviceDescriptor
import com.boz700908.remotemic.device.InputSpec
import com.boz700908.remotemic.device.RemoteActions
import com.boz700908.remotemic.inject.Injector
import com.boz700908.remotemic.inject.KeyInjector
import com.boz700908.remotemic.protocol.AtvvCapabilities
import com.boz700908.remotemic.protocol.AtvvProtocol
import com.boz700908.remotemic.protocol.PcmPostprocessor
import com.boz700908.remotemic.protocol.RemoteButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 前台服务：蓝牙（ATVV）→ 解码 → 可选播放 + 离线识别 → 无障碍注入。
 * 界面通过 [BridgeState] 观察状态，通过 [instance] 调用控制方法。
 */
class BridgeService : Service(), RemoteGatt.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var gatt: RemoteGatt
    private lateinit var volume: VolumeController
    private lateinit var downloader: ModelDownloader
    private val player = PcmPlayer()

    private var asr: OfflineAsr? = null
    private var asrSpec: AsrModelSpec? = null
    private var settings = AppSettings()

    private val sessionBytes = ByteArrayOutputStream(320_000)
    private val sessionLock = Any()
    private var sessionSamples = 0
    private var sessionStartAt = 0L
    private var lastUsages: Set<Int> = emptySet()
    private var lastInputs: Set<String> = emptySet()
    private var profile: DeviceDescriptor? = null
    private var lastLevelUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat(buildNotification("遥控麦", "准备就绪"))
        volume = VolumeController(getSystemService(AudioManager::class.java))
        downloader = ModelDownloader(File(filesDir, "models"))
        gatt = RemoteGatt(this, this)

        scope.launch {
            var first = true
            SettingsStore.flow(this@BridgeService).collect { s ->
                val changed = s.modelId != settings.modelId
                settings = s
                BridgeState.modelId.value = s.modelId
                BridgeState.a11yEnabled.value = Injector.isEnabled(this@BridgeService)
                if (first) {
                    first = false
                    applyModel(s.modelId, false)
                } else if (changed) {
                    applyModel(s.modelId, false)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCAN -> beginScan()
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_ADDRESS)?.let { connect(it) }
            ACTION_DISCONNECT -> disconnect()
            ACTION_DOWNLOAD -> downloadSelectedModel()
            ACTION_RELOAD -> applyModel(BridgeState.modelId.value, false)
        }
        BridgeState.a11yEnabled.value = Injector.isEnabled(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        runCatching { gatt.disconnect() }
        stopPlayback()
        runCatching { asr?.close() }
        asr = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 对外控制 ----------

    fun beginScan() {
        if (!gatt.hasPermissions()) {
            BridgeState.lastError.value = "缺少蓝牙扫描/连接权限，请先授权"
            return
        }
        BridgeState.devices.value = emptyList()
        gatt.startScan(autoConnectFirst = true)
    }

    fun connect(address: String) {
        BridgeState.deviceAddress.value = address
        BridgeState.devices.value.firstOrNull { it.address == address }?.let {
            BridgeState.deviceName.value = it.name
        }
        gatt.connect(address)
    }

    fun disconnect() {
        gatt.disconnect()
        stopPlayback()
        BridgeState.reset()
    }

    fun downloadSelectedModel() {
        val spec = AsrModels.byId(BridgeState.modelId.value)
        scope.launch { downloadModel(spec) }
    }

    fun reloadModel() = applyModel(BridgeState.modelId.value, false)

    fun refreshAccessibility() {
        BridgeState.a11yEnabled.value = Injector.isEnabled(this)
    }

    // ---------- 模型 / ASR ----------

    private fun applyModel(id: String, forceDownload: Boolean) {
        scope.launch {
            val spec = AsrModels.byId(id)
            BridgeState.modelId.value = spec.id
            if (asrSpec?.id == spec.id && BridgeState.asrReady.value) return@launch

            val ready = downloader.isReady(spec)
            BridgeState.modelReady.value = ready
            if (!ready) {
                BridgeState.asrReady.value = false
                BridgeState.downloadProgress.value = if (downloader.downloadedBytes(spec) > 0) 0f else -1f
                BridgeState.asrStatus.value = "尚未下载 ${spec.displayName}（约 ${spec.approxSizeMb} MB）"
                if (forceDownload) downloadModel(spec)
                return@launch
            }
            BridgeState.downloadProgress.value = 1f
            loadAsr(spec)
        }
    }

    private suspend fun downloadModel(spec: AsrModelSpec) {
        BridgeState.downloadProgress.value = 0f
        BridgeState.downloadText.value = "准备下载 ${spec.displayName}…"
        BridgeState.asrStatus.value = "正在下载 ${spec.displayName}"
        val result = runCatching {
            downloader.download(spec) { done, total, current ->
                val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                BridgeState.downloadProgress.value = p
                BridgeState.downloadText.value = "${humanBytes(done)} / ${humanBytes(total)}  $current"
            }
        }
        result.exceptionOrNull()?.let { err ->
            BridgeState.downloadProgress.value = -1f
            BridgeState.downloadText.value = "下载失败：${err.message}"
            BridgeState.asrStatus.value = "模型下载失败：${err.message}"
            BridgeState.lastError.value = "模型下载失败：${err.message}"
            return
        }
        BridgeState.downloadProgress.value = 1f
        BridgeState.downloadText.value = "下载完成"
        BridgeState.modelReady.value = true
        loadAsr(spec)
    }

    private suspend fun loadAsr(spec: AsrModelSpec) = withContext(Dispatchers.Default) {
        BridgeState.asrStatus.value = "正在加载 ${spec.displayName}…"
        BridgeState.asrReady.value = false
        val result = runCatching {
            runCatching { asr?.close() }
            asr = null
            asrSpec = null
            OfflineAsr(downloader.modelDir(spec), spec, numThreads = 2).also { it.ensureLoaded() }
        }
        result.onSuccess { engine ->
            asr = engine
            asrSpec = spec
            BridgeState.asrReady.value = true
            BridgeState.asrStatus.value = "${spec.displayName} 已就绪"
        }.onFailure { err ->
            asr = null
            asrSpec = null
            BridgeState.asrReady.value = false
            BridgeState.asrStatus.value = "模型加载失败：${err.message}"
            BridgeState.lastError.value = "模型加载失败：${err.message}"
            Log.e(TAG, "加载模型失败", err)
        }
    }

    // ---------- GATT 回调 ----------

    override fun onStatus(status: RemoteGatt.Status, detail: String) {
        val base = when (status) {
            RemoteGatt.Status.IDLE -> "空闲"
            RemoteGatt.Status.SCANNING -> "正在搜索遥控器…"
            RemoteGatt.Status.CONNECTING -> "正在连接…"
            RemoteGatt.Status.DISCOVERING -> "正在读取服务…"
            RemoteGatt.Status.READY -> "已连接，可以说话了"
            RemoteGatt.Status.DISCONNECTED -> "已断开"
            RemoteGatt.Status.ERROR -> "出错了"
        }
        BridgeState.status.value = if (detail.isBlank()) base else "$base（$detail）"
        BridgeState.connected.value = status == RemoteGatt.Status.READY
        if (status == RemoteGatt.Status.SCANNING) BridgeState.devices.value = emptyList()
        if (status == RemoteGatt.Status.DISCONNECTED || status == RemoteGatt.Status.ERROR) {
            BridgeState.reset()
            stopPlayback()
        }
        if (status == RemoteGatt.Status.ERROR && detail.isNotBlank()) {
            BridgeState.lastError.value = detail
        }
        updateNotification()
    }

    override fun onDeviceFound(name: String, address: String, rssi: Int) {
        val list = BridgeState.devices.value.toMutableList()
        val item = FoundDevice(name.ifBlank { "未命名设备" }, address, rssi)
        val idx = list.indexOfFirst { it.address == address }
        if (idx >= 0) list[idx] = item else list.add(item)
        BridgeState.devices.value = list.sortedByDescending { it.rssi }
    }

    override fun onCapabilities(caps: AtvvCapabilities) {
        BridgeState.caps.value = caps
        val rate = if (caps.selectedCodec == 0x02) "16 kHz" else "8 kHz"
        BridgeState.capsText.value = buildString {
            append(if (caps.isVoiceV11) "ATVV 1.1+" else "ATVV 1.0")
            append(" · ").append(rate)
            append(" · 帧 ").append(caps.frameSize).append(" B / ").append(caps.frameSamples).append(" 采样")
        }
        updateNotification()
    }

    override fun onModelNumber(model: String) {
        BridgeState.modelNumber.value = model
    }

    override fun onBattery(level: Int?) {
        BridgeState.battery.value = level
    }

    override fun onVoiceStart(sessionId: Int) {
        synchronized(sessionLock) {
            sessionBytes.reset()
            sessionSamples = 0
        }
        sessionStartAt = System.currentTimeMillis()
        BridgeState.recording.value = true
        BridgeState.sessionMs.value = 0L
        BridgeState.coverage.value = 0.0
        if (settings.playAudio) player.start()
        updateNotification()
        Log.i(TAG, "语音开始，sessionId=$sessionId")
    }

    override fun onVoiceStop(timeout: Boolean) {
        BridgeState.recording.value = false
        stopPlayback()
        val durationMs = System.currentTimeMillis() - sessionStartAt
        val pcm = takeSession()
        BridgeState.sessionMs.value = durationMs
        val caps = BridgeState.caps.value
        if (pcm.isNotEmpty() && caps != null) {
            BridgeState.coverage.value =
                AtvvProtocol.audioCoveragePercent(pcm.size, durationMs)
        }
        updateNotification()
        Log.i(TAG, "语音结束：${durationMs}ms，${pcm.size} 采样，timeout=$timeout")
        if (pcm.size >= SAMPLE_RATE / 5) {
            transcribe(pcm, durationMs)
        } else {
            BridgeState.asrStatus.value = "这段音频太短，已跳过"
        }
    }

    override fun onPcm(input: ShortArray) {
        if (input.isEmpty()) return
        val samples = if (settings.gainDb != 0f) {
            PcmPostprocessor.process(input, settings.gainDb.toDouble())
        } else {
            input
        }
        if (settings.playAudio && player.isRunning) player.write(samples)
        appendSession(samples)
        val now = System.currentTimeMillis()
        if (now - lastLevelUpdate >= 80) {
            lastLevelUpdate = now
            BridgeState.level.value =
                (PcmPostprocessor.peak(samples) / 32768f).coerceIn(0f, 1f)
            BridgeState.sessionMs.value = now - sessionStartAt
        }
    }

    override fun onDescriptor(descriptor: DeviceDescriptor) {
        profile = descriptor
        BridgeState.deviceProfile.value = descriptor.displayName
        lastInputs = emptySet()
        KeyInjector.refresh(force = true)
    }

    /** 描述符解出的按键：只对「新按下」的输入触发动作。 */
    override fun onHidDecoded(decoded: DescriptorEngine.Decoded) {
        val d = profile ?: return
        val now = decoded.pressed.toSet()
        val fresh = now - lastInputs
        lastInputs = now
        for (id in fresh) {
            val input = d.inputs.firstOrNull { it.id == id } ?: continue
            dispatchInput(input)
        }
    }

    private fun isVoiceInput(input: InputSpec): Boolean =
        input.target == "#microphone" || input.icon == "mic" || input.label.contains("语音")

    private fun dispatchInput(input: InputSpec) {
        BridgeState.lastKey.value = input.label
        if (isVoiceInput(input)) {
            if (!gatt.isStreaming) gatt.openMicrophone()
            return
        }
        if (!settings.forwardKeys) return
        val src = input.sources.firstOrNull { it.page != null && it.usage != null }
        val page = src?.page ?: 0
        val usage = src?.usage ?: 0
        val keyCode = RemoteActions.keyCodeFor(page, usage)
        if (keyCode != 0 && KeyInjector.tap(keyCode)) return
        // 注入不可用（未 root）：退回无障碍能做的全局动作
        if (usage != 0) dispatchUsage(usage)
    }

    override fun onHidUsages(usages: List<Int>) {
        val now = usages.toSet()
        val pressed = now - lastUsages
        lastUsages = now
        for (usage in pressed) dispatchUsage(usage)
    }

    private fun dispatchUsage(usage: Int) {
        run {
            if (usage == RemoteButton.VOICE_KEY_F5) {
                BridgeState.lastKey.value = "语音键"
                if (!gatt.isStreaming) gatt.openMicrophone()
                return
            }
            val button = RemoteButton.from(usage) ?: return
            BridgeState.lastKey.value = button.label
            if (!settings.forwardKeys) return
            val keyCode = RemoteActions.keyCodeFor(RemoteActions.PAGE_KEYBOARD, usage)
            if (keyCode != 0 && KeyInjector.tap(keyCode)) return
            when (button) {
                RemoteButton.BACK -> Injector.globalBack()
                RemoteButton.HOME -> Injector.globalHome()
                RemoteButton.MENU -> Injector.globalRecents()
                RemoteButton.VOLUME_UP -> volume.up()
                RemoteButton.VOLUME_DOWN -> volume.down()
                RemoteButton.UP -> Injector.scroll(false)
                RemoteButton.DOWN -> Injector.scroll(true)
                else -> Unit
            }
        }
    }

    // ---------- 音频缓冲与识别 ----------

    private fun appendSession(samples: ShortArray) {
        if (sessionSamples >= MAX_SESSION_SAMPLES) return
        val bytes = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = s.toInt()
            bytes[i++] = (v and 0xFF).toByte()
            bytes[i++] = ((v shr 8) and 0xFF).toByte()
        }
        synchronized(sessionLock) {
            sessionBytes.write(bytes, 0, bytes.size)
            sessionSamples += samples.size
        }
    }

    private fun takeSession(): ShortArray = synchronized(sessionLock) {
        val bytes = sessionBytes.toByteArray()
        sessionBytes.reset()
        val count = sessionSamples
        sessionSamples = 0
        val out = ShortArray(count.coerceAtMost(bytes.size / 2))
        for (i in out.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        out
    }

    private fun transcribe(pcm: ShortArray, durationMs: Long) {
        scope.launch {
            val engine = asr
            if (engine == null) {
                BridgeState.asrStatus.value = "语音模型未就绪，先到「模型」页下载"
                BridgeState.lastError.value = "语音模型未就绪，无法识别"
                return@launch
            }
            BridgeState.asrBusy.value = true
            val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
            val result = runCatching { engine.recognize(samples) }
            BridgeState.asrBusy.value = false
            result.onSuccess { raw ->
                val text = raw.trim()
                if (text.isEmpty()) {
                    BridgeState.asrStatus.value = "没听清，再说一次"
                    return@onSuccess
                }
                BridgeState.transcript.value = text
                BridgeState.asrStatus.value = "识别完成"
                BridgeState.history.value =
                    (listOf(Transcript(text, System.currentTimeMillis(), durationMs)) +
                        BridgeState.history.value).take(50)
                if (settings.autoInject) {
                    val ok = Injector.typeText(text, settings.replaceText)
                    if (!ok) BridgeState.lastError.value = "识别成功，但没能写入输入框（检查无障碍服务与光标位置）"
                }
            }.onFailure { err ->
                BridgeState.asrStatus.value = "识别失败：${err.message}"
                BridgeState.lastError.value = "识别失败：${err.message}"
                Log.e(TAG, "识别失败", err)
            }
        }
    }

    private fun stopPlayback() {
        runCatching { player.stop() }
    }

    // ---------- 通知 ----------

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val kill = PendingIntent.getService(
            this, 1,
            Intent(this, BridgeService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, RemoteMicApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "断开", kill)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val status = BridgeState.status.value
        val text = when {
            BridgeState.recording.value -> "正在接收语音…"
            BridgeState.transcript.value.isNotBlank() -> BridgeState.transcript.value
            else -> BridgeState.asrStatus.value
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIF_ID, buildNotification(status, text)) }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIF_ID = 41
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SESSION_SAMPLES = SAMPLE_RATE * 60

        const val ACTION_SCAN = "com.boz700908.remotemic.action.SCAN"
        const val ACTION_CONNECT = "com.boz700908.remotemic.action.CONNECT"
        const val ACTION_DISCONNECT = "com.boz700908.remotemic.action.DISCONNECT"
        const val ACTION_DOWNLOAD = "com.boz700908.remotemic.action.DOWNLOAD"
        const val ACTION_RELOAD = "com.boz700908.remotemic.action.RELOAD"
        const val EXTRA_ADDRESS = "address"

        @Volatile
        var instance: BridgeService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun send(context: Context, action: String, address: String? = null) {
            val intent = Intent(context, BridgeService::class.java).setAction(action)
            if (address != null) intent.putExtra(EXTRA_ADDRESS, address)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun humanBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1073741824.0)
            bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
