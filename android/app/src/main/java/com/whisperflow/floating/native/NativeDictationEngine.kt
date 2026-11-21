package com.whisperflow.floating.native

interface DictationListener {
    fun onAudioLevel(levelDb: Float)
    fun onDraftUpdated(draft: String)
    fun onFullTranscriptUpdated(fullText: String)
    fun onStateChanged(state: String)
}

object NativeDictationEngine {
    init {
        try {
            System.loadLibrary("dictation_core")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("Native library dictation_core not yet packaged: ${e.message}")
        }
    }

    private var activeListener: DictationListener? = null

    fun setListener(listener: DictationListener) {
        this.activeListener = listener
    }

    external fun nativeInit(modelDir: String)
    external fun nativeStartListening()
    external fun nativePushPcmData(samples: FloatArray, listener: Any)
    external fun nativePauseListening()
    external fun nativeClear()
    external fun nativeGetTranscript(): String

    fun pushPcmData(samples: FloatArray) {
        activeListener?.let { listener ->
            try {
                nativePushPcmData(samples, listener)
            } catch (e: UnsatisfiedLinkError) {
                // Fallback simulation for preview or non-NDK test environment
                val sumSq = samples.fold(0f) { acc, s -> acc + s * s }
                val rms = Math.sqrt((sumSq / samples.size).toDouble()).toFloat()
                val db = if (rms <= 1e-5f) -60f else (20f * Math.log10(rms.toDouble())).toFloat().coerceIn(-60f, 0f)
                listener.onAudioLevel(db)
            }
        }
    }
}
