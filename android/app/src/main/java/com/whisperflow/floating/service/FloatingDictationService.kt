package com.whisperflow.floating.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.whisperflow.floating.audio.AudioCaptureManager
import com.whisperflow.floating.native.DictationListener
import com.whisperflow.floating.native.NativeDictationEngine
import com.whisperflow.floating.ui.FloatingWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DictationUiState(
    val isListening: Boolean = false,
    val isExpanded: Boolean = false,
    val audioLevelDb: Float = -60f,
    val draftText: String = "",
    val fullTranscript: String = "",
    val engineState: String = "Idle"
)

class FloatingDictationService : Service(), DictationListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val audioCapture = AudioCaptureManager()
    private var windowManager: FloatingWindowManager? = null

    private val _uiState = MutableStateFlow(DictationUiState())
    val uiState: StateFlow<DictationUiState> = _uiState.asStateFlow()

    companion object {
        const val CHANNEL_ID = "floating_dictation_channel"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FloatingDictationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingDictationService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // A foreground service must call startForeground() promptly, so do it first. It throws
        // if a required permission (e.g. the microphone, on Android 14+) is missing.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            stopSelf()
            return
        }

        if (!hasRequiredPermissions()) {
            Toast.makeText(
                this,
                "Enable microphone access and \"Draw over other apps\" first",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        NativeDictationEngine.setListener(this)
        NativeDictationEngine.initialize(filesDir.absolutePath)

        windowManager = FloatingWindowManager(this, uiState) { action ->
            handleUserAction(action)
        }
        try {
            windowManager?.show()
        } catch (_: Exception) {
            // e.g. the overlay permission was revoked while starting.
            windowManager = null
            stopSelf()
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            Settings.canDrawOverlays(this)

    private fun handleUserAction(action: FloatingAction) {
        when (action) {
            FloatingAction.ToggleListening -> {
                val wantListening = !_uiState.value.isListening
                if (wantListening) {
                    if (!audioCapture.startCapture(serviceScope)) {
                        Toast.makeText(this, "Couldn't start the microphone", Toast.LENGTH_SHORT).show()
                        return
                    }
                } else {
                    audioCapture.stopCapture()
                }
                _uiState.value = _uiState.value.copy(
                    isListening = wantListening,
                    isExpanded = wantListening || _uiState.value.fullTranscript.isNotEmpty()
                )
            }
            FloatingAction.ToggleExpanded -> {
                _uiState.value = _uiState.value.copy(
                    isExpanded = !_uiState.value.isExpanded
                )
            }
            FloatingAction.Clear -> {
                NativeDictationEngine.clear()
                _uiState.value = _uiState.value.copy(
                    draftText = "",
                    fullTranscript = ""
                )
            }
            FloatingAction.CloseService -> {
                stopSelf()
            }
        }
    }

    override fun onAudioLevel(levelDb: Float) {
        _uiState.value = _uiState.value.copy(audioLevelDb = levelDb)
    }

    override fun onDraftUpdated(draft: String) {
        _uiState.value = _uiState.value.copy(draftText = draft)
    }

    override fun onFullTranscriptUpdated(fullText: String) {
        _uiState.value = _uiState.value.copy(fullTranscript = fullText)
    }

    override fun onStateChanged(state: String) {
        _uiState.value = _uiState.value.copy(engineState = state)
    }

    override fun onDestroy() {
        audioCapture.stopCapture()
        windowManager?.hide()
        windowManager = null
        serviceJob.cancel()

        // Release the engine (and the transcript it holds) and drop the listener so the
        // singleton doesn't keep this destroyed service alive.
        NativeDictationEngine.clearListener()
        NativeDictationEngine.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WhisperFlow Dictation Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent status while dictation overlay is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhisperFlow Dictation")
            .setContentText("Tap overlay pill to dictate anytime")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}

sealed class FloatingAction {
    object ToggleListening : FloatingAction()
    object ToggleExpanded : FloatingAction()
    object Clear : FloatingAction()
    object CloseService : FloatingAction()
}
