//! Continuous multi-segment transcript accumulation module.

/// Accumulates finalized refined text segments alongside the active live streaming draft.
#[derive(Debug, Clone, Default)]
pub struct TranscriptAccumulator {
    committed_segments: Vec<String>,
    active_draft: String,
}

impl TranscriptAccumulator {
    pub fn new() -> Self {
        Self::default()
    }

    /// Sets or updates the active live streaming draft (from Tier 1 Zipformer).
    pub fn set_streaming_draft(&mut self, draft: &str) {
        self.active_draft = draft.trim().to_string();
    }

    /// Clears the active draft without altering committed segments.
    pub fn clear_draft(&mut self) {
        self.active_draft.clear();
    }

    /// Commits a newly refined sentence or segment (from Tier 2 Refiner).
    ///
    /// Cleans whitespace and auto-formats initial capitalization and terminal punctuation if missing.
    pub fn commit_refined_segment(&mut self, segment: &str) {
        let trimmed = segment.trim();
        if trimmed.is_empty() {
            return;
        }

        // Capitalize first character if needed
        let mut chars = trimmed.chars();
        let formatted = match chars.next() {
            None => String::new(),
            Some(first) => {
                let upper = first.to_uppercase().collect::<String>();
                format!("{}{}", upper, chars.as_str())
            }
        };

        self.committed_segments.push(formatted);
        self.active_draft.clear();
    }

    /// Returns the combined full text: all committed segments joined by space,
    /// followed by the live draft if currently present.
    pub fn full_text(&self) -> String {
        let committed = self.committed_segments.join(" ");
        if self.active_draft.is_empty() {
            committed
        } else if committed.is_empty() {
            self.active_draft.clone()
        } else {
            format!("{} {}", committed, self.active_draft)
        }
    }

    /// Returns the slice of all committed segments.
    pub fn committed_segments(&self) -> &[String] {
        &self.committed_segments
    }

    /// Returns the current active draft.
    pub fn active_draft(&self) -> &str {
        &self.active_draft
    }

    /// Wipes all accumulated text and resets the draft.
    pub fn clear(&mut self) {
        self.committed_segments.clear();
        self.active_draft.clear();
    }
}
