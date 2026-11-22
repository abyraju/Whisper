package com.whisperflow.floating.native

interface DictationListener {
    fun onAudioLevel(levelDb: Float)
    fun onDraftUpdated(draft: String)
    fun onFullTranscriptUpdated(fullText: String)
    fun onStateChanged(state: String)
}

/**
 * Kotlin facade over the Rust engine. The raw JNI functions are private; everything goes through
 * the wrappers below, which are no-ops (instead of crashing) if the native library is missing.
 */
object NativeDictationEngine {

    @Volatile
    private var libraryLoaded = false

    init {
        libraryLoaded = try {
            System.loadLibrary("dictation_core")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @Volatile
    private var activeListener: DictationListener? = null

    fun setListener(listener: DictationListener) {
        this.activeListener = listener
    }

    /** Drops the listener so the singleton doesn't keep a destroyed Service alive. */
    fun clearListener() {
        this.activeListener = null
    }

    private external fun nativeInit(modelDir: String)
    private external fun nativeStartListening()
    private external fun nativePushPcmData(samples: FloatArray, listener: Any)
    private external fun nativePauseListening()
    private external fun nativeClear()
    private external fun nativeShutdown()
    private external fun nativeGetTranscript(): String

    fun initialize(modelDir: String) {
        if (libraryLoaded) nativeInit(modelDir)
    }

    fun startListening() {
        if (libraryLoaded) nativeStartListening()
    }

    fun pauseListening() {
        if (libraryLoaded) nativePauseListening()
    }

    fun clear() {
        if (libraryLoaded) nativeClear()
    }

    /** Releases the engine and the transcript it holds. Call when the service is destroyed. */
    fun shutdown() {
        if (libraryLoaded) nativeShutdown()
    }

    fun getTranscript(): String = if (libraryLoaded) nativeGetTranscript() else ""

    fun pushPcmData(samples: FloatArray) {
        if (samples.isEmpty()) return
        val listener = activeListener ?: return

        if (libraryLoaded) {
            try {
                nativePushPcmData(samples, listener)
                return
            } catch (_: UnsatisfiedLinkError) {
                // Fall through to the level-meter-only simulation below.
            }
        }

        // Fallback for preview or non-NDK environments: just drive the level meter.
        val sumSq = samples.fold(0f) { acc, s -> acc + s * s }
        val rms = Math.sqrt((sumSq / samples.size).toDouble()).toFloat()
        val db = if (rms <= 1e-5f) -60f else (20f * Math.log10(rms.toDouble())).toFloat().coerceIn(-60f, 0f)
        listener.onAudioLevel(db)
    }
}
