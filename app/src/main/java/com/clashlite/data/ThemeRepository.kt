package com.clashlite.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.themeDataStore by preferencesDataStore(name = "theme")

/**
 * 主题仓库：持久化当前主题（JSON），提供响应式读取。
 * 自定义主题保存为一份 ThemeConfig，支持导出/导入。
 */
class ThemeRepository(private val context: Context) {

    private val key = stringPreferencesKey("current_theme_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val current: Flow<ThemeConfig> = context.themeDataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString<ThemeConfig>(raw) }.getOrNull()
        } ?: ThemeConfig("default", "Clash 蓝")
    }

    suspend fun save(theme: ThemeConfig) {
        context.themeDataStore.edit { it[key] = json.encodeToString(theme) }
    }

    fun export(theme: ThemeConfig): String = json.encodeToString(theme)

    fun import(raw: String): ThemeConfig? =
        runCatching { json.decodeFromString<ThemeConfig>(raw) }.getOrNull()
}
