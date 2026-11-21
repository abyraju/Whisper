//! Audio ring buffer and DSP calculation module.

use std::collections::VecDeque;

/// A circular rolling buffer designed for low-latency 16kHz mono audio streams.
pub struct AudioRingBuffer {
    buffer: VecDeque<f32>,
    max_capacity: usize,
}

impl AudioRingBuffer {
    /// Creates a new `AudioRingBuffer` with a fixed maximum capacity in samples.
    ///
    /// For 16kHz audio, 480,000 samples represents 30 seconds of rolling audio.
    pub fn new(max_capacity: usize) -> Self {
        Self {
            buffer: VecDeque::with_capacity(max_capacity),
            max_capacity,
        }
    }

    /// Pushes incoming PCM float samples into the buffer.
    /// If capacity is exceeded, oldest samples are dropped to maintain realtime latency.
    pub fn push_samples(&mut self, samples: &[f32]) {
        for &sample in samples {
            if self.buffer.len() >= self.max_capacity {
                self.buffer.pop_front();
            }
            self.buffer.push_back(sample);
        }
    }

    /// Returns the current number of samples stored in the buffer.
    pub fn len(&self) -> usize {
        self.buffer.len()
    }

    /// Returns true if the buffer has no samples.
    pub fn is_empty(&self) -> bool {
        self.buffer.is_empty()
    }

    /// Drains all accumulated audio samples into a linear vector and resets the buffer.
    pub fn drain_segment(&mut self) -> Vec<f32> {
        self.buffer.drain(..).collect()
    }

    /// Clones the last `n_samples` from the buffer without clearing it.
    pub fn peek_tail(&self, n_samples: usize) -> Vec<f32> {
        let count = n_samples.min(self.buffer.len());
        let start = self.buffer.len() - count;
        self.buffer.iter().skip(start).copied().collect()
    }

    /// Calculates Root Mean Square (RMS) decibels from the recent audio window.
    ///
    /// Returns a value typically between -60.0 (near silence) and 0.0 (peak volume).
    pub fn calculate_rms_db(&self) -> f32 {
        if self.buffer.is_empty() {
            return -60.0;
        }

        // Use up to the last 1600 samples (100ms at 16kHz) for a snappy UI visualizer meter
        let window_size = 1600.min(self.buffer.len());
        let start_idx = self.buffer.len() - window_size;

        let sum_squares: f32 = self
            .buffer
            .iter()
            .skip(start_idx)
            .map(|&s| s * s)
            .sum();

        let mean_square = sum_squares / window_size as f32;
        let rms = mean_square.sqrt();

        if rms <= 1e-5 {
            -60.0
        } else {
            let db = 20.0 * rms.log10();
            db.clamp(-60.0, 0.0)
        }
    }

    /// Clears all samples in the buffer.
    pub fn clear(&mut self) {
        self.buffer.clear();
    }
}
