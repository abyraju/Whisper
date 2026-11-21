use dictation_core::vad::{VadDetector, VadEvent};

#[test]
fn test_vad_silence_then_speech_then_pause() {
    // 16kHz audio: 1600 samples = 100ms
    let mut vad = VadDetector::new(300, 0.05); // 300ms pause threshold, RMS 0.05

    let silence_frame = vec![0.001f32; 1600]; // 100ms silence
    let speech_frame = vec![0.1f32; 1600];    // 100ms speech

    // 1. Initial silence
    assert_eq!(vad.process_chunk(&silence_frame), VadEvent::Silence);

    // 2. Speech onset
    assert_eq!(vad.process_chunk(&speech_frame), VadEvent::SpeechStarted);

    // 3. Ongoing speech
    assert_eq!(
        vad.process_chunk(&speech_frame),
        VadEvent::SpeechOngoing { duration_ms: 200 }
    );

    // 4. Silence starts (100ms silence < 300ms threshold)
    assert_eq!(
        vad.process_chunk(&silence_frame),
        VadEvent::SpeechOngoing { duration_ms: 300 }
    );

    // 5. Silence continues (200ms silence < 300ms threshold)
    assert_eq!(
        vad.process_chunk(&silence_frame),
        VadEvent::SpeechOngoing { duration_ms: 400 }
    );

    // 6. Silence hits 300ms -> SpeechEnded emitted with full segment!
    let event = vad.process_chunk(&silence_frame);
    match event {
        VadEvent::SpeechEnded { segment_samples } => {
            // Speech segment includes initial speech chunks + trailing silence window
            assert!(segment_samples.len() >= 3200);
        }
        other => panic!("Expected SpeechEnded, got {:?}", other),
    }

    // 7. Next frame is silence again
    assert_eq!(vad.process_chunk(&silence_frame), VadEvent::Silence);
}
