package com.canonmulticam.pro

import com.canonmulticam.pro.data.ILabelStore
import com.canonmulticam.pro.domain.CameraHandshakeInfo
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.ProtocolType
import com.canonmulticam.pro.domain.RecordAckState
import com.canonmulticam.pro.domain.StreamQuality
import com.canonmulticam.pro.engine.CameraSessionActor
import com.canonmulticam.pro.engine.ThermalLevel
import com.canonmulticam.pro.transport.ICameraDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class TemporalWatchdogAndRecordProofTest {

    private class InMemoryLabelStore : ILabelStore {
        private val map = mutableMapOf<String, String>()
        override fun saveLabel(slotIndex: Int, cameraUuid: String?, rawLabel: String) {
            map[cameraUuid ?: "slot_$slotIndex"] = rawLabel
        }
        override fun resolveLabel(slotIndex: Int, cameraUuid: String?): String? =
            map[cameraUuid ?: "slot_$slotIndex"]
    }

    @Suppress("TooManyFunctions")
    private class ControllableFakeDriver(
        override val ipAddress: String = "192.168.43.10"
    ) : ICameraDriver {
        val actualCameraRecording = AtomicBoolean(false)
        val stallFrameStream = AtomicBoolean(false)

        override suspend fun handshake(): Result<CameraHandshakeInfo> = Result.success(
            CameraHandshakeInfo("UUID-R6II", "Canon EOS R6 II", ProtocolType.CCAPI, listOf("f2.8"))
        )
        override suspend fun setStreamQuality(quality: StreamQuality): Result<Unit> = Result.success(Unit)
        override suspend fun pullNextLiveFrame(slot: LatestFrameSlot): Result<Unit> {
            if (!stallFrameStream.get()) {
                slot.writeFrame(
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()), 4
                )
            }
            return Result.success(Unit)
        }
        override suspend fun setRecording(record: Boolean): Result<Unit> {
            actualCameraRecording.set(record)
            return Result.success(Unit)
        }
        override suspend fun queryActualRecordingState(): Result<Boolean> =
            Result.success(actualCameraRecording.get())
        override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentExposure(): Result<ExposureSettings> =
            Result.success(ExposureSettings("f2.8", "1/50", 800, 5600))
        override suspend fun applyExposure(
            target: ExposureSettings,
            supportedApertures: List<String>,
            supportedShutters: List<String>
        ): Result<Unit> = Result.success(Unit)
        override suspend fun getBatteryStatus(): Result<Pair<String, Boolean>> = Result.success("85%" to false)
        override suspend fun getRemainingRecordMinutes(): Result<Int?> = Result.success(120)
        override fun close() {}
    }

    @Test
    fun `prove Watchdog adapts to HOT thermal throttling without false alarms and catches sudden card-full stop`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val currentThermal = AtomicReference(ThermalLevel.HOT)
            val unexpectedStopTriggered = AtomicBoolean(false)
            val fakeDriver = ControllableFakeDriver()

            val actor = CameraSessionActor(
                slotIndex = 0,
                labelStore = InMemoryLabelStore(),
                thermalLevelProvider = { currentThermal.get() },
                onUnexpectedRecordStop = { unexpectedStopTriggered.set(true) },
                onDisconnectedCallback = {},
                onFrameUpdated = { _, _ -> },
                ioDispatcher = dispatcher,
                clockMs = { testScheduler.currentTime },
                driverProvider = {
                    Result.success(fakeDriver to fakeDriver.handshake().getOrThrow())
                }
            )

            actor.connect("192.168.43.10", StreamQuality.LOW_QUAD)
            advanceTimeBy(300L)
            runCurrent()
            assertTrue("Camera must be connected", actor.state.value.isConnected)
            assertFalse("Signal must be healthy initially", actor.state.value.isSignalStale)

            fakeDriver.stallFrameStream.set(true)
            advanceTimeBy(800L)
            runCurrent()
            assertFalse(
                "Watchdog must NOT fire false stale alarm at 800ms when ThermalLevel is HOT",
                actor.state.value.isSignalStale
            )

            advanceTimeBy(350L)
            runCurrent()
            assertTrue("Watchdog MUST flag stale signal after 1150ms stall", actor.state.value.isSignalStale)

            fakeDriver.stallFrameStream.set(false)
            advanceTimeBy(300L)
            runCurrent()
            assertFalse("Signal must recover immediately when frames resume", actor.state.value.isSignalStale)

            val recStarted = actor.setRecordingWithAck(true)
            assertTrue(recStarted)
            assertEquals(RecordAckState.RECORDING, actor.state.value.recordState)

            fakeDriver.actualCameraRecording.set(false)

            advanceTimeBy(3200L)
            runCurrent()

            assertEquals(
                "Actor must detect silent camera stop and transition to ERROR",
                RecordAckState.ERROR,
                actor.state.value.recordState
            )
            assertTrue(
                "Emergency alarm callback MUST fire when camera stops recording unexpectedly",
                unexpectedStopTriggered.get()
            )

            actor.destroy()
        }
}
