package com.clashlite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 鐢ㄧ湡鏈烘姄鍙栫殑 /proxies 鍝嶅簲澶嶇幇浠ｇ悊缁勮В鏋愰€昏緫 */
class GroupsParseTest {

    private data class ProxyGroup(val name: String, val type: String, val now: String, val nodes: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseGroups(raw: String): List<ProxyGroup> {
        val root = json.parseToJsonElement(raw).jsonObject
        // 鍝嶅簲鏍煎紡涓?{"proxies": {name: {...}}}锛屽彇鍐呭眰瀵硅薄
        val proxies = root["proxies"]?.jsonObject ?: root
        return proxies.map { (_, element) ->
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

    @Test
    fun `parse real device proxies response`() {
        val raw = javaClass.classLoader!!.getResourceAsStream("px.json")!!.readBytes().toString(Charsets.UTF_8)
        val root = json.parseToJsonElement(raw).jsonObject
        println("total entries: ${root.size}")
        val types = root.values.map { el ->
            val obj = el.jsonObject
            val t = obj["type"]?.jsonPrimitive?.content
            t
        }
        println("types: ${types.groupingBy { it }.eachCount()}")
        // 妫€鏌ヤ笁姣涙満鍦虹殑瀹屾暣缁撴瀯
        val sm = root["涓夋瘺鏈哄満"]?.jsonObject
        println("涓夋瘺鏈哄満 keys: ${sm?.keys}")
        val smType = sm?.get("type")
        println("涓夋瘺鏈哄満 type: $smType")
        val groups = parseGroups(raw)
        println("groups: ${groups.map { it.name + '(' + it.type + ',' + it.nodes.size + ')' }}")
        assertTrue(groups.isNotEmpty())
    }
}

