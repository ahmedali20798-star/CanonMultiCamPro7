package com.canonmulticam.pro.transport

import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.ProtocolType
import com.canonmulticam.pro.domain.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.roundToInt

@Suppress("TooManyFunctions")
class CcapiCameraDriver(
    override val ipAddress: String,
    private val port: Int = 8080
) : ICameraDriver {

    private val baseUrl = "http://$ipAddress:$port/ccapi"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val priorityMutex = Mutex()
    private val telemetryMutex = Mutex()
    private val boundFactory = NetworkBinder.createBoundSocketFactory(timeoutMs = 1000)

    private val priorityControlClient: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(boundFactory)
        .connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS))
        .dispatcher(Dispatcher(Executors.newSingleThreadExecutor()))
        .connectTimeout(750, TimeUnit.MILLISECONDS)
        .readTimeout(750, TimeUnit.MILLISECONDS)
        .writeTimeout(750, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val telemetryClient: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(boundFactory)
        .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
        .dispatcher(Dispatcher(Executors.newFixedThreadPool(2)))
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var frameScratchBuffer = ByteArray(1536 * 1024)
    private val resolvedPaths = mutableMapOf<String, String>()

    private fun resolveEndpoint(suffix: String, defaultVersion: String = "ver100"): String {
        return resolvedPaths[suffix] ?: "$baseUrl/$defaultVersion/$suffix"
    }

    override suspend fun handshake(): Result<CameraHandshakeInfo> = withContext(Dispatchers.IO) {
        telemetryMutex.withLock {
            runCatching {
                discoverApiEndpoints()
                val infoJson = getJson(resolveEndpoint("deviceinformation"))
                val serial = infoJson.optString("serialnumber", ipAddress)
                val product = infoJson.optString("productname", "Canon EOS R")

                val apertures = parseAbilityList(
                    runCatching { getJson(resolveEndpoint("shooting/settings/av")) }.getOrNull()
                )
                val shutters = parseAbilityList(
                    runCatching { getJson(resolveEndpoint("shooting/settings/tv")) }.getOrNull()
                )

                CameraHandshakeInfo(
                    uuid = serial,
                    modelName = product,
                    protocol = ProtocolType.CCAPI,
                    supportedApertures = apertures,
                    supportedShutters = shutters
                )
            }
        }
    }

    private fun parseAbilityList(json: JSONObject?): List<String> {
        val arr = json?.optJSONArray("ability") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                add(arr.getString(i))
            }
        }
    }

    private fun discoverApiEndpoints() {
        val rootJson = getJson(baseUrl)
        for (ver in listOf("ver120", "ver110", "ver100")) {
            val arr = rootJson.optJSONArray(ver) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val path = item.optString("path")
                val key = path.substringAfter("/$ver/", "")
                if (key.isNotEmpty() && !resolvedPaths.containsKey(key)) {
                    resolvedPaths[key] = "http://$ipAddress:$port$path"
                }
            }
        }
    }

    override suspend fun setStreamQuality(quality: StreamQuality): Result<Unit> =
        withContext(Dispatchers.IO) {
            telemetryMutex.withLock {
                runCatching {
                    val body = JSONObject().apply {
                        put("liveviewsize", quality.ccapiSize)
                        put("cameradisplay", "on")
                    }.toString().toRequestBody(jsonMedia)

                    val req = Request.Builder()
                        .url(resolveEndpoint("shooting/liveview"))
                        .post(body)
                        .build()

                    telemetryClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("LiveView quality failed: ${resp.code}")
                    }
                }
            }
        }

    override suspend fun pullNextLiveFrame(slot: LatestFrameSlot): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(resolveEndpoint("shooting/liveview/flip"))
                    .get()
                    .build()

                telemetryClient.newCall(req).execute().use { resp ->
                    if (resp.code == 503 || resp.code == 404) return@use
                    if (!resp.isSuccessful) error("Frame HTTP ${resp.code}")

                    val stream = resp.body?.byteStream() ?: return@use
                    var totalRead = 0
                    while (true) {
                        if (totalRead == frameScratchBuffer.size) {
                            if (frameScratchBuffer.size >= 4 * 1024 * 1024) {
                                error("Frame exceeds 4MB")
                            }
                            frameScratchBuffer = frameScratchBuffer.copyOf(frameScratchBuffer.size * 2)
                        }
                        val read = stream.read(
                            frameScratchBuffer,
                            totalRead,
                            frameScratchBuffer.size - totalRead
                        )
                        if (read <= 0) break
                        totalRead += read
                    }
                    if (totalRead > 0) {
                        slot.writeFrame(frameScratchBuffer, totalRead)
                    }
                }
            }
        }

    override suspend fun setRecording(record: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        priorityMutex.withLock {
            runCatching {
                val action = if (record) "start" else "stop"
                val body = JSONObject().put("action", action).toString().toRequestBody(jsonMedia)
                val req = Request.Builder()
                    .url(resolveEndpoint("shooting/control/recbutton"))
                    .post(body)
                    .build()

                priorityControlClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("REC $action rejected: HTTP ${resp.code}")
                }
            }
        }
    }

    override suspend fun queryActualRecordingState(): Result<Boolean> = withContext(Dispatchers.IO) {
        telemetryMutex.withLock {
            runCatching {
                val json = getJson(resolveEndpoint("shooting/control/recbutton"))
                val status = json.optString("status", "").lowercase()
                status == "rec" || status == "recording" || status == "start"
            }
        }
    }

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            priorityMutex.withLock {
                runCatching {
                    val x = (normX.coerceIn(0f, 1f) * 10000).roundToInt()
                    val y = (normY.coerceIn(0f, 1f) * 10000).roundToInt()

                    if (resolvedPaths.containsKey("shooting/control/touchaf")) {
                        val touchBody = JSONObject().apply {
                            put("positionx", x)
                            put("positiony", y)
                        }.toString().toRequestBody(jsonMedia)
                        val req = Request.Builder()
                            .url(resolveEndpoint("shooting/control/touchaf", "ver110"))
                            .post(touchBody)
                            .build()
                        priorityControlClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) error("TouchAF failed: ${resp.code}")
                        }
                    } else {
                        val startBody = JSONObject().apply {
                            put("action", "start")
                            put("positionx", x)
                            put("positiony", y)
                        }.toString().toRequestBody(jsonMedia)
                        val startReq = Request.Builder()
                            .url(resolveEndpoint("shooting/control/af"))
                            .post(startBody)
                            .build()
                        priorityControlClient.newCall(startReq).execute().close()

                        delay(220L)

                        val stopBody = JSONObject().put("action", "stop")
                            .toString().toRequestBody(jsonMedia)
                        val stopReq = Request.Builder()
                            .url(resolveEndpoint("shooting/control/af"))
                            .post(stopBody)
                            .build()
                        priorityControlClient.newCall(stopReq).execute().close()
                    }
                }
            }
        }

    override suspend fun getCurrentExposure(): Result<ExposureSettings> =
        withContext(Dispatchers.IO) {
            telemetryMutex.withLock {
                runCatching {
                    val av = getJson(resolveEndpoint("shooting/settings/av"))
                        .optString("value", "f4.0")
                    val tv = getJson(resolveEndpoint("shooting/settings/tv"))
                        .optString("value", "1/50")
                    val isoStr = getJson(resolveEndpoint("shooting/settings/iso"))
                        .optString("value", "800")
                    val wb = getJson(resolveEndpoint("shooting/settings/colortemperature"))
                        .optInt("value", 5600)
                    ExposureSettings(
                        aperture = av,
                        shutterSpeed = tv,
                        iso = isoStr.toIntOrNull() ?: 800,
                        kelvin = wb
                    )
                }
            }
        }

    override suspend fun applyExposure(
        target: ExposureSettings,
        supportedApertures: List<String>,
        supportedShutters: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        telemetryMutex.withLock {
            runCatching {
                if (supportedShutters.isEmpty() || supportedShutters.contains(target.shutterSpeed)) {
                    putSetting(resolveEndpoint("shooting/settings/tv"), target.shutterSpeed)
                }
                putSettingInt(resolveEndpoint("shooting/settings/colortemperature"), target.kelvin)

                if (supportedApertures.contains(target.aperture)) {
                    putSetting(resolveEndpoint("shooting/settings/av"), target.aperture)
                    putSetting(resolveEndpoint("shooting/settings/iso"), target.iso.toString())
                } else {
                    val currentAv = getJson(resolveEndpoint("shooting/settings/av"))
                        .optString("value", "f4.0")
                    val targetF = target.aperture.removePrefix("f").toFloatOrNull() ?: 4.0f
                    val currentF = currentAv.removePrefix("f").toFloatOrNull() ?: 4.0f
                    val stopRatio = (currentF / targetF).pow(2)
                    val compensatedIso = (target.iso * stopRatio)
                        .roundToInt()
                        .coerceIn(100, 12800)
                    putSetting(resolveEndpoint("shooting/settings/iso"), compensatedIso.toString())
                }
            }
        }
    }

    override suspend fun getBatteryStatus(): Result<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            telemetryMutex.withLock {
                runCatching {
                    val json = getJson(resolveEndpoint("devicestatus/battery"))
                    val rawLevel = json.optString("level", "unknown").lowercase()
                    val percent = json.optInt("value", -1)

                    if (percent in 0..100) {
                        "$percent%" to (percent <= 20)
                    } else {
                        when (rawLevel) {
                            "full" -> "100%" to false
                            "high" -> "75%" to false
                            "half" -> "50%" to false
                            "quarter" -> "25%" to true
                            "low" -> "15%" to true
                            "charge" -> "CHG" to false
                            else -> "AC/EXT" to false
                        }
                    }
                }
            }
        }

    override suspend fun getRemainingRecordMinutes(): Result<Int?> =
        withContext(Dispatchers.IO) {
            telemetryMutex.withLock {
                runCatching {
                    val json = getJson(resolveEndpoint("devicestatus/storage"))
                    val storageArr = json.optJSONArray("storagelist") ?: return@runCatching null
                    var totalRemainSec = 0L
                    for (i in 0 until storageArr.length()) {
                        val item = storageArr.optJSONObject(i) ?: continue
                        if (item.optString("accesscapability") != "readwrite") continue
                        val sec = item.optLong("remainingtime", -1L)
                        if (sec > 0L) totalRemainSec += sec
                    }
                    if (totalRemainSec > 0L) (totalRemainSec / 60L).toInt() else null
                }
            }
        }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url).get().build()
        telemetryClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("GET $url failed: ${resp.code}")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun putSetting(url: String, value: String) {
        val body = JSONObject().put("value", value).toString().toRequestBody(jsonMedia)
        val req = Request.Builder().url(url).put(body).build()
        telemetryClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("PUT $url ($value) rejected: ${resp.code}")
        }
    }

    private fun putSettingInt(url: String, value: Int) {
        val body = JSONObject().put("value", value).toString().toRequestBody(jsonMedia)
        val req = Request.Builder().url(url).put(body).build()
        telemetryClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("PUT $url ($value) rejected: ${resp.code}")
        }
    }

    override fun close() {
        priorityControlClient.dispatcher.executorService.shutdownNow()
        priorityControlClient.connectionPool.evictAll()
        telemetryClient.dispatcher.executorService.shutdownNow()
        telemetryClient.connectionPool.evictAll()
    }
}
