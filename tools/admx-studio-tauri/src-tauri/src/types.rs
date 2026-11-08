use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioMetadata {
    pub file_path: String,
    pub duration_sec: f64,
    pub sample_rate: u32,
    pub channels: u16,
    pub total_samples: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CliffhangerHit {
    pub beat: usize,
    pub bar: usize,
    pub beat_in_bar: usize,
    pub time_sec: f64,
    pub gap_beats: usize,
    pub has_ausklang: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CinematicHit {
    pub beat: usize,
    pub bar: usize,
    pub beat_in_bar: usize,
    pub time_sec: f64,
    pub strength: f32,
    pub has_ausklang: bool,
    pub decay_tau_ms: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SongSection {
    pub id: String,
    pub name: String,
    pub section_type: String,
    pub start_bar: usize,
    pub end_bar: usize,
    pub bars: usize,
    pub start_time: f64,
    pub end_time: f64,
    pub color: String,
    pub pattern: String,
    pub speed: f32,
    pub amp: f32,
    pub strobe: f32,
    pub flame: bool,
    pub dim: u32,
    pub ausklang_mode: String,
    pub decay_tau_ms: u32,
    pub has_ausklang: bool,
    pub cliffhangers: Vec<CliffhangerHit>,
    pub cinematic_hits: Vec<CinematicHit>,
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlowConnection {
    pub id: String,
    pub from_node_id: String,
    pub from_pin: usize, // 0 = on_end, 1 = on_accent/kick/chord
    pub to_node_id: String,
    pub to_pin: usize,   // 0 = in
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalysisResult {
    pub duration_sec: f64,
    pub total_bars: usize,
    pub bar_sec: f64,
    pub grid_offset_sec: f64,
    pub bpm: f64,
    pub genre: String,
    pub nodes: Vec<SongSection>,
    pub connections: Vec<FlowConnection>,
    pub cliffhangers: Vec<CliffhangerHit>,
    pub cinematic_hits: Vec<CinematicHit>,
    pub energy_curve: Vec<f32>,
    pub bass_curve: Vec<f32>,
    pub high_curve: Vec<f32>,
    pub decay_curve: Vec<f32>,
    pub attack_curve: Vec<f32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OptimizationProgress {
    pub progress: f64,
    pub processed_passes: usize,
    pub total_passes: usize,
    pub fitness: f64,
    pub loss: f64,
    pub temperature: f64,
    pub passes_per_sec: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaybackStatus {
    pub is_playing: bool,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub bar: usize,
    pub beat_in_bar: usize,
    pub active_phase_idx: i32,
}
