package com.whisperflow.floating.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object GlassColors {
    val DeepGlassSurface = Color(0x38121624)
    val GlassBorderHigh = Color(0x66FFFFFF)
    val GlassBorderLow = Color(0x15FFFFFF)
    
    val AccentViolet = Color(0xFF8B5CF6)
    val AccentCyan = Color(0xFF06B6D4)
    val AccentGlow = Color(0x408B5CF6)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextDraft = Color(0xFF38BDF8)

    val GlassBorderGradient = Brush.linearGradient(
        listOf(GlassBorderHigh, GlassBorderLow)
    )

    val AccentGradient = Brush.horizontalGradient(
        listOf(AccentViolet, AccentCyan)
    )
}
