package com.whisperflow.floating.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object GlassColors {
    // Opaque surfaces: text must stay readable on top of any app behind the overlay.
    val CardSurface = Color(0xFF172033)
    val DeepGlassSurface = Color(0xFF0B1220)
    val GlassBorderHigh = Color(0x66FFFFFF)
    val GlassBorderLow = Color(0x15FFFFFF)

    val AccentViolet = Color(0xFF8B5CF6)
    val AccentCyan = Color(0xFF06B6D4)
    val AccentGlow = Color(0x408B5CF6)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFFB6C2D4)
    val TextDraft = Color(0xFF7DD3FC)

    val GlassBorderGradient = Brush.linearGradient(
        listOf(GlassBorderHigh, GlassBorderLow)
    )

    val AccentGradient = Brush.horizontalGradient(
        listOf(AccentViolet, AccentCyan)
    )
}
