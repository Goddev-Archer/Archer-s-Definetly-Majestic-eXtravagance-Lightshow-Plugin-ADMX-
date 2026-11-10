use crate::types::{FlowConnection, SongSection};
use crc32fast::Hasher;
use rusqlite::{params, Connection};
use serde_json::json;

pub fn save_profile_to_db(
    db_path: &str,
    slot_index: u32,
    bpm: f64,
    grid_offset_sec: f64,
    nodes: &[SongSection],
    connections: &[FlowConnection],
    confidence: f64,
    passes: usize,
) -> Result<(String, u32), String> {
    let target_path_str = if db_path.trim().is_empty() {
        "plugins/ADMX/admx.db"
    } else {
        db_path.trim()
    };
    let path = std::path::Path::new(target_path_str);
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent).map_err(|e| {
                format!("Failed to create parent directory '{}': {e}", parent.display())
            })?;
        }
    }
    let conn = Connection::open(path).map_err(|e| format!("Failed to open '{target_path_str}': {e}"))?;

    let show_id = format!("show_{:02}", slot_index + 1);
    let total_duration_ticks = if let Some(last) = nodes.last() {
        (last.end_time * 20.0).round().max(100.0) as i64
    } else {
        400
    };

    // 1. Persistiere in 'flowboards' Tabelle (damit /admx flow play <id> sofort funktioniert)
    conn.execute(
        "CREATE TABLE IF NOT EXISTS flowboards (
            board_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            duration_ticks INTEGER NOT NULL,
            loop_mode INTEGER NOT NULL,
            nodes_json TEXT NOT NULL,
            connections_json TEXT NOT NULL
        );",
        [],
    ).map_err(|e| format!("Failed to create flowboards table: {e}"))?;

    let flow_nodes: Vec<serde_json::Value> = nodes
        .iter()
        .enumerate()
        .map(|(i, n)| {
            let node_id = i + 1;
            let start_tick = (n.start_time * 20.0).round() as i64;
            let duration_ticks = ((n.end_time - n.start_time) * 20.0).round().max(20.0) as i64;

            json!({
                "nodeId": node_id,
                "slot": (i as u32),
                "type": "COMPOSITE_CUE",
                "targetGroupId": "*",
                "startTick": start_tick,
                "durationTicks": duration_ticks,
                "param1": n.speed,
                "param2": (n.dim as f32 / 100.0).clamp(0.0, 1.0),
                "paramString": n.pattern
            })
        })
        .collect();

    let flow_connections: Vec<serde_json::Value> = if !connections.is_empty() {
        connections
            .iter()
            .filter_map(|c| {
                let from_idx = nodes.iter().position(|n| n.id == c.from_node_id)?;
                let to_idx = nodes.iter().position(|n| n.id == c.to_node_id)?;
                let conn_type = if c.from_pin == 1 { "SYNC_START" } else { "TRIGGER_ON_END" };
                Some(json!({
                    "fromNodeId": from_idx + 1,
                    "toNodeId": to_idx + 1,
                    "type": conn_type
                }))
            })
            .collect()
    } else {
        (0..nodes.len().saturating_sub(1))
            .map(|i| {
                json!({
                    "fromNodeId": i + 1,
                    "toNodeId": i + 2,
                    "type": "TRIGGER_ON_END"
                })
            })
            .collect()
    };

    let nodes_json = serde_json::to_string(&flow_nodes).map_err(|e| format!("Nodes JSON error: {e}"))?;
    let connections_json = serde_json::to_string(&flow_connections).map_err(|e| format!("Connections JSON error: {e}"))?;
    let board_name = format!("{:.0} BPM Flowboard Show", bpm);

    conn.execute(
        "INSERT INTO flowboards (board_id, name, duration_ticks, loop_mode, nodes_json, connections_json)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(board_id) DO UPDATE SET
            name = excluded.name,
            duration_ticks = excluded.duration_ticks,
            loop_mode = excluded.loop_mode,
            nodes_json = excluded.nodes_json,
            connections_json = excluded.connections_json;",
        params![show_id, board_name, total_duration_ticks, 1, nodes_json, connections_json],
    ).map_err(|e| format!("Failed to insert into flowboards table: {e}"))?;

    // 2. Persistiere in 'auto_show_profiles' Tabelle (für /admx autostart & SmartAutoDirector)
    conn.execute(
        "CREATE TABLE IF NOT EXISTS auto_show_profiles (
            profile_id TEXT PRIMARY KEY,
            display_name TEXT NOT NULL,
            slot_index INTEGER NOT NULL,
            profile_json TEXT NOT NULL,
            integrity_crc INTEGER NOT NULL
        );",
        [],
    ).map_err(|e| format!("Failed to create auto_show_profiles table: {e}"))?;

    // Berechne echte Quantile und Kennwerte aus den analysierten Abschnitten
    let dims: Vec<f32> = nodes.iter().map(|n| n.dim as f32 / 100.0).collect();
    let min_dim = dims.iter().copied().fold(1.0f32, f32::min).clamp(0.01, 0.40);
    let max_dim = dims.iter().copied().fold(0.0f32, f32::max).clamp(0.50, 1.00);

    let floor_energy = min_dim;
    let peak_energy = max_dim;
    let breakdown_thresh = (floor_energy + (peak_energy - floor_energy) * 0.20).clamp(0.10, 0.50);
    let drop_thresh = (floor_energy + (peak_energy - floor_energy) * 0.65).clamp(0.45, 0.95);

    let detected_drops = nodes.iter().filter(|n| {
        n.section_type.contains("DROP") || n.pattern.contains("SNAP") || n.strobe > 4.0
    }).count().max(1);

    let phrase_length_bars = if nodes.iter().any(|n| n.bars >= 16) {
        16
    } else if nodes.iter().any(|n| n.bars >= 8) {
        8
    } else {
        4
    };

    let ref_genre = if bpm <= 115.0 {
        "ACOUSTIC_TRAP"
    } else if bpm >= 140.0 {
        "TECHNO"
    } else {
        "MELODIC_EDM"
    };

    let choreo_style = if bpm <= 115.0 {
        "SUNSET_GROOVE"
    } else if bpm >= 140.0 {
        "SCHRANZ_STACCATO"
    } else {
        "FESTIVAL_MAINSTAGE"
    };

    let parse_hex_hue = |hex: &str| -> f32 {
        let clean = hex.trim_start_matches('#');
        if clean.len() >= 6 {
            if let (Ok(r), Ok(g), Ok(b)) = (
                u8::from_str_radix(&clean[0..2], 16),
                u8::from_str_radix(&clean[2..4], 16),
                u8::from_str_radix(&clean[4..6], 16),
            ) {
                let rf = r as f32 / 255.0;
                let gf = g as f32 / 255.0;
                let bf = b as f32 / 255.0;
                let c_max = rf.max(gf).max(bf);
                let c_min = rf.min(gf).min(bf);
                let delta = c_max - c_min;
                if delta > 1e-4 {
                    let mut h = if (c_max - rf).abs() < 1e-5 {
                        ((gf - bf) / delta) % 6.0
                    } else if (c_max - gf).abs() < 1e-5 {
                        ((bf - rf) / delta) + 2.0
                    } else {
                        ((rf - gf) / delta) + 4.0
                    } * 60.0;
                    if h < 0.0 {
                        h += 360.0;
                    }
                    return h;
                }
            }
        }
        38.0
    };

    let primary_hue = nodes.first().map(|n| parse_hex_hue(&n.color)).unwrap_or(38.0);
    let secondary_hue = nodes.iter().find(|n| n.section_type.contains("DROP") || n.pattern.contains("SNAP"))
        .map(|n| parse_hex_hue(&n.color))
        .unwrap_or(195.0);

    let max_strobe = nodes.iter().map(|n| n.strobe).fold(0.0f32, f32::max).clamp(0.0, 10.0);
    let learned_beats = nodes.iter().map(|n| n.bars * 4).sum::<usize>();

    let profile_data = json!({
        "profileId": show_id,
        "displayName": board_name,
        "slotIndex": slot_index,
        "referenceGenre": ref_genre,
        "choreoStyle": choreo_style,
        "referenceBpm": bpm,
        "gridOffsetSec": grid_offset_sec,
        "phraseLengthBars": phrase_length_bars,
        "learnedFloorEnergy": floor_energy,
        "learnedPeakEnergy": peak_energy,
        "breakdownThreshold": breakdown_thresh,
        "buildupSlopeThreshold": 0.10,
        "dropThreshold": drop_thresh,
        "bassDominanceRatio": 1.65,
        "primaryHueDeg": primary_hue,
        "secondaryHueDeg": secondary_hue,
        "saturation": 0.95,
        "breakdownPattern": if bpm <= 115.0 { "BREATHING_CENTER_GLOW" } else { "TILT_WAVE" },
        "versePattern": "PAN_SWEEP",
        "buildupPattern": "TILT_WAVE",
        "dropPattern": if bpm <= 115.0 { "WIDE_ORBITAL_SUNSET" } else { "HARD_SNAP_STACCATO" },
        "speedMultiplier": 1.20,
        "amplitude": 1.00,
        "phaseSpread": 0.90,
        "maxStrobeHz": max_strobe,
        "blinderFlashOnKick": true,
        "pyroOnDropBar": true,
        "learnedBeats": learned_beats,
        "completedPasses": passes,
        "detectedDrops": detected_drops,
        "confidence": confidence,
        "sections": nodes
    });

    let profile_json_str = serde_json::to_string(&profile_data).map_err(|e| format!("Profile JSON error: {e}"))?;

    // Exakte CRC32-Prüfsumme nach ADMX-Standard
    let payload = format!(
        "{}|{}|{}|{:.2}|{:.4}|{:.4}|{:.4}|{:.2}|{}",
        show_id, board_name, slot_index, bpm, floor_energy, peak_energy, drop_thresh, max_strobe, learned_beats
    );
    let mut hasher = Hasher::new();
    hasher.update(payload.as_bytes());
    let crc = hasher.finalize();

    conn.execute(
        "INSERT INTO auto_show_profiles (profile_id, display_name, slot_index, profile_json, integrity_crc)
         VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(profile_id) DO UPDATE SET
            display_name = excluded.display_name,
            slot_index = excluded.slot_index,
            profile_json = excluded.profile_json,
            integrity_crc = excluded.integrity_crc;",
        params![show_id, board_name, slot_index, profile_json_str, crc],
    ).map_err(|e| format!("Failed to insert into auto_show_profiles: {e}"))?;

    Ok((show_id, crc))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_save_profile_creates_parent_dir_and_tables() {
        let test_dir = "target/test_admx_db/plugins/ADMX";
        let test_db = format!("{test_dir}/test_admx.db");
        let _ = fs::remove_dir_all("target/test_admx_db");

        let node = SongSection {
            id: "test_1".to_string(),
            name: "1. Intro".to_string(),
            section_type: "INTRO".to_string(),
            start_bar: 0,
            end_bar: 8,
            bars: 8,
            start_time: 0.0,
            end_time: 19.2,
            color: "#f59e0b".to_string(),
            pattern: "GENTLE_FAN_SWAY".to_string(),
            speed: 1.0,
            amp: 1.0,
            strobe: 0.0,
            flame: false,
            dim: 80,
            ausklang_mode: "NATURAL_EXPONENTIAL".to_string(),
            decay_tau_ms: 250,
            has_ausklang: true,
            cliffhangers: vec![],
            cinematic_hits: vec![],
            x: 0.0,
            y: 0.0,
            w: 190.0,
            h: 120.0,
        };

        let res = save_profile_to_db(&test_db, 0, 100.0, 0.036, &[node], &[], 0.95, 1000);
        assert!(res.is_ok(), "save_profile_to_db failed: {:?}", res.err());
        let (id, crc) = res.unwrap();
        assert_eq!(id, "show_01");
        assert!(crc > 0);

        let _ = fs::remove_dir_all("target/test_admx_db");
    }
}
