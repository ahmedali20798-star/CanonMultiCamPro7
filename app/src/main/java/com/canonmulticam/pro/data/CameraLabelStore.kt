package com.canonmulticam.pro.data

import android.content.Context

interface ILabelStore {
    fun saveLabel(slotIndex: Int, cameraUuid: String?, rawLabel: String)
    fun resolveLabel(slotIndex: Int, cameraUuid: String?): String?
}

class CameraLabelStore(context: Context) : ILabelStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("canon_multicam_labels", Context.MODE_PRIVATE)

    override fun saveLabel(slotIndex: Int, cameraUuid: String?, rawLabel: String) {
        val clean = rawLabel.trim().take(24)
        prefs.edit().apply {
            if (!cameraUuid.isNullOrBlank()) {
                if (clean.isEmpty()) remove("uuid_$cameraUuid")
                else putString("uuid_$cameraUuid", clean)
                remove("slot_$slotIndex")
            } else {
                if (clean.isEmpty()) remove("slot_$slotIndex")
                else putString("slot_$slotIndex", clean)
            }
        }.apply()
    }

    override fun resolveLabel(slotIndex: Int, cameraUuid: String?): String? {
        if (!cameraUuid.isNullOrBlank()) {
            val byUuid = prefs.getString("uuid_$cameraUuid", null)
            if (!byUuid.isNullOrBlank()) return byUuid
        }
        return prefs.getString("slot_$slotIndex", null)?.takeIf { it.isNotBlank() }
    }
}
