package com.canonmulticam.pro.engine

import com.canonmulticam.pro.data.ILabelStore
import com.canonmulticam.pro.domain.AnamorphicRatio
import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.CameraSlotState
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.RecordAckState
import com.canonmulticam.pro.domain.StreamQuality
import com.canonmulticam.pro.gl.CubeLutData
import com.canonmulticam.pro.gl.ShaderToolFlags
import com.canonmulticam.pro.transport.CameraDriverFactory
import com.canonmulticam.pro.transport.ICameraDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class CameraSessionActor(
    val slotIndex: Int,
    private val labelStore: ILabelStore,
    private val thermalLevelProvider: () -> ThermalLevel,
    private val onUnexpectedRecordStop: (CameraSlotState) -> Unit,
    private val onDisconnectedCallback: (slotIndex: Int) -> Unit,
    private val onFrameUpdated: (slotIndex: Int, slot: LatestFrameSlot) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val driverProvider: suspend (String) -> Result<Pair<ICameraDriver, CameraHandshakeInfo>> =
        { ip -> CameraDriverFactory.connectAndLockProtocol(ip) }
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val sessionGeneration = AtomicLong(0L)
    val frameSlot = LatestFrameSlot(clockMs = clockMs)

    private val _state = MutableStateFlow(
        CameraSlotState(
            slotIndex = slotIndex,
            customLabel = labelStore.resolveLabel(slotIndex, null)
        )
    )
    val state: StateFlow<CameraSlotState> = _state

    @Volatile private var driver: ICameraDriver? = null
    @Volatile private var supportedApertures: List<String> = emptyList()
    @Volatile private var supportedShutters: List<String> = emptyList()
    @Volatile private var isStreamPausedByLifecycle: Boolean = false
    @Volatile private var matchedReferenceExposure: ExposureSettings? = null
    private var streamJob: Job? = null
    private var watchdogJob: Job? = null

    fun setLifecyclePaused(paused: Boolean) {
        isStreamPausedByLifecycle = paused
        if (!paused) frameSlot.reset()
    }

    fun isHealthyOn(targetIp: String): Boolean {
        return _state.value.isConnected &&
            !_state.value.isSignalStale &&
            _state.value.ipAddress == targetIp &&
            driver != null
    }

    fun setMatchedReference(reference: ExposureSettings?) {
        matchedReferenceExposure = reference
        _state.update { it.copy(isExposureDrifted = false) }
    }

    fun connect(ipAddress: String, initialQuality: StreamQuality) {
        if (isHealthyOn(ipAddress)) return
        val myGen = sessionGeneration.incrementAndGet()
        cleanupActiveResources()

        streamJob = scope.launch {
            var backoffMs = 500L
            while (isActive && sessionGeneration.get() == myGen) {
                val handshakeResult = driverProvider(ipAddress)
                if (sessionGeneration.get() != myGen) {
                    handshakeResult.getOrNull()?.first?.close()
                    break
                }

                if (handshakeResult.isSuccess) {
                    val (lockedDriver, info) = handshakeResult.getOrThrow()
                    driver = lockedDriver
                    supportedApertures = info.supportedApertures
                    supportedShutters = info.supportedShutters
                    backoffMs = 500L

                    val resolvedLabel = labelStore.resolveLabel(slotIndex, info.uuid)
                    if (!resolvedLabel.isNullOrBlank()) {
                        labelStore.saveLabel(slotIndex, info.uuid, resolvedLabel)
                    }

                    _state.update {
                        it.copy(
                            boundUuid = info.uuid,
                            ipAddress = ipAddress,
                            modelName = info.modelName,
                            customLabel = resolvedLabel,
                            isConnected = true,
                            isSignalStale = false
                        )
                    }

                    lockedDriver.setStreamQuality(initialQuality)
                    startWatchdog(myGen)

                    while (isActive && sessionGeneration.get() == myGen) {
                        if (isStreamPausedByLifecycle) {
                            delay(200L)
                            continue
                        }
                        val t0 = clockMs()
                        val frameRes = lockedDriver.pullNextLiveFrame(frameSlot)
                        val rtt = (clockMs() - t0).coerceAtLeast(1L)
                        if (frameRes.isSuccess) {
                            if (abs(_state.value.latencyMs - rtt) >= 5L) {
                                _state.update { it.copy(latencyMs = rtt) }
                            }
                            onFrameUpdated(slotIndex, frameSlot)
                        } else {
                            break
                        }
                        delay(thermalLevelProvider().pollDelayMs)
                    }
                }

                if (sessionGeneration.get() != myGen) break

                driver?.close()
                driver = null
                frameSlot.reset()
                onDisconnectedCallback(slotIndex)
                _state.update { it.copy(isConnected = false, isSignalStale = true, latencyMs = 0L) }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(4000L)
            }
        }
    }

    private fun startWatchdog(myGen: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var tick = 0
            while (isActive && sessionGeneration.get() == myGen) {
                delay(250L)
                val dynamicThreshold = thermalLevelProvider().pollDelayMs + 850L
                val ageMs = clockMs() - frameSlot.frameTimestampMs
                val stale = !isStreamPausedByLifecycle &&
                    frameSlot.frameTimestampMs > 0L &&
                    ageMs > dynamicThreshold

                if (_state.value.isSignalStale != stale) {
                    _state.update { it.copy(isSignalStale = stale) }
                }

                if (stale) {
                    tick++
                    continue
                }

                if (tick % 12 == 0) {
                    driver?.queryActualRecordingState()?.onSuccess { actuallyRecording ->
                        val cur = _state.value.recordState
                        if (cur == RecordAckState.RECORDING && !actuallyRecording) {
                            _state.update { it.copy(recordState = RecordAckState.ERROR) }
                            onUnexpectedRecordStop(_state.value)
                        } else if (cur == RecordAckState.IDLE && actuallyRecording) {
                            _state.update { it.copy(recordState = RecordAckState.RECORDING) }
                        }
                    }
                }

                if (tick % 20 == 0) readExposure()

                if (tick % 48 == 0) {
                    driver?.getBatteryStatus()?.onSuccess { (label, isLow) ->
                        _state.update { it.copy(batteryLabel = label, isBatteryLow = isLow) }
                    }
                    driver?.getRemainingRecordMinutes()?.onSuccess { mins ->
                        _state.update { it.copy(remainingCardMinutes = mins) }
                    }
                }
                tick++
            }
        }
    }

    suspend fun switchStreamQuality(quality: StreamQuality) {
        driver?.setStreamQuality(quality)
    }

    suspend fun setRecordingWithAck(record: Boolean): Boolean {
        val currentDriver = driver ?: run {
            _state.update { it.copy(recordState = RecordAckState.ERROR) }
            return false
        }
        _state.update { it.copy(recordState = RecordAckState.PENDING) }
        val result = withTimeoutOrNull(850L) { currentDriver.setRecording(record) }
        if (result == null) {
            currentDriver.close()
        }
        val success = result?.isSuccess == true
        _state.update {
            it.copy(
                recordState = when {
                    success && record -> RecordAckState.RECORDING
                    success && !record -> RecordAckState.IDLE
                    else -> RecordAckState.ERROR
                }
            )
        }
        return success
    }

    fun updateCustomLabel(newLabel: String) {
        val uuid = _state.value.boundUuid
        labelStore.saveLabel(slotIndex, uuid, newLabel)
        _state.update { it.copy(customLabel = labelStore.resolveLabel(slotIndex, uuid)) }
    }

    fun setCubeLut(lut: CubeLutData?) {
        val mask = _state.value.activeToolsMask
        val nextMask = if (lut != null) {
            mask or ShaderToolFlags.LUT
        } else {
            mask and ShaderToolFlags.LUT.inv()
        }
        updateToolsMask(nextMask)
    }

    suspend fun touchAf(normX: Float, normY: Float) {
        driver?.triggerTouchAf(normX, normY)
    }

    suspend fun readExposure(): ExposureSettings? {
        return driver?.getCurrentExposure()?.getOrNull()?.also { exp ->
            val ref = matchedReferenceExposure
            val drifted = ref != null &&
                (ref.kelvin != exp.kelvin || ref.shutterSpeed != exp.shutterSpeed)
            _state.update { it.copy(exposure = exp, isExposureDrifted = drifted) }
        }
    }

    suspend fun syncExposureFrom(master: ExposureSettings) {
        driver?.applyExposure(master, supportedApertures, supportedShutters)
    }

    fun updateToolsMask(mask: Int) {
        _state.update { it.copy(activeToolsMask = mask) }
    }

    fun updateAnamorphic(ratio: AnamorphicRatio) {
        _state.update { it.copy(anamorphic = ratio) }
    }

    private fun cleanupActiveResources() {
        watchdogJob?.cancel()
        streamJob?.cancel()
        driver?.close()
        driver = null
        frameSlot.reset()
        onDisconnectedCallback(slotIndex)
        _state.update { it.copy(isConnected = false, isSignalStale = false, latencyMs = 0L) }
    }

    fun disconnect() {
        sessionGeneration.incrementAndGet()
        cleanupActiveResources()
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
