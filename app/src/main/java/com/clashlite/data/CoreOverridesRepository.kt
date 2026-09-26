package com.clashlite.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.overridesDataStore by preferencesDataStore(name = "core_overrides")

/**
 * 内核运行覆盖配置（基础配置页）。
 * 连接时注入 mihomo 配置生成；模式/日志等级可通过 REST API 实时热更。
 */
@Serializable
data class CoreOverrides(
    /** 混合代理端口 */
    val mixedPort: Int = 7890,
    /** 允许局域网代理 */
    val allowLan: Boolean = false,
    /** 外部控制器（关闭后应用将无法控制内核） */
    val externalController: Boolean = true,
    /** 为本地代理端口启用认证 */
    val authentication: Boolean = false,
    val authUser: String = "clashlite",
    val authPass: String = "clashlite123",
    /** 日志等级 debug/info/warning/error/silent */
    val logLevel: String = "warning",
    /** 用户代理 */
    val userAgent: String = "",
    /** 测速链接 */
    val delayTestUrl: String = "https://www.gstatic.com/generate_204",
    /** 追加系统 DNS 到 nameserver */
    val appendSystemDns: Boolean = false,
    /** IPv6 */
    val ipv6: Boolean = false,
    /** 统一延迟（去除握手等额外延迟） */
    val unifiedDelay: Boolean = true,
    /** TCP 并发 */
    val tcpConcurrent: Boolean = true,
    /** 查找进程（find-process-mode always，开启有性能损耗） */
    val findProcess: Boolean = false,
    /** Geo 低内存模式（geodata-loader memconservative） */
    val geoLowMemory: Boolean = true,
    /** 运行模式 rule/global/direct（热更字段，也持久化） */
    val mode: String = "rule",
)

class CoreOverridesRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("core_overrides_json")

    val overrides: Flow<CoreOverrides> = context.overridesDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<CoreOverrides>(it) }.getOrNull() } ?: CoreOverrides()
    }

    suspend fun save(o: CoreOverrides) {
        context.overridesDataStore.edit { it[key] = json.encodeToString(o) }
    }
}
