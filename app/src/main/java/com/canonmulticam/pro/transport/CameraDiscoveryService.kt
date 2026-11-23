package com.canonmulticam.pro.transport

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DiscoveredCamera(
    val uuid: String,
    val ipAddress: String,
    val port: Int = 8080,
    val modelName: String,
    val ccapiBaseUrl: String,
    val lastSeenMs: Long = System.currentTimeMillis()
)

@Suppress("TooManyFunctions")
class CameraDiscoveryService(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discoveryJob: Job? = null
    private val foundMap = ConcurrentHashMap<String, DiscoveredCamera>()

    private val sharedProbeClient: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(NetworkBinder.createBoundSocketFactory(650))
        .connectionPool(ConnectionPool(4, 15, TimeUnit.SECONDS))
        .connectTimeout(650, TimeUnit.MILLISECONDS)
        .readTimeout(650, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    companion object {
        private const val SSDP_MULTICAST_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val CANON_CCAPI_ST =
            "urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1"
        private const val DEFAULT_CCAPI_PORT = 8080
        private const val PTP_IP_PORT = 15740
    }

    fun startDiscovery(
        connectedCountProvider: () -> Int = { 0 },
        onCameraFound: ((DiscoveredCamera) -> Unit)? = null
    ) {
        if (discoveryJob?.isActive == true) return
        acquireMulticastLock()

        discoveryJob = scope.launch {
            while (isActive) {
                val connected = connectedCountProvider()
                if (connected >= 4) {
                    delay(6000L)
                    continue
                }

                val localIp = NetworkBinder.resolveLocalWifiAddress(forceRefresh = true)
                if (localIp != null) {
                    val scanConcurrency = if (connected == 0) 24 else 6
                    coroutineScope {
                        launch { runSsdpDiscoveryCycle(localIp, onCameraFound) }
                        launch { runSubnetFallbackScan(localIp, scanConcurrency, onCameraFound) }
                    }
                }
                delay(if (connected == 0) 4000L else 18000L)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        releaseMulticastLock()
    }

    private suspend fun runSsdpDiscoveryCycle(
        localIp: Inet4Address,
        onCameraFound: ((DiscoveredCamera) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        var socket: MulticastSocket? = null
        try {
            val netIf = NetworkInterface.getByInetAddress(localIp)
            socket = MulticastSocket(InetSocketAddress(localIp, 0)).apply {
                if (netIf != null) networkInterface = netIf
                soTimeout = 1000
                timeToLive = 2
            }

            val groupAddr = InetAddress.getByName(SSDP_MULTICAST_IP)
            for (st in listOf(CANON_CCAPI_ST, "ssdp:all")) {
                val query = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_MULTICAST_IP:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $st\r\n\r\n")
                }.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(query, query.size, groupAddr, SSDP_PORT))
            }

            val receiveBuf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 1800L

            while (isActive && System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(receiveBuf, receiveBuf.size)
                    socket.receive(packet)
                    val rawText = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val senderIp = packet.address?.hostAddress ?: continue

                    if (rawText.contains("canon", ignoreCase = true) ||
                        rawText.contains("ccapi", ignoreCase = true)
                    ) {
                        val headers = parseSsdpHeaders(rawText)
                        val parsedUri = headers["location"]?.let {
                            runCatching { URI(it) }.getOrNull()
                        }
                        val targetIp = parsedUri?.host ?: senderIp
                        val targetPort = if (parsedUri != null && parsedUri.port > 0) {
                            parsedUri.port
                        } else {
                            DEFAULT_CCAPI_PORT
                        }
                        verifyAndRegisterCamera(
                            targetIp,
                            targetPort,
                            extractUuidFromUsn(headers["usn"]),
                            onCameraFound
                        )
                    }
                } catch (_: SocketTimeoutException) { }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket?.close() }
        }
    }

    private suspend fun runSubnetFallbackScan(
        localIp: Inet4Address,
        concurrency: Int,
        onCameraFound: ((DiscoveredCamera) -> Unit)?
    ) = coroutineScope {
        val bytes = localIp.address
        val prefix = "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}." +
            "${bytes[2].toInt() and 0xFF}"
        val selfHost = bytes[3].toInt() and 0xFF
        val limiter = Semaphore(concurrency)

        (1..254).filter { it != selfHost }.map { host ->
            async(Dispatchers.IO) {
                limiter.withPermit {
                    val candidateIp = "$prefix.$host"
                    if (isTcpPortOpen(candidateIp, DEFAULT_CCAPI_PORT, 150)) {
                        verifyAndRegisterCamera(
                            candidateIp, DEFAULT_CCAPI_PORT, null, onCameraFound
                        )
                    } else if (isTcpPortOpen(candidateIp, PTP_IP_PORT, 150)) {
                        registerPtpOnlyCamera(candidateIp, onCameraFound)
                    }
                }
            }
        }.awaitAll()
    }

    private fun verifyAndRegisterCamera(
        ip: String,
        port: Int,
        fallbackUuid: String?,
        onCameraFound: ((DiscoveredCamera) -> Unit)?
    ) {
        val baseUrl = "http://$ip:$port/ccapi"
        try {
            val req = Request.Builder().url("$baseUrl/ver100/deviceinformation").get().build()
            sharedProbeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val json = JSONObject(resp.body?.string() ?: return)
                val serial = json.optString("serialnumber").ifBlank { fallbackUuid ?: ip }
                val productName = json.optString("productname", "Canon EOS R")

                val camera = DiscoveredCamera(serial, ip, port, productName, baseUrl)
                val changed = foundMap[serial]?.ipAddress != ip
                foundMap[serial] = camera
                if (changed) onCameraFound?.invoke(camera)
            }
        } catch (_: Exception) { }
    }

    private fun registerPtpOnlyCamera(
        ip: String,
        onCameraFound: ((DiscoveredCamera) -> Unit)?
    ) {
        val key = "ptp_$ip"
        val camera = DiscoveredCamera(key, ip, PTP_IP_PORT, "Canon Cinema/PTP", "")
        val changed = foundMap[key]?.ipAddress != ip
        foundMap[key] = camera
        if (changed) onCameraFound?.invoke(camera)
    }

    private fun isTcpPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            NetworkBinder.createBoundSocketFactory(timeoutMs).createSocket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseSsdpHeaders(raw: String): Map<String, String> = buildMap {
        raw.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                put(line.substring(0, idx).trim().lowercase(), line.substring(idx + 1).trim())
            }
        }
    }

    private fun extractUuidFromUsn(usn: String?): String? {
        val raw = usn ?: return null
        val idx = raw.indexOf("uuid:", ignoreCase = true)
        if (idx == -1) return null
        return raw.substring(idx + 5).substringBefore("::").trim().ifBlank { null }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("CanonMultiCam_SSDP_Lock").apply {
                setReferenceCounted(false)
            }
        }
        if (multicastLock?.isHeld == false) multicastLock?.acquire()
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) multicastLock?.release()
    }

    fun destroy() {
        stopDiscovery()
        sharedProbeClient.dispatcher.executorService.shutdownNow()
        sharedProbeClient.connectionPool.evictAll()
        scope.cancel()
    }
}
