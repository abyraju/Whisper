//! JNI bindings exporting the dictation engine to Android / Kotlin.

use crate::coordinator::{DictationCoordinator, DictationEvent};
use crate::models::{RefinerConfig, ZipformerConfig};
use jni::objects::{JClass, JFloatArray, JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::sync::Mutex;

struct EngineWrapper {
    coordinator: DictationCoordinator,
}

static mut ENGINE_INSTANCE: Option<Mutex<EngineWrapper>> = None;

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    model_dir: JString,
) {
    let model_dir_str: String = match env.get_string(&model_dir) {
        Ok(s) => s.into(),
        Err(_) => "models".to_string(),
    };

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
    unsafe {
        ENGINE_INSTANCE = Some(Mutex::new(EngineWrapper { coordinator }));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeStartListening(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe {
        if let Some(ref lock) = ENGINE_INSTANCE {
            if let Ok(mut engine) = lock.lock() {
                engine.coordinator.start_listening();
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativePushPcmData(
    mut env: JNIEnv,
    _class: JClass,
    samples: JFloatArray,
    listener: JObject,
) {
    let len = match env.get_array_length(&samples) {
        Ok(l) => l as usize,
        Err(_) => return,
    };

    let mut buf = vec![0.0f32; len];
    if env.get_float_array_region(&samples, 0, &mut buf).is_err() {
        return;
    }

    unsafe {
        if let Some(ref lock) = ENGINE_INSTANCE {
            if let Ok(mut engine) = lock.lock() {
                let events = engine.coordinator.feed_audio(&buf);

                for event in events {
                    match event {
                        DictationEvent::AudioLevelChanged(db) => {
                            let _ = env.call_method(
                                &listener,
                                "onAudioLevel",
                                "(F)V",
                                &[jni::objects::JValue::Float(db)],
                            );
                        }
                        DictationEvent::DraftUpdated(draft) => {
                            if let Ok(j_str) = env.new_string(&draft) {
                                let _ = env.call_method(
                                    &listener,
                                    "onDraftUpdated",
                                    "(Ljava/lang/String;)V",
                                    &[jni::objects::JValue::Object(&j_str)],
                                );
                            }
                        }
                        DictationEvent::FullTranscriptUpdated(full_text) => {
                            if let Ok(j_str) = env.new_string(&full_text) {
                                let _ = env.call_method(
                                    &listener,
                                    "onFullTranscriptUpdated",
                                    "(Ljava/lang/String;)V",
                                    &[jni::objects::JValue::Object(&j_str)],
                                );
                            }
                        }
                        DictationEvent::StateChanged(state) => {
                            let state_str = format!("{:?}", state);
                            if let Ok(j_str) = env.new_string(&state_str) {
                                let _ = env.call_method(
                                    &listener,
                                    "onStateChanged",
                                    "(Ljava/lang/String;)V",
                                    &[jni::objects::JValue::Object(&j_str)],
                                );
                            }
                        }
                        DictationEvent::SegmentCommitted(_) => {}
                    }
                }
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativePauseListening(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe {
        if let Some(ref lock) = ENGINE_INSTANCE {
            if let Ok(mut engine) = lock.lock() {
                engine.coordinator.pause_listening();
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeClear(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe {
        if let Some(ref lock) = ENGINE_INSTANCE {
            if let Ok(mut engine) = lock.lock() {
                engine.coordinator.clear();
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_whisperflow_floating_native_NativeDictationEngine_nativeGetTranscript(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = unsafe {
        if let Some(ref lock) = ENGINE_INSTANCE {
            if let Ok(engine) = lock.lock() {
                engine.coordinator.get_transcript()
            } else {
                String::new()
            }
        } else {
            String::new()
        }
    };

    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
