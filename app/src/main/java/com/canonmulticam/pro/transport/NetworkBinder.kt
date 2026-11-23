package com.canonmulticam.pro.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.SocketFactory

object NetworkBinder {

    @Volatile private var cachedWifiNetwork: Network? = null
    @Volatile private var cachedLocalIp: Inet4Address? = null
    @Volatile private var isSoftApMode: Boolean = false
    @Volatile private var lastRefreshMs: Long = 0L

    private val _localWifiIpFlow = MutableStateFlow<String?>(null)
    val localWifiIpFlow: StateFlow<String?> = _localWifiIpFlow.asStateFlow()

    fun init(context: Context) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cachedWifiNetwork = network
                    refreshLocalIp(force = true)
                }

                override fun onLost(network: Network) {
                    if (cachedWifiNetwork == network) cachedWifiNetwork = null
                    refreshLocalIp(force = true)
                }
            })
        }
        refreshLocalIp(force = true)
    }

    fun resolveLocalWifiAddress(forceRefresh: Boolean = false): Inet4Address? {
        val now = System.currentTimeMillis()
        if (forceRefresh || now - lastRefreshMs > 2500L) {
            refreshLocalIp(force = forceRefresh)
        }
        return cachedLocalIp
    }

    @Synchronized
    @Suppress("ForbiddenMethodCall")
    private fun refreshLocalIp(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs <= 2500L) return
        lastRefreshMs = now

        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
        }.getOrNull() ?: return

        val (apCandidates, wlanCandidates) = classifyInterfaceAddresses(interfaces)

        val resolved = when {
            apCandidates.isNotEmpty() -> {
                isSoftApMode = true
                apCandidates.first()
            }
            wlanCandidates.isNotEmpty() -> {
                isSoftApMode = false
                wlanCandidates.first()
            }
            else -> {
                isSoftApMode = false
                null
            }
        }
        cachedLocalIp = resolved
        _localWifiIpFlow.value = resolved?.hostAddress
    }

    private fun classifyInterfaceAddresses(
        interfaces: List<NetworkInterface>
    ): Pair<List<Inet4Address>, List<Inet4Address>> {
        val apCandidates = mutableListOf<Inet4Address>()
        val wlanCandidates = mutableListOf<Inet4Address>()

        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (isCellularInterface(name)) continue

            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    when {
                        name.startsWith("ap") || name.startsWith("swlan") -> apCandidates.add(addr)
                        name.startsWith("wlan") -> wlanCandidates.add(addr)
                    }
                }
            }
        }
        return apCandidates to wlanCandidates
    }

    private fun isCellularInterface(name: String): Boolean =
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")

    private fun isLoopbackTarget(host: String): Boolean {
        val clean = host.trim().lowercase()
        return clean == "127.0.0.1" || clean == "localhost" || clean == "::1"
    }

    fun createBoundSocketFactory(timeoutMs: Int = 1000): SocketFactory {
        return object : SocketFactory() {
            private fun bindSocketSafe(socket: Socket, targetHost: String? = null) {
                socket.tcpNoDelay = true
                if (targetHost != null && isLoopbackTarget(targetHost)) return

                if (!isSoftApMode) {
                    cachedWifiNetwork?.let { net -> runCatching { net.bindSocket(socket) } }
                }
                val localIp = resolveLocalWifiAddress()
                if (localIp != null && !socket.isBound) {
                    runCatching { socket.bind(InetSocketAddress(localIp, 0)) }
                }
            }

            override fun createSocket(): Socket = Socket().apply { bindSocketSafe(this) }

            override fun createSocket(host: String, port: Int): Socket =
                Socket().apply {
                    bindSocketSafe(this, host)
                    connect(InetSocketAddress(host, port), timeoutMs)
                }

            override fun createSocket(
                host: String,
                port: Int,
                localHost: InetAddress,
                localPort: Int
            ): Socket {
                val bindHost = if (isLoopbackTarget(host)) localHost
                else (resolveLocalWifiAddress() ?: localHost)
                return Socket(host, port, bindHost, localPort).apply {
                    tcpNoDelay = true
                    if (!isLoopbackTarget(host) && !isSoftApMode) {
                        cachedWifiNetwork?.let { runCatching { it.bindSocket(this) } }
                    }
                }
            }

            override fun createSocket(host: InetAddress, port: Int): Socket =
                Socket().apply {
                    bindSocketSafe(this, host.hostAddress)
                    connect(InetSocketAddress(host, port), timeoutMs)
                }

            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int
            ): Socket {
                val isLoop = address.isLoopbackAddress
                val bindHost = if (isLoop) localAddress
                else (resolveLocalWifiAddress() ?: localAddress)
                return Socket(address, port, bindHost, localPort).apply {
                    tcpNoDelay = true
                    if (!isLoop && !isSoftApMode) {
                        cachedWifiNetwork?.let { runCatching { it.bindSocket(this) } }
                    }
                }
            }
        }
    }
}
