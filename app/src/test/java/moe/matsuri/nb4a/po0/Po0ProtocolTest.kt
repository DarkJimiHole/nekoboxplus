package moe.matsuri.nb4a.po0

import org.junit.Assert.*
import org.junit.Test

class Po0ProtocolTest {
    @Test fun validatesCompleteHttpsEndpointsAndSeparateSlots() {
        val endpoint = Po0Protocol.endpoint(
            " https://api.example.com/firewall/pgnfw_secret/add?source=android ", 0
        )
        assertEquals("https://api.example.com/firewall/pgnfw_secret/add?source=android", endpoint.url)
        assertEquals(0, endpoint.slot)

        val url = Po0Protocol.url(endpoint)
        assertEquals("api.example.com", url.host)
        assertEquals("/firewall/pgnfw_secret/add", url.encodedPath)
        assertEquals("android", url.queryParameter("source"))
        assertEquals("0", url.queryParameter("slot"))
        assertNull(Po0Protocol.url(Po0Protocol.endpoint("https://api.example.com/add", null))
            .queryParameter("slot"))
    }

    @Test fun rejectsUnsafeOrAmbiguousEndpointInput() {
        listOf(
            "", "not a url", "http://api.example.com/add", "https://user:pass@example.com/add",
            "https://api.example.com/add#fragment", "https://api.example.com/add?slot=0",
            "https://api.example.com/add?SLOT=0"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Po0Protocol.endpoint(value, null) }
        }
        listOf(-1, 5, Int.MAX_VALUE).forEach { slot ->
            assertThrows(IllegalArgumentException::class.java) {
                Po0Protocol.endpoint("https://api.example.com/add", slot)
            }
        }
    }

    @Test fun summariesAndObjectsNeverRevealCredentialPaths() {
        val endpoint = Po0Protocol.Endpoint("https://api.example.com/pgnfw_secret/add?key=also-secret", 1)
        assertEquals("https://api.example.com", Po0Protocol.displayOrigin(endpoint))
        assertFalse(endpoint.toString().contains("secret"))
    }

    @Test fun acceptsLegacyExactIpAndCidrResponses() {
        assertEquals(Po0Protocol.State.APPLIED, result("""{"enabled":true,"currentIp":"1.2.3.4","whitelist":["1.2.3.4"]}""").state)
        assertEquals(Po0Protocol.State.APPLIED, result("""{"enabled":true,"currentIp":"1.2.3.0/24","whitelist":[{"ip":"1.2.3.4","slot":null}]}""").state)
        assertFalse(Po0Protocol.sameNetwork("1.2.3.4", "1.2.3.5"))
        assertFalse(Po0Protocol.sameNetwork("1.2.3.0/24", "1.2.4.5"))
        assertFalse(Po0Protocol.sameNetwork("999.2.3.0/24", "999.2.3.4"))
    }

    @Test fun requiresTheRequestedSlot() {
        val body = """{"enabled":true,"currentIp":"1.2.3.4","whitelist":[{"ip":"1.2.3.0/24","slot":0}]}"""
        assertEquals(Po0Protocol.State.APPLIED, result(body, 0).state)
        assertEquals(Po0Protocol.State.NOT_APPLIED, result(body, 1).state)
    }

    @Test fun neverTreatsHttp200AloneAsSuccess() {
        listOf("{}", "null", "Error", "[]", """{"enabled":"true"}""",
            """{"enabled":true,"currentIp":"not an IP","whitelist":[]}""").forEach {
            assertEquals(Po0Protocol.State.INVALID_RESPONSE, result(it).state)
        }
        assertEquals(Po0Protocol.State.DISABLED, result("""{"enabled":false}""").state)
        assertEquals(Po0Protocol.State.NOT_APPLIED, result("""{"enabled":true,"currentIp":"1.2.3.4","whitelist":[]}""").state)
    }

    @Test fun distinguishesPermanentAndTransientErrors() {
        assertFalse(Po0Protocol.response(403, "Error", null).retryable)
        assertFalse(Po0Protocol.response(401, "Error", null).retryable)
        assertFalse(Po0Protocol.response(400, """{"error":"invalid token"}""", null).retryable)
        assertTrue(Po0Protocol.response(400, "Error", null).retryable)
        assertTrue(Po0Protocol.response(503, "Error", null).retryable)
        assertTrue(Po0Protocol.response(429, "{}", null).retryable)
        assertFalse(Po0Protocol.response(302, "", null).retryable)
    }

    private fun result(body: String, slot: Int? = null) = Po0Protocol.response(200, body, slot)
}
