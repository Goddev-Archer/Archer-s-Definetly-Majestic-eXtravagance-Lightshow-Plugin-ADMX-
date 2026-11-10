use crate::types::{AnalysisResult, SongSection};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};
use std::time::Instant;

#[derive(Clone, Copy)]
pub struct ParamTarget {
    pub dim: f32,       // 0..1
    pub tau: f32,       // 0..1 (0..600ms)
    pub speed: f32,     // 0..1 (0..3.0x)
    pub strobe: f32,    // 0..1 (0..10.0Hz)
    pub amp: f32,       // 0..1
    pub is_freeze: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OptimizationOutcome {
    pub optimized_nodes: Vec<SongSection>,
    pub total_passes: usize,
    pub elapsed_sec: f64,
    pub passes_per_sec: f64,
    pub final_loss: f64,
    pub final_fitness: f64,
}

pub fn run_parallel_simulated_annealing(
    analysis: &AnalysisResult,
    passes: usize,
) -> OptimizationOutcome {
    let t0 = Instant::now();
    let num_nodes = analysis.nodes.len();
    if num_nodes == 0 {
        return OptimizationOutcome {
            optimized_nodes: analysis.nodes.clone(),
            total_passes: 0,
            elapsed_sec: 0.0,
            passes_per_sec: 0.0,
            final_loss: 0.0,
            final_fitness: 1.0,
        };
    }

    let n_frames = analysis.energy_curve.len();
    let mut targets = Vec::with_capacity(num_nodes);
    let mut current_theta: Vec<[f32; 5]> = Vec::with_capacity(num_nodes);

    for n in &analysis.nodes {
        let st_f = (n.start_time / 0.010) as usize;
        let et_f = ((n.end_time / 0.010) as usize).min(n_frames).max(st_f + 1);

        let sub_ar = &analysis.energy_curve[st_f..et_f];
        let sub_hr = &analysis.high_curve[st_f..et_f];
        let sub_dec = &analysis.decay_curve[st_f..et_f];

        let m_e = if !sub_ar.is_empty() { sub_ar.iter().sum::<f32>() / sub_ar.len() as f32 } else { 0.5 };
        let p_e = sub_ar.iter().copied().fold(0.0f32, f32::max);
        let m_h = if !sub_hr.is_empty() { sub_hr.iter().sum::<f32>() / sub_hr.len() as f32 } else { 0.2 };
        let m_dec = if !sub_dec.is_empty() { sub_dec.iter().sum::<f32>() / sub_dec.len() as f32 } else { 0.3 };

        let is_freeze = n.section_type == "CLIFFHANGER_PRE_DROP";

        let (t_dim, t_tau, t_speed, t_strobe, t_amp) = if is_freeze {
            (15.0f32, 260.0f32, 0.0f32, 0.0f32, 0.0f32)
        } else {
            // Kontinuierliche Zielparameter-Berechnung aus den echten Audio-Kurven
            let tempo_scale = (analysis.bpm as f32 / 120.0).clamp(0.6, 1.8);
            let target_dim = (25.0 + 75.0 * (m_e * 0.65 + p_e * 0.35).powf(0.85)).clamp(25.0, 100.0);
            let target_tau = (110.0 + 440.0 * m_dec).clamp(90.0, 580.0);
            let target_speed = ((0.55 + 1.15 * m_e) * tempo_scale).clamp(0.4, 2.5);
            // Strenges Einhalten des Fotosensibilitäts-Limits (SEC-09, SEC-15): Strobe max 10.0 Hz
            let target_strobe = if m_h > 0.35 && m_e > 0.65 {
                ((m_h - 0.35) * 16.0).clamp(2.0, 10.0)
            } else {
                0.0
            };
            let target_amp = (0.35 + 0.65 * (m_e * 0.5 + p_e * 0.5)).clamp(0.35, 1.0);
            (target_dim, target_tau, target_speed, target_strobe, target_amp)
        };

        targets.push(ParamTarget {
            dim: t_dim / 100.0,
            tau: t_tau / 600.0,
            speed: t_speed / 3.0,
            strobe: t_strobe / 10.0,
            amp: t_amp,
            is_freeze,
        });

        current_theta.push([
            n.dim as f32 / 100.0,
            n.decay_tau_ms as f32 / 600.0,
            n.speed / 3.0,
            n.strobe / 10.0,
            n.amp,
        ]);
    }

    let weights = [0.30f32, 0.20f32, 0.25f32, 0.15f32, 0.10f32];

    let calc_loss = |theta: &[[f32; 5]]| -> f64 {
        let mut total = 0.0;
        for (i, p) in theta.iter().enumerate() {
            let t = &targets[i];
            let diff = [
                p[0] - t.dim,
                p[1] - t.tau,
                p[2] - t.speed,
                p[3] - t.strobe,
                p[4] - t.amp,
            ];
            let node_loss: f32 = diff.iter().zip(&weights).map(|(d, w)| d * d * w).sum();
            total += node_loss as f64;
        }

        // Dynamischer Phasen-Kontrast-Loss: Belohnt markante Helligkeitssprünge zwischen Abschnitten
        let mut contrast_loss = 0.0;
        for j in 1..num_nodes {
            let target_jump = (targets[j].dim - targets[j - 1].dim).abs();
            let actual_jump = (theta[j][0] - theta[j - 1][0]).abs();
            contrast_loss += ((actual_jump - target_jump).powi(2) as f64) * 0.12;
        }

        (total + contrast_loss) / num_nodes as f64
    };

    let mut best_theta = current_theta.clone();
    let mut best_loss = calc_loss(&best_theta);

    // Parallel Batch Annealing über Rayon
    let batch_size = 250;
    let n_batches = (passes / batch_size).max(1);

    for b in 0..n_batches {
        let prog = (b * batch_size) as f64 / passes as f64;
        let temp = (1.0 - prog).powf(1.8).max(0.0001);
        let sigma = 0.18 * temp as f32;

        // Generiere und bewerte Batches parallel mit Rayon
        let candidate_batch: Vec<(Vec<[f32; 5]>, f64)> = (0..batch_size)
            .into_par_iter()
            .map(|thread_idx| {
                // Schneller deterministischer LCG Pseudo-Random pro Thread
                let mut seed = (b as u64)
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(thread_idx as u64)
                    .wrapping_add(1);

                let mut rand_f32 = || -> f32 {
                    seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                    ((seed >> 32) as f32 / u32::MAX as f32) * 2.0 - 1.0
                };

                let mut cand = best_theta.clone();
                for (i, p) in cand.iter_mut().enumerate() {
                    let tgt = &targets[i];
                    if tgt.is_freeze {
                        p[0] = (p[0] + rand_f32() * sigma).clamp(0.10, 0.25);
                        p[1] = (p[1] + rand_f32() * sigma).clamp(0.20, 0.60);
                        p[2] = 0.0;
                        p[3] = 0.0;
                        p[4] = 0.0;
                    } else {
                        p[0] = (p[0] + rand_f32() * sigma).clamp(0.0, 1.0);
                        p[1] = (p[1] + rand_f32() * sigma).clamp(0.0, 1.0);
                        p[2] = (p[2] + rand_f32() * sigma).clamp(0.0, 1.0);
                        p[3] = (p[3] + rand_f32() * sigma).clamp(0.0, 1.0);
                        p[4] = (p[4] + rand_f32() * sigma).clamp(0.0, 1.0);
                    }
                }

                let loss = calc_loss(&cand);
                (cand, loss)
            })
            .collect();

        // Finde den besten Kandidaten des Batches
        if let Some((min_cand, min_loss)) = candidate_batch.into_iter().min_by(|a, b| a.1.partial_cmp(&b.1).unwrap()) {
            let delta_l = min_loss - best_loss;
            if delta_l < 0.0 || (temp > 0.01 && (-delta_l / (temp * 0.05)).exp() > 0.5) {
                best_loss = min_loss;
                best_theta = min_cand;
            }
        }
    }

    let elapsed = t0.elapsed().as_secs_f64();
    let passes_per_sec = passes as f64 / elapsed.max(0.001);
    let final_fitness = 1.0 / (1.0 + best_loss);

    // Optimierte Parameter in die SongSections zurückschreiben
    let mut optimized_nodes = analysis.nodes.clone();
    for (i, n) in optimized_nodes.iter_mut().enumerate() {
        let p = &best_theta[i];
        n.dim = (p[0] * 100.0).round() as u32;
        n.decay_tau_ms = (p[1] * 600.0).round() as u32;
        n.speed = ((p[2] * 3.0) * 100.0).round() / 100.0;
        n.strobe = ((p[3] * 10.0) * 10.0).round() / 10.0;
        n.amp = (p[4] * 100.0).round() / 100.0;
    }

    OptimizationOutcome {
        optimized_nodes,
        total_passes: passes,
        elapsed_sec: elapsed,
        passes_per_sec,
        final_loss: best_loss,
        final_fitness,
    }
}
