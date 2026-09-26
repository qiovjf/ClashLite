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
            else -> null
        }
    }.getOrNull()

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
