package com.clashlite

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clashlite.core.CoreManager
import com.clashlite.core.CoreState
import com.clashlite.core.MihomoApi
import com.clashlite.core.ParsedSubscription
import com.clashlite.core.ProxyGroup
import com.clashlite.core.SubscriptionParser
import com.clashlite.data.AppSettings
import com.clashlite.data.DarkMode
import com.clashlite.data.PerAppMode
import com.clashlite.data.Profile
import com.clashlite.data.ProfilesRepository
import com.clashlite.data.ThemeConfig
import com.clashlite.data.ThemeRepository
import com.clashlite.vpn.VpnRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 全局共享 ViewModel */
class ClashViewModel(private val app: Application) : ViewModel() {

    private val profileRepo = ProfilesRepository(app)
    private val themeRepo = ThemeRepository(app)
    private val api = MihomoApi()

    val theme: StateFlow<ThemeConfig> = themeRepo.current
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeConfig("default", "Clash 蓝"))

    val profiles: StateFlow<List<Profile>> = profileRepo.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<AppSettings> = profileRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val vpnRunning: StateFlow<Boolean> = VpnRuntime.running
    val vpnStatus: StateFlow<String> = VpnRuntime.statusText

    // 实时速率（由 /traffic 流式推送更新）
    data class Speed(val up: Long, val down: Long)
    val speed = MutableStateFlow(Speed(0, 0))

    // 代理组
    val groups = MutableStateFlow<List<ProxyGroup>>(emptyList())
    // 各节点延迟（node -> ms）
    val delays = MutableStateFlow<Map<String, Int>>(emptyMap())
    // 节点类型（node -> ss/vmess/Selector/...）
    val nodeTypes = MutableStateFlow<Map<String, String>>(emptyMap())
    // 代理页显示偏好
    val proxiesDisplay: StateFlow<com.clashlite.data.ProxiesDisplay> =
        profileRepo.proxiesDisplay.stateIn(viewModelScope, SharingStarted.Eagerly, com.clashlite.data.ProxiesDisplay())

    fun setProxiesDisplay(d: com.clashlite.data.ProxiesDisplay) =
        viewModelScope.launch { profileRepo.setProxiesDisplay(d) }

    // 基础配置覆盖项
    val overrides: StateFlow<com.clashlite.data.CoreOverrides> =
        com.clashlite.data.CoreOverridesRepository(app).overrides
            .stateIn(viewModelScope, SharingStarted.Eagerly, com.clashlite.data.CoreOverrides())

    fun updateOverrides(transform: (com.clashlite.data.CoreOverrides) -> com.clashlite.data.CoreOverrides) =
        viewModelScope.launch {
            val repo = com.clashlite.data.CoreOverridesRepository(app)
            repo.save(transform(overrides.value))
            // 模式与日志等级支持热更，其余项下次连接生效
            val newOv = overrides.value
            val api = MihomoApi()
            if (VpnRuntime.running.value) {
                launch(Dispatchers.IO) {
                    api.patchMode(newOv.mode)
                    api.patchLogLevel(newOv.logLevel)
                }
            }
        }

    /** 运行模式 rule/global/direct（热更） */
    fun setMode(mode: String) = viewModelScope.launch(Dispatchers.IO) {
        if (VpnRuntime.running.value) MihomoApi().patchMode(mode)
        updateOverrides { it.copy(mode = mode) }
    }

    // ── IP 检测（经本地代理端口转发，反映用户真实出口）──
    data class IpCheck(val loading: Boolean = false, val overseas: Boolean? = null, val info: MihomoApi.IpInfo? = null)
    val ipCheck = MutableStateFlow(IpCheck())

    fun checkExitIp() = viewModelScope.launch(Dispatchers.IO) {
        if (!VpnRuntime.running.value) {
            ipCheck.value = IpCheck(overseas = null, info = null)
            return@launch
        }
        ipCheck.value = IpCheck(loading = true)
        val info = MihomoApi(mixedPort = VpnRuntime.mixedPort.value).exitIpInfo()
        ipCheck.value = IpCheck(
            loading = false,
            overseas = info?.let { it.countryCode.uppercase() != "CN" },
            info = info,
        )
    }

    private var trafficJob: Job? = null

    init {
        // VPN 运行期间：开启 /traffic 流式连接获取实时速率；断开即归零
        viewModelScope.launch {
            VpnRuntime.running.collect { running ->
                if (running && trafficJob?.isActive != true) {
                    trafficJob = launch(Dispatchers.IO) {
                        // 连接初期内核可能尚未就绪，流断开自动重连
                        while (isActive && VpnRuntime.running.value) {
                            runCatching { api.trafficStream { up, down -> speed.value = Speed(up, down) } }
                            speed.value = Speed(0, 0)
                            delay(1000)
                        }
                    }
                    // 连接建立后加载一次代理组 + 同步测速链接/端口
                    delay(800)
                    refreshGroups()
                    api.delayUrl = VpnRuntime.delayUrl.value
                    api.mixedPort = VpnRuntime.mixedPort.value
                    checkExitIp()
                } else if (!running) {
                    trafficJob?.cancel()
                    trafficJob = null
                    speed.value = Speed(0, 0)
                    groups.value = emptyList()
                    ipCheck.value = IpCheck()
                }
            }
        }
    }

    // ---------- 主题 ----------
    fun saveTheme(theme: ThemeConfig) = viewModelScope.launch { themeRepo.save(theme) }
    fun exportTheme(theme: ThemeConfig): String = themeRepo.export(theme)
    fun importTheme(raw: String): Boolean {
        val t = themeRepo.import(raw) ?: return false
        viewModelScope.launch { themeRepo.save(t) }
        return true
    }

    // ---------- 订阅 ----------
    fun addSubscription(url: String, name: String) = viewModelScope.launch(Dispatchers.IO) {
        val content = withContext(Dispatchers.IO) { fetchUrl(url) } ?: return@launch
        val parsed = SubscriptionParser.parse(content)
        val profile = profileRepo.addProfile(name, url, content)
        profileRepo.updateProfileContent(profile.id, content, parsed.proxies.size)
    }

    fun refreshSubscription(id: String) = viewModelScope.launch(Dispatchers.IO) {
        val profile = profiles.value.find { it.id == id } ?: return@launch
        if (profile.url.isBlank()) return@launch
        val content = fetchUrl(profile.url) ?: return@launch
        val parsed = SubscriptionParser.parse(content)
        profileRepo.updateProfileContent(id, content, parsed.proxies.size)
        if (settings.value.activeProfileId == id && VpnRuntime.running.value) {
            // 已连接状态下更新配置后重启内核
            applyConfig(parsed)
        }
    }

    fun deleteProfile(id: String) = viewModelScope.launch { profileRepo.deleteProfile(id) }
    fun setActiveProfile(id: String) = viewModelScope.launch { profileRepo.setActiveProfile(id) }

    private suspend fun applyConfig(parsed: ParsedSubscription) {
        val config = com.clashlite.core.ConfigGenerator.generate(parsed)
        CoreManager(app).stop()
        CoreManager(app).start(config)
    }

    // ---------- 设置 ----------
    fun setPerAppMode(mode: PerAppMode) = viewModelScope.launch { profileRepo.setPerAppMode(mode) }
    fun setPerAppPackages(pkgs: Set<String>) = viewModelScope.launch { profileRepo.setPerAppPackages(pkgs) }
    fun setAutoBoot(enabled: Boolean) = viewModelScope.launch { profileRepo.setAutoBoot(enabled) }

    // ---------- VPN ----------
    fun requestVpnStartVia(context: android.content.Context) {
        val intent = android.content.Intent(context, com.clashlite.vpn.ClashVpnService::class.java)
            .setAction(com.clashlite.vpn.ClashVpnService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun requestVpnStop() {
        val intent = android.content.Intent(app, com.clashlite.vpn.ClashVpnService::class.java)
            .setAction(com.clashlite.vpn.ClashVpnService.ACTION_STOP)
        app.startService(intent)
    }

    // ---------- 代理 ----------
    fun refreshGroups() = viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            val g = api.groups()
            val t = runCatching { api.nodeTypes() }.getOrDefault(emptyMap())
            g to t
        }
        result.onSuccess { (list, types) ->
            groups.value = list
            if (types.isNotEmpty()) nodeTypes.value = types
            CoreManager.appendLog("[app] 代理组加载: ${list.size} 组 [${list.joinToString { it.name }}]")
        }.onFailure {
            CoreManager.appendLog("[app] 代理组加载失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 分组整体测速（mihomo 并发执行），更新延迟表 */
    fun testGroupDelayViaApi(group: String) = viewModelScope.launch(Dispatchers.IO) {
        CoreManager.appendLog("[app] 开始测速: $group")
        val result = runCatching { api.testGroupDelay(group) }
        result.onSuccess { map ->
            delays.value = delays.value + map
            CoreManager.appendLog("[app] 测速完成: ${map.size} 个节点成功")
        }.onFailure {
            CoreManager.appendLog("[app] 测速失败: ${it.message}")
        }
    }

    fun selectNode(group: String, node: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { api.selectNode(group, node) }
        delay(200)
        refreshGroups()
    }

    fun testGroupDelay(nodes: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        nodes.forEach { node ->
            val ms = runCatching { api.testDelay(node) }.getOrNull()
            delays.value = delays.value + (node to (ms ?: -1))
        }
    }

    fun coreVersion(): String =
        (CoreManager.state.value as? CoreState.Running)?.version ?: ""

    private fun fetchUrl(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "clash-lite/0.1")
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ClashViewModel(app) as T
    }
}
