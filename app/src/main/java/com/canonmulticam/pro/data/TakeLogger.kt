package com.canonmulticam.pro.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TakeRecord(
    val takeNumber: Int,
    val startWallTime: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val activeCameras: List<String>,
    val isGoodTake: Boolean = false,
    val slateCount: Int = 0
)

class TakeLogger {

    private val _takes = MutableStateFlow<List<TakeRecord>>(emptyList())
    val takes: StateFlow<List<TakeRecord>> = _takes.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun startTake(cameraNames: List<String>): Int {
        val nextNum = (_takes.value.lastOrNull()?.takeNumber ?: 0) + 1
        val now = System.currentTimeMillis()
        val record = TakeRecord(
            takeNumber = nextNum,
            startWallTime = timeFormatter.format(Date(now)),
            startEpochMs = now,
            activeCameras = cameraNames
        )
        _takes.value = _takes.value + record
        return nextNum
    }

    fun markCurrentTakeGood() {
        val current = _takes.value
        if (current.isEmpty()) return
        val last = current.last()
        _takes.value = current.dropLast(1) + last.copy(isGoodTake = !last.isGoodTake)
    }

    fun incrementSlateOnCurrentTake() {
        val current = _takes.value
        if (current.isEmpty()) return
        val last = current.last()
        _takes.value = current.dropLast(1) + last.copy(slateCount = last.slateCount + 1)
    }

    fun stopCurrentTake() {
        val current = _takes.value
        if (current.isEmpty()) return
        val last = current.last()
        if (last.endEpochMs == null) {
            _takes.value = current.dropLast(1) + last.copy(endEpochMs = System.currentTimeMillis())
        }
    }

    fun exportPremiereMarkersCsv(): String = buildString {
        appendLine("Marker Name,Description,In,Out,Duration,Marker Type")
        for (t in _takes.value) {
            val endMs = t.endEpochMs ?: System.currentTimeMillis()
            val durSec = ((endMs - t.startEpochMs) / 1000L).coerceAtLeast(1L)
            val hrs = durSec / 3600L
            val mins = (durSec % 3600L) / 60L
            val secs = durSec % 60L
            val rating = if (t.isGoodTake) " [GOOD TAKE]" else ""
            val cams = t.activeCameras.joinToString(" + ").replace("\"", "\"\"")
            val takeCol = String.format(Locale.US, "\"TAKE %02d%s\"", t.takeNumber, rating)
            val descCol = "\"Cameras: $cams | Slates: ${t.slateCount}\""
            val durCol = String.format(Locale.US, "\"%02d:%02d:%02d:00\"", hrs, mins, secs)
            appendLine("$takeCol,$descCol,\"${t.startWallTime}:00\",\"\",$durCol,\"Comment\"")
        }
    }
}
