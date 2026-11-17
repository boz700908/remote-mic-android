package com.boz700908.remotemic.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 全部偏好在同一个文件里。DataStore 不允许同文件开两个实例，
 * 所以按键映射（[KeymapStore]）也走这个属性，不要另建 preferencesDataStore。
 */
internal val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val modelId: String = "sense_voice",
    val gainDb: Float = 0f,
    val playAudio: Boolean = false,
    val autoInject: Boolean = true,
    val replaceText: Boolean = false,
    val forwardKeys: Boolean = true,
)

object SettingsStore {
    private val KEY_MODEL = stringPreferencesKey("model_id")
    private val KEY_GAIN = floatPreferencesKey("gain_db")
    private val KEY_PLAY = booleanPreferencesKey("play_audio")
    private val KEY_INJECT = booleanPreferencesKey("auto_inject")
    private val KEY_REPLACE = booleanPreferencesKey("replace_text")
    private val KEY_KEYS = booleanPreferencesKey("forward_keys")

    fun flow(context: Context): Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            modelId = p[KEY_MODEL] ?: "sense_voice",
            gainDb = p[KEY_GAIN] ?: 0f,
            playAudio = p[KEY_PLAY] ?: false,
            autoInject = p[KEY_INJECT] ?: true,
            replaceText = p[KEY_REPLACE] ?: false,
            forwardKeys = p[KEY_KEYS] ?: true,
        )
    }

    suspend fun setModel(context: Context, id: String) =
        context.dataStore.edit { it[KEY_MODEL] = id }

    suspend fun setGain(context: Context, gain: Float) =
        context.dataStore.edit { it[KEY_GAIN] = gain }

    suspend fun setPlayAudio(context: Context, value: Boolean) =
        context.dataStore.edit { it[KEY_PLAY] = value }

    suspend fun setAutoInject(context: Context, value: Boolean) =
        context.dataStore.edit { it[KEY_INJECT] = value }

    suspend fun setReplaceText(context: Context, value: Boolean) =
        context.dataStore.edit { it[KEY_REPLACE] = value }

    suspend fun setForwardKeys(context: Context, value: Boolean) =
        context.dataStore.edit { it[KEY_KEYS] = value }
}
