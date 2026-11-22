package com.whisperflow.floating.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisperflow.floating.service.DictationUiState
import com.whisperflow.floating.service.FloatingAction
import com.whisperflow.floating.ui.components.GlassmorphicCard
import com.whisperflow.floating.ui.components.WaveformVisualizer
import com.whisperflow.floating.ui.theme.GlassColors

@Composable
fun FloatingOverlayView(
    state: DictationUiState,
    onAction: (FloatingAction) -> Unit,
    onInject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(6.dp)
    ) {
        if (!state.isExpanded) {
            // Compact Docked Pill
            DockedPill(
                isListening = state.isListening,
                levelDb = state.audioLevelDb,
                onClick = { onAction(FloatingAction.ToggleListening) },
                onLongClick = { onAction(FloatingAction.ToggleExpanded) }
            )
        } else {
            // Expanded Console Card
            ExpandedConsoleCard(
                state = state,
                onAction = onAction,
                onInject = onInject
            )
        }
    }
}

@Composable
private fun DockedPill(
    isListening: Boolean,
    levelDb: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val scalePulse by animateFloatAsState(
        targetValue = if (isListening) 1.08f else 1.0f,
        label = "pill_pulse"
    )

    GlassmorphicCard(
        modifier = Modifier
            .size(56.dp)
            .scale(scalePulse)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        cornerRadius = 28.dp
    ) {
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isListening) {
                WaveformVisualizer(
                    levelDb = levelDb,
                    isListening = true,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Start Dictation",
                    tint = GlassColors.TextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun ExpandedConsoleCard(
    state: DictationUiState,
    onAction: (FloatingAction) -> Unit,
    onInject: () -> Unit
) {
    val scrollState = rememberScrollState()

    GlassmorphicCard(
        modifier = Modifier.width(300.dp),
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Drag handle + Live Waveform + Close
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Drag handle pill
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GlassColors.GlassBorderHigh)
                )

                WaveformVisualizer(
                    levelDb = state.audioLevelDb,
                    isListening = state.isListening
                )

                IconButton(
                    onClick = { onAction(FloatingAction.ToggleExpanded) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Collapse",
                        tint = GlassColors.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Transcript Body: Committed + Active Draft
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassColors.DeepGlassSurface)
                    .padding(10.dp)
                    .verticalScroll(scrollState)
            ) {
                if (state.fullTranscript.isEmpty() && state.draftText.isEmpty()) {
                    Text(
                        text = if (state.isListening) "Listening..." else "Tap mic to speak",
                        color = GlassColors.TextSecondary,
                        fontSize = 16.sp
                    )
                } else {
                    Column {
                        if (state.fullTranscript.isNotEmpty()) {
                            Text(
                                text = state.fullTranscript,
                                color = GlassColors.TextPrimary,
                                fontSize = 17.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        if (state.draftText.isNotEmpty()) {
                            Text(
                                text = " ${state.draftText}",
                                color = GlassColors.TextDraft,
                                fontSize = 17.sp,
                                lineHeight = 24.sp,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Bar: [Clear], [Mic Toggle], [One-Tap Inject]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Clear button
                IconButton(
                    onClick = { onAction(FloatingAction.Clear) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Clear",
                        tint = GlassColors.TextSecondary
                    )
                }

                // Mic toggle button
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.isListening) GlassColors.AccentGradient else GlassColors.GlassBorderGradient
                        )
                        .clickable { onAction(FloatingAction.ToggleListening) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state.isListening) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Toggle Mic",
                        tint = GlassColors.TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // One-Tap Inject Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(GlassColors.AccentGradient)
                        .clickable(onClick = onInject)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Inject",
                            tint = GlassColors.TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Inject",
                            color = GlassColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
