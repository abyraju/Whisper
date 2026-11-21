//! Zipformer streaming transducer wrapper (Tier 1 live speech draft).

#[derive(Debug, Clone)]
pub struct ZipformerConfig {
    pub model_path: String,
    pub tokens_path: String,
    pub num_threads: usize,
}

impl Default for ZipformerConfig {
    fn default() -> Self {
        Self {
            model_path: "models/zipformer-en.onnx".into(),
            tokens_path: "models/tokens.txt".into(),
            num_threads: 2,
        }
    }
}

/// A streaming Zipformer session that accumulates audio frames and decodes partial tokens.
pub struct ZipformerStream {
    config: ZipformerConfig,
    current_hypothesis: String,
    total_samples_processed: usize,
}

impl ZipformerStream {
    pub fn new(config: ZipformerConfig) -> Self {
        Self {
            config,
            current_hypothesis: String::new(),
            total_samples_processed: 0,
        }
    }

    /// Feeds a chunk of 16kHz float samples into the streaming decoder.
    ///
    /// In production ONNX runtime, this invokes the encoder forward pass
    /// with cached chunk states and greedily decodes modified transducer tokens.
    pub fn accept_waveform(&mut self, samples: &[f32]) {
        self.total_samples_processed += samples.len();
        // In local mock/testing mode, we track audio length or simulate recognition
        // Real ONNX engine binds to sherpa_onnx_online_stream_accept_waveform
    }

    /// Simulates or extracts the currently decoded partial hypothesis text.
    pub fn get_text(&self) -> String {
        self.current_hypothesis.clone()
    }

    /// Injects or updates hypothesis (used by native ONNX bindings).
    pub fn set_text(&mut self, text: &str) {
        self.current_hypothesis = text.to_string();
    }

    /// Resets the streaming decoder state (e.g., after an utterance finishes).
    pub fn reset(&mut self) {
        self.current_hypothesis.clear();
        self.total_samples_processed = 0;
    }
}
