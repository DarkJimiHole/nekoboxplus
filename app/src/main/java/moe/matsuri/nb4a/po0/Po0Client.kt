package moe.matsuri.nb4a.po0

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume

class Po0Client(private val client: OkHttpClient) {
    companion object {
        fun directClient(socketFactory: SocketFactory, dns: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
            .socketFactory(socketFactory)
            .dns(dns)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun add(endpoint: Po0Protocol.Endpoint): Po0Protocol.Result {
        var result = Po0Protocol.Result(Po0Protocol.State.NETWORK_ERROR, retryable = true)
        repeat(3) { attempt ->
            if (attempt > 0) delay(1500L * attempt)
            result = once(endpoint)
            if (!result.retryable) return result
        }
        return result
    }

    private suspend fun once(endpoint: Po0Protocol.Endpoint): Po0Protocol.Result = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(Po0Protocol.url(endpoint))
            .post("".toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Exception messages can contain the credential-bearing URL. Never persist/log them.
                continuation.resume(Po0Protocol.Result(Po0Protocol.State.NETWORK_ERROR, retryable = true))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    try {
                        val source = it.body?.source()
                        if (source == null || source.request(Po0Protocol.MAX_RESPONSE_BYTES + 1)) {
                            Po0Protocol.Result(Po0Protocol.State.INVALID_RESPONSE)
                        } else {
                            Po0Protocol.response(it.code, source.readUtf8(), endpoint.slot)
                        }
                    } catch (_: IOException) {
                        Po0Protocol.Result(Po0Protocol.State.NETWORK_ERROR, retryable = true)
                    }
                }
                continuation.resume(result)
            }
        })
    }
}
