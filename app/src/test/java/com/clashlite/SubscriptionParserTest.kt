package com.clashlite

import com.clashlite.core.ConfigGenerator
import com.clashlite.core.SubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParserTest {

    @Test
    fun `parse clash yaml subscription`() {
        val yaml = """
            port: 7890
            proxies:
              - name: "节点A"
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: "pw"
            proxy-groups:
              - name: PROXY
                type: select
                proxies: [节点A]
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val parsed = SubscriptionParser.parse(yaml)
        assertTrue(parsed.isClashConfig)
        assertEquals(1, parsed.proxies.size)
        assertEquals("节点A", parsed.proxies[0]["name"])
        assertEquals(1, parsed.groups.size)
        assertEquals(1, parsed.rules.size)
    }

    @Test
    fun `parse sip002 ss link`() {
        // aes-256-gcm:test -> base64
        val userInfo = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:test123".toByteArray())
        val link = "ss://$userInfo@1.2.3.4:8388#My%20Node"
        val parsed = SubscriptionParser.parse(link)
        assertEquals(1, parsed.proxies.size)
        val proxy = parsed.proxies[0]
        assertEquals("My Node", proxy["name"])
        assertEquals("ss", proxy["type"])
        assertEquals("aes-256-gcm", proxy["cipher"])
        assertEquals("test123", proxy["password"])
        assertEquals("1.2.3.4", proxy["server"])
        assertEquals(8388, proxy["port"])
    }

    @Test
    fun `parse whole base64 subscription of ss links`() {
        val userInfo = java.util.Base64.getEncoder()
            .encodeToString("aes-256-gcm:test123".toByteArray())
        val links = "ss://$userInfo@1.1.1.1:443#Node1\nss://$userInfo@2.2.2.2:443#Node2"
        val whole = java.util.Base64.getEncoder().encodeToString(links.toByteArray())
        val parsed = SubscriptionParser.parse(whole)
        assertEquals(2, parsed.proxies.size)
    }

    @Test
    fun `parse vmess link`() {
        val vmessJson = """{"v":"2","ps":"vmess节点","add":"5.6.7.8","port":"443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"ws","host":"","path":"/path","tls":"tls"}"""
        val link = "vmess://" + java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val parsed = SubscriptionParser.parse(link)
        assertEquals(1, parsed.proxies.size)
        val proxy = parsed.proxies[0]
        assertEquals("vmess", proxy["type"])
        assertEquals("5.6.7.8", proxy["server"])
        assertEquals(443, proxy["port"])
        assertEquals(true, proxy["tls"])
        assertEquals("ws", proxy["network"])
    }

    @Test
    fun `parse trojan link`() {
        val link = "trojan://pass%40word@9.9.9.9:443?sni=example.com#Trojan节点"
        val parsed = SubscriptionParser.parse(link)
        val proxy = parsed.proxies[0]
        assertEquals("trojan", proxy["type"])
        assertEquals("pass@word", proxy["password"])
        assertEquals("9.9.9.9", proxy["server"])
        assertEquals("example.com", proxy["sni"])
    }

    @Test
    fun `duplicate names get deduped`() {
        val p = mapOf("name" to "A", "type" to "ss")
        val out = SubscriptionParser.dedupeNames(listOf(p, p, p))
        assertEquals(listOf("A", "A 2", "A 3"), out.map { it["name"] })
    }
}

class ConfigGeneratorTest {

    @Test
    fun `generates runnable config from bare nodes`() {
        val userInfo = java.util.Base64.getEncoder().encodeToString("aes-256-gcm:pw".toByteArray())
        val parsed = SubscriptionParser.parse("ss://$userInfo@1.2.3.4:8388#X")
        val config = ConfigGenerator.generate(parsed)
        assertTrue(config.contains("mixed-port: 7890"))
        assertTrue(config.contains("external-controller: 127.0.0.1:9090"))
        assertTrue(config.contains("PROXY"))
        assertTrue(config.contains("MATCH,PROXY"))
        // 必须是合法 YAML
        val yaml = org.yaml.snakeyaml.Yaml()
        @Suppress("UNCHECKED_CAST")
        val root = yaml.load<Map<String, Any>>(config)
        assertTrue(root.containsKey("proxies"))
        assertTrue(root.containsKey("dns"))
        assertEquals(7890, root["mixed-port"])
    }
}
