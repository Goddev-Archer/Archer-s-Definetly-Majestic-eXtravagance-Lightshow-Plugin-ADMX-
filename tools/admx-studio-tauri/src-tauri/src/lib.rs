pub mod artnet;
pub mod audio_player;
pub mod db;
pub mod dsp;
pub mod optimizer;
pub mod types;

use artnet::ArtNetSender;
use audio_player::AudioPlayer;
use dsp::analyze_track;
use optimizer::{run_parallel_simulated_annealing, OptimizationOutcome};
use std::sync::Mutex;
use tauri::State;
use types::{AnalysisResult, PlaybackStatus, SongSection};

pub struct AppState {
    pub player: AudioPlayer,
    pub current_analysis: Mutex<Option<AnalysisResult>>,
    pub artnet_sender: ArtNetSender,
    pub bpm: Mutex<f64>,
    pub grid_offset_sec: Mutex<f64>,
}

#[tauri::command]
fn load_and_analyze(
    state: State<AppState>,
    file_path: String,
    bpm: Option<f64>,
) -> Result<AnalysisResult, String> {
    let result = analyze_track(&file_path, bpm)?;
    state.player.load_file(&file_path, result.duration_sec)?;
    *state.bpm.lock().unwrap() = result.bpm;
    *state.grid_offset_sec.lock().unwrap() = result.grid_offset_sec;
    *state.current_analysis.lock().unwrap() = Some(result.clone());
    Ok(result)
}

#[tauri::command]
fn detect_track_bpm(file_path: String) -> Result<f64, String> {
    dsp::detect_audio_bpm(&file_path)
}

#[tauri::command]
fn run_optimization(state: State<AppState>, passes: usize) -> Result<OptimizationOutcome, String> {
    let analysis_guard = state.current_analysis.lock().unwrap();
    let analysis = analysis_guard
        .as_ref()
        .ok_or_else(|| "No track analyzed yet. Please load an audio track first.".to_string())?;

    let outcome = run_parallel_simulated_annealing(analysis, passes);
    Ok(outcome)
}

#[tauri::command]
fn play_audio(state: State<AppState>) -> Result<(), String> {
    state.player.play()
}

#[tauri::command]
fn pause_audio(state: State<AppState>) {
    state.player.pause();
}

#[tauri::command]
fn stop_audio(state: State<AppState>) {
    state.player.stop();
}

#[tauri::command]
fn seek_audio(state: State<AppState>, to_ms: u64) -> Result<(), String> {
    state.player.seek(to_ms)
}

#[tauri::command]
fn get_playback_status(state: State<AppState>) -> PlaybackStatus {
    let bpm = *state.bpm.lock().unwrap();
    let off = *state.grid_offset_sec.lock().unwrap();
    state.player.get_status(bpm, off)
}

#[tauri::command]
fn set_bpm(state: State<AppState>, bpm: f64) {
    *state.bpm.lock().unwrap() = bpm;
}

#[tauri::command]
fn set_grid_offset(state: State<AppState>, offset_sec: f64) {
    *state.grid_offset_sec.lock().unwrap() = offset_sec;
}

#[tauri::command]
fn save_profile(
    state: State<AppState>,
    db_path: String,
    slot_index: u32,
    nodes: Vec<SongSection>,
    connections: Option<Vec<crate::types::FlowConnection>>,
    confidence: f64,
    passes: usize,
) -> Result<(String, u32), String> {
    let bpm = *state.bpm.lock().unwrap();
    let off = *state.grid_offset_sec.lock().unwrap();
    let conns = connections.unwrap_or_default();
    db::save_profile_to_db(&db_path, slot_index, bpm, off, &nodes, &conns, confidence, passes)
}

#[tauri::command]
fn send_dmx_universe(
    state: State<AppState>,
    universe: u16,
    target_addr: String,
    channels: Vec<u8>,
) -> Result<usize, String> {
    state.artnet_sender.send_dmx(universe, &target_addr, &channels)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let player = AudioPlayer::new().expect("Failed to initialize audio output engine");
    let artnet_sender = ArtNetSender::new().expect("Failed to initialize ArtNet UDP socket");

    let state = AppState {
        player,
        current_analysis: Mutex::new(None),
        artnet_sender,
        bpm: Mutex::new(146.0),
        grid_offset_sec: Mutex::new(0.158),
    };

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            load_and_analyze,
            detect_track_bpm,
            run_optimization,
            play_audio,
            pause_audio,
            stop_audio,
            seek_audio,
            get_playback_status,
            set_bpm,
            set_grid_offset,
            save_profile,
            send_dmx_universe
        ])
        .run(tauri::generate_context!())
        .expect("error while running ADMX Studio Tauri application");
}
