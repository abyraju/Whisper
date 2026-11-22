package com.whisperflow.floating.speech

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

interface DictationListener {
    fun onAudioLevel(levelDb: Float)
    fun onDraftUpdated(draft: String)
    fun onFullTranscriptUpdated(fullText: String)
    fun onStateChanged(state: String)
}

/**
 * Fully on-device, two-stage speech recognition (no network, nothing leaves the phone):
 *
 *  1. DRAFT  - a small streaming Zipformer transcribes while you talk, so words appear instantly.
 *              It also detects when you pause (end of an utterance).
 *  2. POLISH - at each pause, the buffered utterance is re-transcribed by Moonshine, which is far
 *              more accurate and adds punctuation and capitalisation. That text is committed.
 *
 * All recognition runs on one worker thread, so callers (the audio thread, the UI) never block.
 */
object SpeechEngine {

    private const val SAMPLE_RATE = 16_000
    private const val PRE_ROLL_SAMPLES = SAMPLE_RATE / 2        // keep 0.5 s before speech starts
    private const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 28    // hard cap on a single utterance
    private const val MAX_QUEUED_CHUNKS = 200                   // ~20 s of audio of backlog
    private const val MAX_TRANSCRIPT_CHARS = 20_000

    // Model locations inside the APK's assets (placed there by scripts/fetch_speech_assets.sh).
    private const val DRAFT_DIR = "models/draft"
    private const val POLISH_DIR = "models/polish"

    @Volatile private var listener: DictationListener? = null
    @Volatile private var worker: ExecutorService? = null
    private val queuedChunks = AtomicInteger(0)

    // The fields below are only touched on the worker thread.
    private var online: OnlineRecognizer? = null
    private var offline: OfflineRecognizer? = null
    private var stream: OnlineStream? = null
    private var segment = FloatArrayBuilder()
    private var committed = StringBuilder()
    private var listening = false

    @Volatile private var transcriptSnapshot: String = ""

    fun setListener(l: DictationListener) { listener = l }

    /** Drops the listener so this singleton doesn't keep a destroyed Service alive. */
    fun clearListener() { listener = null }

    /** Loads the models in the background and reports progress through [DictationListener.onStateChanged]. */
    fun initialize(context: Context) {
        if (worker != null) return
        val assets = context.applicationContext.assets
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "speech-engine").apply { priority = Thread.NORM_PRIORITY + 1 } }
        worker = exec
        post {
            state("Loading speech models…")
            try {
                loadModels(assets)
                state("Ready")
            } catch (t: Throwable) {
                // Typically: models missing from the APK, or the native library failed to load.
                state("Speech models unavailable")
            }
        }
    }

    private fun loadModels(assets: AssetManager) {
        val draft = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$DRAFT_DIR/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$DRAFT_DIR/decoder-epoch-99-avg-1.int8.onnx",
                    joiner = "$DRAFT_DIR/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$DRAFT_DIR/tokens.txt",
                numThreads = 2,
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 0.8f, 0.0f),   // 0.8 s of silence after speech = end of utterance
                rule3 = EndpointRule(false, 0.0f, 20.0f), // never let one utterance run past 20 s
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        val polish = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "$POLISH_DIR/encoder_model.ort",
                    mergedDecoder = "$POLISH_DIR/decoder_model_merged.ort",
                ),
                tokens = "$POLISH_DIR/tokens.txt",
                numThreads = 4,
            ),
        )
        online = OnlineRecognizer(assets, draft)
        offline = OfflineRecognizer(assets, polish)
        stream = online?.createStream()
    }

    fun startListening() = post {
        if (online == null) return@post
        listening = true
        state("Listening")
    }

    /** Stops listening and polishes whatever was said since the last pause. */
    fun pauseListening() = post {
        if (!listening) return@post
        listening = false
        finishUtterance(force = true)
        state("Idle")
    }

    fun clear() = post {
        resetStream()
        segment.clear()
        committed = StringBuilder()
        transcriptSnapshot = ""
        listener?.onDraftUpdated("")
        listener?.onFullTranscriptUpdated("")
    }

    fun getTranscript(): String = transcriptSnapshot

    /** Releases the models and the transcript. Call when the service is destroyed. */
    fun shutdown() {
        val exec = worker ?: return
        worker = null
        try {
            exec.execute {
                try { stream?.release() } catch (_: Throwable) {}
                try { online?.release() } catch (_: Throwable) {}
                try { offline?.release() } catch (_: Throwable) {}
                stream = null; online = null; offline = null
                segment.clear(); committed = StringBuilder(); transcriptSnapshot = ""
            }
        } catch (_: RejectedExecutionException) {
        }
        exec.shutdown()
    }

    /** Called from the audio thread with 16 kHz mono float samples in [-1, 1]. */
    fun pushPcmData(samples: FloatArray) {
        if (samples.isEmpty()) return
        listener?.onAudioLevel(levelDb(samples))
        if (queuedChunks.get() >= MAX_QUEUED_CHUNKS) return // recogniser is behind; drop rather than grow
        val copy = samples.copyOf()
        queuedChunks.incrementAndGet()
        post { try { processChunk(copy) } finally { queuedChunks.decrementAndGet() } }
    }

    // ---- worker-thread internals -------------------------------------------------------------

    private fun processChunk(samples: FloatArray) {
        val rec = online ?: return
        val st = stream ?: return
        if (!listening) return

        segment.append(samples)
        st.acceptWaveform(samples, SAMPLE_RATE)
        while (rec.isReady(st)) rec.decode(st)

        val draftText = rec.getResult(st).text.trim()
        if (draftText.isEmpty()) {
            segment.keepLast(PRE_ROLL_SAMPLES) // silence so far: don't let the buffer grow
        } else {
            listener?.onDraftUpdated(draftText)
        }

        if (rec.isEndpoint(st) || segment.size >= MAX_SEGMENT_SAMPLES) {
            finishUtterance(force = false)
        }
    }

    /** Re-transcribes the buffered utterance with Moonshine and commits it. */
    private fun finishUtterance(force: Boolean) {
        val rec = online ?: return
        val st = stream ?: return
        if (force) {
            st.inputFinished()
            while (rec.isReady(st)) rec.decode(st)
        }
        val draftText = rec.getResult(st).text.trim()
        val audio = segment.toArray()
        segment.clear()
        resetStream()
        listener?.onDraftUpdated("")
        if (draftText.isEmpty()) return // nothing but silence/noise

        val polished = try { polish(audio) } catch (_: Throwable) { "" }
        val text = polished.ifEmpty { draftText }
        if (committed.isNotEmpty()) committed.append(' ')
        committed.append(text)
        if (committed.length > MAX_TRANSCRIPT_CHARS) committed.delete(0, committed.length - MAX_TRANSCRIPT_CHARS)
        transcriptSnapshot = committed.toString()
        listener?.onFullTranscriptUpdated(transcriptSnapshot)
    }

    private fun polish(audio: FloatArray): String {
        val rec = offline ?: return ""
        val s = rec.createStream()
        try {
            s.acceptWaveform(audio, SAMPLE_RATE)
            rec.decode(s)
            return rec.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    private fun resetStream() {
        val rec = online ?: return
        val old = stream
        // A fresh stream is the most robust way to clear the recogniser state after a force-finish.
        stream = rec.createStream()
        try { old?.release() } catch (_: Throwable) {}
    }

    private fun post(block: () -> Unit) {
        try {
            worker?.execute {
                try { block() } catch (_: Throwable) { /* never let recognition crash the app */ }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun state(s: String) { listener?.onStateChanged(s) }

    private fun levelDb(samples: FloatArray): Float {
        var sum = 0f
        for (v in samples) sum += v * v
        val rms = Math.sqrt((sum / samples.size).toDouble())
        return if (rms <= 1e-5) -60f else (20.0 * Math.log10(rms)).toFloat().coerceIn(-60f, 0f)
    }

    /** Minimal growable float buffer. */
    private class FloatArrayBuilder {
        private var data = FloatArray(SAMPLE_RATE * 4)
        var size = 0
            private set

        fun append(src: FloatArray) {
            if (size + src.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + src.size))
            System.arraycopy(src, 0, data, size, src.size)
            size += src.size
        }

        fun keepLast(n: Int) {
            if (size <= n) return
            System.arraycopy(data, size - n, data, 0, n)
            size = n
        }

        fun clear() { size = 0 }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
