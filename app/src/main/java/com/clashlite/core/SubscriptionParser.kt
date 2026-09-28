package com.clashlite.core

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URLDecoder

/** 解析结果：节点列表（mihomo proxies 格式的 Map）+ 是否来自 Clash 配置 */
data class ParsedSubscription(
    val proxies: List<Map<String, Any>>,
    val groups: List<Map<String, Any>>,
    val rules: List<String>,
    val isClashConfig: Boolean,
    /** 原始 YAML 根节点（Clash 配置时保留全部键，如 rule-providers） */
    val root: Map<String, Any> = emptyMap(),
)

/**
 * 订阅解析器，支持两种格式：
 * 1. Clash/mihomo YAML（直接取 proxies / proxy-groups / rules）
 * 2. base64 节点订阅（ss://、vmess://、trojan://，SIP002）
 */
object SubscriptionParser {

    @Suppress("UNCHECKED_CAST")
    fun parse(content: String): ParsedSubscription {
        val trimmed = content.trim()
        return if (trimmed.contains("proxies:") || trimmed.startsWith("{")) {
            parseClashYaml(trimmed)
        } else {
            parseBase64ShareLinks(trimmed)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseClashYaml(content: String): ParsedSubscription {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val root = yaml.load<Map<String, Any>>(content) ?: emptyMap()
        val proxies = (root["proxies"] as? List<Map<String, Any>>)?.toList() ?: emptyList()
        val groups = (root["proxy-groups"] as? List<Map<String, Any>>)?.toList() ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val rules = (root["rules"] as? List<Any>)?.map { it.toString() } ?: emptyList()
        return ParsedSubscription(proxies, groups, rules, isClashConfig = true, root = root)
    }

    fun parseBase64ShareLinks(content: String): ParsedSubscription {
        // 订阅可能是整包 base64
        val links = decodeWholeIfBase64(content)
        val proxies = links.mapNotNull(::parseShareLink)
        return ParsedSubscription(proxies, emptyList(), emptyList(), isClashConfig = false)
    }

    private fun decodeWholeIfBase64(content: String): List<String> {
        val cleaned = content.replace("\\s".toRegex(), "")
        val looksLikeLinks = content.contains("://")
        if (looksLikeLinks) {
            return content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        return runCatching {
            val decoded = String(java.util.Base64.getMimeDecoder().decode(cleaned))
            decoded.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }.getOrDefault(emptyList())
    }

    /** 解析单条分享链接为 mihomo proxies 条目 */
    fun parseShareLink(link: String): Map<String, Any>? = runCatching {
        when {
            link.startsWith("ss://") -> parseSs(link)
            link.startsWith("vmess://") -> parseVmess(link)
            link.startsWith("trojan://") -> parseTrojan(link)
            link.startsWith("vless://") -> parseVless(link)
            link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2(link)
            else -> null
        }
    }.getOrNull()

    /** vless://uuid@host:port?security=tls|reality&type=ws&flow=...&sni=&fp=&pbk=&sid=&path=#name */
    private fun parseVless(link: String): Map<String, Any>? {
        val body = link.removePrefix("vless://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(body.substring(hashIdx + 1), "UTF-8") else "VLESS节点"
        val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val userInfo = main.substringBefore("@").substringAfter("//")
        val rest = main.substringAfter("@")
        val hostPart = rest.substringBefore("?")
        val query = rest.substringAfter("?", "").takeIf { it.isNotEmpty() } ?: ""
        val params = query.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8") else null
        }.toMap()
        val host = hostPart.substringBeforeLast(":").trim('[', ']')
        val port = hostPart.substringAfterLast(":").trim(']').toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null

        val entry = linkedMapOf<String, Any>(
            "name" to name,
            "type" to "vless",
            "server" to host,
            "port" to port,
            "uuid" to URLDecoder.decode(userInfo, "UTF-8"),
            "udp" to true,
        )
        params["flow"]?.takeIf { it.isNotBlank() }?.let { entry["flow"] = it }
        when (params["security"]) {
            "tls" -> entry["tls"] = true
            "reality" -> {
                entry["tls"] = true
                val reality = linkedMapOf<String, Any>()
                params["pbk"]?.let { reality["public-key"] = it }
                params["sid"]?.let { reality["short-id"] = it }
                if (reality.isNotEmpty()) entry["reality-opts"] = reality
            }
        }
        params["sni"]?.takeIf { it.isNotBlank() }?.let { entry["servername"] = it }
        params["fp"]?.takeIf { it.isNotBlank() }?.let { entry["client-fingerprint"] = it }
        params["allowInsecure"]?.let { if (it == "1" || it == "true") entry["skip-cert-verify"] = true }
        when (params["type"]) {
            "ws" -> {
                entry["network"] = "ws"
                val wsOpts = linkedMapOf<String, Any>()
                params["path"]?.takeIf { it.isNotBlank() }?.let { wsOpts["path"] = it }
                params["host"]?.takeIf { it.isNotBlank() }?.let { wsOpts["headers"] = linkedMapOf<String, Any>("Host" to it) }
                if (wsOpts.isNotEmpty()) entry["ws-opts"] = wsOpts
            }
            "grpc" -> {
                entry["network"] = "grpc"
                params["serviceName"]?.takeIf { it.isNotBlank() }?.let {
                    entry["grpc-opts"] = linkedMapOf<String, Any>("grpc-service-name" to it)
                }
            }
        }
        return entry
    }

    /** hysteria2://password@host:port?sni=&insecure=&obfs=salamander&obfs-password=#name（hy2:// 同） */
    private fun parseHysteria2(link: String): Map<String, Any>? {
        val body = link.substringAfter("://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(body.substring(hashIdx + 1), "UTF-8") else "Hysteria2节点"
        val main = (if (hashIdx >= 0) body.substring(0, hashIdx) else body)
        val password = URLDecoder.decode(main.substringBefore("@").substringAfter("//"), "UTF-8")
        val rest = main.substringAfter("@")
        val hostPart = rest.substringBefore("?")
        val query = rest.substringAfter("?", "").takeIf { it.isNotEmpty() } ?: ""
        val params = query.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8") else null
        }.toMap()
        val host = hostPart.substringBeforeLast(":").trim('[', ']')
        val port = hostPart.substringAfterLast(":").trim(']').toIntOrNull() ?: 443
        if (host.isBlank()) return null

        val entry = linkedMapOf<String, Any>(
            "name" to name,
            "type" to "hysteria2",
            "server" to host,
            "port" to port,
            "password" to password,
            "udp" to true,
        )
        params["sni"]?.takeIf { it.isNotBlank() }?.let { entry["sni"] = it }
        if (params["insecure"] == "1" || params["insecure"] == "true") entry["skip-cert-verify"] = true
        params["obfs"]?.takeIf { it.isNotBlank() }?.let { entry["obfs"] = it }
        params["obfs-password"]?.takeIf { it.isNotBlank() }?.let { entry["obfs-password"] = it }
        return entry
    }

    private fun userInfo(base: String): Pair<String, String> {
        val info = base.substringAfter("://").substringBefore("@")
        val decoded = if (info.contains(":") && !info.contains("%")) info else String(java.util.Base64.getMimeDecoder().decode(info.pad()))
        val idx = decoded.indexOf(':')
        return decoded.substring(0, idx) to decoded.substring(idx + 1)
    }

    private fun String.pad(): String = this + "=".repeat((4 - length % 4) % 4)

    /** SIP002: ss://base64(method:pass)@host:port#name 或 ss://base64(method:pass@host:port)#name */
    private fun parseSs(link: String): Map<String, Any>? {
        val body = link.removePrefix("ss://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(body.substring(hashIdx + 1), "UTF-8") else "SS节点"
        val main = (if (hashIdx >= 0) body.substring(0, hashIdx) else body).substringBefore("?")
        return if (main.contains("@")) {
            val (method, pass) = userInfo(main)
            val hostPart = main.substringAfter("@")
            val host = hostPart.substringBeforeLast(":")
            val port = hostPart.substringAfterLast(":").toIntOrNull() ?: return null
            mapOf(
                "name" to name, "type" to "ss", "cipher" to method, "password" to pass,
                "server" to host, "port" to port, "udp" to true,
            )
        } else {
            val decoded = String(java.util.Base64.getMimeDecoder().decode(main.pad()))
            val method = decoded.substringBefore(":")
            val rest = decoded.substringAfter(":")
            val pass = rest.substringBefore("@")
            val hostPart = rest.substringAfter("@")
            val host = hostPart.substringBeforeLast(":")
            val port = hostPart.substringAfterLast(":").substringBefore("/?").toIntOrNull() ?: return null
            mapOf(
                "name" to name, "type" to "ss", "cipher" to method, "password" to pass,
                "server" to host, "port" to port, "udp" to true,
            )
        }
    }

    /** vmess://base64({v,ps,add,port,id,aid,net,type,host,path,tls}) */
    private fun parseVmess(link: String): Map<String, Any>? {
        val decoded = String(java.util.Base64.getMimeDecoder().decode(link.removePrefix("vmess://").pad()))
        val json = org.json.JSONObject(decoded)
        val port = when (val p = json.opt("port")) {
            is Number -> p.toInt()
            is String -> p.toIntOrNull() ?: return null
            else -> return null
        }
        val entry = linkedMapOf<String, Any>(
            "name" to (json.optString("ps").ifBlank { "VMess节点" }),
            "type" to "vmess",
            "server" to json.optString("add"),
            "port" to port,
            "uuid" to json.optString("id"),
            "alterId" to (json.optInt("aid", 0)),
            "cipher" to "auto",
            "udp" to true,
        )
        if (json.optString("tls") == "tls") entry["tls"] = true
        if (json.optString("net") == "ws") {
            entry["network"] = "ws"
            entry["ws-opts"] = linkedMapOf<String, Any>(
                "path" to json.optString("path", "/").ifBlank { "/" },
            )
        }
        return entry
    }

    /** trojan://password@host:port?sni=xx#name */
    private fun parseTrojan(link: String): Map<String, Any>? {
        val body = link.removePrefix("trojan://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) URLDecoder.decode(body.substring(hashIdx + 1), "UTF-8") else "Trojan节点"
        val main = (if (hashIdx >= 0) body.substring(0, hashIdx) else body)
        val pass = URLDecoder.decode(main.substringAfter("://").substringBefore("@"), "UTF-8")
        val hostPart = main.substringAfter("@").substringBefore("?")
        val host = hostPart.substringBeforeLast(":")
        val port = hostPart.substringAfterLast(":").toIntOrNull() ?: 443
        val query = main.substringAfter("?", "").takeIf { it.isNotEmpty() } ?: ""
        val params = query.split("&").mapNotNull {
            val i = it.indexOf('='); if (i > 0) it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8") else null
        }.toMap()
        return linkedMapOf(
            "name" to name, "type" to "trojan", "server" to host, "port" to port,
            "password" to pass, "udp" to true, "sni" to (params["sni"] ?: host),
        )
    }

    /** 去重：重名节点追加序号 */
    fun dedupeNames(proxies: List<Map<String, Any>>): List<Map<String, Any>> {
        val seen = HashMap<String, Int>()
        return proxies.map { p ->
            val name = p["name"]?.toString() ?: "节点"
            val n = seen.getOrDefault(name, 0)
            seen[name] = n + 1
            if (n == 0) p else p + ("name" to "$name ${n + 1}")
        }
    }
}
