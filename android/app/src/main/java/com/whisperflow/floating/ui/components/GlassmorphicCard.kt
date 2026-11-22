package com.whisperflow.floating.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whisperflow.floating.ui.theme.GlassColors

/**
 * A solid, high-contrast card with a soft glass-style border.
 *
 * NOTE: this intentionally does NOT use RenderEffect blur. A `graphicsLayer { renderEffect = blur }`
 * blurs the card's own content (text and icons included), not the backdrop behind it, which made
 * everything unreadable.
 */
@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(GlassColors.CardSurface)
            .border(1.dp, GlassColors.GlassBorderGradient, shape)
    ) {
        content()
    }
}
