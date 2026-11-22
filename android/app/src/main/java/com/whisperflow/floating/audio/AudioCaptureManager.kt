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

    @Volatile
    private var isRecording = false

    /** Returns true if the microphone is now capturing, false if it could not be started. */
    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope): Boolean {
        if (isRecording) return true

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) return false
        val bufferSize = maxOf(minBufferSize, sampleRate / 10 * 2) // 100ms buffer chunks

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        try {
            record.startRecording()
        } catch (_: IllegalStateException) {
            record.release()
            return false
        }

        audioRecord = record
        isRecording = true
        NativeDictationEngine.startListening()

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(1600) // 100ms at 16kHz
            val floatBuffer = FloatArray(1600)

            try {
                while (isActive && isRecording) {
                    val readCount = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readCount < 0) break // read error or recorder released
                    if (readCount == 0) continue

                    for (i in 0 until readCount) {
                        floatBuffer[i] = shortBuffer[i] / 32768.0f
                    }
                    // Only hand over the samples actually read, never stale data from the
                    // previous chunk.
                    val chunk = if (readCount == floatBuffer.size) floatBuffer else floatBuffer.copyOf(readCount)
                    NativeDictationEngine.pushPcmData(chunk)
                }
            } catch (_: Exception) {
                // A failing recorder must never take the whole app down.
            }
        }
        return true
    }

    fun stopCapture() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        NativeDictationEngine.pauseListening()
    }
}
