//! Voice Activity Detection (VAD) and utterance segmentation module.

/// Events emitted by the VAD state detector as audio chunks are processed.
#[derive(Debug, Clone, PartialEq)]
pub enum VadEvent {
    /// Silence detected, no active speech.
    Silence,
    /// Transition from silence to active speech.
    SpeechStarted,
    /// Ongoing continuous speech utterance with current elapsed duration.
    SpeechOngoing { duration_ms: u64 },
    /// Speech ended (silence threshold elapsed) with the complete segmented audio.
    SpeechEnded { segment_samples: Vec<f32> },
}

/// State of the VAD engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum VadState {
    Silence,
    InSpeech,
}

/// Real-time speech boundary detector and segmenter.
pub struct VadDetector {
    state: VadState,
    silence_threshold_ms: u64,
    speech_threshold_rms: f32,
    consecutive_silence_ms: u64,
    current_speech_duration_ms: u64,
    active_segment: Vec<f32>,
    sample_rate: u32,
}

impl VadDetector {
    /// Creates a new `VadDetector`.
    ///
    /// - `silence_threshold_ms`: Duration of silence (e.g. 700ms) required to trigger `SpeechEnded`.
    /// - `speech_threshold_rms`: RMS threshold (e.g. 0.015 or -36dB) to classify frame as speech.
    pub fn new(silence_threshold_ms: u64, speech_threshold_rms: f32) -> Self {
        Self {
            state: VadState::Silence,
            silence_threshold_ms,
            speech_threshold_rms,
            consecutive_silence_ms: 0,
            current_speech_duration_ms: 0,
            active_segment: Vec::with_capacity(16000 * 5), // 5 seconds initial capacity
            sample_rate: 16000,
        }
    }

    /// Evaluates an incoming audio frame/chunk (typically 512 to 1600 samples at 16kHz).
    pub fn process_chunk(&mut self, chunk: &[f32]) -> VadEvent {
        if chunk.is_empty() {
            return VadEvent::Silence;
        }

        let chunk_duration_ms = (chunk.len() as u64 * 1000) / self.sample_rate as u64;

        // Calculate chunk energy / RMS
        let sum_sq: f32 = chunk.iter().map(|&s| s * s).sum();
        let rms = (sum_sq / chunk.len() as f32).sqrt();
        let is_speech_frame = rms >= self.speech_threshold_rms;

        match self.state {
            VadState::Silence => {
                if is_speech_frame {
                    self.state = VadState::InSpeech;
                    self.consecutive_silence_ms = 0;
                    self.current_speech_duration_ms = chunk_duration_ms;
                    self.active_segment.clear();
                    self.active_segment.extend_from_slice(chunk);
                    VadEvent::SpeechStarted
                } else {
                    VadEvent::Silence
                }
            }
            VadState::InSpeech => {
                self.active_segment.extend_from_slice(chunk);
                self.current_speech_duration_ms += chunk_duration_ms;

                if is_speech_frame {
                    self.consecutive_silence_ms = 0;
                    VadEvent::SpeechOngoing {
                        duration_ms: self.current_speech_duration_ms,
                    }
                } else {
                    self.consecutive_silence_ms += chunk_duration_ms;

                    if self.consecutive_silence_ms >= self.silence_threshold_ms {
                        // Utterance boundary reached!
                        self.state = VadState::Silence;
                        self.consecutive_silence_ms = 0;
                        self.current_speech_duration_ms = 0;

                        let segment = std::mem::take(&mut self.active_segment);
                        VadEvent::SpeechEnded {
                            segment_samples: segment,
                        }
                    } else {
                        VadEvent::SpeechOngoing {
                            duration_ms: self.current_speech_duration_ms,
                        }
                    }
                }
            }
        }
    }

    /// Resets the internal state and discards any active partial segments.
    pub fn reset(&mut self) {
        self.state = VadState::Silence;
        self.consecutive_silence_ms = 0;
        self.current_speech_duration_ms = 0;
        self.active_segment.clear();
    }
}
