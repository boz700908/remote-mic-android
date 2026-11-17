package com.boz700908.remotemic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boz700908.remotemic.core.BridgeState
import com.boz700908.remotemic.core.KeymapStore
import com.boz700908.remotemic.core.RemoteInput
import com.boz700908.remotemic.inject.ActionRunner
import com.boz700908.remotemic.inject.KeyCatalog
import kotlinx.coroutines.launch

/** (显示名, 动作串前缀)；前缀为空的「默认」表示不绑定、走原来的转发。 */
private val ActionTypes: List<Pair<String, String>> = listOf(
    "默认" to "",
    "按键" to "key:",
    "文字" to "text:",
    "应用" to "app:",
    "网址" to "url:",
    "系统动作" to "act:",
    "屏蔽" to "none",
)

private fun splitAction(action: String?): Pair<String, String> {
    val s = action?.trim().orEmpty()
    if (s.isEmpty()) return "" to ""
    if (s.equals("none", ignoreCase = true)) return "none" to ""
    val hit = ActionTypes.firstOrNull { it.second.isNotEmpty() && s.startsWith(it.second, ignoreCase = true) }
    return if (hit == null) "key:" to s else hit.second to s.substring(hit.second.length)
}

private fun joinAction(type: String, arg: String): String? = when {
    type.isEmpty() -> null
    type == "none" -> "none"
    arg.isBlank() -> null
    else -> type + arg.trim()
}

/** 把每个遥控器按键绑到一个动作；空绑定表示保持原来的按键转发。 */
@Composable
fun KeymapScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val deviceId by BridgeState.deviceId.collectAsState()
    val inputs by BridgeState.remoteInputs.collectAsState()
    val bindingsFlow = remember { KeymapStore.flow(context) }
    val bindings by bindingsFlow.collectAsState(initial = KeymapStore.snapshot())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<RemoteInput?>(null) }

    val prefix = if (deviceId.isBlank()) "" else "$deviceId/"

    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
    ) {
        Text(
            if (deviceId.isBlank()) "还没识别到机型：连上遥控器后再来配，映射按机型分开存。"
            else "机型 $deviceId · 共 ${inputs.size} 个键。没配的键保持原样转发。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        if (inputs.isEmpty()) {
            Text("先到「连接」页连上遥控器，这里就会列出它的按键。", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(inputs, key = { it.id }) { input ->
                    KeymapRow(
                        input = input,
                        action = bindings[prefix + input.id],
                        onEdit = { editing = input },
                    )
                }
            }
        }
    }

    val target = editing
    if (target != null) {
        val current = bindings[prefix + target.id]
        ActionEditor(
            input = target,
            current = current,
            onDismiss = { editing = null },
            onSave = { action ->
                scope.launch {
                    KeymapStore.set(context, deviceId, target.id, action)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun KeymapRow(input: RemoteInput, action: String?, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(input.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    describe(input, action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onEdit) { Text(if (action == null) "绑定" else "修改") }
        }
    }
}

private fun describe(input: RemoteInput, action: String?): String {
    if (action != null) return action
    val key = KeyCatalog.name(input.legacyKey)
    return if (key != null) "默认转发 → $key" else "默认（无系统键码，靠无障碍）"
}

@Composable
private fun ActionEditor(
    input: RemoteInput,
    current: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    val initial = remember(current) { splitAction(current) }
    var type by remember(current) { mutableStateOf(initial.first) }
    var arg by remember(current) { mutableStateOf(initial.second) }
    var error by remember(current) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("绑定「${input.label}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTypes.take(4).forEach { (label, prefix) ->
                        FilterChip(
                            selected = type == prefix,
                            onClick = { type = prefix; error = null },
                            label = { Text(label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTypes.drop(4).forEach { (label, prefix) ->
                        FilterChip(
                            selected = type == prefix,
                            onClick = { type = prefix; error = null },
                            label = { Text(label) },
                        )
                    }
                }

                when (type) {
                    "key:" -> {
                        OutlinedTextField(
                            value = arg,
                            onValueChange = { arg = it; error = null },
                            label = { Text("键名") },
                            placeholder = { Text("CTRL+SHIFT+V，或 F5@800 按住 800 毫秒") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "可用：A–Z / 0–9 / F1–F12 / ENTER / ESC / TAB / SPACE / DEL / BACK / HOME / UP…（用 + 连写组合键）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    "text:" -> {
                        OutlinedTextField(
                            value = arg,
                            onValueChange = { arg = it },
                            label = { Text("要输入的文字") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "写进当前焦点输入框，需要无障碍服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    "app:" -> OutlinedTextField(
                        value = arg,
                        onValueChange = { arg = it },
                        label = { Text("包名") },
                        placeholder = { Text("com.tencent.mm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    "url:" -> OutlinedTextField(
                        value = arg,
                        onValueChange = { arg = it },
                        label = { Text("网址") },
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    "act:" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionRunner.actNames.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { name ->
                                    FilterChip(
                                        selected = arg == name,
                                        onClick = { arg = name },
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                    }
                    else -> Text(
                        if (type == "none") "这个键按下后什么都不做。" else "不绑定，保持原来的转发行为。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (type == "key:" && arg.isNotBlank()) {
                    val spec = arg.substringBeforeLast('@', arg)
                    if (KeyCatalog.codes(spec).isEmpty()) {
                        error = "认不出这个键名，检查一下拼写"
                        return@TextButton
                    }
                }
                onSave(joinAction(type, arg))
            }) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) {
                    TextButton(onClick = { onSave(null) }) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
