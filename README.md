# WhisperFlow Floating Dictation (Android + Rust Core)

A modern, privacy-first, on-device floating dictation application for Android inspired by Whisper Flow.

Features a **persistent glassmorphic floating overlay** backed by a high-performance **Rust speech engine** using a **two-stage cascaded speech recognition architecture**:
1. **Instant First-Pass:** Fast, low-latency streaming (sub-150ms) using a streaming Transducer model (**Zipformer**) for real-time word-by-word visual feedback.
2. **Contextual Polish & Formatting:** When speech pauses, an audio buffer refiner (**Moonshine Base** or **Whisper**) re-processes the completed segment to fix grammar, context, casing, and punctuation.
3. **Continuous Accumulation:** You can speak continuously. Sentences format and accumulate into a running paragraph while live drafts stream seamlessly.
4. **One-Tap Inject / Auto-Paste:** Tap "Inject" to copy text to clipboard and automatically paste it into whatever text field has focus via Android Accessibility.

---

## Architecture Overview

```
[ Microphone Input ] (16kHz PCM Mono)
         │
         ▼
[ Rust Engine (dictation-core) ]
  ├── Silero VAD (Speech / Pause Detection)
  ├── Audio Ring Buffer & RMS Decibel Meter
  ├── Tier 1: Zipformer Streaming Transducer (Instant draft)
  └── Tier 2: Moonshine Base Batch Refiner (Formatting polish)
         │ (JNI Events)
         ▼
[ Android Foreground Service ]
         │ (StateFlow)
         ▼
[ Jetpack Compose Floating Window ]
  ├── Docked Mini-Pill (56dp edge-snapped circle)
  ├── Expanded Frosted Glass Console (RenderEffect blur)
  ├── Real-time glowing audio waveform visualizer
  └── One-Tap "Inject" / Clipboard auto-paste
```

---

## Project Structure

```
├── dictation-core/               # Rust Native Core
│   ├── Cargo.toml
│   ├── src/
│   │   ├── accumulator.rs        # Continuous multi-segment transcript accumulation
│   │   ├── audio.rs              # 16kHz ring buffer & RMS dB calculator
│   │   ├── coordinator.rs        # Master dual-tier pipeline coordinator
│   │   ├── ffi.rs                # Android JNI bindings
│   │   ├── lib.rs
│   │   ├── vad.rs                # Silero VAD speech onset / pause detector
│   │   └── models/
│   │       ├── moonshine.rs      # Tier 2 batch refiner
│   │       └── zipformer.rs      # Tier 1 streaming transducer
│   └── tests/
│
├── android/                      # Native Android Application
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── java/com/whisperflow/floating/
│   │           ├── audio/        # AudioRecord 16kHz capture manager
│   │           ├── injection/    # Clipboard & Accessibility paste injector
│   │           ├── speech/       # On-device ASR: streaming draft + Moonshine polish
│   │           ├── service/      # Foreground overlay service
│   │           └── ui/           # Jetpack Compose glassmorphism & drag physics
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── .github/workflows/
│   └── build-apk.yml             # Automated CI workflow building trial APK
├── build-apk.bat                 # Local Windows 1-click build script
└── docs/superpowers/
    ├── specs/                    # Architecture design document
    └── plans/                    # Step-by-step implementation plan
```

---

## Building the APK

### Option A: Automated GitHub Actions (Recommended)
Push this repository to GitHub or run the workflow under the **Actions** tab (`Build WhisperFlow Floating APK`). GitHub Actions will automatically compile the Rust NDK library, assemble the Android debug APK, and provide the downloadable `.apk` file under workflow artifacts.

### Option B: Local Build
1. **Prerequisites:**
   - Android SDK (API 35) & NDK (r26+)
   - JDK 17
   - Rust (`rustup target add aarch64-linux-android`) & `cargo install cargo-ndk`
2. **Run:**
   - Double-click `build-apk.bat` (Windows) or execute:
     ```bash
     cd dictation-core
     cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
     cd ../android
     ./gradlew assembleDebug
     ```
   - The compiled APK will be at:
     `android/app/build/outputs/apk/debug/app-debug.apk`
