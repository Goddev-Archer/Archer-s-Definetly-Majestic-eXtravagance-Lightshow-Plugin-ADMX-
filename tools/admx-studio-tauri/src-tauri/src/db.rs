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
    let conn = Connection::open(db_path).map_err(|e| format!("Failed to open admx.db: {e}"))?;

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
            let slot = (i % 36) as u32;
            let node_type = if n.strobe > 0.0 {
                "STROBE_BLAST"
            } else if n.flame {
                "FLAME_SHOT"
            } else if n.pattern.contains("SWEEP")
                || n.pattern.contains("WAVE")
                || n.pattern.contains("FAN")
                || n.pattern.contains("CIRCLE")
                || n.pattern.contains("SNAP")
            {
                "MOVEMENT_SHAPE"
            } else {
                "DIMMER_PULSE"
            };

            let start_tick = (n.start_time * 20.0).round() as i64;
            let duration_ticks = ((n.end_time - n.start_time) * 20.0).round().max(20.0) as i64;

            json!({
                "nodeId": node_id,
                "slot": slot,
                "type": node_type,
                "targetGroupId": "*",
                "startTick": start_tick,
                "durationTicks": duration_ticks,
                "param1": n.speed,
                "param2": n.amp,
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
                Some(json!({
                    "fromNodeId": from_idx + 1,
                    "fromPin": c.from_pin,
                    "toNodeId": to_idx + 1,
                    "toPin": c.to_pin
                }))
            })
            .collect()
    } else {
        (0..nodes.len().saturating_sub(1))
            .map(|i| {
                json!({
                    "fromNodeId": i + 1,
                    "fromPin": 0,
                    "toNodeId": i + 2,
                    "toPin": 0
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

    let learned_beats = nodes.iter().map(|n| n.bars * 4).sum::<usize>();
    let profile_data = json!({
        "profileId": show_id,
        "displayName": board_name,
        "slotIndex": slot_index,
        "referenceGenre": if bpm <= 95.0 { "ACOUSTIC_TRAP" } else { "TECHNO" },
        "choreoStyle": if bpm <= 95.0 { "SUNSET_GROOVE" } else { "SCHRANZ_STACCATO" },
        "referenceBpm": bpm,
        "gridOffsetSec": grid_offset_sec,
        "phraseLengthBars": 16,
        "learnedFloorEnergy": 0.03,
        "learnedPeakEnergy": 0.94,
        "breakdownThreshold": 0.18,
        "buildupSlopeThreshold": 0.10,
        "dropThreshold": 0.85,
        "bassDominanceRatio": 1.65,
        "primaryHueDeg": 38.0,
        "secondaryHueDeg": 195.0,
        "saturation": 0.98,
        "breakdownPattern": "TILT_WAVE",
        "versePattern": "PAN_SWEEP",
        "buildupPattern": "FAN_SPREAD",
        "dropPattern": if bpm <= 95.0 { "WIDE_ORBITAL_SUNSET" } else { "HARD_SNAP_STACCATO" },
        "speedMultiplier": 1.20,
        "amplitude": 1.00,
        "phaseSpread": 0.90,
        "maxStrobeHz": 10.0,
        "blinderFlashOnKick": true,
        "pyroOnDropBar": true,
        "learnedBeats": learned_beats,
        "completedPasses": passes,
        "detectedDrops": 2,
        "confidence": confidence,
        "sections": nodes
    });

    let profile_json_str = serde_json::to_string(&profile_data).map_err(|e| format!("Profile JSON error: {e}"))?;

    // Exakte CRC32-Prüfsumme nach ADMX-Standard
    let payload = format!(
        "{}|{}|{}|{:.2}|0.0300|0.9400|0.8500|10.00|{}",
        show_id, board_name, slot_index, bpm, learned_beats
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
