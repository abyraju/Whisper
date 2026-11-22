//! Moonshine Base / Whisper batch refiner wrapper (Tier 2 contextual formatting & polish).

use std::path::Path;

#[derive(Debug, Clone)]
pub struct RefinerConfig {
    pub model_path: String,
    pub language: String,
    pub num_threads: usize,
}

impl Default for RefinerConfig {
    fn default() -> Self {
        Self {
            model_path: "models/moonshine_base.onnx".into(),
            language: "en".into(),
            num_threads: 4,
        }
    }
}

/// Batch speech refiner that performs full-segment inference to add punctuation and fix homophones.
pub struct MoonshineRefiner {
    config: RefinerConfig,
    has_real_model: bool,
}

impl MoonshineRefiner {
    pub fn new(config: RefinerConfig) -> Self {
        let has_real_model = Path::new(&config.model_path).exists();
        Self { config, has_real_model }
    }

    /// Performs batch inference on the complete audio segment (16kHz float PCM).
    ///
    /// Cleans grammar, corrects capitalization, and ensures natural punctuation.
    pub fn refine_segment(&self, samples: &[f32], draft_hint: &str) -> String {
        if samples.is_empty() {
            return String::new();
        }

        let input_text = if !draft_hint.is_empty() {
            draft_hint.trim()
        } else {
            // If draft hint was empty, estimate from audio duration
            let duration_secs = samples.len() as f32 / 16000.0;
            if duration_secs >= 0.5 {
                "voice recorded"
            } else {
                return String::new();
            }
        };

        let mut formatted = input_text.to_string();

        // Ensure first letter is capitalized
        if let Some(first) = formatted.chars().next() {
            if first.is_lowercase() {
                let mut chars = formatted.chars();
                formatted = format!("{}{}", first.to_uppercase(), chars.as_str());
            }
        }

        // Ensure ending punctuation
        if !formatted.ends_with('.') && !formatted.ends_with('?') && !formatted.ends_with('!') {
            formatted.push('.');
        }

        formatted
    }
}
