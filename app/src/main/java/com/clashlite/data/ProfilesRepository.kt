package com.clashlite.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * 设置与订阅仓库。
 * - 设置存 DataStore
 * - 每份订阅的原始 YAML 存 filesDir/profiles/<id>.yaml
 * - 订阅元数据列表存 DataStore（JSON）
 */
class ProfilesRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val kProfiles = stringPreferencesKey("profiles_json")
    private val kActive = stringPreferencesKey("active_profile")
    private val kPerAppMode = intPreferencesKey("per_app_mode")
    private val kPerAppPkgs = stringSetPreferencesKey("per_app_packages")
    private val kAutoBoot = booleanPreferencesKey("auto_boot")
    private val kProxiesDisplay = stringPreferencesKey("proxies_display_json")

    private val serializer = ListSerializer(Profile.serializer())

    val proxiesDisplay: Flow<ProxiesDisplay> = context.settingsDataStore.data.map { p ->
        p[kProxiesDisplay]?.let { runCatching { json.decodeFromString<ProxiesDisplay>(it) }.getOrNull() } ?: ProxiesDisplay()
    }

    suspend fun setProxiesDisplay(d: ProxiesDisplay) {
        context.settingsDataStore.edit { it[kProxiesDisplay] = json.encodeToString(d) }
    }

    private fun decodeProfiles(raw: String?): List<Profile> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrDefault(emptyList()) } ?: emptyList()

    val profiles: Flow<List<Profile>> = context.settingsDataStore.data.map { decodeProfiles(it[kProfiles]) }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            activeProfileId = p[kActive],
            perAppMode = PerAppMode.entries.getOrElse(p[kPerAppMode] ?: 0) { PerAppMode.OFF },
            perAppPackages = p[kPerAppPkgs] ?: emptySet(),
            autoBoot = p[kAutoBoot] ?: false,
        )
    }

    private fun profileFile(id: String): File =
        File(context.filesDir, "profiles").apply { mkdirs() }.resolve("$id.yaml")

    fun readProfileContent(id: String): String? = profileFile(id).takeIf { it.exists() }?.readText()

    suspend fun addProfile(name: String, url: String, content: String): Profile {
        val now = System.currentTimeMillis()
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "订阅" },
            url = url,
            createdAt = now,
            updatedAt = now,
        )
        profileFile(profile.id).writeText(content)
        context.settingsDataStore.edit {
            it[kProfiles] = json.encodeToString(serializer, decodeProfiles(it[kProfiles]) + profile)
        }
        return profile
    }

    suspend fun updateProfileContent(id: String, content: String, nodeCount: Int) {
        profileFile(id).writeText(content)
        context.settingsDataStore.edit {
            val list = decodeProfiles(it[kProfiles]).map {
                if (it.id == id) it.copy(updatedAt = System.currentTimeMillis(), nodeCount = nodeCount) else it
            }
            it[kProfiles] = json.encodeToString(serializer, list)
        }
    }

    suspend fun deleteProfile(id: String) {
        profileFile(id).delete()
        context.settingsDataStore.edit {
            val list = decodeProfiles(it[kProfiles]).filter { p -> p.id != id }
            it[kProfiles] = json.encodeToString(serializer, list)
            if (it[kActive] == id) it.remove(kActive)
        }
    }

    suspend fun setActiveProfile(id: String) {
        context.settingsDataStore.edit { it[kActive] = id }
    }

    /** 订阅用量信息入库 */
    suspend fun updateProfileTraffic(id: String, upload: Long, download: Long, total: Long, expire: Long) {
        context.settingsDataStore.edit {
            val list = decodeProfiles(it[kProfiles]).map {
                if (it.id == id) it.copy(upload = upload, download = download, total = total, expire = expire) else it
            }
            it[kProfiles] = json.encodeToString(serializer, list)
        }
    }

    // 订阅自动更新周期（小时；0=关闭）
    private val kAutoUpdateHours = intPreferencesKey("auto_update_hours")
    val autoUpdateHours: Flow<Int> = context.settingsDataStore.data.map { it[kAutoUpdateHours] ?: 0 }
    suspend fun setAutoUpdateHours(h: Int) {
        context.settingsDataStore.edit { it[kAutoUpdateHours] = h }
    }

    suspend fun setPerAppMode(mode: PerAppMode) {
        context.settingsDataStore.edit { it[kPerAppMode] = mode.ordinal }
    }

    suspend fun setPerAppPackages(packages: Set<String>) {
        context.settingsDataStore.edit { it[kPerAppPkgs] = packages }
    }

    suspend fun setAutoBoot(enabled: Boolean) {
        context.settingsDataStore.edit { it[kAutoBoot] = enabled }
    }
}
