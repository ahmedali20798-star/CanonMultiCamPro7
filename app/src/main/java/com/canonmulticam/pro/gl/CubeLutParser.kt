package com.canonmulticam.pro.gl

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

data class CubeLutData(
    val size: Int,
    val buffer: FloatBuffer
)

object CubeLutParser {
    fun parse(inputStream: InputStream): Result<CubeLutData> = runCatching {
        var lutSize = 0
        val values = ArrayList<Float>(33 * 33 * 33 * 3)

        BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("TITLE") || line.startsWith("DOMAIN_")) continue

                if (line.startsWith("LUT_3D_SIZE")) {
                    lutSize = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                    continue
                }

                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val r = parts[0].toFloatOrNull()
                    val g = parts[1].toFloatOrNull()
                    val b = parts[2].toFloatOrNull()
                    if (r != null && g != null && b != null) {
                        values.add(r.coerceIn(0f, 1f))
                        values.add(g.coerceIn(0f, 1f))
                        values.add(b.coerceIn(0f, 1f))
                    }
                }
            }
        }

        require(lutSize in 2..65) { "Invalid LUT_3D_SIZE: $lutSize" }
        val expectedFloats = lutSize * lutSize * lutSize * 3
        require(values.size == expectedFloats) {
            "LUT entry count mismatch: expected $expectedFloats, got ${values.size}"
        }

        val byteBuffer = ByteBuffer.allocateDirect(expectedFloats * 4)
            .order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()
        for (v in values) floatBuffer.put(v)
        floatBuffer.position(0)

        CubeLutData(size = lutSize, buffer = floatBuffer)
    }
}
