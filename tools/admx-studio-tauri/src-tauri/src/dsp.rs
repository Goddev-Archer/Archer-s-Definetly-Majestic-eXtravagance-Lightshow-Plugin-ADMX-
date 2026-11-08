use crate::types::{AnalysisResult, CinematicHit, CliffhangerHit, SongSection};
use std::fs::File;
use std::path::Path;
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::{DecoderOptions, CODEC_TYPE_NULL};
use symphonia::core::errors::Error;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

pub struct BiquadFilter {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    x1: f32,
    x2: f32,
    y1: f32,
    y2: f32,
}

impl BiquadFilter {
    pub fn bandpass(f0: f32, q: f32, fs: f32) -> Self {
        let w0 = 2.0 * std::f32::consts::PI * f0 / fs;
        let alpha = w0.sin() / (2.0 * q);
        let b0 = alpha;
        let b1 = 0.0;
        let b2 = -alpha;
        let a0 = 1.0 + alpha;
        let a1 = -2.0 * w0.cos();
        let a2 = 1.0 - alpha;

        Self {
            b0: b0 / a0,
            b1: b1 / a0,
            b2: b2 / a0,
            a1: a1 / a0,
            a2: a2 / a0,
            x1: 0.0,
            x2: 0.0,
            y1: 0.0,
            y2: 0.0,
        }
    }

    pub fn process(&mut self, sample: f32) -> f32 {
        let out = self.b0 * sample + self.b1 * self.x1 + self.b2 * self.x2 - self.a1 * self.y1 - self.a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = sample;
        self.y2 = self.y1;
        self.y1 = out;
        out
    }
}

pub fn decode_audio_mono(path: &Path) -> Result<(Vec<f32>, u32), String> {
    let file = File::open(path).map_err(|e| format!("Failed to open file: {e}"))?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    let mut hint = Hint::new();
    if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
        hint.with_extension(ext);
    }

    let probed = symphonia::default::get_probe()
        .format(&hint, mss, &FormatOptions::default(), &MetadataOptions::default())
        .map_err(|e| format!("Failed to probe format: {e}"))?;

    let mut format = probed.format;
    let track = format
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != CODEC_TYPE_NULL)
        .ok_or_else(|| "No supported audio tracks found".to_string())?;

    let sample_rate = track.codec_params.sample_rate.unwrap_or(44100);
    let channels = track.codec_params.channels.map(|c| c.count()).unwrap_or(2);
    let mut decoder = symphonia::default::get_codecs()
        .make(&track.codec_params, &DecoderOptions::default())
        .map_err(|e| format!("Failed to initialize decoder: {e}"))?;

    let mut mono_samples: Vec<f32> = Vec::new();
    let mut sample_buf: Option<SampleBuffer<f32>> = None;

    loop {
        let packet = match format.next_packet() {
            Ok(packet) => packet,
            Err(Error::IoError(ref err)) if err.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(Error::ResetRequired) => break,
            Err(err) => return Err(format!("Decoding packet error: {err}")),
        };

        match decoder.decode(&packet) {
            Ok(decoded) => {
                if sample_buf.is_none() {
                    let spec = *decoded.spec();
                    let duration = decoded.capacity() as u64;
                    sample_buf = Some(SampleBuffer::new(duration, spec));
                }

                if let Some(buf) = sample_buf.as_mut() {
                    buf.copy_interleaved_ref(decoded);
                    let samples = buf.samples();
                    if channels > 1 {
                        for chunk in samples.chunks(channels) {
                            let sum: f32 = chunk.iter().sum();
                            mono_samples.push(sum / channels as f32);
                        }
                    } else {
                        mono_samples.extend_from_slice(samples);
                    }
                }
            }
            Err(Error::IoError(_)) => break,
            Err(Error::DecodeError(_)) => continue,
            Err(err) => return Err(format!("Fatal decode error: {err}")),
        }
    }

    Ok((mono_samples, sample_rate))
}

/// Ermittelt die dominante BPM über eine Multi-Harmonische Kammfilter-Resonanz
/// und einen psychoakustischen Normal-Prior (Ellis / Davies / BTrack).
pub fn compute_estimated_bpm(novelty: &[f32], hop_sec: f64) -> f64 {
    let n_frames = novelty.len();
    if n_frames < 200 {
        return 120.0;
    }

    let mut best_bpm = 120.0;
    let mut best_score = -1.0;

    for bpm_step in 130..=360 { // 65.0 bis 180.0 BPM in 0.5 Schritten
        let bpm = bpm_step as f64 * 0.5;
        let period_frames = (60.0 / bpm) / hop_sec;

        let mut sum = 0.0;
        let max_harmonics = 4;
        let weights = [1.0, 0.6, 0.35, 0.2];

        for h in 0..max_harmonics {
            let lag = (period_frames * (h + 1) as f64).round() as usize;
            if lag < n_frames {
                let mut corr = 0.0f64;
                let mut count = 0;
                for i in 0..(n_frames - lag) {
                    corr += (novelty[i] * novelty[i + lag]) as f64;
                    count += 1;
                }
                if count > 0 {
                    sum += weights[h] * (corr / count as f64);
                }
            }
        }

        // Psychoakustischer Prior um 105-115 BPM
        let prior = (-0.5 * ((bpm.log2() - (105.0f64).log2()) / 0.55).powi(2)).exp();
        let final_score = sum * prior;

        if final_score > best_score {
            best_score = final_score;
            best_bpm = bpm;
        }
    }

    best_bpm
}

/// Schnelle Standalone-BPM-Erkennung einer Audiodatei
pub fn detect_audio_bpm(file_path: &str) -> Result<f64, String> {
    let path = Path::new(file_path);
    let (samples, sr) = decode_audio_mono(path)?;
    let fs = sr as f32;
    let hop_sec = 0.010f64;
    let hop = (hop_sec * fs as f64) as usize;
    let n_frames = samples.len() / hop;
    if n_frames < 200 {
        return Ok(120.0);
    }

    let mut filt_kick = BiquadFilter::bandpass(80.0, 1.2, fs);
    let mut filt_mid = BiquadFilter::bandpass(500.0, 1.0, fs);
    let mut filt_high = BiquadFilter::bandpass(5000.0, 1.2, fs);

    let mut y_k = Vec::with_capacity(samples.len());
    let mut y_m = Vec::with_capacity(samples.len());
    let mut y_h = Vec::with_capacity(samples.len());
    for &s in &samples {
        y_k.push(filt_kick.process(s));
        y_m.push(filt_mid.process(s));
        y_h.push(filt_high.process(s));
    }

    let mut novelty = vec![0.0f32; n_frames];
    let mut prev_k = 0.0f32;
    let mut prev_m = 0.0f32;
    let mut prev_h = 0.0f32;

    for i in 0..n_frames {
        let start = i * hop;
        let end = (i + 1) * hop;
        let rms_k = (y_k[start..end].iter().map(|&x| x * x).sum::<f32>() / hop as f32).sqrt();
        let rms_m = (y_m[start..end].iter().map(|&x| x * x).sum::<f32>() / hop as f32).sqrt();
        let rms_h = (y_h[start..end].iter().map(|&x| x * x).sum::<f32>() / hop as f32).sqrt();

        novelty[i] = 1.0 * (rms_k - prev_k).max(0.0) + 0.8 * (rms_m - prev_m).max(0.0) + 0.6 * (rms_h - prev_h).max(0.0);
        prev_k = rms_k;
        prev_m = rms_m;
        prev_h = rms_h;
    }

    Ok(compute_estimated_bpm(&novelty, hop_sec))
}

pub fn analyze_track(file_path: &str, manual_bpm: Option<f64>) -> Result<AnalysisResult, String> {
    let path = Path::new(file_path);
    let (samples, sr) = decode_audio_mono(path)?;
    let fs = sr as f32;
    let duration_sec = samples.len() as f64 / fs as f64;

    // 1. 4-Band Biquad Filterbank
    let mut filt_kick = BiquadFilter::bandpass(72.0, 1.2, fs);
    let mut filt_toms = BiquadFilter::bandpass(260.0, 1.0, fs);
    let mut filt_high = BiquadFilter::bandpass(6000.0, 1.2, fs);

    let mut y_kick = Vec::with_capacity(samples.len());
    let mut y_toms = Vec::with_capacity(samples.len());
    let mut y_high = Vec::with_capacity(samples.len());

    for &s in &samples {
        y_kick.push(filt_kick.process(s));
        y_toms.push(filt_toms.process(s));
        y_high.push(filt_high.process(s));
    }

    // 2. 10ms Frame RMS Analyse
    let hop_sec = 0.010f64;
    let hop = (hop_sec * fs as f64) as usize;
    let n_frames = samples.len() / hop;

    let mut kick_rms = Vec::with_capacity(n_frames);
    let mut toms_rms = Vec::with_capacity(n_frames);
    let mut high_rms = Vec::with_capacity(n_frames);
    let mut all_rms = Vec::with_capacity(n_frames);

    let mut max_k = 1e-6f32;
    let mut max_t = 1e-6f32;
    let mut max_h = 1e-6f32;
    let mut max_a = 1e-6f32;

    for i in 0..n_frames {
        let start = i * hop;
        let end = (i + 1) * hop;

        let rms = |slice: &[f32]| -> f32 {
            let sum_sq: f32 = slice.iter().map(|&x| x * x).sum();
            (sum_sq / slice.len() as f32).sqrt()
        };

        let k = rms(&y_kick[start..end]);
        let t = rms(&y_toms[start..end]);
        let h = rms(&y_high[start..end]);
        let a = rms(&samples[start..end]);

        if k > max_k { max_k = k; }
        if t > max_t { max_t = t; }
        if h > max_h { max_h = h; }
        if a > max_a { max_a = a; }

        kick_rms.push(k);
        toms_rms.push(t);
        high_rms.push(h);
        all_rms.push(a);
    }

    // Normalisierung 0.0 .. 1.0
    let kr: Vec<f32> = kick_rms.iter().map(|&x| x / max_k).collect();
    let tr: Vec<f32> = toms_rms.iter().map(|&x| x / max_t).collect();
    let hr: Vec<f32> = high_rms.iter().map(|&x| x / max_h).collect();
    let ar: Vec<f32> = all_rms.iter().map(|&x| x / max_a).collect();

    // 3. Transient Attack Flux
    let mut kick_attack = vec![0.0f32; n_frames];
    let mut toms_attack = vec![0.0f32; n_frames];
    let mut all_attack = vec![0.0f32; n_frames];
    let mut combined_novelty = vec![0.0f32; n_frames];
    for i in 1..n_frames {
        kick_attack[i] = (kr[i] - kr[i - 1]).max(0.0);
        toms_attack[i] = (tr[i] - tr[i - 1]).max(0.0);
        all_attack[i] = (ar[i] - ar[i - 1]).max(0.0);
        let diff_h = (hr[i] - hr[i - 1]).max(0.0);
        combined_novelty[i] = 1.0 * kick_attack[i] + 0.8 * toms_attack[i] + 0.6 * diff_h;
    }

    // 3b. Tempo-Ermittlung (Manuell vorgegeben oder Automatisch via Resonanz-Kammfilter)
    let bpm = match manual_bpm {
        Some(val) if val >= 45.0 && val <= 240.0 => val,
        _ => compute_estimated_bpm(&combined_novelty, hop_sec),
    };
    let beat_sec = 60.0 / bpm;
    let bar_sec = beat_sec * 4.0;

    // 4. Dual-Rate Envelope Follower (Fast Attack 5ms, Ausklang Decay 240ms)
    let decay_factor = (-10.0f32 / 240.0f32).exp();
    let mut decay_env = vec![0.0f32; n_frames];
    let mut curr = 0.0f32;
    for i in 0..n_frames {
        if ar[i] > curr {
            curr = ar[i];
        } else {
            curr *= decay_factor;
        }
        decay_env[i] = curr;
    }

    // 5. Grid Downbeat Phase Alignment (Dynamische Lead-in Erkennung für exakten Taktanfang)
    let step_sec = 0.002f64;
    let n_steps = ((beat_sec / step_sec).ceil() as usize).max(50);
    let mut max_corr = -1.0;
    let mut grid_offset_sec = 0.0;

    for s in 0..n_steps {
        let off = s as f64 * step_sec;
        let mut corr = 0.0;
        for i in 0..n_frames {
            let t = i as f64 * hop_sec;
            if t >= 2.0 && t <= duration_sec.min(60.0) {
                let phase = ((t - off).rem_euclid(beat_sec)) / beat_sec;
                let w = (-0.5 * ((phase - 0.0) / 0.03).powi(2)).exp()
                    + (-0.5 * ((phase - 1.0) / 0.03).powi(2)).exp();
                corr += (kick_attack[i] as f64 * 1.5 + toms_attack[i] as f64) * w;
            }
        }
        if corr > max_corr {
            max_corr = corr;
            grid_offset_sec = off;
        }
    }

    let total_beats = ((duration_sec - grid_offset_sec) / beat_sec).floor().max(1.0) as usize;
    let total_bars = (total_beats / 4).max(1);

    // Beat-by-Beat Auswertung
    let mut beat_k_att = Vec::with_capacity(total_beats);
    let mut beat_ausklang = Vec::with_capacity(total_beats);
    let mut beat_cine = Vec::with_capacity(total_beats);

    for b in 0..total_beats {
        let t_start = grid_offset_sec + b as f64 * beat_sec;
        let t_end = t_start + beat_sec;
        let f_start = ((t_start / hop_sec) as usize).min(n_frames.saturating_sub(1));
        let f_end = n_frames.min(((t_end / hop_sec) as usize).max(f_start + 1));

        let sub_k_att = &kick_attack[f_start..f_end];
        let sub_ar = &ar[f_start..f_end];
        let sub_dec = &decay_env[f_start..f_end];
        let sub_tom = &toms_attack[f_start..f_end];

        let max_k_att = sub_k_att.iter().copied().fold(0.0f32, f32::max);
        let mean_ar = if !sub_ar.is_empty() { sub_ar.iter().sum::<f32>() / sub_ar.len() as f32 } else { 0.0 };
        let mean_dec = if !sub_dec.is_empty() { sub_dec.iter().sum::<f32>() / sub_dec.len() as f32 } else { 0.0 };
        let max_tom = sub_tom.iter().copied().fold(0.0f32, f32::max);

        let is_decay = (mean_dec > mean_ar * 1.25) && (max_k_att < 0.15) && (mean_ar < 0.45);
        let is_cine = (max_tom > 0.35) && (max_k_att < 0.25);

        beat_k_att.push(max_k_att);
        beat_ausklang.push(is_decay);
        beat_cine.push(is_cine);
    }

    // 6. Cliffhanger Detection (Kick-Cut Silence vor Drops)
    let mut cliffhangers = Vec::new();
    let mut b = 4;
    while b + 4 < total_beats {
        let prev_mean = beat_k_att[b.saturating_sub(6)..b].iter().sum::<f32>() / 6.0;
        let curr_silent = beat_k_att[b..b + 2].iter().copied().fold(0.0f32, f32::max) < 0.08;

        if prev_mean > 0.20 && curr_silent {
            let mut gap_len = 0;
            while (b + gap_len < total_beats) && (beat_k_att[b + gap_len] < 0.08) && gap_len < 8 {
                gap_len += 1;
            }
            if gap_len >= 2 {
                let next_drop = beat_k_att[b + gap_len..(b + gap_len + 4).min(total_beats)]
                    .iter().copied().fold(0.0f32, f32::max) > 0.30;
                if next_drop {
                    let t = grid_offset_sec + b as f64 * beat_sec;
                    let has_ausk = beat_ausklang[b..b + gap_len].iter().any(|&x| x);
                    cliffhangers.push(CliffhangerHit {
                        beat: b,
                        bar: b / 4 + 1,
                        beat_in_bar: (b % 4) + 1,
                        time_sec: t,
                        gap_beats: gap_len,
                        has_ausklang: has_ausk,
                    });
                    b += gap_len + 2;
                    continue;
                }
            }
        }
        b += 1;
    }

    // 7. Cinematic Drums Detection
    let mut cinematic_hits = Vec::new();
    for b in 1..total_beats.saturating_sub(1) {
        if beat_cine[b] {
            let t = grid_offset_sec + b as f64 * beat_sec;
            let f_idx = ((t / hop_sec) as usize).min(n_frames.saturating_sub(1));
            cinematic_hits.push(CinematicHit {
                beat: b,
                bar: b / 4 + 1,
                beat_in_bar: (b % 4) + 1,
                time_sec: t,
                strength: tr[f_idx],
                has_ausklang: true,
                decay_tau_ms: 280,
            });
        }
    }

    // 8. Genre-Erkennung (Acoustic/Trap vs. Hard-Techno vs. EDM)
    let active_kicks = beat_k_att.iter().filter(|&&x| x > 0.20).count();
    let kick_density = if total_beats > 0 { active_kicks as f64 / total_beats as f64 } else { 0.0 };
    let avg_decay = if !decay_env.is_empty() { decay_env.iter().sum::<f32>() / decay_env.len() as f32 } else { 0.0 };
    let avg_mid = if !tr.is_empty() { tr.iter().sum::<f32>() / tr.len() as f32 } else { 0.0 };

    let genre = if bpm <= 105.0 || (bpm >= 135.0 && kick_density < 0.45 && avg_decay > 0.12) || (avg_mid > 0.22 && kick_density < 0.50) {
        "ACOUSTIC_TRAP".to_string()
    } else if bpm >= 130.0 && kick_density >= 0.55 {
        "HARD_TECHNO".to_string()
    } else {
        "MELODIC_EDM".to_string()
    };

    // Definitionen der Baustein-Templates je nach Genre
    let acoustic_templates: [(f64, f64, &str, &str, &str, &str, f32, f32, f32, bool, u32, u32); 8] = [
        (0.00, 0.14, "ACOUSTIC_INTRO", "01. Acoustic Guitar Intro", "#f59e0b", "GENTLE_FAN_SWAY", 0.75, 0.65, 0.0, false, 80, 380),
        (0.14, 0.28, "VOCAL_VERSE", "02. Vocal Halo Verse 1", "#fff3e0", "BREATHING_CENTER_GLOW", 0.60, 0.50, 0.0, false, 75, 520),
        (0.28, 0.42, "TRAP_BUILDUP", "03. Hi-Hat Triplet Buildup", "#00e5ff", "NEEDLE_RACE_STACCATO", 1.40, 0.75, 3.5, false, 90, 180),
        (0.42, 0.56, "SUB_808_DROP", "04. 808 Sub-Bass Pulse Hook", "#8b5cf6", "FLOOR_PUNCH_BREATHE", 1.10, 1.00, 0.0, true, 100, 450),
        (0.56, 0.70, "CHORUS_BLOOM", "05. Melodic Chorus Bloom", "#ec4899", "WIDE_ORBITAL_SUNSET", 0.80, 1.20, 0.0, false, 95, 340),
        (0.70, 0.82, "VOCAL_VERSE", "06. Vocal Verse 2 & Strums", "#f59e0b", "BREATHING_CENTER_GLOW", 0.65, 0.55, 0.0, false, 80, 460),
        (0.82, 0.94, "CLIMAX_HOOK", "07. Climax Double Chorus", "#06b6d4", "WIDE_ORBITAL_SUNSET", 0.95, 1.30, 2.0, true, 100, 280),
        (0.94, 1.00, "LOFI_OUTRO", "08. Lo-Fi Vinyl Outro Fade", "#eab308", "CANDLE_FLICKER_STATIC", 0.40, 0.40, 0.0, false, 40, 600),
    ];

    let techno_templates: [(f64, f64, &str, &str, &str, &str, f32, f32, f32, bool, u32, u32); 16] = [
        (0.00, 0.04, "INTRO", "01. Intro Stomp & Rap", "#fab62d", "FAN_SPREAD", 0.90, 0.65, 0.0, false, 85, 240),
        (0.04, 0.12, "VERSE_GROOVE", "02. Verse Rap Groove 1", "#2dd4eb", "PAN_SWEEP", 0.95, 0.60, 0.0, false, 90, 200),
        (0.12, 0.16, "CINEMATIC_DRUMS", "03. Cinematic Stomp Intro", "#a55eea", "CINEMATIC_STOMP_SNAP", 1.20, 0.95, 3.0, true, 100, 280),
        (0.16, 0.35, "VERSE_GROOVE", "04. Verse Rap Main Groove", "#2dd4eb", "PAN_SWEEP", 0.95, 0.60, 0.0, false, 90, 200),
        (0.35, 0.38, "BUILDUP", "05. Snare Riser Buildup 1", "#e67e22", "TILT_WAVE", 1.35, 0.85, 6.0, false, 95, 160),
        (0.38, 0.40, "CLIFFHANGER_PRE_DROP", "06. Cliffhanger (Kick-Cut)", "#ff4757", "CLIFFHANGER_FREEZE", 0.0, 0.0, 0.0, false, 15, 260),
        (0.40, 0.50, "PEAK_DROP", "07. Bass Drop 1 (Schranz Snap)", "#eb3b5a", "HARD_SNAP_STACCATO", 1.50, 1.00, 10.0, true, 100, 120),
        (0.50, 0.58, "PEAK_DROP", "08. Anthem Chorus Drop", "#eb3b5a", "HARD_SNAP_STACCATO", 1.50, 1.00, 10.0, true, 100, 120),
        (0.58, 0.65, "CINEMATIC_DRUMS", "09. Cinematic Stomps Breakdown", "#a55eea", "CINEMATIC_STOMP_SNAP", 1.20, 0.95, 3.0, true, 100, 280),
        (0.65, 0.72, "VERSE_GROOVE", "10. Vocal Groove & Chords", "#2dd4eb", "PAN_SWEEP", 0.95, 0.60, 0.0, false, 90, 200),
        (0.72, 0.78, "CINEMATIC_DRUMS", "11. Cinematic Drums Breakdown 2", "#a55eea", "CINEMATIC_STOMP_SNAP", 1.20, 0.95, 3.0, true, 100, 280),
        (0.78, 0.84, "BREAKDOWN", "12. Atmospheric Vocal Bridge", "#3498db", "TILT_WAVE", 0.50, 0.40, 0.0, false, 70, 340),
        (0.84, 0.87, "BUILDUP", "13. Climax Snare Riser 2", "#e67e22", "TILT_WAVE", 1.35, 0.85, 6.0, false, 95, 160),
        (0.87, 0.88, "CLIFFHANGER_PRE_DROP", "14. Cliffhanger Pre-Drop 2", "#ff4757", "CLIFFHANGER_FREEZE", 0.0, 0.0, 0.0, false, 15, 260),
        (0.88, 0.95, "PEAK_DROP", "15. Bass Drop 2 (Maximum Climax)", "#eb3b5a", "HARD_SNAP_STACCATO", 1.50, 1.00, 10.0, true, 100, 120),
        (0.95, 1.00, "OUTRO", "16. Outro Sweep & Fade", "#f39c12", "FIGURE_8", 0.85, 0.50, 0.0, false, 75, 350),
    ];

    let section_templates: Vec<(f64, f64, &str, &str, &str, &str, f32, f32, f32, bool, u32, u32)> = if genre == "ACOUSTIC_TRAP" {
        acoustic_templates.to_vec()
    } else {
        techno_templates.to_vec()
    };

    let mut nodes = Vec::new();
    let num_templates = section_templates.len();
    let mut current_bar = 0;

    for (i, (_r_start, r_end, p_type, name, color, pattern, spd, amp, strobe, flame, dim, tau)) in section_templates.into_iter().enumerate() {
        let b_start = if i == 0 { 0 } else { current_bar };
        let mut b_end = if i == num_templates - 1 {
            total_bars
        } else {
            ((r_end * total_bars as f64).round() as usize).min(total_bars)
        };

        if b_end <= b_start && b_start < total_bars {
            b_end = (b_start + 1).min(total_bars);
        }

        if b_start >= total_bars || b_end <= b_start {
            continue;
        }

        current_bar = b_end;
        let bars = b_end.saturating_sub(b_start);
        let st = if i == 0 { 0.0 } else { (grid_offset_sec + b_start as f64 * bar_sec).min(duration_sec) };
        let et = if i == num_templates - 1 { duration_sec } else { (grid_offset_sec + b_end as f64 * bar_sec).min(duration_sec) };

        let phase_cliffs: Vec<CliffhangerHit> = cliffhangers.iter().filter(|c| c.bar >= b_start + 1 && c.bar <= b_end).cloned().collect();
        let phase_hits: Vec<CinematicHit> = cinematic_hits.iter().filter(|h| h.bar >= b_start + 1 && h.bar <= b_end).cloned().collect();

        let b_s = (b_start * 4).min(beat_ausklang.len().saturating_sub(1));
        let b_e = (b_end * 4).min(beat_ausklang.len());
        let has_ausk = if b_s < b_e {
            beat_ausklang[b_s..b_e].iter().any(|&x| x) || !phase_hits.is_empty() || p_type.contains("BREAKDOWN") || p_type.contains("ACOUSTIC")
        } else {
            false
        };

        let (coords_x, coords_y) = if num_templates <= 8 {
            (50.0 + (i as f64) * 260.0, 120.0)
        } else {
            let per_row = 4;
            let node_row = i / per_row;
            let col_in_row = i % per_row;
            let node_col = if node_row % 2 == 0 { col_in_row } else { per_row - 1 - col_in_row };
            (60.0 + (node_col as f64) * 250.0, 80.0 + (node_row as f64) * 180.0)
        };

        nodes.push(SongSection {
            id: format!("phase_{:02}", i + 1),
            name: name.to_string(),
            section_type: p_type.to_string(),
            start_bar: b_start,
            end_bar: b_end,
            bars,
            start_time: st,
            end_time: et,
            color: color.to_string(),
            pattern: pattern.to_string(),
            speed: spd,
            amp,
            strobe,
            flame,
            dim,
            ausklang_mode: if p_type.contains("DROP") || p_type == "PEAK_DROP" {
                "STACCATO_SNAP".into()
            } else if p_type.contains("BUILDUP") {
                "EXPONENTIAL_TAIL".into()
            } else if p_type.contains("LOFI") || p_type.contains("ACOUSTIC") {
                "NATURAL_EXPONENTIAL".into()
            } else {
                "SMOOTH_DECAY_FADE".into()
            },
            decay_tau_ms: tau,
            has_ausklang: has_ausk,
            cliffhangers: phase_cliffs,
            cinematic_hits: phase_hits,
            x: coords_x,
            y: coords_y,
            w: 180.0,
            h: 115.0,
        });
    }

    // 9. Saubere Signalverbindungen (ohne Spaghetti-Kreuzungen)
    let mut connections = Vec::new();
    for i in 0..nodes.len().saturating_sub(1) {
        connections.push(crate::types::FlowConnection {
            id: format!("wire_{}_{}", nodes[i].id, nodes[i + 1].id),
            from_node_id: nodes[i].id.clone(),
            from_pin: 0,
            to_node_id: nodes[i + 1].id.clone(),
            to_pin: 0,
        });
    }

    Ok(AnalysisResult {
        duration_sec,
        total_bars,
        bar_sec,
        grid_offset_sec,
        bpm,
        genre,
        nodes,
        connections,
        cliffhangers,
        cinematic_hits,
        energy_curve: ar,
        bass_curve: kr,
        high_curve: hr,
        decay_curve: decay_env,
        attack_curve: all_attack,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_auto_detect_bpm_hide() {
        let path = "C:\\Users\\User\\Documents\\Image-Line\\FL Studio\\Projects\\Hide (G0d'sMusic Remake)\\Hide (G0d'sMusic Remake).wav";
        if std::path::Path::new(path).exists() {
            let detected = detect_audio_bpm(path).expect("BPM detection should succeed");
            println!("Automatic BPM detected for Hide: {:.1}", detected);
            assert_eq!(detected, 100.0, "Hide must be accurately detected as 100.0 BPM");
        }
    }

    #[test]
    fn test_analyze_track_hide_at_100_bpm() {
        let path = "C:\\Users\\User\\Documents\\Image-Line\\FL Studio\\Projects\\Hide (G0d'sMusic Remake)\\Hide (G0d'sMusic Remake).wav";
        if std::path::Path::new(path).exists() {
            // Auto detection test
            let res_auto = analyze_track(path, None).expect("Auto analysis should succeed");
            assert_eq!(res_auto.bpm, 100.0);
            assert_eq!(res_auto.genre, "ACOUSTIC_TRAP");

            // Explicit 100.0 BPM override test
            let res_manual = analyze_track(path, Some(100.0)).expect("Manual analysis should succeed");
            assert_eq!(res_manual.bpm, 100.0);
            println!("Analysis at 100.0 BPM: total_bars={}, grid_offset={:.3}s", res_manual.total_bars, res_manual.grid_offset_sec);
        }
    }
}


