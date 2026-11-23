package com.canonmulticam.pro.engine

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.canonmulticam.pro.data.CameraLabelStore
import com.canonmulticam.pro.data.TakeLogger
import com.canonmulticam.pro.domain.CameraSlotState
import com.canonmulticam.pro.domain.ExposureSettings
import com.canonmulticam.pro.domain.LatestFrameSlot
import com.canonmulticam.pro.domain.StreamQuality
import com.canonmulticam.pro.gl.CubeLutData
import com.canonmulticam.pro.gl.CubeLutParser
import com.canonmulticam.pro.transport.DiscoveredCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray

class MultiCamViewModel(application: Application) : AndroidViewModel(application) {

    private val labelStore = CameraLabelStore(application)
    private val safetyAlarm = SafetyAlarm(application)
    val thermalGuard = ThermalGuard(application)
    val takeLogger = TakeLogger()

    val histogramBins = AtomicReferenceArray<FloatArray>(4)

    var frameDeliveryCallback: ((Int, LatestFrameSlot) -> Unit)? = null
    var slotDisconnectedCallback: ((Int) -> Unit)? = null

    val actors = List(4) { index ->
        CameraSessionActor(
            slotIndex = index,
            labelStore = labelStore,
            thermalLevelProvider = { thermalGuard.thermalLevel.value },
            onUnexpectedRecordStop = { stoppedSlot ->
                safetyAlarm.triggerRecordFailureAlert()
                _recordFailureBanner.value =
                    "تنبيه طارئ: توقف التسجيل فجأة في (${stoppedSlot.displayHeader})"
            },
            onDisconnectedCallback = { slotIdx ->
                histogramBins.set(slotIdx, null)
                slotDisconnectedCallback?.invoke(slotIdx)
            },
            onFrameUpdated = { slotIdx, frameSlot ->
                frameDeliveryCallback?.invoke(slotIdx, frameSlot)
            }
        )
    }

    val slotsState: StateFlow<List<CameraSlotState>> = combine(
        actors.map { it.state }
    ) { it.toList() }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        List(4) { CameraSlotState(it) }
    )

    private val _selectedSlotIndex = MutableStateFlow(0)
    val selectedSlotIndex: StateFlow<Int> = _selectedSlotIndex.asStateFlow()

    private val _soloSlotIndex = MutableStateFlow<Int?>(null)
    val soloSlotIndex: StateFlow<Int?> = _soloSlotIndex.asStateFlow()

    private val _isGroupRecording = MutableStateFlow(false)
    val isGroupRecording: StateFlow<Boolean> = _isGroupRecording.asStateFlow()

    private val _recordingStartTimeMs = MutableStateFlow<Long?>(null)
    val recordingStartTimeMs: StateFlow<Long?> = _recordingStartTimeMs.asStateFlow()

    private val _recordFailureBanner = MutableStateFlow<String?>(null)
    val recordFailureBanner: StateFlow<String?> = _recordFailureBanner.asStateFlow()

    private val _previousExposureSnapshot = MutableStateFlow<Map<Int, ExposureSettings>>(emptyMap())
    val canUndoMatch: StateFlow<Boolean> = _previousExposureSnapshot
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAppBackgrounded(paused: Boolean) {
        actors.forEach { it.setLifecyclePaused(paused) }
    }

    fun selectSlot(slotIndex: Int) {
        if (slotIndex in 0..3) _selectedSlotIndex.value = slotIndex
    }

    fun onCameraDiscovered(camera: DiscoveredCamera) {
        val existingSlot = actors.firstOrNull { it.state.value.boundUuid == camera.uuid }?.slotIndex
        if (existingSlot != null) {
            bindOrReconnectCamera(existingSlot, camera.ipAddress, camera.uuid)
            return
        }

        val freeSlot = actors.firstOrNull {
            !it.state.value.isConnected && it.state.value.boundUuid == null
        }?.slotIndex ?: return

        bindOrReconnectCamera(freeSlot, camera.ipAddress, camera.uuid)
    }

    fun bindOrReconnectCamera(preferredSlot: Int, ipAddress: String, knownUuid: String? = null) {
        val targetSlot = if (knownUuid != null) {
            actors.firstOrNull { it.state.value.boundUuid == knownUuid }?.slotIndex ?: preferredSlot
        } else {
            preferredSlot
        }

        val quality = if (_soloSlotIndex.value == targetSlot) {
            StreamQuality.FULL_SOLO
        } else {
            StreamQuality.LOW_QUAD
        }
        actors[targetSlot].connect(ipAddress, quality)
    }

    fun toggleSoloZoom(slotIndex: Int) {
        viewModelScope.launch {
            _selectedSlotIndex.value = slotIndex
            val newSolo = if (_soloSlotIndex.value == slotIndex) null else slotIndex
            _soloSlotIndex.value = newSolo

            actors.forEachIndexed { idx, actor ->
                val targetQuality = if (newSolo == idx) {
                    StreamQuality.FULL_SOLO
                } else {
                    StreamQuality.LOW_QUAD
                }
                actor.switchStreamQuality(targetQuality)
            }
        }
    }

    fun toggleGroupRecord() {
        viewModelScope.launch {
            val targetState = !_isGroupRecording.value
            _recordFailureBanner.value = null

            val connectedActors = actors.filter { it.state.value.isConnected }
            if (connectedActors.isEmpty()) {
                _isGroupRecording.value = false
                _recordingStartTimeMs.value = null
                return@launch
            }

            val results = connectedActors.map { actor ->
                async { actor to actor.setRecordingWithAck(targetState) }
            }.awaitAll()

            val succeededActors = results.filter { it.second }.map { it.first }
            val failedActors = results.filter { !it.second }.map { it.first }

            if (targetState && succeededActors.isNotEmpty()) {
                _isGroupRecording.value = true
                if (_recordingStartTimeMs.value == null) {
                    _recordingStartTimeMs.value = System.currentTimeMillis()
                    takeLogger.startTake(succeededActors.map { it.state.value.displayHeader })
                }
            } else {
                if (_isGroupRecording.value) takeLogger.stopCurrentTake()
                _isGroupRecording.value = false
                _recordingStartTimeMs.value = null
            }

            if (failedActors.isNotEmpty()) {
                safetyAlarm.triggerRecordFailureAlert()
                val names = failedActors.joinToString(" • ") { it.state.value.displayHeader }
                _recordFailureBanner.value = "تحذير: فشل أمر التسجيل في ($names)"
            }
        }
    }

    fun onSlateTriggered() {
        if (_isGroupRecording.value) {
            takeLogger.incrementSlateOnCurrentTake()
        }
    }

    fun shareTakeLogCsv() {
        val csvContent = takeLogger.exportPremiereMarkersCsv()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "CanonMultiCam Pro - Edit Markers CSV")
            putExtra(Intent.EXTRA_TEXT, csvContent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "تصدير سجل اللقطات للمونتاج").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { getApplication<Application>().startActivity(chooser) }
    }

    fun dismissFailureBanner() {
        _recordFailureBanner.value = null
    }

    fun renameCamera(slotIndex: Int, newLabel: String) {
        if (slotIndex in 0..3) actors[slotIndex].updateCustomLabel(newLabel)
    }

    fun importCubeLutAsync(slotIndex: Int, uri: Uri, onParsed: (Int, CubeLutData) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = getApplication<Application>().contentResolver
                .openInputStream(uri)
                ?.use { CubeLutParser.parse(it).getOrNull() }
                ?: return@launch

            actors[slotIndex].setCubeLut(parsed)
            onParsed(slotIndex, parsed)
        }
    }

    fun matchAllToMaster(masterSlotIndex: Int) {
        viewModelScope.launch {
            val masterExposure = actors[masterSlotIndex].readExposure() ?: return@launch
            val backups = ConcurrentHashMap<Int, ExposureSettings>()

            actors.filterIndexed { idx, actor ->
                idx != masterSlotIndex && actor.state.value.isConnected
            }.map { actor ->
                async {
                    actor.readExposure()?.let { old -> backups[actor.slotIndex] = old }
                    actor.syncExposureFrom(masterExposure)
                    actor.setMatchedReference(masterExposure)
                }
            }.awaitAll()

            if (backups.isNotEmpty()) _previousExposureSnapshot.value = backups.toMap()
        }
    }

    fun undoMatchAll() {
        viewModelScope.launch {
            val snapshot = _previousExposureSnapshot.value
            if (snapshot.isEmpty()) return@launch
            snapshot.map { (slotIdx, oldExp) ->
                async {
                    actors[slotIdx].setMatchedReference(null)
                    actors[slotIdx].syncExposureFrom(oldExp)
                }
            }.awaitAll()
            _previousExposureSnapshot.value = emptyMap()
        }
    }

    fun onTouchAf(slotIndex: Int, normX: Float, normY: Float) {
        _selectedSlotIndex.value = slotIndex
        viewModelScope.launch { actors[slotIndex].touchAf(normX, normY) }
    }

    override fun onCleared() {
        actors.forEach { it.destroy() }
        thermalGuard.destroy()
        super.onCleared()
    }
}
