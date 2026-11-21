package com.whisperflow.floating.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.whisperflow.floating.ui.theme.GlassColors

@Composable
fun WaveformVisualizer(
    levelDb: Float,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    // Normalize dB (-60dB to 0dB) to a fraction (0.1 to 1.0)
    val normalized = if (!isListening) 0.08f else {
        ((levelDb + 60f) / 60f).coerceIn(0.1f, 1.0f)
    }

    val animatedLevel by animateFloatAsState(
        targetValue = normalized,
        animationSpec = tween(durationMillis = 80),
        label = "waveform_anim"
    )

    val barMultipliers = listOf(0.4f, 0.7f, 1.0f, 0.85f, 0.5f)

    Row(
        modifier = modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        barMultipliers.forEach { mult ->
            val barFraction = (animatedLevel * mult).coerceIn(0.12f, 1.0f)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(barFraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GlassColors.AccentGradient)
            )
        }
    }
}
