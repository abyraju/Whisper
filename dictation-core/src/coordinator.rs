//! Dictation coordinator orchestrating VAD, Tier 1 streaming, Tier 2 batch refinement, and accumulation.

use crate::accumulator::TranscriptAccumulator;
use crate::audio::AudioRingBuffer;
use crate::models::{MoonshineRefiner, RefinerConfig, ZipformerConfig, ZipformerStream};
use crate::vad::{VadDetector, VadEvent};

/// High-level session states of the dictation engine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionState {
    Idle,
    Listening,
    Speaking,
    Refining,
}

/// Events emitted to the UI layer during dictation.
#[derive(Debug, Clone, PartialEq)]
pub enum DictationEvent {
    StateChanged(SessionState),
    AudioLevelChanged(f32),
    DraftUpdated(String),
    SegmentCommitted(String),
    FullTranscriptUpdated(String),
}

/// The master coordinator tying the dual-tier speech pipeline together.
pub struct DictationCoordinator {
    audio_buffer: AudioRingBuffer,
    vad: VadDetector,
    zipformer: ZipformerStream,
    refiner: MoonshineRefiner,
    accumulator: TranscriptAccumulator,
    state: SessionState,
}

impl DictationCoordinator {
    pub fn new(zipformer_config: ZipformerConfig, refiner_config: RefinerConfig) -> Self {
        Self {
            audio_buffer: AudioRingBuffer::new(16000 * 30), // 30s rolling buffer
            vad: VadDetector::new(750, 0.02),               // 750ms silence pause, 0.02 RMS
            zipformer: ZipformerStream::new(zipformer_config),
            refiner: MoonshineRefiner::new(refiner_config),
            accumulator: TranscriptAccumulator::new(),
            state: SessionState::Idle,
        }
    }

    /// Starts or resumes listening.
    pub fn start_listening(&mut self) -> Vec<DictationEvent> {
        self.state = SessionState::Listening;
        vec![DictationEvent::StateChanged(SessionState::Listening)]
    }

    /// Pauses listening.
    pub fn pause_listening(&mut self) -> Vec<DictationEvent> {
        let mut events = self.stop_and_finalize();
        self.state = SessionState::Idle;
        events.push(DictationEvent::StateChanged(SessionState::Idle));
        events
    }

    /// Feeds a chunk of 16kHz audio samples into the pipeline.
    pub fn feed_audio(&mut self, pcm_chunk: &[f32]) -> Vec<DictationEvent> {
        let mut events = Vec::new();
        if self.state == SessionState::Idle || pcm_chunk.is_empty() {
            return events;
        }

        // 1. Update rolling audio buffer and calculate decibels for the visualizer
        self.audio_buffer.push_samples(pcm_chunk);
        let db_level = self.audio_buffer.calculate_rms_db();
        events.push(DictationEvent::AudioLevelChanged(db_level));

        // 2. Process VAD
        let vad_event = self.vad.process_chunk(pcm_chunk);

        match vad_event {
            VadEvent::Silence => {
                // Keep listening silently
            }
            VadEvent::SpeechStarted => {
                self.state = SessionState::Speaking;
                events.push(DictationEvent::StateChanged(SessionState::Speaking));
                self.zipformer.accept_waveform(pcm_chunk);
            }
            VadEvent::SpeechOngoing { .. } => {
                self.zipformer.accept_waveform(pcm_chunk);
                let draft = self.zipformer.get_text();
                if !draft.is_empty() {
                    self.accumulator.set_streaming_draft(&draft);
                    events.push(DictationEvent::DraftUpdated(draft));
                    events.push(DictationEvent::FullTranscriptUpdated(
                        self.accumulator.full_text(),
                    ));
                }
            }
            VadEvent::SpeechEnded { segment_samples } => {
                self.state = SessionState::Refining;
                events.push(DictationEvent::StateChanged(SessionState::Refining));

                // Grab active draft hint before reset
                let draft_hint = self.zipformer.get_text();
                self.zipformer.reset();

                // Run Tier 2 Refiner
                let refined = self.refiner.refine_segment(&segment_samples, &draft_hint);
                if !refined.is_empty() {
                    self.accumulator.commit_refined_segment(&refined);
                    events.push(DictationEvent::SegmentCommitted(refined));
                    events.push(DictationEvent::FullTranscriptUpdated(
                        self.accumulator.full_text(),
                    ));
                }

                // Return to listening state
                self.state = SessionState::Listening;
                events.push(DictationEvent::StateChanged(SessionState::Listening));
            }
        }

        events
    }

    /// Finalizes any active utterance and flushes the draft into the transcript.
    pub fn stop_and_finalize(&mut self) -> Vec<DictationEvent> {
        let mut events = Vec::new();
        let active_draft = self.zipformer.get_text();

        if !active_draft.is_empty() {
            let segment_samples = self.audio_buffer.drain_segment();
            let refined = self.refiner.refine_segment(&segment_samples, &active_draft);
            let final_text = if !refined.is_empty() {
                refined
            } else {
                active_draft
            };

            self.accumulator.commit_refined_segment(&final_text);
            self.zipformer.reset();
            self.vad.reset();

            events.push(DictationEvent::SegmentCommitted(final_text));
            events.push(DictationEvent::FullTranscriptUpdated(
                self.accumulator.full_text(),
            ));
        }

        events
    }

    /// Returns the full accumulated transcript.
    pub fn get_transcript(&self) -> String {
        self.accumulator.full_text()
    }

    /// Clears all accumulated transcript state.
    pub fn clear(&mut self) {
        self.audio_buffer.clear();
        self.vad.reset();
        self.zipformer.reset();
        self.accumulator.clear();
    }
}
