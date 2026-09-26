package com.clashlite.vpn

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.clashlite.ClashApp
import com.clashlite.R
import com.clashlite.core.ConfigGenerator
import com.clashlite.core.CoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/** VPN 运行状态，UI 直接观察 */
object VpnRuntime {
    val running = MutableStateFlow(false)
    val statusText = MutableStateFlow("未连接")
    val mixedPort = MutableStateFlow(com.clashlite.core.ConfigGenerator.MIXED_PORT)
    val delayUrl = MutableStateFlow("https://www.gstatic.com/generate_204")
}

/**
 * VpnService：建立 TUN 网卡后，
 * 1. 启动 mihomo 内核（SOCKS/HTTP 混合端口 7890 + 控制端口 9090）
 * 2. 启动 tun2socks，把 TUN 流量转发到 127.0.0.1:7890
 *
 * tun2socks 通过文件描述符继承接管 TUN：清除 CLOEXEC 后以 `--tun fd:N` 传给子进程。
 */
class ClashVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.clashlite.action.START"
        const val ACTION_STOP = "com.clashlite.action.STOP"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 8500

        /** VPN DNS 与 hev mapdns 的拦截地址必须一致（见 establishTun 注释） */
        private const val DNS_SERVER = "223.5.5.5"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var configSnapshot: String? = null
    private var configOverrides: com.clashlite.data.CoreOverrides =
        com.clashlite.data.CoreOverrides()

    /** 追加系统 DNS：取当前底层网络的 DNS 服务器 */
    private fun detectSystemDns(): List<String> {
        return runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return@runCatching emptyList()
            cm.allNetworks
                .map { n -> n to cm.getNetworkCapabilities(n) }
                .firstOrNull { (_, caps) ->
                    caps != null &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                ?.let { (n, _) -> cm.getLinkProperties(n)?.dnsServers }
                ?.orEmpty()
                ?.mapNotNull { it.hostAddress }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    // 网络切换监听
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkHandle: Long = -1
    private var reconnectPending = false

    override fun onCreate() {
        super.onCreate()
        VpnRuntime.statusText.value = "准备中"
        registerNetworkCallback()
    }

    /**
     * 监听系统网络变化（Wi-Fi ↔ 蜂窝切换）。
     * 防抖：网络事件后等 3 秒确认默认网络真的变了才重连
     * （双卡设备上 onAvailable 会频繁触发）。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleReconnectCheck()
            }

            override fun onLost(network: Network) {
                scheduleReconnectCheck()
            }
        }
        runCatching { cm.registerNetworkCallback(request, networkCallback!!) }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun scheduleReconnectCheck() {
        if (reconnectPending) return
        reconnectPending = true
        scope.launch {
            delay(3000)
            reconnectPending = false
            if (!VpnRuntime.running.value) return@launch
            val cm = getSystemService(ConnectivityManager::class.java) ?: return@launch
            val current = cm.activeNetwork ?: return@launch
            val handle = current.networkHandle
            if (lastNetworkHandle != -1L && handle != lastNetworkHandle) {
                lastNetworkHandle = handle
                CoreManager.appendLog("[app] 检测到网络切换，自动重连…")
                VpnRuntime.statusText.value = "网络切换，重连中"
                restartEverything()
            } else if (lastNetworkHandle == -1L) {
                lastNetworkHandle = handle
            }
        }
    }

    /** 网络切换后的重连：完整重建链路，但不停止服务 */
    private fun restartEverything() {
        scope.launch {
            runCatching { if (hev.htproxy.TProxyService.TProxyIsRunning()) hev.htproxy.TProxyService.TProxyStopService() }
            runCatching { tun?.close() }
            tun = null
            CoreManager(this@ClashVpnService).stop()
            VpnRuntime.running.value = false
            startEverything()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            else -> startEverything()
        }
        return START_STICKY
    }

    private fun startEverything() {
        val app = ClashApp.instance
        val repo = com.clashlite.data.ProfilesRepository(app)
        val ovrRepo = com.clashlite.data.CoreOverridesRepository(app)
        val settings = runBlocking { repo.settings.first() }
        val overrides = runBlocking { ovrRepo.overrides.first() }
        val activeId = settings.activeProfileId
        val content = activeId?.let { repo.readProfileContent(it) }
        if (content == null) {
            VpnRuntime.statusText.value = "没有可用订阅"
            stopSelf()
            return
        }
        val parsed = com.clashlite.core.SubscriptionParser.parse(content)
        if (parsed.proxies.isEmpty()) {
            VpnRuntime.statusText.value = "订阅中没有可用节点"
            stopSelf()
            return
        }
        // 追加系统 DNS：取当前底层网络的 DNS 服务器
        val systemDns = if (overrides.appendSystemDns) detectSystemDns() else emptyList()
        configSnapshot = ConfigGenerator.generate(parsed, null, overrides, systemDns)
        configOverrides = overrides
        startForegroundCompat()

        scope.launch {
            // 1. 内核
            val ok = CoreManager(app).start(configSnapshot!!, overrides.externalController)
            if (!ok) {
                VpnRuntime.statusText.value = "内核启动失败，详见日志"
                stopEverything()
                return@launch
            }
            // 2. TUN + tun2socks
            val pfd = establishTun(settings) { stopEverything() }
            if (pfd == null) {
                VpnRuntime.statusText.value = "VPN 权限被拒绝"
                stopEverything()
                return@launch
            }
            tun = pfd
            if (!launchTun2socks(pfd)) {
                VpnRuntime.statusText.value = "tun2socks 启动失败"
                stopEverything()
                return@launch
            }
            VpnRuntime.running.value = true
            VpnRuntime.statusText.value = "已连接"
            VpnRuntime.mixedPort.value = configOverrides.mixedPort
            VpnRuntime.delayUrl.value = configOverrides.delayTestUrl
            // 记录当前默认网络，作为后续切换检测的基线
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.activeNetwork?.let {
                    lastNetworkHandle = it.networkHandle
                }
            }
        }
    }

    private fun establishTun(
        settings: com.clashlite.data.AppSettings,
        onFail: () -> Unit,
    ): ParcelFileDescriptor? {
        val builder = Builder()
        builder.setSession("ClashLite")
        builder.setMtu(MTU)
        builder.addAddress("198.18.0.2", 32)
        builder.addRoute("0.0.0.0", 0)
        // DNS 必须是真实 IP（Android 并行解析器只查真实 DNS 服务器，不会查 VPN 自己的地址），
        // 同时 hev mapdns 以同一 IP 拦截应答（fake-ip），DNS 本地应答、域名远端解析
        builder.addDnsServer(DNS_SERVER)
        // 防止回环：排除自身（内核与 tun2socks 都在本应用内）
        runCatching { builder.addDisallowedApplication(packageName) }
        when (settings.perAppMode) {
            com.clashlite.data.PerAppMode.OFF -> {}
            com.clashlite.data.PerAppMode.EXCLUDE ->
                settings.perAppPackages.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            com.clashlite.data.PerAppMode.INCLUDE ->
                settings.perAppPackages.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
        }
        return runCatching { builder.establish() }.getOrNull()
    }

    /**
     * 用进程内的 hev-socks5-tunnel JNI 库接管 TUN。
     * 不能用子进程方案：Android 的 Runtime.exec 会在子进程中关闭除 0/1/2 外的所有 fd，
     * TUN fd 无法通过 exec 继承；JNI 在同进程内直接持有 fd，没有这个问题。
     */
    private fun launchTun2socks(pfd: ParcelFileDescriptor): Boolean {
        return runCatching {
            val fd = pfd.fd
            val coreDir = File(filesDir, "core").apply { mkdirs() }
            val configFile = File(coreDir, "hev.yaml")
            configFile.writeText(
                """
                tunnel:
                  name: tun0
                  mtu: $MTU
                  ipv4: 198.18.0.1
                socks5:
                  port: ${configOverrides.mixedPort}
                  address: 127.0.0.1
                  udp: 'udp'
                mapdns:
                  address: $DNS_SERVER
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000
                misc:
                  log-file: '${File(coreDir, "t2s.log").absolutePath}'
                  log-level: warn
                """.trimIndent()
            )
            val ok = hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
            if (!ok) {
                CoreManager.appendLog("[app] hev-socks5-tunnel 启动失败")
            } else {
                CoreManager.appendLog("[app] hev-socks5-tunnel 已接管 TUN (fd=$fd)")
            }
            ok
        }.onFailure {
            CoreManager.appendLog("[app] TUN 接管异常: ${it.message}")
        }.getOrDefault(false)
    }

    private fun startForegroundCompat() {
        val channel = androidx.core.app.NotificationChannelCompat.Builder(CHANNEL_ID, androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("VPN 连接").build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)

        val stopIntent = android.app.PendingIntent.getService(
            this, 1, Intent(this, ClashVpnService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = android.app.PendingIntent.getActivity(
            this, 2, Intent(this, com.clashlite.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("ClashLite 运行中")
            .setContentText("点击断开连接")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "断开", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopEverything() {
        scope.launch {
            runCatching { if (hev.htproxy.TProxyService.TProxyIsRunning()) hev.htproxy.TProxyService.TProxyStopService() }
            runCatching { tun?.close() }
            tun = null
            CoreManager(this@ClashVpnService).stop()
            VpnRuntime.running.value = false
            VpnRuntime.statusText.value = "未连接"
            ServiceCompat.stopForeground(this@ClashVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { if (hev.htproxy.TProxyService.TProxyIsRunning()) hev.htproxy.TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        runCatching { CoreManager(this).destroyNow() }
        unregisterNetworkCallback()
        scope.cancel()
        VpnRuntime.running.value = false
        super.onDestroy()
    }

    override fun onRevoke() {
        // 系统或其他应用抢占了 VPN
        stopEverything()
        super.onRevoke()
    }
}
