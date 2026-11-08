export interface CliffhangerHit {
  beat: number;
  bar: number;
  beat_in_bar: number;
  time_sec: number;
  gap_beats: number;
  has_ausklang: boolean;
}

export interface CinematicHit {
  beat: number;
  bar: number;
  beat_in_bar: number;
  time_sec: number;
  strength: number;
  has_ausklang: boolean;
  decay_tau_ms: number;
}

export interface SongSection {
  id: string;
  name: string;
  section_type: string;
  start_bar: number;
  end_bar: number;
  bars: number;
  start_time: number;
  end_time: number;
  color: string;
  pattern: string;
  speed: number;
  amp: number;
  strobe: number;
  flame: boolean;
  dim: number;
  ausklang_mode: string;
  decay_tau_ms: number;
  has_ausklang: boolean;
  cliffhangers: CliffhangerHit[];
  cinematic_hits: CinematicHit[];
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface FlowConnection {
  id: string;
  from_node_id: string;
  from_pin: number; // 0 = on_end, 1 = on_accent/kick/drop
  to_node_id: string;
  to_pin: number;   // 0 = in
}

export interface AnalysisResult {
  duration_sec: number;
  total_bars: number;
  bar_sec: number;
  grid_offset_sec: number;
  bpm: number;
  genre: string;
  nodes: SongSection[];
  connections: FlowConnection[];
  cliffhangers: CliffhangerHit[];
  cinematic_hits: CinematicHit[];
  energy_curve: number[];
  bass_curve: number[];
  high_curve: number[];
  decay_curve: number[];
  attack_curve: number[];
}

export interface OptimizationOutcome {
  optimized_nodes: SongSection[];
  total_passes: number;
  elapsed_sec: number;
  passes_per_sec: number;
  final_loss: number;
  final_fitness: number;
}

export interface PlaybackStatus {
  is_playing: boolean;
  position_ms: number;
  duration_ms: number;
  bar: number;
  beat_in_bar: number;
  active_phase_idx: number;
}

export interface PlasmaWindowState {
  id: string;
  title: string;
  icon: string;
  x: number;
  y: number;
  w: number;
  h: number;
  minW: number;
  minH: number;
  zIndex: number;
  isOpen: boolean;
  isMinimized: boolean;
  isMaximized: boolean;
  prevBounds?: { x: number; y: number; w: number; h: number };
}
