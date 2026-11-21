package com.whisperflow.floating.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.whisperflow.floating.native.NativeDictationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioCaptureManager {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope) {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, sampleRate / 10 * 2) // 100ms buffer chunks

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()
        isRecording = true
        NativeDictationEngine.nativeStartListening()

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(1600) // 100ms at 16kHz
            val floatBuffer = FloatArray(1600)

            while (isActive && isRecording) {
                val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                if (readCount > 0) {
                    for (i in 0 until readCount) {
                        floatBuffer[i] = shortBuffer[i] / 32768.0f
                    }
                    NativeDictationEngine.pushPcmData(floatBuffer)
                }
            }
        }
    }

    fun stopCapture() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        NativeDictationEngine.nativePauseListening()
    }
}
