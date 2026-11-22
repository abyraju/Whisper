package com.whisperflow.floating.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisperflow.floating.injection.DictationAccessibilityService
import com.whisperflow.floating.model.ModelDownloader
import com.whisperflow.floating.service.FloatingDictationService
import com.whisperflow.floating.ui.components.GlassmorphicCard
import com.whisperflow.floating.ui.theme.GlassColors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var hasMicPermission by mutableStateOf(false)
    private var hasOverlayPermission by mutableStateOf(false)
    private var hasAccessibilityPermission by mutableStateOf(false)

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePermissionStates()

        setContent {
            FunOnboardingScreen(
                hasMic = hasMicPermission,
                hasOverlay = hasOverlayPermission,
                hasAccessibility = hasAccessibilityPermission,
                onGrantAudio = {
                    requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onGrantOverlay = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                },
                onGrantAccessibility = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                },
                onLaunchService = {
                    if (!hasMicPermission || !hasOverlayPermission) {
                        Toast.makeText(
                            this,
                            "Enable microphone access and \"Draw over other apps\" first!",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        FloatingDictationService.start(this)
                        finish()
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        hasMicPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        hasAccessibilityPermission = DictationAccessibilityService.isRunning()
    }
}

@Composable
fun FunOnboardingScreen(
    hasMic: Boolean,
    hasOverlay: Boolean,
    hasAccessibility: Boolean,
    onGrantAudio: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onLaunchService: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isModelReady by remember { mutableStateOf(ModelDownloader.isModelInstalled(context)) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(if (isModelReady) 1.0f else 0.0f) }
    var downloadStatus by remember {
        mutableStateOf(if (isModelReady) "✨ Neural speech engine ready!" else "Tap below to fetch speech models")
    }

    // Auto-trigger model download if permissions are partially or fully ready
    LaunchedEffect(hasMic, hasOverlay) {
        if (!isModelReady && !isDownloading && (hasMic || hasOverlay)) {
            isDownloading = true
            scope.launch {
                ModelDownloader.downloadAndInstall(
                    context = context,
                    onProgress = { progress, status ->
                        downloadProgress = progress
                        downloadStatus = status
                    },
                    onSuccess = {
                        isModelReady = true
                        isDownloading = false
                    },
                    onError = {
                        isDownloading = false
                    }
                )
            }
        }
    }

    // Animated glow transition
    val infiniteTransition = rememberInfiniteTransition(label = "orb_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val orbRotate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassmorphicCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            cornerRadius = 32.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Pulsing Futuristic AI Orb Header
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .scale(pulseScale),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .rotate(orbRotate)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        GlassColors.AccentViolet,
                                        GlassColors.AccentCyan,
                                        Color(0xFFEC4899),
                                        GlassColors.AccentViolet
                                    )
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isModelReady) Icons.Default.AutoAwesome else Icons.Default.Mic,
                            contentDescription = "AI Core",
                            tint = GlassColors.AccentCyan,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Let's Get Things Started!",
                    color = GlassColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Your private on-device dictation companion. Speak anywhere, anytime with instant Zipformer streaming.",
                    color = GlassColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Interactive Model Downloader Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x2B1E293B))
                        .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isModelReady) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = if (isModelReady) Color(0xFF10B981) else GlassColors.AccentCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isModelReady) "Speech Engine Installed" else "Local Neural Models",
                                    color = GlassColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "${(downloadProgress * 100).toInt()}%",
                                color = GlassColors.AccentCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val animatedProgress by animateFloatAsState(
                            targetValue = downloadProgress,
                            animationSpec = tween(400),
                            label = "progress_bar"
                        )

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = GlassColors.AccentCyan,
                            trackColor = Color(0x33475569)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = downloadStatus,
                            color = GlassColors.TextSecondary,
                            fontSize = 12.sp
                        )

                        if (!isModelReady && !isDownloading) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    isDownloading = true
                                    scope.launch {
                                        ModelDownloader.downloadAndInstall(
                                            context = context,
                                            onProgress = { progress, status ->
                                                downloadProgress = progress
                                                downloadStatus = status
                                            },
                                            onSuccess = {
                                                isModelReady = true
                                                isDownloading = false
                                            },
                                            onError = {
                                                isDownloading = false
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GlassColors.AccentViolet)
                            ) {
                                Text(text = "Download Neural Models (~45MB)", fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Permissions List
                FunPermissionCard(
                    title = "Microphone Access",
                    description = "Required to capture audio for speech recognition.",
                    isGranted = hasMic,
                    onClick = onGrantAudio
                )

                Spacer(modifier = Modifier.height(10.dp))

                FunPermissionCard(
                    title = "Draw Over Other Apps",
                    description = "Enables the floating pill to appear everywhere.",
                    isGranted = hasOverlay,
                    onClick = onGrantOverlay
                )

                Spacer(modifier = Modifier.height(10.dp))

                FunPermissionCard(
                    title = "Instant Auto-Paste",
                    description = "Optional: automatically types into the focused input field.",
                    isGranted = hasAccessibility,
                    onClick = onGrantAccessibility
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Big Glowing Launch Button
                val canLaunch = hasMic && hasOverlay
                Button(
                    onClick = onLaunchService,
                    enabled = canLaunch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GlassColors.AccentViolet,
                        disabledContainerColor = Color(0x33475569)
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (canLaunch) "Launch WhisperFlow Floating" else "Grant Permissions to Launch",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FunPermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isGranted) Color(0x6610B981) else Color(0x22FFFFFF),
        label = "perm_border"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x1F1E293B))
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isGranted) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Granted",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    color = GlassColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = description,
                color = GlassColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isGranted) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x3310B981))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(text = "Active", fontSize = 12.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Enable", fontSize = 12.sp, color = GlassColors.AccentCyan)
            }
        }
    }
}
