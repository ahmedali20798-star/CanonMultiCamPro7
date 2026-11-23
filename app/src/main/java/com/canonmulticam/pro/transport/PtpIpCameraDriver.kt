package com.canonmulticam.pro.transport

import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.ProtocolType
import com.canonmulticam.pro.domain.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@Suppress("TooManyFunctions")
class PtpIpCameraDriver(
    override val ipAddress: String,
    private val port: Int = 15740
) : ICameraDriver {

    private val commandMutex = Mutex()
    private val txCounter = AtomicInteger(1)
    private var frameScratchBuffer = ByteArray(1536 * 1024)
    private val boundFactory = NetworkBinder.createBoundSocketFactory(timeoutMs = 1500)

    private var cmdSocket: Socket? = null
    private var cmdIn: DataInputStream? = null
    private var cmdOut: DataOutputStream? = null

    private var eventSocket: Socket? = null
    private var eventIn: DataInputStream? = null
    private var eventOut: DataOutputStream? = null
    private var eventDrainThread: Thread? = null

    @Volatile private var lastKnownRecordingState = false

    companion object {
        private const val PKT_INIT_CMD_REQ = 0x00000001
        private const val PKT_INIT_CMD_ACK = 0x00000002
        private const val PKT_INIT_EVENT_REQ = 0x00000003
        private const val PKT_INIT_EVENT_ACK = 0x00000004
        private const val PKT_OP_REQ = 0x00000006
        private const val PKT_OP_RESP = 0x00000007
        private const val PKT_START_DATA = 0x00000009
        private const val PKT_DATA = 0x0000000A
        private const val PKT_END_DATA = 0x0000000C

        private const val OP_OPEN_SESSION = 0x1002
        private const val OP_CLOSE_SESSION = 0x1003
        private const val OP_GET_DEVICE_PROP_VALUE = 0x1015
        private const val OP_EOS_SET_DEVICE_PROP = 0x9110
        private const val OP_EOS_SET_REMOTE_MODE = 0x9114
        private const val OP_EOS_SET_EVENT_MODE = 0x9115
        private const val OP_EOS_REMOTE_RELEASE_ON = 0x9128
        private const val OP_EOS_REMOTE_RELEASE_OFF = 0x9129
        private const val OP_EOS_GET_VIEWFINDER_DATA = 0x9153
        private const val OP_EOS_SET_TOUCH_AF = 0x915B

        private const val RESP_OK = 0x2001
        private const val RESP_DEVICE_PROP_NOT_SUPPORTED = 0x200A
        private const val RESP_DEVICE_BUSY = 0x2019
        private const val RESP_SESSION_ALREADY_OPEN = 0x201E

        private const val PROP_BATTERY_LEVEL = 0x5001
        private const val PROP_EOS_ISO = 0xD103
        private const val PROP_EOS_COLOR_TEMP = 0xD10A
        private const val PROP_EOS_EVF_OUTPUT = 0xD1B0
        private const val PROP_EOS_MOVIE_RECORD = 0xD1B8
    }

    override suspend fun handshake(): Result<CameraHandshakeInfo> = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            runCatching {
                close()

                val cSock = boundFactory.createSocket().apply {
                    soTimeout = 2000
                    connect(InetSocketAddress(ipAddress, port), 1500)
                }
                cmdSocket = cSock
                cmdIn = DataInputStream(BufferedInputStream(cSock.getInputStream()))
                cmdOut = DataOutputStream(BufferedOutputStream(cSock.getOutputStream()))

                val (connNumber, responderGuid, cameraName) = performInitCommand()

                val eSock = boundFactory.createSocket().apply {
                    soTimeout = 2000
                    connect(InetSocketAddress(ipAddress, port), 1500)
                }
                eventSocket = eSock
                eventIn = DataInputStream(BufferedInputStream(eSock.getInputStream()))
                eventOut = DataOutputStream(BufferedOutputStream(eSock.getOutputStream()))
                performInitEvent(connNumber)
                startEventChannelDrain()

                txCounter.set(1)
                val openCode = sendSimpleOperation(OP_OPEN_SESSION, intArrayOf(1))
                if (openCode == RESP_SESSION_ALREADY_OPEN) {
                    sendSimpleOperation(OP_CLOSE_SESSION, intArrayOf(1))
                    val retryCode = sendSimpleOperation(OP_OPEN_SESSION, intArrayOf(1))
                    check(retryCode == RESP_OK) {
                        "PTP OpenSession retry failed: 0x${retryCode.toString(16)}"
                    }
                } else {
                    check(openCode == RESP_OK) {
                        "PTP OpenSession failed: 0x${openCode.toString(16)}"
                    }
                }

                sendSimpleOperation(OP_EOS_SET_REMOTE_MODE, intArrayOf(1))
                sendSimpleOperation(OP_EOS_SET_EVENT_MODE, intArrayOf(1))
                setEosIntProperty(PROP_EOS_EVF_OUTPUT, 2)

                CameraHandshakeInfo(
                    uuid = responderGuid,
                    modelName = cameraName.ifBlank { "Canon EOS (PTP/IP)" },
                    protocol = ProtocolType.PTP_IP,
                    supportedApertures = emptyList()
                )
            }.onFailure { close() }
        }
    }

    private fun startEventChannelDrain() {
        eventDrainThread?.interrupt()
        eventDrainThread = Thread {
            val input = eventIn ?: return@Thread
            try {
                while (!Thread.currentThread().isInterrupted && eventSocket?.isConnected == true) {
                    val pktLen = readIntLE(input)
                    readIntLE(input)
                    val bodyLen = (pktLen - 8).coerceAtLeast(0)
                    if (bodyLen > 0) skipFully(input, bodyLen)
                }
            } catch (_: Exception) { }
        }.apply {
            name = "PTP-EventDrain-$ipAddress"
            isDaemon = true
            start()
        }
    }

    override suspend fun setStreamQuality(quality: StreamQuality): Result<Unit> =
        withContext(Dispatchers.IO) {
            commandMutex.withLock {
                runCatching {
                    setEosIntProperty(PROP_EOS_EVF_OUTPUT, 2)
                    Unit
                }
            }
        }

    override suspend fun pullNextLiveFrame(slot: LatestFrameSlot): Result<Unit> =
        withContext(Dispatchers.IO) {
            commandMutex.withLock {
                runCatching {
                    val out = cmdOut ?: error("PTP command channel closed")
                    val input = cmdIn ?: error("PTP input channel closed")

                    val txId = txCounter.getAndIncrement()
                    sendOperationPacket(
                        out, 1, OP_EOS_GET_VIEWFINDER_DATA, txId, intArrayOf(0x00100000)
                    )

                    var frameBytesWritten = 0
                    while (true) {
                        val pktLen = readIntLE(input)
                        val pktType = readIntLE(input)
                        val bodyLen = (pktLen - 8).coerceAtLeast(0)

                        when (pktType) {
                            PKT_START_DATA -> {
                                readIntLE(input)
                                readLongLE(input)
                                frameBytesWritten = 0
                            }
                            PKT_DATA, PKT_END_DATA -> {
                                frameBytesWritten = handleViewfinderDataChunk(
                                    input, bodyLen, frameBytesWritten
                                )
                            }
                            PKT_OP_RESP -> {
                                val respCode = readShortLE(input) and 0xFFFF
                                readIntLE(input)
                                val remaining = (bodyLen - 6).coerceAtLeast(0)
                                if (remaining > 0) skipFully(input, remaining)

                                if (respCode == RESP_OK && frameBytesWritten > 0) {
                                    slot.writeFrame(frameScratchBuffer, frameBytesWritten)
                                } else if (respCode != RESP_OK && respCode != RESP_DEVICE_BUSY) {
                                    error("PTP LiveView error: 0x${respCode.toString(16)}")
                                }
                                break
                            }
                            else -> if (bodyLen > 0) skipFully(input, bodyLen)
                        }
                    }
                }
            }
        }

    private fun handleViewfinderDataChunk(
        input: DataInputStream,
        bodyLen: Int,
        currentOffset: Int
    ): Int {
        readIntLE(input)
        val payloadLen = (bodyLen - 4).coerceAtLeast(0)
        if (payloadLen <= 0) return currentOffset

        val needed = currentOffset + payloadLen
        if (needed > frameScratchBuffer.size && frameScratchBuffer.size < 4 * 1024 * 1024) {
            frameScratchBuffer = frameScratchBuffer.copyOf(needed * 2)
        }
        return if (needed <= frameScratchBuffer.size) {
            input.readFully(frameScratchBuffer, currentOffset, payloadLen)
            currentOffset + payloadLen
        } else {
            skipFully(input, payloadLen)
            currentOffset
        }
    }

    override suspend fun setRecording(record: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            runCatching {
                val recVal = if (record) 4 else 0
                var code = setEosIntProperty(PROP_EOS_MOVIE_RECORD, recVal)
                if (code == RESP_DEVICE_PROP_NOT_SUPPORTED) {
                    code = if (record) {
                        sendSimpleOperation(OP_EOS_REMOTE_RELEASE_ON, intArrayOf(3, 0))
                    } else {
                        sendSimpleOperation(OP_EOS_REMOTE_RELEASE_OFF, intArrayOf(3))
                    }
                }
                check(code == RESP_OK) { "PTP REC failed: 0x${code.toString(16)}" }
                lastKnownRecordingState = record
            }
        }
    }

    override suspend fun queryActualRecordingState(): Result<Boolean> =
        Result.success(lastKnownRecordingState)

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            commandMutex.withLock {
                runCatching {
                    val x = (normX.coerceIn(0f, 1f) * 10000).roundToInt()
                    val y = (normY.coerceIn(0f, 1f) * 10000).roundToInt()
                    val code = sendSimpleOperation(OP_EOS_SET_TOUCH_AF, intArrayOf(x, y))
                    check(code == RESP_OK) { "PTP TouchAF failed: 0x${code.toString(16)}" }
                }
            }
        }

    override suspend fun getCurrentExposure(): Result<ExposureSettings> =
        Result.failure(UnsupportedOperationException("Use CCAPI camera as Exposure Master"))

    override suspend fun applyExposure(
        target: ExposureSettings,
        supportedApertures: List<String>,
        supportedShutters: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            runCatching {
                setEosIntProperty(PROP_EOS_COLOR_TEMP, target.kelvin)
                setEosIntProperty(PROP_EOS_ISO, mapIsoToCanonCode(target.iso))
                Unit
            }
        }
    }

    override suspend fun getBatteryStatus(): Result<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            commandMutex.withLock {
                runCatching {
                    val out = cmdOut ?: error("Closed")
                    val input = cmdIn ?: error("Closed")
                    val txId = txCounter.getAndIncrement()
                    sendOperationPacket(
                        out, 1, OP_GET_DEVICE_PROP_VALUE, txId, intArrayOf(PROP_BATTERY_LEVEL)
                    )

                    var batteryPct = -1
                    while (true) {
                        val pktLen = readIntLE(input)
                        val pktType = readIntLE(input)
                        val bodyLen = (pktLen - 8).coerceAtLeast(0)
                        when (pktType) {
                            PKT_DATA, PKT_END_DATA -> {
                                readIntLE(input)
                                val payloadLen = (bodyLen - 4).coerceAtLeast(0)
                                if (payloadLen >= 1) {
                                    batteryPct = input.readUnsignedByte()
                                    if (payloadLen > 1) skipFully(input, payloadLen - 1)
                                }
                            }
                            PKT_OP_RESP -> {
                                if (bodyLen > 0) skipFully(input, bodyLen)
                                break
                            }
                            else -> if (bodyLen > 0) skipFully(input, bodyLen)
                        }
                    }
                    if (batteryPct in 0..100) "$batteryPct%" to (batteryPct <= 20)
                    else "PTP" to false
                }
            }
        }

    override suspend fun getRemainingRecordMinutes(): Result<Int?> = Result.success(null)

    private fun performInitCommand(): Triple<Int, String, String> {
        val out = requireNotNull(cmdOut) { "Command output stream not initialized" }
        val input = requireNotNull(cmdIn) { "Command input stream not initialized" }
        val nameBytes = "CanonMultiCamPro\u0000".toByteArray(Charsets.UTF_16LE)
        val totalLen = 8 + 16 + nameBytes.size + 4

        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(totalLen)
        buf.putInt(PKT_INIT_CMD_REQ)
        val uuid = UUID.randomUUID()
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        buf.put(nameBytes)
        buf.putInt(0x00010000)

        out.write(buf.array())
        out.flush()

        val ackLen = readIntLE(input)
        val ackType = readIntLE(input)
        check(ackType == PKT_INIT_CMD_ACK) {
            "Expected INIT_CMD_ACK, got 0x${ackType.toString(16)}"
        }

        val connNumber = readIntLE(input)
        val guidBytes = ByteArray(16)
        input.readFully(guidBytes)
        val responderGuid = guidBytes.joinToString("") { String.format(Locale.US, "%02x", it) }

        val remainingNameLen = (ackLen - 8 - 4 - 16 - 4).coerceAtLeast(0)
        val rawName = ByteArray(remainingNameLen)
        if (remainingNameLen > 0) input.readFully(rawName)
        readIntLE(input)

        val friendlyName = String(rawName, Charsets.UTF_16LE).trimEnd('\u0000')
        return Triple(connNumber, responderGuid, friendlyName)
    }

    private fun performInitEvent(connNumber: Int) {
        val out = requireNotNull(eventOut) { "Event output stream not initialized" }
        val input = requireNotNull(eventIn) { "Event input stream not initialized" }
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(12)
        buf.putInt(PKT_INIT_EVENT_REQ)
        buf.putInt(connNumber)
        out.write(buf.array())
        out.flush()

        val ackLen = readIntLE(input)
        val ackType = readIntLE(input)
        check(ackType == PKT_INIT_EVENT_ACK) {
            "Expected INIT_EVENT_ACK, got 0x${ackType.toString(16)}"
        }
        val extra = (ackLen - 8).coerceAtLeast(0)
        if (extra > 0) skipFully(input, extra)
    }

    private fun setEosIntProperty(propCode: Int, value: Int): Int {
        val out = cmdOut ?: error("Command channel closed")
        val input = cmdIn ?: error("Input channel closed")
        val txId = txCounter.getAndIncrement()

        sendOperationPacket(out, 2, OP_EOS_SET_DEVICE_PROP, txId, intArrayOf())

        val startBuf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        startBuf.putInt(20)
        startBuf.putInt(PKT_START_DATA)
        startBuf.putInt(txId)
        startBuf.putLong(12L)
        out.write(startBuf.array())

        val endBuf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        endBuf.putInt(24)
        endBuf.putInt(PKT_END_DATA)
        endBuf.putInt(txId)
        endBuf.putInt(12)
        endBuf.putInt(propCode)
        endBuf.putInt(value)
        out.write(endBuf.array())
        out.flush()

        return awaitOperationResponse(input)
    }

    private fun sendSimpleOperation(opCode: Int, params: IntArray): Int {
        val out = cmdOut ?: error("Command channel closed")
        val input = cmdIn ?: error("Input channel closed")
        val txId = txCounter.getAndIncrement()
        sendOperationPacket(out, 1, opCode, txId, params)
        return awaitOperationResponse(input)
    }

    private fun sendOperationPacket(
        out: DataOutputStream,
        dataPhase: Int,
        opCode: Int,
        txId: Int,
        params: IntArray
    ) {
        val len = 18 + params.size * 4
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(len)
        buf.putInt(PKT_OP_REQ)
        buf.putInt(dataPhase)
        buf.putShort(opCode.toShort())
        buf.putInt(txId)
        for (p in params) buf.putInt(p)
        out.write(buf.array())
        out.flush()
    }

    private fun awaitOperationResponse(input: DataInputStream): Int {
        while (true) {
            val pktLen = readIntLE(input)
            val pktType = readIntLE(input)
            val bodyLen = (pktLen - 8).coerceAtLeast(0)
            if (pktType == PKT_OP_RESP) {
                val respCode = readShortLE(input) and 0xFFFF
                readIntLE(input)
                val rest = (bodyLen - 6).coerceAtLeast(0)
                if (rest > 0) skipFully(input, rest)
                return respCode
            } else if (bodyLen > 0) {
                skipFully(input, bodyLen)
            }
        }
    }

    private fun skipFully(input: DataInputStream, byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) {
                    throw EOFException("Unexpected EOF while skipping $byteCount bytes")
                }
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun mapIsoToCanonCode(iso: Int): Int = when {
        iso <= 100 -> 0x48
        iso <= 200 -> 0x50
        iso <= 400 -> 0x58
        iso <= 800 -> 0x60
        iso <= 1600 -> 0x68
        iso <= 3200 -> 0x70
        iso <= 6400 -> 0x78
        else -> 0x80
    }

    private fun readIntLE(input: DataInputStream): Int = Integer.reverseBytes(input.readInt())
    private fun readLongLE(input: DataInputStream): Long =
        java.lang.Long.reverseBytes(input.readLong())
    private fun readShortLE(input: DataInputStream): Int =
        java.lang.Short.reverseBytes(input.readShort()).toInt()

    override fun close() {
        eventDrainThread?.interrupt()
        eventDrainThread = null
        runCatching { cmdIn?.close() }
        runCatching { cmdOut?.close() }
        runCatching { cmdSocket?.close() }
        runCatching { eventIn?.close() }
        runCatching { eventOut?.close() }
        runCatching { eventSocket?.close() }
        cmdSocket = null
        eventSocket = null
    }
}
