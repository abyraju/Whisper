@echo off
echo ===================================================
echo WhisperFlow Floating: Rust NDK & Android APK Build
echo ===================================================

echo [1/3] Checking environment...
where cargo >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Cargo / Rust is not found in PATH.
    echo Please install Rust from https://rustup.rs
    exit /b 1
)

echo [2/3] Compiling Rust Core for Android NDK (arm64-v8a)...
cd dictation-core
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
if %errorlevel% neq 0 (
    echo [WARNING] cargo-ndk build failed. Fallback: cargo build --release
    cargo build --release
)
cd ..

echo [3/3] Building Android APK with Gradle...
cd android
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo [ERROR] Gradle build failed. Ensure Android SDK is installed.
    exit /b 1
)

echo ===================================================
echo [SUCCESS] APK generated at:
echo android/app/build/outputs/apk/debug/app-debug.apk
echo ===================================================
pause
