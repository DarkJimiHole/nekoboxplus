package moe.matsuri.nb4a.po0

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Protocol and validation for a po0-compatible firewall API. */
object Po0Protocol {
    const val MAX_ENDPOINTS = 20
    const val MAX_RESPONSE_BYTES = 65536L
    const val MAX_URL_LENGTH = 4096
    const val MIN_SLOT = 0
    const val MAX_SLOT = 4

    /** A complete add endpoint. A null slot means ordinary, non-pinned insertion. */
    data class Endpoint(val url: String, val slot: Int?) {
        override fun toString() = "Endpoint([redacted], slot=$slot)"
    }

    enum class State { APPLIED, DISABLED, NOT_APPLIED, ACCESS_DENIED, HTTP_ERROR, INVALID_RESPONSE, NETWORK_ERROR }
    data class Result(val state: State, val ip: String? = null, val httpCode: Int? = null, val retryable: Boolean = false)

    /** Validate and normalize a user-provided complete API endpoint. */
    fun endpoint(input: String, slot: Int?): Endpoint {
        require(input.length <= MAX_URL_LENGTH) { "Invalid API address" }
        require(slot == null || slot in MIN_SLOT..MAX_SLOT) { "Invalid slot" }
        val url = input.trim().toHttpUrlOrNull()
        require(url != null && url.scheme == "https") { "Invalid API address" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Invalid API address" }
        require(url.queryParameterNames.none { it.equals("slot", ignoreCase = true) }) {
            "Slot must be configured separately"
        }
        return Endpoint(url.toString(), slot)
    }

    /** Add the selected slot using the API query parameter, never an @N suffix. */
    fun url(endpoint: Endpoint): HttpUrl {
        require(endpoint.slot == null || endpoint.slot in MIN_SLOT..MAX_SLOT) { "Invalid slot" }
        val url = endpoint.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid API address")
        return url.newBuilder().apply {
            removeAllQueryParameters("slot")
            endpoint.slot?.let { addQueryParameter("slot", it.toString()) }
        }.build()
    }

    /** Safe text for summaries and diagnostics; never contains the credential-bearing path or query. */
    fun displayOrigin(endpoint: Endpoint): String {
        val url = endpoint.url.toHttpUrlOrNull() ?: return "API"
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        return "${url.scheme}://$host$port"
    }

    private fun ipv4(value: String): List<Int>? {
        val parts = value.removeSuffix("/24").split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (!part.matches(Regex("[0-9]{1,3}"))) return null
            part.toInt().takeIf { it in 0..255 } ?: return null
        }
    }

    fun sameNetwork(a: String, b: String): Boolean {
        val first = ipv4(a) ?: return false
        val second = ipv4(b) ?: return false
        return if (a.endsWith("/24") || b.endsWith("/24")) first.take(3) == second.take(3)
        else first == second
    }

    fun response(code: Int, body: String, slot: Int?): Result {
        if (code == 401 || code == 403) return Result(State.ACCESS_DENIED, httpCode = code)
        val data = try { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }
        catch (_: Exception) { null }
        if (code !in 200..299) return Result(State.HTTP_ERROR, httpCode = code,
            retryable = code >= 500 || code == 429 || (code == 400 && data == null))
        if (data == null) return Result(State.INVALID_RESPONSE)
        return try {
            val enabled = data.getAsJsonPrimitive("enabled")
            if (enabled == null || !enabled.isBoolean) return Result(State.INVALID_RESPONSE)
            if (!enabled.asBoolean) return Result(State.DISABLED)
            val ip = data.getAsJsonPrimitive("currentIp")?.takeIf { it.isString }?.asString
                ?: return Result(State.INVALID_RESPONSE)
            if (ipv4(ip) == null) return Result(State.INVALID_RESPONSE)
            val whitelist = data.getAsJsonArray("whitelist") ?: return Result(State.INVALID_RESPONSE)
            val applied = whitelist.any { entry ->
                when {
                    entry.isJsonPrimitive && entry.asJsonPrimitive.isString ->
                        slot == null && sameNetwork(entry.asString, ip)
                    entry.isJsonObject -> {
                        val item = entry.asJsonObject
                        val address = item.getAsJsonPrimitive("ip")?.takeIf { it.isString }?.asString
                        val actualSlot = item.get("slot")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
                        address != null && sameNetwork(address, ip) && (slot == null || actualSlot == slot)
                    }
                    else -> false
                }
            }
            Result(if (applied) State.APPLIED else State.NOT_APPLIED, ip)
        } catch (_: Exception) { Result(State.INVALID_RESPONSE) }
    }
}
