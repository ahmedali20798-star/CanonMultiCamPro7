package com.canonmulticam.pro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReferenceArray

@Composable
fun LumaHistogramWidget(
    slotIndex: Int,
    histogramSource: AtomicReferenceArray<FloatArray>,
    modifier: Modifier = Modifier
) {
    var pulse by remember { mutableIntStateOf(0) }
    LaunchedEffect(slotIndex) {
        while (true) {
            delay(66L)
            pulse++
        }
    }

    val bins = histogramSource.get(slotIndex) ?: return

    Canvas(
        modifier = modifier
            .width(108.dp)
            .height(38.dp)
            .background(Color(0x99111111), MaterialTheme.shapes.extraSmall)
            .border(1.dp, Color(0x55B8B5AE), MaterialTheme.shapes.extraSmall)
            .padding(3.dp)
    ) {
        @Suppress("UNUSED_EXPRESSION")
        pulse
        val barWidth = size.width / bins.size.toFloat()
        for (i in bins.indices) {
            val h = (bins[i].coerceIn(0f, 1f)) * size.height
            if (h > 0.5f) {
                val barColor = when {
                    i >= bins.size - 3 -> Color(0xFFE53935)
                    i <= 2 -> Color(0xFF42A5F5)
                    else -> Color(0xFFF5F3EE).copy(alpha = 0.85f)
                }
                drawRect(
                    color = barColor,
                    topLeft = Offset(i * barWidth, size.height - h),
                    size = Size(barWidth * 0.9f, h)
                )
            }
        }
    }
}
