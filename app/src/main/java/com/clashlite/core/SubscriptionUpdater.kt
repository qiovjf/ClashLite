package com.clashlite.core

import android.content.Context
import com.clashlite.data.ProfilesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 订阅抓取与刷新（VM 与 WorkManager 共用） */
object SubscriptionUpdater {

    /** 抓取订阅：返回 内容 + subscription-userinfo 解析结果（upload/download/total/expire） */
    fun fetchWithInfo(url: String): Pair<String, Map<String, Long>>? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "clash-lite/2.0")
        val content = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val infoHeader = conn.headerFields["subscription-userinfo"]?.firstOrNull()
        val info = mutableMapOf<String, Long>()
        infoHeader?.split(";")?.forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) {
                val k = part.substring(0, i).trim()
                val v = part.substring(i + 1).trim().toLongOrNull()
                if (v != null) info[k] = v
            }
        }
        content to info
    }.getOrNull()

    /** 刷新单个订阅（下载、解析、落盘、更新流量信息）。返回是否成功 */
    suspend fun refreshProfile(context: Context, profileId: String, url: String): Boolean =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext false
            val (content, info) = fetchWithInfo(url) ?: return@withContext false
            val parsed = SubscriptionParser.parse(content)
            val repo = ProfilesRepository(context)
            repo.updateProfileContent(profileId, content, parsed.proxies.size)
            if (info.isNotEmpty()) {
                repo.updateProfileTraffic(
                    profileId,
                    info["upload"] ?: 0L,
                    info["download"] ?: 0L,
                    info["total"] ?: 0L,
                    info["expire"] ?: 0L,
                )
            }
            true
        }
}
