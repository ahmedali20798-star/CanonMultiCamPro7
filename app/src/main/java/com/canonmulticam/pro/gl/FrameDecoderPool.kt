package com.canonmulticam.pro.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.canonmulticam.pro.domain.LatestFrameSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

data class DecodedRgbaFrame(
    val width: Int,
    val height: Int,
    val pixelBuffer: ByteBuffer,
    val sequenceId: Long
)

class FrameDecoderPool(
    private val onFrameDecoded: (slotIndex: Int, frame: DecodedRgbaFrame) -> Unit,
    private val onHistogramUpdated: ((slotIndex: Int, bins: FloatArray) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val slotWorkers = Array(4) { SlotDecoder(it) }

    fun submitEncodedFrame(slotIndex: Int, encodedSlot: LatestFrameSlot) {
        if (slotIndex in 0..3) slotWorkers[slotIndex].enqueue(encodedSlot)
    }

    fun destroy() {
        scope.cancel()
        slotWorkers.forEach { it.release() }
    }

    private inner class SlotDecoder(private val slotIndex: Int) {
        private val isDecoding = AtomicBoolean(false)
        private val hasPending = AtomicBoolean(false)
        private var reusableBitmap: Bitmap? = null

        private val rgbaBuffers = arrayOfNulls<ByteBuffer>(3)
        private var writeBufferIdx = 0
        private var tempBytes = ByteArray(2 * 1024 * 1024)
        private var seq = 0L

        fun enqueue(encodedSlot: LatestFrameSlot) {
            hasPending.set(true)
            if (isDecoding.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        while (hasPending.getAndSet(false)) {
                            decodeOnce(encodedSlot)
                        }
                    } finally {
                        isDecoding.set(false)
                    }
                }
            }
        }

        private fun decodeOnce(encodedSlot: LatestFrameSlot) {
            val byteCount: Int
            val readBuf = encodedSlot.acquireReadBuffer() ?: return
            try {
                byteCount = readBuf.remaining()
                if (byteCount <= 0) return
                if (tempBytes.size < byteCount) tempBytes = ByteArray(byteCount)
                readBuf.get(tempBytes, 0, byteCount)
            } finally {
                encodedSlot.releaseReadBuffer()
            }

            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                inBitmap = reusableBitmap
            }

            val bmp = try {
                BitmapFactory.decodeByteArray(tempBytes, 0, byteCount, opts)
            } catch (_: IllegalArgumentException) {
                reusableBitmap?.recycle()
                reusableBitmap = null
                BitmapFactory.decodeByteArray(
                    tempBytes, 0, byteCount,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                    }
                )
            } ?: return

            reusableBitmap = bmp
            val requiredBytes = bmp.width * bmp.height * 4
            var targetBuf = rgbaBuffers[writeBufferIdx]
            if (targetBuf == null || targetBuf.capacity() < requiredBytes) {
                targetBuf = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
                rgbaBuffers[writeBufferIdx] = targetBuf
            }

            targetBuf.clear()
            bmp.copyPixelsToBuffer(targetBuf)
            targetBuf.flip()

            val readyBuffer = targetBuf.asReadOnlyBuffer()
            writeBufferIdx = (writeBufferIdx + 1) % 3
            seq++

            onFrameDecoded(
                slotIndex,
                DecodedRgbaFrame(bmp.width, bmp.height, readyBuffer, seq)
            )

            if (seq % 2L == 0L) {
                val bins = LumaHistogramAnalyzer.computeNormalizedBins(
                    readyBuffer.duplicate(), bmp.width, bmp.height
                )
                onHistogramUpdated?.invoke(slotIndex, bins)
            }
        }

        fun release() {
            reusableBitmap?.recycle()
            reusableBitmap = null
            for (i in 0..2) rgbaBuffers[i] = null
        }
    }
}
