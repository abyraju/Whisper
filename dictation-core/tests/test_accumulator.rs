use dictation_core::accumulator::TranscriptAccumulator;
use dictation_core::audio::AudioRingBuffer;

#[test]
fn test_audio_ring_buffer_operations() {
    let mut buffer = AudioRingBuffer::new(10);
    assert!(buffer.is_empty());

    let samples = vec![0.1, 0.2, 0.3, 0.4, 0.5];
    buffer.push_samples(&samples);
    assert_eq!(buffer.len(), 5);

    let db = buffer.calculate_rms_db();
    assert!(db > -60.0 && db <= 0.0);

    let drained = buffer.drain_segment();
    assert_eq!(drained.len(), 5);
    assert!(buffer.is_empty());
}

#[test]
fn test_transcript_accumulator_accumulation() {
    let mut accumulator = TranscriptAccumulator::new();
    assert_eq!(accumulator.full_text(), "");

    // User speaks first sentence (live draft)
    accumulator.set_streaming_draft("hello world this is");
    assert_eq!(accumulator.full_text(), "hello world this is");

    // Pause occurs -> refiner commits finalized segment
    accumulator.commit_refined_segment("Hello world, this is a test.");
    assert_eq!(accumulator.full_text(), "Hello world, this is a test.");
    assert_eq!(accumulator.active_draft(), "");

    // User speaks second sentence
    accumulator.set_streaming_draft("and now we continue");
    assert_eq!(
        accumulator.full_text(),
        "Hello world, this is a test. and now we continue"
    );

    // Second segment committed
    accumulator.commit_refined_segment("And now we continue dictating.");
    assert_eq!(
        accumulator.full_text(),
        "Hello world, this is a test. And now we continue dictating."
    );

    // Clear
    accumulator.clear();
    assert_eq!(accumulator.full_text(), "");
}
