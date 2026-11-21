use dictation_core::coordinator::{DictationCoordinator, DictationEvent, SessionState};
use dictation_core::models::{RefinerConfig, ZipformerConfig};

#[test]
fn test_coordinator_lifecycle() {
    let mut coordinator =
        DictationCoordinator::new(ZipformerConfig::default(), RefinerConfig::default());

    // 1. Start listening
    let start_events = coordinator.start_listening();
    assert_eq!(
        start_events,
        vec![DictationEvent::StateChanged(SessionState::Listening)]
    );

    // 2. Feed silence chunk
    let silence = vec![0.001f32; 1600];
    let silence_events = coordinator.feed_audio(&silence);
    assert!(silence_events
        .iter()
        .any(|e| matches!(e, DictationEvent::AudioLevelChanged(_))));

    // 3. Pause
    let pause_events = coordinator.pause_listening();
    assert!(pause_events.contains(&DictationEvent::StateChanged(SessionState::Idle)));
}
