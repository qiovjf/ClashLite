package com.clashlite.core

import com.clashlite.data.CoreOverrides

/**
 * 生成 mihomo 运行配置：
 * - 以订阅原始 YAML 为基础（保留 rule-providers 等全部字段），
 *   仅覆盖端口/控制器/DNS 等运行参数
 * - 应用基础配置页的覆盖项（端口/局域网/认证/IPv6/统一延迟/TCP并发/Geo低内存等）
 * - 订阅只有节点列表时，自动补一个 PROXY 选择组和 MATCH 规则
 */
object ConfigGenerator {

    const val MIXED_PORT = 7890
    const val CONTROLLER = "127.0.0.1:9090"

    @Suppress("UNCHECKED_CAST")
    fun generate(
        parsed: ParsedSubscription,
        interfaceName: String? = null,
        overrides: CoreOverrides = CoreOverrides(),
        systemDns: List<String> = emptyList(),
    ): String {
        // 以原始 YAML 为底（Clash 配置），base64 订阅则从空开始
        val root = LinkedHashMap<String, Any>(parsed.root)

        // 移除会与运行方式冲突的字段：mihomo 自建 TUN 需要 root，必须关闭
        root.remove("tun")

        // Android 应用无权读内核路由表（SELinux），显式指定出接口，
        // 让 mihomo 的 UDP DIRECT 跳过 netlink 路由查询
        if (!interfaceName.isNullOrBlank()) {
            root["interface-name"] = interfaceName
        }

        // ── 运行参数覆盖 ──
        root["mixed-port"] = overrides.mixedPort
        root["external-controller"] = CONTROLLER
        root["allow-lan"] = overrides.allowLan
        root["bind-address"] = if (overrides.allowLan) "*"
        else "127.0.0.1"
        if (overrides.authentication) {
            root["authentication"] = listOf("${overrides.authUser}:${overrides.authPass}")
        }
        root["mode"] = overrides.mode
        root["log-level"] = overrides.logLevel
        root["ipv6"] = overrides.ipv6
        root["unified-delay"] = overrides.unifiedDelay
        root["tcp-concurrent"] = overrides.tcpConcurrent
        if (overrides.findProcess) {
            root["find-process-mode"] = "always"
        }
        root["geodata-loader"] = if (overrides.geoLowMemory) "memconservative" else "standard"
        if (overrides.userAgent.isNotBlank()) {
            root["global-ua"] = overrides.userAgent
        }

        if (!root.containsKey("dns")) {
            root["dns"] = linkedMapOf<String, Any>(
                "enable" to true,
                "listen" to "127.0.0.1:1053",
                "enhanced-mode" to "fake-ip",
                "fake-ip-range" to "198.18.0.1/16",
                "nameserver" to (listOf<Any>("223.5.5.5", "119.29.29.29") + systemDns),
            )
        }

        val proxies = SubscriptionParser.dedupeNames(parsed.proxies)
        if (proxies.isNotEmpty()) root["proxies"] = proxies

        @Suppress("UNCHECKED_CAST")
        val groups = (root["proxy-groups"] as? List<Map<String, Any>>)?.toMutableList()
            ?: parsed.groups.toMutableList()
        if (groups.isEmpty() && proxies.isNotEmpty()) {
            groups.add(
                linkedMapOf(
                    "name" to "PROXY",
                    "type" to "select",
                    "proxies" to (proxies.map { it["name"] } + listOf("DIRECT")),
                )
            )
        }
        if (groups.isNotEmpty()) root["proxy-groups"] = groups

        val rules = (root["rules"] as? List<Any>)?.map { it.toString() }?.toMutableList()
            ?: parsed.rules.toMutableList()
        if (rules.isEmpty()) {
            rules.add("MATCH,PROXY")
        }
        root["rules"] = rules

        val options = org.yaml.snakeyaml.DumperOptions().apply {
            defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
        }
        return org.yaml.snakeyaml.Yaml(options).dump(root)
    }
}
