package com.canonmulticam.pro.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalLevel(val label: String, val pollDelayMs: Long) {
    NORMAL("COOL", 25L),
    WARM("WARM", 55L),
    HOT("HOT-ECO", 110L)
}

class ThermalGuard(context: Context) {

    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _thermalLevel = MutableStateFlow(ThermalLevel.NORMAL)
    val thermalLevel: StateFlow<ThermalLevel> = _thermalLevel.asStateFlow()

    private val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            _thermalLevel.value = when {
                status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.HOT
                status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
                else -> ThermalLevel.NORMAL
            }
        }
    } else {
        null
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener != null) {
            runCatching { powerManager.addThermalStatusListener(listener) }
        }
    }

    fun destroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener != null) {
            runCatching { powerManager.removeThermalStatusListener(listener) }
        }
    }
}
