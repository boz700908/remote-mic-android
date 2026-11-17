package com.boz700908.remotemic.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boz700908.remotemic.asr.AsrModels
import com.boz700908.remotemic.core.AppSettings
import com.boz700908.remotemic.core.BridgeState
import com.boz700908.remotemic.core.SettingsStore
import com.boz700908.remotemic.inject.KeyInjector
import com.boz700908.remotemic.protocol.RemoteButton
import com.boz700908.remotemic.service.BridgeService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun ScreenColumn(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun InfoCard(title: String, lines: List<Pair<String, String>>, footer: String? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { (k, v) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(76.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!footer.isNullOrBlank()) {
                Text(
                    footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ------------------------------- 连接页 ------------------------------- */

@Composable
fun ConnectScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val status by BridgeState.status.collectAsState()
    val connected by BridgeState.connected.collectAsState()
    val devices by BridgeState.devices.collectAsState()
    val deviceName by BridgeState.deviceName.collectAsState()
    val deviceAddress by BridgeState.deviceAddress.collectAsState()
    val battery by BridgeState.battery.collectAsState()
    val modelNumber by BridgeState.modelNumber.collectAsState()
    val capsText by BridgeState.capsText.collectAsState()
    val recording by BridgeState.recording.collectAsState()
    val level by BridgeState.level.collectAsState()
    val sessionMs by BridgeState.sessionMs.collectAsState()
    val coverage by BridgeState.coverage.collectAsState()
    val lastKey by BridgeState.lastKey.collectAsState()
    val lastError by BridgeState.lastError.collectAsState()

    ScreenColumn(padding) {
        InfoCard(
            title = status,
            lines = listOf(
                "设备" to (deviceName ?: "—"),
                "地址" to (deviceAddress ?: "—"),
                "电量" to (battery?.let { "$it%" } ?: "—"),
                "型号" to (modelNumber ?: "—"),
                "能力" to capsText.ifBlank { "—" },
                "按键" to lastKey.ifBlank { "—" },
            ),
            footer = lastError,
        )

        if (recording) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("正在接收音频", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { level.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "已录 ${sessionMs} ms · 覆盖率 ${String.format(Locale.US, "%.0f", coverage)}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                BridgeService.instance?.beginScan()
                    ?: BridgeService.send(context, BridgeService.ACTION_SCAN)
            }) {
                Text(if (devices.isEmpty()) "搜索遥控器" else "重新搜索")
            }
            OutlinedButton(
                enabled = connected,
                onClick = {
                    BridgeService.instance?.disconnect()
                        ?: BridgeService.send(context, BridgeService.ACTION_DISCONNECT)
                },
            ) {
                Text("断开")
            }
        }

        Text("可用设备", style = MaterialTheme.typography.titleSmall)
        if (devices.isEmpty()) {
            Text(
                "把遥控器靠近手机，同时按住「主页 + 返回」让它进入配对模式，然后点上面的搜索。已配对过的遥控器开机就能被找到。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            devices.forEach { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        BridgeService.instance?.connect(device.address)
                            ?: BridgeService.send(context, BridgeService.ACTION_CONNECT, device.address)
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${device.address} · ${device.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("连接", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/* ------------------------------- 识别页 ------------------------------- */

@Composable
fun TranscriptScreen(padding: PaddingValues) {
    val clipboard = LocalClipboardManager.current
    val transcript by BridgeState.transcript.collectAsState()
    val history by BridgeState.history.collectAsState()
    val asrStatus by BridgeState.asrStatus.collectAsState()
    val asrBusy by BridgeState.asrBusy.collectAsState()
    val recording by BridgeState.recording.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when {
                            recording -> "正在听…（松开语音键结束）"
                            asrBusy -> "识别中…"
                            else -> "识别结果"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        transcript.ifBlank {
                            "按住遥控器的语音键说话，说完松手，文字会自动出现在这里并写入当前输入框。"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (asrStatus.isNotBlank()) {
                        Text(
                            asrStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = transcript.isNotBlank(),
                    onClick = { clipboard.setText(AnnotatedString(transcript)) },
                ) {
                    Text("复制")
                }
                OutlinedButton(
                    enabled = history.isNotEmpty(),
                    onClick = { BridgeState.history.value = emptyList() },
                ) {
                    Text("清空历史")
                }
            }
        }

        item { HorizontalDivider() }
        item { Text("历史", style = MaterialTheme.typography.titleSmall) }

        if (history.isEmpty()) {
            item {
                Text(
                    "还没有记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(history) { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${fmt.format(Date(item.at))} · ${item.durationMs} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ------------------------------- 模型页 ------------------------------- */

@Composable
fun ModelScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val settingsFlow = remember { SettingsStore.flow(context) }
    val settings by settingsFlow.collectAsState(initial = AppSettings())
    val modelId by BridgeState.modelId.collectAsState()
    val modelReady by BridgeState.modelReady.collectAsState()
    val downloadProgress by BridgeState.downloadProgress.collectAsState()
    val downloadText by BridgeState.downloadText.collectAsState()
    val asrStatus by BridgeState.asrStatus.collectAsState()
    val scope = rememberCoroutineScope()

    ScreenColumn(padding) {
        Text("离线语音模型", style = MaterialTheme.typography.titleMedium)
        Text(
            "模型在应用内下载到私有目录，不随 APK 分发。切换后会自动加载；没下载的会先下载再加载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AsrModels.ALL.forEach { spec ->
            val selected = spec.id == modelId
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { SettingsStore.setModel(context, spec.id) } },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (selected) "${spec.displayName} · 当前" else spec.displayName,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            spec.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "≈${spec.approxSizeMb} MB",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (settings.modelId == modelId && downloadProgress >= 0f) {
            LinearProgressIndicator(
                progress = { downloadProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(downloadText, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                BridgeService.instance?.downloadSelectedModel()
                    ?: BridgeService.send(context, BridgeService.ACTION_DOWNLOAD)
            }) {
                Text(if (modelReady) "重新下载" else "下载当前模型")
            }
            OutlinedButton(onClick = {
                BridgeService.instance?.reloadModel()
                    ?: BridgeService.send(context, BridgeService.ACTION_RELOAD)
            }) {
                Text("重新加载")
            }
        }

        if (asrStatus.isNotBlank()) {
            Text(asrStatus, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("说明", style = MaterialTheme.typography.titleSmall)
        Text(
            "· SenseVoice Small：约 229 MB，中英日韩粤，速度快，默认推荐。\n" +
                "· 千问 Qwen3-ASR 0.6B（int8）：约 954 MB，精度最好，首次下载耗时较长，建议 WiFi。\n" +
                "· Paraformer 中文：约 230 MB，纯中文场景备选。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ------------------------------- 设置页 ------------------------------- */

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val settingsFlow = remember { SettingsStore.flow(context) }
    val settings by settingsFlow.collectAsState(initial = AppSettings())
    val a11yEnabled by BridgeState.a11yEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    var gain by remember(settings.gainDb) { mutableFloatStateOf(settings.gainDb) }
    var injectChannel by remember { mutableStateOf(KeyInjector.refresh(force = true)) }
    var shizukuReady by remember { mutableStateOf(KeyInjector.shizukuUsable()) }
    var shizukuHint by remember { mutableStateOf<String?>(null) }

    ScreenColumn(padding) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("文字注入", style = MaterialTheme.typography.titleSmall)
                Text(
                    "识别结果通过无障碍服务写进当前输入框。需要先在系统设置里打开「遥控麦」的无障碍服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text(if (a11yEnabled) "无障碍已开启 · 去查看" else "去开启无障碍服务")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("按键注入通道", style = MaterialTheme.typography.titleSmall)
                Text(
                    "把遥控器按键变成真实按键事件，需要一个有 shell 身份的通道：root 优先（能写 uinput 就支持按住与真组合键），其次是 Shizuku。" +
                        "当前：${injectChannel.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (KeyInjector.requestShizuku()) {
                                shizukuHint = "已向 Shizuku 申请授权，同意后点「重新检测」"
                            } else {
                                shizukuHint = if (shizukuReady) "Shizuku 已授权" else "未检测到 Shizuku，请先安装并启动它"
                            }
                        },
                        enabled = shizukuReady &&
                            injectChannel != KeyInjector.Channel.ROOT &&
                            injectChannel != KeyInjector.Channel.UINPUT,
                    ) {
                        Text(if (injectChannel == KeyInjector.Channel.SHIZUKU) "Shizuku 已授权" else "授权 Shizuku")
                    }
                    OutlinedButton(onClick = {
                        injectChannel = KeyInjector.refresh(force = true)
                        shizukuReady = KeyInjector.shizukuUsable()
                        shizukuHint = null
                    }) {
                        Text("重新检测")
                    }
                }
                if (shizukuHint != null) {
                    Text(
                        shizukuHint!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (injectChannel == KeyInjector.Channel.UINPUT) {
                    Text(
                        "uinput 虚拟键盘已就绪：按键是真正的按下/抬起，可长按、可组合键。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (injectChannel == KeyInjector.Channel.ROOT) {
                    Text(
                        "root 已就绪，但 /dev/uinput 打不开，只能整按（无长按/组合）。可尝试在 root 管理里给本应用授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (injectChannel == KeyInjector.Channel.NONE) {
                    Text(
                        "当前只能退回无障碍全局动作（返回 / 主页 / 最近任务 / 音量），方向键与 OK 无法当真实按键发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SwitchRow("自动写入输入框", settings.autoInject) { v ->
            scope.launch { SettingsStore.setAutoInject(context, v) }
        }
        SwitchRow("覆盖已有文字（关闭则追加）", settings.replaceText) { v ->
            scope.launch { SettingsStore.setReplaceText(context, v) }
        }
        SwitchRow("边听边从手机播放", settings.playAudio) { v ->
            scope.launch { SettingsStore.setPlayAudio(context, v) }
        }
        SwitchRow("转发方向 / 音量 / 返回键", settings.forwardKeys) { v ->
            scope.launch { SettingsStore.setForwardKeys(context, v) }
        }

        HorizontalDivider()
        Text("音频增益：${String.format(Locale.US, "%+.1f", gain)} dB", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = gain,
            onValueChange = { gain = it },
            onValueChangeFinished = { scope.launch { SettingsStore.setGain(context, gain) } },
            valueRange = -12f..12f,
            steps = 23,
        )

        HorizontalDivider()
        Text("按键对照", style = MaterialTheme.typography.titleSmall)
        Text(
            "按住语音键说话，松手自动识别。其余按键在开启转发时会映射到系统动作。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RemoteButton.entries.forEach { button ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(button.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format(Locale.US, "0x%02X", button.usage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        HorizontalDivider()
        Text(
            "遥控麦 0.1.0 · 参考 HD838A/remote-mic-app（GPL-3.0）\n协议：Android ATVV（小米蓝牙遥控器 2 / 2 Pro），离线识别引擎：sherpa-onnx",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
