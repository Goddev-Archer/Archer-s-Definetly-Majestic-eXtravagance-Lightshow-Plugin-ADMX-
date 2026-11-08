use crate::types::PlaybackStatus;
use rodio::{Decoder, OutputStream, OutputStreamHandle, Sink};
use std::fs::File;
use std::io::BufReader;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

pub struct AudioPlayer {
    _stream: OutputStream,
    stream_handle: OutputStreamHandle,
    sink: Arc<Mutex<Option<Sink>>>,
    current_path: Arc<Mutex<String>>,
    is_playing: Arc<AtomicBool>,
    start_instant: Arc<Mutex<Option<Instant>>>,
    offset_ms: Arc<AtomicU64>,
    duration_ms: Arc<AtomicU64>,
}

impl AudioPlayer {
    pub fn new() -> Result<Self, String> {
        let (stream, stream_handle) =
            OutputStream::try_default().map_err(|e| format!("Failed to get audio output device: {e}"))?;

        Ok(Self {
            _stream: stream,
            stream_handle,
            sink: Arc::new(Mutex::new(None)),
            current_path: Arc::new(Mutex::new(String::new())),
            is_playing: Arc::new(AtomicBool::new(false)),
            start_instant: Arc::new(Mutex::new(None)),
            offset_ms: Arc::new(AtomicU64::new(0)),
            duration_ms: Arc::new(AtomicU64::new(0)),
        })
    }

    pub fn load_file(&self, path: &str, duration_sec: f64) -> Result<(), String> {
        let mut cur = self.current_path.lock().unwrap();
        *cur = path.to_string();
        self.duration_ms.store((duration_sec * 1000.0) as u64, Ordering::Relaxed);
        self.stop();
        Ok(())
    }

    pub fn play(&self) -> Result<(), String> {
        let path = self.current_path.lock().unwrap().clone();
        if path.is_empty() {
            return Err("No audio file loaded".into());
        }

        let mut sink_guard = self.sink.lock().unwrap();
        if let Some(ref sink) = *sink_guard {
            if sink.is_paused() {
                sink.play();
                self.is_playing.store(true, Ordering::Relaxed);
                *self.start_instant.lock().unwrap() = Some(Instant::now());
                return Ok(());
            }
        }

        // Neuen Sink instanziieren und von offset abspielen
        let file = File::open(&path).map_err(|e| format!("File open error: {e}"))?;
        let reader = BufReader::new(file);
        let source = Decoder::new(reader).map_err(|e| format!("Decoder error: {e}"))?;

        let sink = Sink::try_new(&self.stream_handle)
            .map_err(|e| format!("Sink creation error: {e}"))?;

        let current_offset = self.offset_ms.load(Ordering::Relaxed);
        if current_offset > 0 {
            let _ = sink.try_seek(std::time::Duration::from_millis(current_offset));
        }

        sink.append(source);
        sink.play();

        *sink_guard = Some(sink);
        self.is_playing.store(true, Ordering::Relaxed);
        *self.start_instant.lock().unwrap() = Some(Instant::now());

        Ok(())
    }

    pub fn pause(&self) {
        let sink_guard = self.sink.lock().unwrap();
        if let Some(ref sink) = *sink_guard {
            sink.pause();
            self.is_playing.store(false, Ordering::Relaxed);

            // Aktualisiere Offset
            if let Some(start) = self.start_instant.lock().unwrap().take() {
                let elapsed = start.elapsed().as_millis() as u64;
                self.offset_ms.fetch_add(elapsed, Ordering::Relaxed);
            }
        }
    }

    pub fn stop(&self) {
        let mut sink_guard = self.sink.lock().unwrap();
        if let Some(sink) = sink_guard.take() {
            sink.stop();
        }
        self.is_playing.store(false, Ordering::Relaxed);
        self.offset_ms.store(0, Ordering::Relaxed);
        *self.start_instant.lock().unwrap() = None;
    }

    pub fn seek(&self, to_ms: u64) -> Result<(), String> {
        let was_playing = self.is_playing.load(Ordering::Relaxed);
        self.stop();
        self.offset_ms.store(to_ms, Ordering::Relaxed);
        if was_playing {
            self.play()?;
        }
        Ok(())
    }

    pub fn get_status(&self, bpm: f64, grid_offset_sec: f64) -> PlaybackStatus {
        let is_playing = self.is_playing.load(Ordering::Relaxed);
        let mut pos = self.offset_ms.load(Ordering::Relaxed);

        if is_playing {
            if let Some(start) = *self.start_instant.lock().unwrap() {
                pos += start.elapsed().as_millis() as u64;
            }
        }

        let dur = self.duration_ms.load(Ordering::Relaxed);
        let cur_sec = pos as f64 / 1000.0;
        let beat_sec = 60.0 / bpm;
        let rel_sec = (cur_sec - grid_offset_sec).max(0.0);
        let total_beats = rel_sec / beat_sec;
        let bar = (total_beats / 4.0) as usize + 1;
        let beat_in_bar = (total_beats % 4.0) as usize + 1;

        PlaybackStatus {
            is_playing,
            position_ms: pos,
            duration_ms: dur,
            bar,
            beat_in_bar,
            active_phase_idx: 0,
        }
    }
}

unsafe impl Send for AudioPlayer {}
unsafe impl Sync for AudioPlayer {}

