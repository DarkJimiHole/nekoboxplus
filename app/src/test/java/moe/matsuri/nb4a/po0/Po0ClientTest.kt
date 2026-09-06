package moe.matsuri.nb4a.po0

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class Po0ClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val endpoint = Po0Protocol.Endpoint("https://api.example.com/firewall/pgnfw_test/add", 0)
    private val success = """{"enabled":true,"currentIp":"1.2.3.4","whitelist":[{"ip":"1.2.3.0/24","slot":0}]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = Po0Client.directClient(SocketFactory.getDefault()).newBuilder()
            // Every test request is redirected locally before any socket or DNS lookup occurs.
            .addInterceptor { chain ->
                val original = chain.request().url
                val url = server.url(original.encodedPath).newBuilder().encodedQuery(original.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }.build()
    }

    @After fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test fun postsAnEmptyJsonBodyWithApiSlotParameter() = runBlocking {
        server.enqueue(MockResponse().setBody(success))
        assertEquals(Po0Protocol.State.APPLIED, Po0Client(client).add(endpoint).state)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/firewall/pgnfw_test/add?slot=0", request.path)
        assertEquals(0L, request.bodySize)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun transientFailureIsRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Error"))
        server.enqueue(MockResponse().setBody(success))
        assertEquals(Po0Protocol.State.APPLIED, Po0Client(client).add(endpoint).state)
        assertEquals(2, server.requestCount)
    }

    @Test fun slotConflictIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        assertEquals(Po0Protocol.State.ACCESS_DENIED, Po0Client(client).add(endpoint).state)
        assertEquals(1, server.requestCount)
    }

    @Test fun credentialsAreNotForwardedOnRedirect() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/leak")))
        assertEquals(Po0Protocol.State.HTTP_ERROR, Po0Client(client).add(endpoint).state)
        assertEquals(1, server.requestCount)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(Proxy.NO_PROXY, client.proxy)
    }

    @Test fun oversizedResponseIsRejected() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(65537)))
        assertEquals(Po0Protocol.State.INVALID_RESPONSE, Po0Client(client).add(endpoint).state)
    }

    @Test fun cancellationClosesTheInFlightCall() = runBlocking {
        val cancelled = CountDownLatch(1)
        client = client.newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        server.enqueue(MockResponse().setBody(success).setBodyDelay(2, TimeUnit.SECONDS))
        val job = launch(Dispatchers.Default) { Po0Client(client).add(endpoint) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }
}
