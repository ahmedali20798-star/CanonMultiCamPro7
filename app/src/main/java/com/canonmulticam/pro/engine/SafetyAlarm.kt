package com.canonmulticam.pro.engine

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class SafetyAlarm(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    fun triggerRecordFailureAlert() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 200, 90, 320), -1)
            )
        }

        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 95)
            tone.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 400)
            mainHandler.postDelayed({ runCatching { tone.release() } }, 500L)
        }
    }
}
