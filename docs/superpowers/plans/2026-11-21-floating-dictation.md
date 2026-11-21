# WhisperFlow-Style Floating Dictation App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Android floating dictation application with a modern glassmorphic Jetpack Compose overlay and a high-performance Rust core that performs real-time continuous speech recognition using a two-stage cascaded pipeline (Zipformer streaming first-pass + Moonshine/Whisper batch refinement).

**Architecture:** A Rust dynamic library (`dictation-core`) captures 16kHz PCM audio, runs Silero VAD to detect utterance boundaries, feeds streaming audio into Zipformer for sub-150ms draft text, and invokes a batch refiner (Moonshine Base) on completed segments to format and punctuate text into a continuous accumulation buffer. An Android foreground service renders a floating glassmorphic Compose window attached directly to `WindowManager`, exposing live audio waveforms, dual-state draft/refined transcripts, and one-tap clipboard/accessibility text injection.

**Tech Stack:** 
- **Rust Core:** `rustc 1.80+`, `onnxruntime` / `sherpa-onnx-sys`, `hound` / audio DSP, `uniffi` / JNI bindings.
- **Android / Kotlin:** Kotlin 2.0+, Jetpack Compose, Android `WindowManager` overlay (`SYSTEM_ALERT_WINDOW`), Android 12+ `RenderEffect` blur, Android `AccessibilityService`.

**Spec:** [docs/superpowers/specs/2026-11-21-floating-dictation-design.md](file:///c:/Proj/docs/superpowers/specs/2026-11-21-floating-dictation-design.md)

## Global Constraints

- Audio format strictly standardized at 16,000 Hz, 16-bit Mono PCM.
- Maximum RAM consumption during active two-stage inference capped at <500MB.
- UI overlay must run outside an Activity via a persistent Android `ForegroundService` and `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
- Glassmorphic blur rendered via `RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP)` on API 31+ with translucent solid scrim fallback on API <31.
- Tap-to-Talk activation lifecycle: audio stream opens strictly on user tap and closes on pause/stop.

## Review Focus

1. **Audio ring buffer overflow during continuous long dictations:** Ring buffer must advance or flush processed segments without leaking audio chunks or allocating unbounded heap memory.
2. **Race condition during pause boundary handoff:** When silence triggers Tier 2 batch refinement while the user abruptly resumes speaking, the new incoming audio must not overwrite the unrefined segment or block Tier 1 streaming.
3. **Floating window touch dispatch & keyboard occlusion:** Overlay touch events outside the glass pill must pass through to underlying apps (`FLAG_NOT_FOCUSABLE`), only capturing touches on the floating card itself.
4. **Clipboard & Accessibility injection failure:** If Accessibility service permission is not granted, the "Inject" action must gracefully fall back to `ClipboardManager` and show a non-intrusive toast notification.
5. **JNI / UniFFI memory leaks across long sessions:** String allocations across the Rust-to-Kotlin boundary must be properly reclaimed after UI emission.

---

### Task 1: Rust Core Audio Buffer & Transcript Accumulator

**Files:**
- Create: `dictation-core/Cargo.toml`
- Create: `dictation-core/src/audio.rs`
- Create: `dictation-core/src/accumulator.rs`
- Create: `dictation-core/tests/test_accumulator.rs`

**Interfaces:**
- Produces:
  ```rust
  pub struct AudioRingBuffer {
      pub fn new(capacity_samples: usize) -> Self;
      pub fn push_samples(&mut self, samples: &[f32]);
      pub fn drain_segment(&mut self) -> Vec<f32>;
      pub fn calculate_rms_db(&self) -> f32;
  }

  pub struct TranscriptAccumulator {
      pub fn new() -> Self;
      pub fn set_streaming_draft(&mut self, draft: &str);
      pub fn commit_refined_segment(&mut self, segment: &str);
      pub fn full_text(&self) -> String;
      pub fn clear(&mut self);
  }
  ```

- [ ] **Step 1: Write unit tests for AudioRingBuffer and TranscriptAccumulator**
  - Verify `AudioRingBuffer` pushes samples, calculates decibel levels accurately between -60dB and 0dB, and drains segment cleanly.
  - Verify `TranscriptAccumulator` maintains `committed_text`, merges new refined sentences with whitespace handling, and reflects live draft without polluting committed text.

- [ ] **Step 2: Implement `AudioRingBuffer` in `dictation-core/src/audio.rs`**
  - Ring buffer holding rolling 16kHz float samples with lock-free atomic push and segment slicing.
  - Compute RMS decibels: `20.0 * (sum_of_squares / len).sqrt().log10()`.

- [ ] **Step 3: Implement `TranscriptAccumulator` in `dictation-core/src/accumulator.rs`**
  - Manages `committed_segments: Vec<String>` and `active_draft: String`.
  - Formats concatenated output cleanly with proper spacing and trim logic.

- [ ] **Step 4: Run tests**
  - Expected: PASS for ring buffer and accumulator tests.

---

### Task 2: Silero VAD Speech Segmentation Engine

**Files:**
- Create: `dictation-core/src/vad.rs`
- Create: `dictation-core/tests/test_vad.rs`

**Interfaces:**
- Consumes: `AudioRingBuffer` from Task 1.
- Produces:
  ```rust
  pub enum VadEvent {
      SpeechStarted,
      SpeechOngoing { duration_ms: u64 },
      SpeechEnded { segment_samples: Vec<f32> },
      Silence,
  }

  pub struct VadDetector {
      pub fn new(silence_threshold_ms: u64, speech_threshold: f32) -> Self;
      pub fn process_chunk(&mut self, chunk: &[f32]) -> VadEvent;
      pub fn reset(&mut self);
  }
  ```

- [ ] **Step 1: Write unit tests for VAD state transitions**
  - Test silence chunk sequence produces `VadEvent::Silence`.
  - Test loud/speech sequence triggers `VadEvent::SpeechStarted`.
  - Test pause exceeding `silence_threshold_ms` emits `VadEvent::SpeechEnded`.

- [ ] **Step 2: Implement `VadDetector` in `dictation-core/src/vad.rs`**
  - Wraps lightweight Silero VAD ONNX state or energy-hybrid fallback.
  - Tracks frame probabilities and counts consecutive silence frames up to the configured silence threshold (default 700ms).

- [ ] **Step 3: Run tests**
  - Expected: PASS for VAD event cycle.

---

### Task 3: Two-Stage Speech Recognition Pipeline

**Files:**
- Create: `dictation-core/src/models/zipformer.rs`
- Create: `dictation-core/src/models/moonshine.rs`
- Create: `dictation-core/src/coordinator.rs`
- Create: `dictation-core/tests/test_coordinator.rs`

**Interfaces:**
- Consumes: `VadDetector` (Task 2), `TranscriptAccumulator` (Task 1).
- Produces:
  ```rust
  pub enum DictationEvent {
      StateChanged(SessionState),
      AudioLevelChanged(f32),
      DraftUpdated(String),
      SegmentCommitted(String),
      FullTranscriptUpdated(String),
  }

  pub struct DictationCoordinator {
      pub fn new(zipformer_config: ZipformerConfig, refiner_config: RefinerConfig) -> Self;
      pub fn feed_audio(&mut self, pcm_chunk: &[f32]) -> Vec<DictationEvent>;
      pub fn stop_and_finalize(&mut self) -> Vec<DictationEvent>;
      pub fn clear(&mut self);
  }
  ```

- [ ] **Step 1: Write integration tests for DictationCoordinator**
  - Simulate incoming audio chunks.
  - Verify continuous Zipformer token emissions yield `DictationEvent::DraftUpdated`.
  - Verify silence trigger invokes Refiner and emits `DictationEvent::SegmentCommitted`.

- [ ] **Step 2: Implement Zipformer streaming wrapper in `src/models/zipformer.rs`**
  - Wraps sherpa-onnx Zipformer streaming decoder with encoder state caching.

- [ ] **Step 3: Implement Moonshine/Whisper batch refiner in `src/models/moonshine.rs`**
  - Accepts full segment audio buffer, runs sequence-to-sequence decoder, returns punctuated & capitalized string.

- [ ] **Step 4: Implement `DictationCoordinator` in `src/coordinator.rs`**
  - Coordinates VAD trigger -> Tier 1 stream reset -> Tier 2 batch execution -> Accumulator commit.

- [ ] **Step 5: Run tests**
  - Expected: PASS for cascaded coordinator pipeline.

---

### Task 4: UniFFI / JNI Export Layer for Android

**Files:**
- Create: `dictation-core/src/ffi.rs`
- Create: `dictation-core/src/lib.rs`
- Create: `dictation-core/dictation.udl` (or UniFFI proc macros)

**Interfaces:**
- Produces Android-callable native bindings:
  ```kotlin
  interface DictationListener {
      fun onAudioLevel(levelDb: Float)
      fun onDraftUpdated(draft: String)
      fun onFullTranscriptUpdated(fullText: String)
      fun onStateChanged(state: String)
  }

  class NativeDictationEngine {
      fun initialize(modelDir: String)
      fun startListening()
      fun pushPcmData(samples: FloatArray)
      fun pauseListening()
      fun clear()
      fun getFullTranscript(): String
      fun setListener(listener: DictationListener)
  }
  ```

- [ ] **Step 1: Implement FFI interface in `src/ffi.rs`**
  - Expose safe C/JNI boundaries passing float arrays and strings without heap leaks.
- [ ] **Step 2: Configure `Cargo.toml` for Android NDK compilation**
  - Set `crate-type = ["cdylib", "rlib"]`.
  - Configure target flags for `aarch64-linux-android`.

---

### Task 5: Android Project Scaffolding & Floating Foreground Service

**Files:**
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/whisperflow/floating/service/FloatingDictationService.kt`
- Create: `android/app/src/main/java/com/whisperflow/floating/audio/AudioCaptureManager.kt`

**Interfaces:**
- Consumes: `NativeDictationEngine` (Task 4).
- Produces: `FloatingDictationService` managing `WindowManager` and AudioRecord thread.

- [ ] **Step 1: Configure Android Manifest and Permissions**
  - Add `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.
- [ ] **Step 2: Implement `AudioCaptureManager`**
  - Captures 16kHz 16-bit PCM using `AudioRecord`, buffers into float chunks, and delivers directly to native Rust engine.
- [ ] **Step 3: Implement `FloatingDictationService`**
  - Starts as a foreground service with notification.
  - Inflates Compose overlay view on `WindowManager` with `LayoutParams.TYPE_APPLICATION_OVERLAY`.

---

### Task 6: Modern Glassmorphic Jetpack Compose Floating UI

**Files:**
- Create: `android/app/src/main/java/com/whisperflow/floating/ui/FloatingOverlayView.kt`
- Create: `android/app/src/main/java/com/whisperflow/floating/ui/components/GlassmorphicCard.kt`
- Create: `android/app/src/main/java/com/whisperflow/floating/ui/components/WaveformVisualizer.kt`
- Create: `android/app/src/main/java/com/whisperflow/floating/ui/theme/GlassTheme.kt`

**Interfaces:**
- Consumes: StateFlow from `FloatingDictationService`.
- Produces: Composable interactive floating window.

- [ ] **Step 1: Implement `GlassmorphicCard.kt`**
  - Rounded container (26dp) with API 31+ `RenderEffect.createBlurEffect(30f, 30f, CLAMP)`.
  - Translucent background `Color(0x33121624)` with 1dp gradient highlight border.
- [ ] **Step 2: Implement `WaveformVisualizer.kt`**
  - Smooth animated sound wave bars oscillating based on decibel level from Rust engine.
- [ ] **Step 3: Implement `FloatingOverlayView.kt`**
  - Docked Mini-Pill State (edge-snapped circle/pill with mic icon).
  - Expanded State:
    - Live waveform header.
    - Continuous transcript view: committed sentences in crisp white text, active streaming draft with shimmering cyan/violet glow.
    - Floating action bar: [Clear], [Mic Toggle], [Inject].

---

### Task 7: Drag Physics, Edge Docking & One-Tap Injection Engine

**Files:**
- Create: `android/app/src/main/java/com/whisperflow/floating/ui/drag/OverlayDragManager.kt`
- Create: `android/app/src/main/java/com/whisperflow/floating/injection/TextInjector.kt`
- Create: `android/app/src/main/java/com/whisperflow/floating/injection/DictationAccessibilityService.kt`

**Interfaces:**
- Consumes: `WindowManager` and `FloatingOverlayView` (Tasks 5 & 6).
- Produces: Touch gesture drag handling and automated cursor text injection.

- [ ] **Step 1: Implement `OverlayDragManager.kt`**
  - Updates `WindowManager.LayoutParams.x` and `y` on pointer drag.
  - Adds spring physics snapping the pill to the nearest left/right screen edge when released.
- [ ] **Step 2: Implement `TextInjector.kt`**
  - Primary path: Sets `ClipData` on `ClipboardManager`.
  - Accessibility path: If `DictationAccessibilityService` is enabled, finds focused `AccessibilityNodeInfo` and performs `ACTION_PASTE`.
  - Triggers collapse animation back to docked pill upon successful inject.
- [ ] **Step 3: Verification & Integration Test**
  - Test overlay drag gestures.
  - Test one-tap inject workflow (copies to clipboard + injects into active input).
