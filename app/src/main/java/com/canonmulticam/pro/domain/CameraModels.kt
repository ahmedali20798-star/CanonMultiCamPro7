package com.canonmulticam.pro.domain

import java.nio.ByteBuffer

enum class ProtocolType { CCAPI, PTP_IP }

enum class StreamQuality(val ccapiSize: String) {
    LOW_QUAD("small"),
    FULL_SOLO("medium")
}

enum class AnamorphicRatio(val factor: Float, val label: String) {
    NONE(1.0f, "1.0x"),
    X1_33(1.33f, "1.33x"),
    X1_5(1.5f, "1.5x"),
    X1_8(1.8f, "1.8x"),
    X2_0(2.0f, "2.0x")
}

enum class PeakingColor(val label: String, val r: Float, val g: Float, val b: Float) {
    RED("PEAK:R", 1.0f, 0.15f, 0.15f),
    GREEN("PEAK:G", 0.15f, 1.0f, 0.25f),
    YELLOW("PEAK:Y", 1.0f, 0.9f, 0.1f)
}

enum class RecordAckState { IDLE, PENDING, RECORDING, ERROR }

data class ExposureSettings(
    val aperture: String,
    val shutterSpeed: String,
    val iso: Int,
    val kelvin: Int
)

data class CameraHandshakeInfo(
    val uuid: String,
    val modelName: String,
    val protocol: ProtocolType,
    val supportedApertures: List<String>,
    val supportedShutters: List<String> = emptyList()
)

data class CameraSlotState(
    val slotIndex: Int,
    val boundUuid: String? = null,
    val ipAddress: String? = null,
    val modelName: String = "Slot ${slotIndex + 1}",
    val customLabel: String? = null,
    val isConnected: Boolean = false,
    val isSignalStale: Boolean = false,
    val latencyMs: Long = 0L,
    val recordState: RecordAckState = RecordAckState.IDLE,
    val exposure: ExposureSettings? = null,
    val isExposureDrifted: Boolean = false,
    val anamorphic: AnamorphicRatio = AnamorphicRatio.NONE,
    val activeToolsMask: Int = 0,
    val batteryLabel: String = "--",
    val isBatteryLow: Boolean = false,
    val remainingCardMinutes: Int? = null
) {
    val displayHeader: String
        get() = customLabel?.takeIf { it.isNotBlank() }
            ?: "CAM ${slotIndex + 1} • $modelName"
}

class LatestFrameSlot(
    initialCapacity: Int = 1024 * 1024,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private var buffers = arrayOf(
        ByteBuffer.allocateDirect(initialCapacity),
        ByteBuffer.allocateDirect(initialCapacity),
        ByteBuffer.allocateDirect(initialCapacity)
    )
    private var writeIdx = 0
    private var latestReadyIdx = 0
    private var pinnedReadIdx = -1

    @Volatile var frameTimestampMs: Long = 0L
        private set

    @Synchronized
    fun writeFrame(data: ByteArray, length: Int) {
        if (length <= 0 || length > 4 * 1024 * 1024) return
        if (writeIdx == pinnedReadIdx) {
            writeIdx = (writeIdx + 1) % 3
        }
        var target = buffers[writeIdx]
        if (length > target.capacity()) {
            target = ByteBuffer.allocateDirect(length + 256 * 1024)
            buffers[writeIdx] = target
        }
        target.clear()
        target.put(data, 0, length)
        target.flip()
        latestReadyIdx = writeIdx
        writeIdx = (writeIdx + 1) % 3
        if (writeIdx == pinnedReadIdx) {
            writeIdx = (writeIdx + 1) % 3
        }
        frameTimestampMs = clockMs()
    }

    @Synchronized
    fun acquireReadBuffer(): ByteBuffer? {
        if (frameTimestampMs == 0L) return null
        pinnedReadIdx = latestReadyIdx
        return buffers[latestReadyIdx].asReadOnlyBuffer()
    }

    @Synchronized
    fun releaseReadBuffer() {
        pinnedReadIdx = -1
    }

    @Synchronized
    fun reset() {
        frameTimestampMs = 0L
        pinnedReadIdx = -1
    }
}
