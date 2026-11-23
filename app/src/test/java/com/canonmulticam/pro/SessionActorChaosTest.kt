package com.canonmulticam.pro

import com.canonmulticam.pro.data.TakeLogger
import com.canonmulticam.pro.domain.LatestFrameSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActorChaosTest {

    @Test
    fun `prove LatestFrameSlot pinned triple buffer prevents overwrite while reading`() {
        val slot = LatestFrameSlot(initialCapacity = 1024)

        slot.writeFrame(ByteArray(2048) { 0x11 }, 2048)
        val pinnedView = requireNotNull(slot.acquireReadBuffer())

        slot.writeFrame(ByteArray(2048) { 0x22 }, 2048)
        slot.writeFrame(ByteArray(2048) { 0x33 }, 2048)

        assertEquals(0x11, pinnedView.get(0).toInt() and 0xFF)
        assertEquals(0x11, pinnedView.get(2047).toInt() and 0xFF)
        slot.releaseReadBuffer()

        val latestView = requireNotNull(slot.acquireReadBuffer())
        assertEquals(0x33, latestView.get(0).toInt() and 0xFF)
        slot.releaseReadBuffer()
    }

    @Test
    fun `prove TakeLogger records takes slates good marks and exports valid Premiere CSV`() {
        val logger = TakeLogger()
        val takeNum = logger.startTake(listOf("CAM 1 • R6 II", "CAM 2 • R5C"))
        assertEquals(1, takeNum)
        logger.incrementSlateOnCurrentTake()
        logger.markCurrentTakeGood()
        logger.stopCurrentTake()

        val csv = logger.exportPremiereMarkersCsv()
        assertTrue(csv.contains("Marker Name,Description,In,Out,Duration,Marker Type"))
        assertTrue(csv.contains("TAKE 01 [GOOD TAKE]"))
        assertTrue(csv.contains("Slates: 1"))
    }
}
