package com.canonmulticam.pro.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.canonmulticam.pro.domain.AnamorphicRatio
import com.canonmulticam.pro.domain.PeakingColor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class QuadGlRenderer : GLSurfaceView.Renderer {

    private data class CompiledProgram(
        val id: Int,
        val uTexture: Int,
        val uLutTex: Int,
        val uHasLutTexture: Int,
        val uPeakingColor: Int,
        val uVideoTexelSize: Int,
        val uScreenTexelSize: Int,
        val uTime: Int
    )

    private val videoTextures = IntArray(4)
    private val lutTextures = IntArray(4)
    private val vboIds = IntArray(1)

    private val hasLutUploaded = AtomicIntegerArray(4)
    private val savedLutData = AtomicReferenceArray<CubeLutData>(4)
    private val lutNeedsUpload = AtomicIntegerArray(4)

    private val texWidth = AtomicIntegerArray(4)
    private val texHeight = AtomicIntegerArray(4)
    private val decodedFrames = AtomicReferenceArray<DecodedRgbaFrame>(4)
    private val uploadedSeq = LongArray(4)

    private val slotToolFlags = AtomicIntegerArray(4)
    private val slotAnamorphic = AtomicReferenceArray<AnamorphicRatio>(4).apply {
        for (i in 0..3) set(i, AnamorphicRatio.NONE)
    }
    private val programCache = ConcurrentHashMap<Int, CompiledProgram>()

    @Volatile var soloSlot: Int? = null
    @Volatile var bypassAllLuts: Boolean = false
    @Volatile var peakingColor: PeakingColor = PeakingColor.RED

    private var viewportW = 1
    private var viewportH = 1
    private val startTimeMs = System.currentTimeMillis()

    private val quadVertices = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f
            ))
            position(0)
        }

    fun submitDecodedFrame(slotIndex: Int, frame: DecodedRgbaFrame) {
        decodedFrames.set(slotIndex, frame)
    }

    fun clearSlotFrame(slotIndex: Int) {
        if (slotIndex in 0..3) decodedFrames.set(slotIndex, null)
    }

    fun setSlotCubeLut(slotIndex: Int, lut: CubeLutData?) {
        savedLutData.set(slotIndex, lut)
        if (lut != null) {
            lutNeedsUpload.set(slotIndex, 1)
        } else {
            hasLutUploaded.set(slotIndex, 0)
            lutNeedsUpload.set(slotIndex, 0)
        }
    }

    fun setSlotTools(slotIndex: Int, flags: Int, ratio: AnamorphicRatio) {
        slotToolFlags.set(slotIndex, flags)
        slotAnamorphic.set(slotIndex, ratio)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        programCache.clear()
        for (i in 0..3) {
            texWidth.set(i, 0)
            texHeight.set(i, 0)
            uploadedSeq[i] = -1L
            hasLutUploaded.set(i, 0)
            if (savedLutData.get(i) != null) lutNeedsUpload.set(i, 1)
        }

        GLES30.glClearColor(0.067f, 0.067f, 0.067f, 1.0f)

        GLES30.glGenBuffers(1, vboIds, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
        quadVertices.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 16 * 4, quadVertices, GLES30.GL_STATIC_DRAW)

        GLES30.glGenTextures(4, videoTextures, 0)
        GLES30.glGenTextures(4, lutTextures, 0)

        for (i in 0..3) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextures[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextures[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val focused = soloSlot
        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000f

        if (focused != null) {
            renderSlot(focused, 0, 0, viewportW, viewportH, elapsedSec)
        } else {
            val halfW = viewportW / 2
            val halfH = viewportH / 2
            renderSlot(0, 0, halfH, halfW, halfH, elapsedSec)
            renderSlot(1, halfW, halfH, halfW, halfH, elapsedSec)
            renderSlot(2, 0, 0, halfW, halfH, elapsedSec)
            renderSlot(3, halfW, 0, halfW, halfH, elapsedSec)
        }
    }

    private fun renderSlot(index: Int, vx: Int, vy: Int, vw: Int, vh: Int, timeSec: Float) {
        if (lutNeedsUpload.getAndSet(index, 0) == 1) {
            savedLutData.get(index)?.let { uploadLutAsRgba8(index, it) }
        }

        val frame = decodedFrames.get(index) ?: return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextures[index])
        if (frame.sequenceId != uploadedSeq[index]) {
            uploadedSeq[index] = frame.sequenceId
            val buf = frame.pixelBuffer.duplicate().apply { position(0) }
            if (texWidth.get(index) != frame.width || texHeight.get(index) != frame.height) {
                texWidth.set(index, frame.width)
                texHeight.set(index, frame.height)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                    frame.width, frame.height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
                )
            } else {
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0,
                    frame.width, frame.height,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
                )
            }
        }

        val w = texWidth.get(index)
        val h = texHeight.get(index)
        val baseAspect = if (h > 0) w.toFloat() / h else (16f / 9f)
        val ratio = slotAnamorphic.get(index) ?: AnamorphicRatio.NONE
        val videoAspect = baseAspect * ratio.factor
        val boxAspect = vw.toFloat() / vh.toFloat()
        val drawW = if (boxAspect > videoAspect) (vh * videoAspect).toInt() else vw
        val drawH = if (boxAspect > videoAspect) vh else (vw / videoAspect).toInt()
        val offsetX = vx + (vw - drawW) / 2
        val offsetY = vy + (vh - drawH) / 2

        GLES30.glViewport(offsetX, offsetY, drawW, drawH)
        val prog = getOrCompileProgram(slotToolFlags.get(index))
        if (prog.id == 0) return
        GLES30.glUseProgram(prog.id)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, videoTextures[index])
        GLES30.glUniform1i(prog.uTexture, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextures[index])
        GLES30.glUniform1i(prog.uLutTex, 1)
        GLES30.glUniform1i(
            prog.uHasLutTexture,
            if (hasLutUploaded.get(index) == 1 && !bypassAllLuts) 1 else 0
        )

        val pk = peakingColor
        val vidTexelX = if (w > 0) 1f / w else 1f / drawW
        val vidTexelY = if (h > 0) 1f / h else 1f / drawH

        GLES30.glUniform3f(prog.uPeakingColor, pk.r, pk.g, pk.b)
        GLES30.glUniform2f(prog.uVideoTexelSize, vidTexelX, vidTexelY)
        GLES30.glUniform2f(prog.uScreenTexelSize, 1f / drawW, 1f / drawH)
        GLES30.glUniform1f(prog.uTime, timeSec)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uploadLutAsRgba8(slotIndex: Int, lut: CubeLutData) {
        val totalEntries = lut.size * lut.size * lut.size
        val src = lut.buffer.duplicate().apply { position(0) }
        val rgbaBytes = ByteBuffer.allocateDirect(totalEntries * 4).order(ByteOrder.nativeOrder())

        for (i in 0 until totalEntries) {
            rgbaBytes.put((src.get().coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            rgbaBytes.put((src.get().coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            rgbaBytes.put((src.get().coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            rgbaBytes.put(0xFF.toByte())
        }
        rgbaBytes.position(0)

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextures[slotIndex])
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8,
            lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgbaBytes
        )
        hasLutUploaded.set(slotIndex, 1)
    }

    fun mapCellTouchToSensor(
        localX: Float,
        localY: Float,
        cellW: Float,
        cellH: Float,
        slotIndex: Int
    ): Pair<Float, Float>? {
        if (cellW <= 0f || cellH <= 0f) return null
        val w = texWidth.get(slotIndex)
        val h = texHeight.get(slotIndex)
        val baseAspect = if (h > 0) w.toFloat() / h else (16f / 9f)
        val ratio = slotAnamorphic.get(slotIndex) ?: AnamorphicRatio.NONE
        val videoAspect = baseAspect * ratio.factor
        val boxAspect = cellW / cellH
        val drawW = if (boxAspect > videoAspect) cellH * videoAspect else cellW
        val drawH = if (boxAspect > videoAspect) cellH else cellW / videoAspect
        val padX = (cellW - drawW) / 2f
        val padY = (cellH - drawH) / 2f

        val normX = (localX - padX) / drawW
        val normY = (localY - padY) / drawH
        if (normX !in 0f..1f || normY !in 0f..1f) return null
        return normX to normY
    }

    fun releaseGlResources() {
        GLES30.glDeleteBuffers(1, vboIds, 0)
        GLES30.glDeleteTextures(4, videoTextures, 0)
        GLES30.glDeleteTextures(4, lutTextures, 0)
        programCache.values.forEach { GLES30.glDeleteProgram(it.id) }
        programCache.clear()
    }

    private fun getOrCompileProgram(flags: Int): CompiledProgram {
        programCache[flags]?.let { return it }

        val compiled = compileProgramInternal(flags)
        if (compiled.id != 0) {
            programCache[flags] = compiled
            return compiled
        }

        if (flags != 0) {
            val fallback = programCache[0] ?: compileProgramInternal(0).also {
                if (it.id != 0) programCache[0] = it
            }
            if (fallback.id != 0) {
                programCache[flags] = fallback
                return fallback
            }
        }
        return createEmptyProgram()
    }

    private fun compileProgramInternal(flags: Int): CompiledProgram {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, ShaderVariantCompiler.VERTEX_SHADER)
        val fs = compileShader(
            GLES30.GL_FRAGMENT_SHADER,
            ShaderVariantCompiler.buildFragmentShader(flags)
        )
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES30.glDeleteShader(vs)
            if (fs != 0) GLES30.glDeleteShader(fs)
            return createEmptyProgram()
        }
        val progId = GLES30.glCreateProgram()
        GLES30.glAttachShader(progId, vs)
        GLES30.glAttachShader(progId, fs)
        GLES30.glLinkProgram(progId)
        GLES30.glDetachShader(progId, vs)
        GLES30.glDetachShader(progId, fs)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(progId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e("QuadGlRenderer", "Program link error: ${GLES30.glGetProgramInfoLog(progId)}")
            GLES30.glDeleteProgram(progId)
            return createEmptyProgram()
        }
        return bindProgramUniforms(progId)
    }

    private fun createEmptyProgram(): CompiledProgram =
        CompiledProgram(0, -1, -1, -1, -1, -1, -1, -1)

    private fun bindProgramUniforms(progId: Int) = CompiledProgram(
        id = progId,
        uTexture = GLES30.glGetUniformLocation(progId, "uTexture"),
        uLutTex = GLES30.glGetUniformLocation(progId, "uLutTex"),
        uHasLutTexture = GLES30.glGetUniformLocation(progId, "uHasLutTexture"),
        uPeakingColor = GLES30.glGetUniformLocation(progId, "uPeakingColor"),
        uVideoTexelSize = GLES30.glGetUniformLocation(progId, "uVideoTexelSize"),
        uScreenTexelSize = GLES30.glGetUniformLocation(progId, "uScreenTexelSize"),
        uTime = GLES30.glGetUniformLocation(progId, "uTime")
    )

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("QuadGlRenderer", "Shader compile error: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
