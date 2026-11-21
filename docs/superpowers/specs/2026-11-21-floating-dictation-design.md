# Design Document: WhisperFlow-Style Floating Dictation App for Android

## 1. Intent & The Why
Mobile voice dictation built into standard Android keyboards often forces full-screen or intrusive keyboard shifts, lacks intelligent two-stage refinement, and sends audio to the cloud. 
This project builds a local, privacy-first, on-device dictation application for Android inspired by Whisper Flow. It features a persistent floating glassmorphic overlay that allows users to dictate anywhere on their phone. It uses a **two-stage cascaded speech recognition architecture** in Rust to deliver instantaneous visual typing feedback alongside high-fidelity punctuation, grammar, and contextual correction.

---

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
- **Instant Live Typing Feedback (First-Pass):** Word-by-word streaming transcription (sub-150ms latency) using a streaming Transducer model (Zipformer via ONNX).
- **Continuous Multi-Segment Accumulation:** Users can dictate continuously across pauses. Live sentences stream in real-time; upon natural pauses, completed clauses/sentences are refined and appended into a running transcript buffer.
- **Contextual Polish & Formatting (Second-Pass):** Completed audio segments are passed to an edge refiner (Moonshine Base / Whisper) to fix grammar, homophones, capitalization, and punctuation.
- **Glassmorphic Floating Overlay:** A fluid, draggable Jetpack Compose overlay window that floats above all apps, featuring frosted glass blur, glowing audio waveforms, and responsive expand/collapse states.
- **One-Tap Injection:** Transcribed text is held on the floating overlay with a one-tap button to copy to the clipboard and trigger direct paste into the active cursor.
- **100% Offline & Private:** Complete speech inference runs locally on the phone's CPU/NPU using Rust and ONNX Runtime. No audio ever leaves the device.
- **English-First with Manual Language Switching:** Primary optimization for English (using ultra-fast Moonshine + Zipformer-en), with modular runtime hooks to manually select other languages (loading multilingual Whisper).

### Non-Goals
- Replacing the system default software keyboard (IME).
- Cloud transcription syncing or multi-device account sync.
- 24/7 always-listening hotword activation.

### Anti-Goals
- Heavy battery-draining continuous background inference when user is not actively speaking.
- Complex multi-window setups that cover significant screen real estate.
- Unstable JNI pointers causing native crashes on Android UI threads.

---

## 3. Constraints & Operating System Guardrails
- **Android Permissions:** Requires `SYSTEM_ALERT_WINDOW` ("Draw over other apps") and `RECORD_AUDIO`. Optional `AccessibilityService` for automated text paste injection.
- **Microphone Lifecycle:** Tap-to-Talk activation. Audio hardware opens only upon user tap and closes on pause/stop, avoiding battery drain and Android 12+ persistent privacy indicators.
- **Mobile Hardware Limitations:** Models must fit comfortably within phone RAM (<500MB peak memory during two-stage inference) without triggering Android's low-memory killer (OOM).
- **Android OS Fragmentation:** Glassmorphic blur uses `RenderEffect.createBlurEffect()` on Android 12+ (API 31+), with an elegant translucent scrim and gradient border fallback on older Android versions.

---

## 4. Architectural Approaches Considered

### Approach 1: Rust-Centric Audio & Model Engine with Jetpack Compose Overlay (Selected)
- **Rust Core:** Compiles as an Android NDK shared library (`cdylib`) with safe bindings (`uniffi-rs` or structured JNI). Manages audio input stream, ring buffer, Silero VAD, and drives the ONNX models. Emits typed event streams to Android.
- **Android Layer:** Kotlin Foreground Service with Jetpack Compose `ComposeView` on `WindowManager`. Handles touch dragging, glassmorphic UI, and text injection.
- **Rationale:** Highest performance, zero-copy audio processing, robust cross-platform engine logic, and idiomatic separation between UI and AI computation.

### Approach 2: Kotlin-Driven Sherpa-ONNX SDK with Rust DSP Plugin (Rejected)
- Puts orchestration logic in Kotlin, relying on pre-built AAR bindings.
- **Why rejected:** Limits flexibility for custom multi-model chaining, audio ring buffer optimizations, and porting to desktop/iOS.

### Approach 3: Pure Rust NativeActivity Overlay (Rejected)
- Attempting to draw floating windows in pure Rust.
- **Why rejected:** Android WindowManager overlay views over other apps must be Android View hierarchies; NativeActivity cannot act as a transparent overlay on third-party apps.

---

## 5. Detailed Architecture & Decisions

### 5.1 System Architecture Diagram
```
[ Microphone Input ] (Activated on Tap-to-Talk)
         │ (PCM Audio stream, 16kHz mono)
         ▼
[ Rust Engine (dictation-core via UniFFI/JNI) ]
  ├── Silero VAD (Speech / Silence Segmentation)
  ├── Utterance Segmenter & Ring Buffer
  ├── Tier 1: Zipformer Streaming Transducer (Instant partial text)
  └── Tier 2: Moonshine Base Batch Refiner (Sentence-level polish & formatting)
         │ 
         │ (Events: AudioLevel, StreamingPartial, RefinedSegment, StateChange)
         ▼
[ Android Service (FloatingDictationService) ]
  ├── State Machine (CommittedBuffer + CurrentDraft)
  └── WindowManager View Attachment
         ▼
[ Jetpack Compose Floating Window ]
  ├── Compact State: Frosted glass pill with glowing mic button (drag & snap to edge)
  ├── Expanded Active State:
  │     ├── Frosted glass surface (RenderEffect blur + luminous border)
  │     ├── Real-time ambient audio waveform
  │     ├── Continuous transcript editor:
  │     │     ├── [Committed Refined Text] (Formatted sentences)
  │     │     └── [Streaming Draft Text] (Live shimmering Zipformer output)
  │     └── Action bar: [Clear], [Pause/Resume], [One-Tap Inject / Copy]
  └── Injection Engine: ClipboardManager copy + AccessibilityNodeInfo.ACTION_PASTE
```

### 5.2 The Two-Stage Continuous Accumulation Pipeline
1. **Activation:** User taps the floating pill. The audio capture stream initializes in Rust at 16kHz mono.
2. **First-Pass (Streaming):** Audio chunks (160ms) feed continuously into Zipformer. Intermediate tokens stream into UI state (`current_streaming_text`), updating the live draft with sub-150ms latency.
3. **Speech Segment Boundary Detection:** When Silero VAD detects speech silence (>600ms–800ms):
   - The current segment's audio buffer is dispatched to the Refiner Model (Moonshine Base).
   - Zipformer resets its internal decoding state to be ready for the immediate next utterance.
   - Refiner emits `RefinedSegment` with proper capitalization, grammar, and punctuation.
4. **Buffer Accumulation:**
   - The `RefinedSegment` is committed to the permanent transcript: `committed_text += " " + refined_text`.
   - The `current_streaming_text` is cleared.
   - As the user continues speaking, the next sentence begins streaming immediately after the committed text.
5. **One-Tap Injection:**
   - Tapping the **Inject** button copies the full accumulated transcript to Android's `ClipboardManager`.
   - If granted Accessibility permission, it sends an `AccessibilityNodeInfo.ACTION_PASTE` event to the currently focused input field on screen, collapsing the overlay back to a mini pill.

### 5.3 Floating Glassmorphic UI Specifications
- **Design Tokens:**
  - Background: `Color(0x33121624)` with real-time blur (`RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP)` on API 31+).
  - Border: 1dp gradient stroke (`Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x15FFFFFF)))`).
  - Corner Radius: 26dp for rounded pill ergonomics.
  - Active Glow / Waveform: Accent cyan/violet gradient (`#8B5CF6`, `#06B6D4`) dynamically pulsed by audio decibels.
- **Window States:**
  - **Docked Pill (Idle):** 56dp height, minimal footprint docked on left or right edge.
  - **Expanded Console (Active):** 280dp wide, expandable height, showing transcript buffer, live waveform, and control buttons.

---

## 6. What is Left to the Builder
- Packaging `int8` ONNX model assets (Zipformer + Moonshine Base) in app bundle vs. background asset unpacker.
- Configuring the `cargo-ndk` build script for `aarch64-linux-android`.
- Android 11 and lower fallback rendering (tinted translucent surface without blur shader).
- Fine-tuning the VAD silence gap threshold (default 750ms).
