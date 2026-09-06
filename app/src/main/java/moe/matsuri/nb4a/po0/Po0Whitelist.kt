package moe.matsuri.nb4a.po0

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.Dns
import java.text.DateFormat
import java.util.Date

/** Owned by the running proxy service, including in proxy-only mode. All state is confined to Main. */
class Po0Whitelist(private val context: Context) : AutoCloseable {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val firstPass = CompletableDeferred<Unit>()
    private var registered = false
    private var network: Network? = null
    private var addresses: String? = null
    private var validated = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(value: Network) {
            scope.launch {
                if (network != value) {
                    network = value
                    addresses = null
                    validated = false
                    requestRefresh()
                }
            }
        }

        override fun onLinkPropertiesChanged(value: Network, properties: LinkProperties) {
            val signature = properties.linkAddresses.map { it.toString() }.sorted().joinToString()
            scope.launch {
                if (network == value && addresses != signature) {
                    addresses = signature
                    requestRefresh()
                }
            }
        }

        override fun onCapabilitiesChanged(value: Network, capabilities: NetworkCapabilities) {
            val ready = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            scope.launch {
                if (network == value && validated != ready) {
                    validated = ready
                    if (ready) requestRefresh()
                }
            }
        }

        override fun onLost(value: Network) {
            scope.launch {
                if (network == value) {
                    network = null
                    addresses = null
                    requestRefresh() // collectLatest cancels requests bound to the lost network.
                }
            }
        }
    }

    suspend fun start() {
        scope.launch {
            triggers.receiveAsFlow().collectLatest {
                delay(800) // Coalesce availability, address and validation callbacks.
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    status(R.string.po0_network_error)
                    firstPass.complete(Unit)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(10 * 60 * 1000L)
                requestRefresh()
            }
        }
        requestRefresh()
        // Give whitelisting a chance before the proxy starts dialing a firewalled node.
        // A failed/slow API must not prevent the user from starting their proxy.
        if (DataStore.po0WhitelistEnabled) {
            withTimeoutOrNull(20_000) { firstPass.await() }
        }
    }

    fun requestRefresh() {
        triggers.trySend(Unit)
    }

    private fun status(resource: Int) {
        DataStore.po0WhitelistStatus = context.getString(resource)
    }

    private suspend fun refresh() {
        if (!DataStore.po0WhitelistEnabled) {
            unregister()
            status(R.string.po0_off)
            firstPass.complete(Unit)
            return
        }
        val endpoints = try { Po0Settings.readEndpoints(context) }
        catch (_: Exception) {
            unregister()
            status(R.string.po0_invalid_configuration)
            firstPass.complete(Unit)
            return
        }
        if (endpoints.isEmpty()) {
            unregister()
            status(R.string.po0_no_configurations)
            firstPass.complete(Unit)
            return
        }
        if (!registered) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
                // Request the best physical network, also on Android versions where the default is the VPN.
                connectivity.requestNetwork(request, callback)
                registered = true
            } catch (_: Exception) {
                status(R.string.po0_network_unavailable)
                firstPass.complete(Unit)
                return
            }
        }
        val current = network
        if (current == null || connectivity.getNetworkCapabilities(current)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true) {
            status(R.string.po0_waiting_network)
            return
        }
        status(R.string.po0_running)
        // Keep platform TLS and hostname validation, independently of proxy TLS preferences.
        // Bind both sockets and hostname lookup to the selected physical network.
        val client = Po0Client.directClient(current.socketFactory, Dns { host ->
            current.getAllByName(host).toList()
        })
        try {
            val api = Po0Client(client)
            val results = coroutineScope {
                endpoints.map { endpoint -> async { api.add(endpoint) } }.awaitAll()
            }
            currentCoroutineContext().ensureActive()
            val lines = results.mapIndexed { index, result ->
                val label = when (result.state) {
                    Po0Protocol.State.APPLIED -> R.string.po0_applied
                    Po0Protocol.State.DISABLED -> R.string.po0_firewall_disabled
                    Po0Protocol.State.NOT_APPLIED -> R.string.po0_not_applied
                    Po0Protocol.State.ACCESS_DENIED -> R.string.po0_access_denied
                    Po0Protocol.State.HTTP_ERROR -> R.string.po0_http_error
                    Po0Protocol.State.INVALID_RESPONSE -> R.string.po0_invalid_response
                    Po0Protocol.State.NETWORK_ERROR -> R.string.po0_network_error
                }
                val slot = endpoints[index].slot?.let {
                    " · ${context.getString(R.string.po0_fixed_slot, it)}"
                } ?: " · ${context.getString(R.string.po0_unpinned_slot)}"
                val ip = result.ip?.let { " · $it" }.orEmpty()
                val http = result.httpCode?.let { " (HTTP $it)" }.orEmpty()
                "#${index + 1}$slot: ${context.getString(label)}$ip$http"
            }
            DataStore.po0WhitelistStatus = DateFormat.getDateTimeInstance().format(Date()) + "\n" + lines.joinToString("\n")
            firstPass.complete(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status(R.string.po0_network_error)
            firstPass.complete(Unit)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun unregister() {
        if (registered) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            registered = false
        }
        network = null
        addresses = null
    }

    override fun close() {
        scope.cancel()
        triggers.close()
        unregister()
    }
}
