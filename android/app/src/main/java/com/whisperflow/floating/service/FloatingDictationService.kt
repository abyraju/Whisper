package com.whisperflow.floating.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
        startForeground(NOTIFICATION_ID, buildNotification())

        NativeDictationEngine.setListener(this)
        NativeDictationEngine.nativeInit(filesDir.absolutePath)

        windowManager = FloatingWindowManager(this, uiState) { action ->
            handleUserAction(action)
        }
        windowManager?.show()
    }

    private fun handleUserAction(action: FloatingAction) {
        when (action) {
            FloatingAction.ToggleListening -> {
                val nextListening = !_uiState.value.isListening
                _uiState.value = _uiState.value.copy(
                    isListening = nextListening,
                    isExpanded = nextListening || _uiState.value.fullTranscript.isNotEmpty()
                )
                if (nextListening) {
                    audioCapture.startCapture(serviceScope)
                } else {
                    audioCapture.stopCapture()
                }
            }
            FloatingAction.ToggleExpanded -> {
                _uiState.value = _uiState.value.copy(
                    isExpanded = !_uiState.value.isExpanded
                )
            }
            FloatingAction.Clear -> {
                NativeDictationEngine.nativeClear()
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
        serviceJob.cancel()
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
