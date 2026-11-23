package com.canonmulticam.pro.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun DigitalClapperOverlay(
    isFlashing: Boolean,
    onFlashComplete: () -> Unit
) {
    if (!isFlashing) return

    LaunchedEffect(Unit) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }
        delay(140L)
        runCatching { tone?.release() }
        onFlashComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "SYNC SLATE",
            color = Color.Black,
            fontSize = 42.sp,
            fontWeight = FontWeight.Black
        )
    }
}
