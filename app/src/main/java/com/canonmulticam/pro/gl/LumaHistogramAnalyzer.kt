package com.canonmulticam.pro.gl

import java.nio.ByteBuffer

object LumaHistogramAnalyzer {

    private const val BIN_COUNT = 64

    fun computeNormalizedBins(rgbaBuffer: ByteBuffer, width: Int, height: Int): FloatArray {
        val bins = FloatArray(BIN_COUNT)
        val totalPixels = width * height
        if (totalPixels <= 0) return bins

        val buf = rgbaBuffer.duplicate().apply { position(0) }
        val limit = buf.limit()
        val byteStride = 64
        var maxCount = 1

        var offset = 0
        while (offset + 3 < limit) {
            val r = buf.get(offset).toInt() and 0xFF
            val g = buf.get(offset + 1).toInt() and 0xFF
            val b = buf.get(offset + 2).toInt() and 0xFF
            val luma = (54 * r + 183 * g + 19 * b) shr 8
            val binIdx = (luma * (BIN_COUNT - 1)) / 255
            val nextVal = bins[binIdx].toInt() + 1
            bins[binIdx] = nextVal.toFloat()
            if (nextVal > maxCount) maxCount = nextVal
            offset += byteStride
        }

        val invMax = 1f / maxCount.toFloat()
        for (i in 0 until BIN_COUNT) {
            bins[i] *= invMax
        }
        return bins
    }
}
