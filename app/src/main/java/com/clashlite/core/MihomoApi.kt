package com.clashlite.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 代理组信息 */
data class ProxyGroup(
    val name: String,
    val type: String,
    val now: String,
    val nodes: List<String>,
)

/** 节点信息 */
data class ProxyNode(
    val name: String,
    val type: String,
    val delay: Int?,
)

/** mihomo RESTful API 客户端（127.0.0.1:9090） */
class MihomoApi(
    private val port: Int = CONTROLLER_PORT,
    /** 测速链接（基础配置页可改） */
    var delayUrl: String = "https://www.gstatic.com/generate_204",
    /** 本地混合代理端口（IP 检测经它转发以反映真实出口） */
    var mixedPort: Int = 7890,
) {

    companion object {
        const val CONTROLLER_PORT = 9090
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val baseUrl get() = "http://127.0.0.1:$port"

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(baseUrl + path).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.string() ?: ""
        }
    }

    private suspend fun put(path: String, body: String) = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(baseUrl + path)
                .put(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
        }
    }

    suspend fun version(): String = runCatching {
        json.parseToJsonElement(get("/version")).jsonObject["version"]?.jsonPrimitive?.content ?: "?"
    }.getOrDefault("?")

    /** 原始 /version 响应；失败时抛出详细异常（用于诊断） */
    suspend fun versionRaw(): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("$baseUrl/version").build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $body")
            body
        }
    }

    /**
     * 订阅 /traffic 实时速率流。
     * 注意：mihomo 的 /traffic 是流式接口，每秒推送一行 JSON 且不会结束，
     * 必须持续读行，不能用一次性 body.string()（会一直阻塞到超时）。
     * 挂起直到连接断开（内核停止）或调用方协程取消。
     */
    suspend fun trafficStream(onSample: (up: Long, down: Long) -> Unit) = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url("$baseUrl/traffic").build())
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val source = resp.body?.source() ?: return@use
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    runCatching {
                        val t = json.decodeFromString(Traffic.serializer(), line)
                        onSample(t.up, t.down)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (_: Exception) {
            // 连接断开（内核停止/重启），正常退出
        }
    }

    @kotlinx.serialization.Serializable
    private data class Traffic(val up: Long, val down: Long)

    suspend fun groups(): List<ProxyGroup> = withContext(Dispatchers.IO) {
        val root = json.parseToJsonElement(get("/proxies")).jsonObject
        // 响应格式为 {"proxies": {name: {...}}}，取内层对象
        val proxies = root["proxies"]?.jsonObject ?: root
        proxies.map { (_, element) ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return@map null
            if (type !in setOf("Selector", "URLTest", "Fallback", "LoadBalance")) return@map null
            ProxyGroup(
                name = obj["name"]?.jsonPrimitive?.content ?: return@map null,
                type = type,
                now = obj["now"]?.jsonPrimitive?.content ?: "",
                nodes = obj["all"]?.let { arr ->
                    arr.toString().removeSurrounding("[", "]").split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
                } ?: emptyList(),
            )
        }.filterNotNull()
    }

    /** 全部代理条目的 name -> type 映射（含普通节点与组），用于卡片类型标签 */
    suspend fun nodeTypes(): Map<String, String> = withContext(Dispatchers.IO) {
        val root = json.parseToJsonElement(get("/proxies")).jsonObject
        val proxies = root["proxies"]?.jsonObject ?: root
        proxies.map { (name, element) ->
            val type = element.jsonObject["type"]?.jsonPrimitive?.content
            if (type != null) name to type else null
        }.filterNotNull().toMap()
    }

    /** 分组并发测速：返回 成功节点 -> 延迟(ms)。超时节点不在结果中 */
    suspend fun testGroupDelay(group: String): Map<String, Int> = withContext(Dispatchers.IO) {
        val g = URLEncoder.encode(group, "UTF-8")
        val body = get("/group/$g/delay?timeout=5000&url=${URLEncoder.encode(delayUrl, "UTF-8")}")
        json.parseToJsonElement(body).jsonObject.mapNotNull { (node, element) ->
            element.jsonPrimitive.content.toIntOrNull()?.let { node to it }
        }.toMap()
    }

    /** 单节点测速 */
    suspend fun testDelay(node: String): Int? = runCatching {
        val n = URLEncoder.encode(node, "UTF-8")
        val body = get("/proxies/$n/delay?timeout=5000&url=${URLEncoder.encode(delayUrl, "UTF-8")}")
        json.parseToJsonElement(body).jsonObject["delay"]?.jsonPrimitive?.content?.toIntOrNull()
    }.getOrNull()

    /** 热更运行模式 rule/global/direct */
    suspend fun patchMode(mode: String): Boolean = patchConfigs("""{"mode":"$mode"}""")

    /** 热更日志等级 */
    suspend fun patchLogLevel(level: String): Boolean = patchConfigs("""{"log-level":"$level"}""")

    private suspend fun patchConfigs(body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder().url("$baseUrl/configs")
                    .patch(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    /** 出口 IP 信息（请求经本地混合端口 SOCKS5 走 mihomo，反映用户真实出口） */
    data class IpInfo(val ip: String, val countryCode: String, val country: String)

    suspend fun exitIpInfo(): IpInfo? = withContext(Dispatchers.IO) {
        val socksClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", mixedPort)))
            .build()
        runCatching {
            val resp = socksClient.newCall(
                Request.Builder().url("http://ip-api.com/json?fields=status,country,countryCode,query").build()
            ).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                r.body?.string() ?: ""
            }
            val obj = json.parseToJsonElement(resp).jsonObject
            val status = obj["status"]?.jsonPrimitive?.content
            if (status != "success") return@runCatching null
            IpInfo(
                ip = obj["query"]?.jsonPrimitive?.content ?: "",
                countryCode = obj["countryCode"]?.jsonPrimitive?.content ?: "",
                country = obj["country"]?.jsonPrimitive?.content ?: "",
            )
        }.getOrNull() ?: runCatching {
            // 备用源：ipinfo.io（HTTPS）
            val resp = socksClient.newCall(
                Request.Builder().url("https://ipinfo.io/json").build()
            ).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                r.body?.string() ?: ""
            }
            val obj = json.parseToJsonElement(resp).jsonObject
            IpInfo(
                ip = obj["ip"]?.jsonPrimitive?.content ?: "",
                countryCode = obj["country"]?.jsonPrimitive?.content ?: "",
                country = "",
            )
        }.getOrNull()
    }

    suspend fun selectNode(group: String, node: String) {
        val g = URLEncoder.encode(group, "UTF-8")
        put("/proxies/$g", """{"name":${kotlinx.serialization.json.JsonPrimitive(node)}}""")
    }

    // ── 连接监控（v2.0）──

    /** 单条活动连接 */
    data class ConnectionItem(
        val id: String,
        val network: String,
        val host: String,
        val destination: String,
        val rule: String,
        val chains: String,
        val process: String,
        val upload: Long,
        val download: Long,
    )

    data class ConnectionsSnapshot(
        val uploadTotal: Long,
        val downloadTotal: Long,
        val items: List<ConnectionItem>,
    )

    /** 获取活动连接列表（含累计流量） */
    suspend fun connections(): ConnectionsSnapshot = withContext(Dispatchers.IO) {
        val root = json.parseToJsonElement(get("/connections")).jsonObject
        val items = mutableListOf<ConnectionItem>()
        root["connections"]?.let { el ->
            val connArr = el as? kotlinx.serialization.json.JsonArray ?: return@let
            connArr.forEach { c ->
                val obj = c.jsonObject
                val meta = obj["metadata"]?.jsonObject
                fun s(key: String) = meta?.get(key)?.jsonPrimitive?.content ?: ""
                fun l(key: String) = obj[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                items.add(
                    ConnectionItem(
                        id = obj["id"]?.jsonPrimitive?.content ?: "",
                        network = s("network"),
                        host = s("host").ifBlank { s("destinationIP") },
                        destination = s("destinationIP") + ":" + s("destinationPort"),
                        rule = (obj["rule"]?.jsonPrimitive?.content ?: "") +
                            (obj["rulePayload"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""),
                        chains = obj["chains"]?.toString()?.removeSurrounding("[", "]")
                            ?.split(",")?.map { it.trim().removeSurrounding("\"") }?.reversed()?.joinToString("→") ?: "",
                        process = s("process"),
                        upload = l("upload"),
                        download = l("download"),
                    )
                )
            }
        }
        ConnectionsSnapshot(
            uploadTotal = root["uploadTotal"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            downloadTotal = root["downloadTotal"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            items = items,
        )
    }

    /** 断开单条连接 */
    suspend fun closeConnection(id: String) = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/connections/${URLEncoder.encode(id, "UTF-8")}").delete().build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** 断开全部连接 */
    suspend fun closeAllConnections() = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/connections").delete().build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
