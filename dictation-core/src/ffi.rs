//! JNI bindings exporting the dictation engine to Android / Kotlin.
//!
//! Hardening notes:
//! * No `static mut` / `unsafe` global access: the engine lives in a `Mutex<Option<_>>`.
//! * Every exported function is wrapped in `catch_unwind`, so a panic can never unwind
//!   across the JNI boundary (which would abort the whole app process).
//! * Input from Java is bounded and sanitised (length cap, NaN / infinity / range clamp).
//! * The engine lock is never held while calling back into Java, so a listener that calls
//!   back into native code cannot deadlock.
//! * A poisoned lock is recovered rather than propagating a permanent failure.

use crate::coordinator::{DictationCoordinator, DictationEvent};
use crate::models::{RefinerConfig, ZipformerConfig};
use jni::objects::{JClass, JFloatArray, JObject, JString, JValue};
use jni::sys::jstring;
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, MutexGuard};

/// Largest PCM chunk accepted from Java in one call (10 s of 16 kHz audio).
const MAX_PCM_SAMPLES: usize = 160_000;
/// Longest model directory path accepted.
const MAX_PATH_LEN: usize = 1024;

struct EngineWrapper {
    coordinator: DictationCoordinator,
}

static ENGINE: Mutex<Option<EngineWrapper>> = Mutex::new(None);

/// Locks the engine, recovering from a poisoned lock instead of failing forever.
fn engine() -> MutexGuard<'static, Option<EngineWrapper>> {
    ENGINE.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Runs `f`, returning `default` if it panics.
fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

/// Model directory must be a plain, bounded path with no parent-directory traversal.
fn is_safe_model_dir(path: &str) -> bool {
    !path.is_empty()
        && path.len() <= MAX_PATH_LEN
        && !path.contains('\0')
        && !path.split(['/', '\\']).any(|part| part == "..")
}

/// Calls `listener.<method>(String)` and always clears any exception the listener threw,
/// so a pending Java exception never leaks into later JNI calls.
fn call_listener_str(env: &mut JNIEnv, listener: &JObject, method: &str, value: &str) {
    if let Ok(j_str) = env.new_string(value) {
        let _ = env.call_method(
            listener,
            method,
            "(Ljava/lang/String;)V",
            &[JValue::Object(&j_str)],
        );
        let _ = env.delete_local_ref(j_str);
    }
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

fn call_listener_level(env: &mut JNIEnv, listener: &JObject, db: f32) {
    let db = if db.is_finite() { db } else { -120.0 };
    let _ = env.call_method(listener, "onAudioLevel", "(F)V", &[JValue::Float(db)]);
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    model_dir: JString,
) {
    guard((), || {
        let model_dir_str: String = match env.get_string(&model_dir) {
            Ok(s) => s.into(),
            Err(_) => return,
        };
        if !is_safe_model_dir(&model_dir_str) {
            return;
        }

        let zipformer_config = ZipformerConfig {
            model_path: format!("{}/zipformer-en.onnx", model_dir_str),
            tokens_path: format!("{}/tokens.txt", model_dir_str),
            num_threads: 2,
        };

        let refiner_config = RefinerConfig {
            model_path: format!("{}/moonshine_base.onnx", model_dir_str),
            language: "en".into(),
            num_threads: 4,
        };

        let coordinator = DictationCoordinator::new(zipformer_config, refiner_config);
        *engine() = Some(EngineWrapper { coordinator });
    });
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeStartListening(
    _env: JNIEnv,
    _class: JClass,
) {
    guard((), || {
        if let Some(e) = engine().as_mut() {
            let _ = e.coordinator.start_listening();
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativePushPcmData(
    mut env: JNIEnv,
    _class: JClass,
    samples: JFloatArray,
    listener: JObject,
) {
    guard((), || {
        if samples.is_null() || listener.is_null() {
            return;
        }

        let len = match env.get_array_length(&samples) {
            Ok(l) if l > 0 => l as usize,
            _ => return,
        };
        if len > MAX_PCM_SAMPLES {
            return;
        }

        let mut buf = vec![0.0f32; len];
        if env.get_float_array_region(&samples, 0, &mut buf).is_err() {
            return;
        }

        // Never trust values coming across the boundary: strip NaN / infinity, clamp range.
        for s in buf.iter_mut() {
            *s = if s.is_finite() { s.clamp(-1.0, 1.0) } else { 0.0 };
        }

        // Do the work under the lock, but release it before calling back into Java.
        let events = match engine().as_mut() {
            Some(e) => e.coordinator.feed_audio(&buf),
            None => return,
        };

        for event in events {
            match event {
                DictationEvent::AudioLevelChanged(db) => {
                    call_listener_level(&mut env, &listener, db)
                }
                DictationEvent::DraftUpdated(draft) => {
                    call_listener_str(&mut env, &listener, "onDraftUpdated", &draft)
                }
                DictationEvent::FullTranscriptUpdated(full_text) => {
                    call_listener_str(&mut env, &listener, "onFullTranscriptUpdated", &full_text)
                }
                DictationEvent::StateChanged(state) => {
                    call_listener_str(&mut env, &listener, "onStateChanged", &format!("{:?}", state))
                }
                DictationEvent::SegmentCommitted(_) => {}
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativePauseListening(
    _env: JNIEnv,
    _class: JClass,
) {
    guard((), || {
        if let Some(e) = engine().as_mut() {
            let _ = e.coordinator.pause_listening();
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeClear(
    _env: JNIEnv,
    _class: JClass,
) {
    guard((), || {
        if let Some(e) = engine().as_mut() {
            e.coordinator.clear();
        }
    });
}

/// Drops the engine and the transcript it holds. Call when the service is destroyed.
#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeShutdown(
    _env: JNIEnv,
    _class: JClass,
) {
    guard((), || {
        *engine() = None;
    });
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeGetTranscript(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let text = engine()
            .as_ref()
            .map(|e| e.coordinator.get_transcript())
            .unwrap_or_default();

        match env.new_string(text) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unsafe_model_dirs() {
        assert!(is_safe_model_dir("/data/user/0/app/files/models"));
        assert!(!is_safe_model_dir(""));
        assert!(!is_safe_model_dir("../../etc"));
        assert!(!is_safe_model_dir("/data/../etc"));
        assert!(!is_safe_model_dir("a\\..\\b"));
        assert!(!is_safe_model_dir(&"x".repeat(MAX_PATH_LEN + 1)));
    }

    #[test]
    fn guard_survives_panics() {
        assert_eq!(guard(7, || panic!("boom")), 7);
        assert_eq!(guard(7, || 3), 3);
    }
}
