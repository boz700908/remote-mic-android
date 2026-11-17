package com.boz700908.remotemic.core

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 「遥控器按键 → 动作」的持久化映射。
 *
 * 键是 `机型 id/input id`（如 `rc003/key_ok`），值是一个动作串：
 *  - `key:CTRL+SHIFT+V` 发真实按键，可带 `@600` 表示按住 600 毫秒；
 *  - `text:你好` 往焦点输入框写字；
 *  - `app:com.tencent.mm` 启动应用；
 *  - `url:https://example.com` 打开网址；
 *  - `act:back` 系统动作（见 [com.boz700908.remotemic.inject.ActionRunner]）；
 *  - `none` 吞掉这个键。
 *
 * 服务端每个按键都要查表，所以常驻一份内存副本（[init] 时开始跟随 DataStore）。
 */
object KeymapStore {

    private val KEY = stringPreferencesKey("keymap_json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cache: Map<String, String> = emptyMap()

    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            flow(app).collect { cache = it }
        }
    }

    fun flow(context: Context): Flow<Map<String, String>> =
        context.dataStore.data.map { parse(it[KEY]) }

    /** 全量快照（`机型 id/input id` → 动作串）。 */
    fun snapshot(): Map<String, String> = cache

    /** 某个按键绑定的动作；没配过返回 null。 */
    fun actionFor(deviceId: String, inputId: String): String? =
        cache[key(deviceId, inputId)]

    /** 某个机型的绑定，键是 input id。 */
    fun deviceBindings(deviceId: String): Map<String, String> {
        val prefix = "${deviceId.ifBlank { UNKNOWN }}/"
        return cache.filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
    }

    fun countFor(deviceId: String): Int = deviceBindings(deviceId).size

    /** 写入一条绑定；[action] 为空表示恢复「原样转发」。 */
    suspend fun set(context: Context, deviceId: String, inputId: String, action: String?) {
        val k = key(deviceId, inputId)
        val value = action?.trim().orEmpty()
        context.dataStore.edit { p ->
            val map = parse(p[KEY]).toMutableMap()
            if (value.isEmpty()) map.remove(k) else map[k] = value
            p[KEY] = encode(map)
            cache = map
        }
    }

    /** 清掉一个机型的全部绑定。 */
    suspend fun clear(context: Context, deviceId: String) {
        val prefix = "${deviceId.ifBlank { UNKNOWN }}/"
        context.dataStore.edit { p ->
            val map = parse(p[KEY]).filterKeys { !it.startsWith(prefix) }
            if (map.isEmpty()) p.remove(KEY) else p[KEY] = encode(map)
            cache = map
        }
    }

    private fun key(deviceId: String, inputId: String): String =
        "${deviceId.ifBlank { UNKNOWN }}/$inputId"

    private fun parse(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(text)
            val out = LinkedHashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.optString(k).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "映射表解析失败，按空表处理", e)
            emptyMap()
        }
    }

    private fun encode(map: Map<String, String>): String {
        val o = JSONObject()
        map.toSortedMap().forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    private const val UNKNOWN = "unknown"
    private const val TAG = "KeymapStore"
}
