package com.whisperflow.floating.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.whisperflow.floating.native.NativeDictationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val tag = "AudioCaptureManager"

    /** Returns true if the microphone is now capturing, false if it could not be started. */
    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope): Boolean {
        if (isRecording) return true

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            Log.e(tag, "Invalid minBufferSize: $minBufferSize")
            return false
        }
        val bufferSize = maxOf(minBufferSize * 2, 4096)

        // Try standard MIC source first (broadest hardware compatibility), fallback to VOICE_RECOGNITION
        val sourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        var record: AudioRecord? = null
        for (source in sourcesToTry) {
            try {
                val candidate = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    Log.d(tag, "AudioRecord initialized successfully with source: $source")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to initialize AudioRecord with source $source: ${e.message}")
            }
        }

        if (record == null) {
            Log.e(tag, "Could not initialize AudioRecord with any audio source")
            return false
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(tag, "Failed to start recording: ${e.message}")
            record.release()
            return false
        }

        audioRecord = record
        isRecording = true
        NativeDictationEngine.startListening()

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(1600) // 100ms chunks at 16kHz
            val floatBuffer = FloatArray(1600)
            var consecutiveErrors = 0

            try {
                while (isActive && isRecording) {
                    val readCount = record.read(shortBuffer, 0, shortBuffer.size)

                    if (readCount > 0) {
                        consecutiveErrors = 0
                        for (i in 0 until readCount) {
                            floatBuffer[i] = shortBuffer[i] / 32768.0f
                        }
                        val chunk = if (readCount == floatBuffer.size) floatBuffer else floatBuffer.copyOf(readCount)
                        NativeDictationEngine.pushPcmData(chunk)
                    } else if (readCount < 0) {
                        consecutiveErrors++
                        Log.w(tag, "AudioRecord.read returned error: $readCount (count=$consecutiveErrors)")
                        if (consecutiveErrors > 10) {
                            Log.e(tag, "Too many consecutive AudioRecord read errors, aborting loop")
                            break
                        }
                        delay(20)
                    } else {
                        // readCount == 0, brief yield
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Exception in audio capture loop: ${e.message}")
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
        } catch (e: Exception) {
            Log.w(tag, "Error stopping audioRecord: ${e.message}")
        }
        audioRecord = null

        NativeDictationEngine.pauseListening()
    }
}
