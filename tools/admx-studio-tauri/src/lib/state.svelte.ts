import { invoke } from "@tauri-apps/api/core";
import type {
  AnalysisResult,
  FlowConnection,
  OptimizationOutcome,
  PlaybackStatus,
  PlasmaWindowState,
  SongSection,
} from "./types";

export class StudioState {
  // Audio & Analysis State
  filePath = $state(
    "C:/Users/User/Documents/Image-Line/FL Studio/Projects/Hide (G0d'sMusic Remake)/Hide (G0d'sMusic Remake).wav"
  );
  dbPath = $state("c:/dev/test-server/plugins/ADMX/admx.db");
  slotIndex = $state(1);
  bpm = $state(100.0);
  gridOffsetSec = $state(0.036);
  analysis = $state<AnalysisResult | null>(null);
  nodes = $state<SongSection[]>([]);
  connections = $state<FlowConnection[]>([]);
  selectedNodeId = $state<string | null>(null);

  // Playback State
  playback = $state<PlaybackStatus>({
    is_playing: false,
    position_ms: 0,
    duration_ms: 0,
    bar: 1,
    beat_in_bar: 1,
    active_phase_idx: 0,
  });
  playbackInterval: number | null = null;

  // Optimization State
  isOptimizing = $state(false);
  optimizationOutcome = $state<OptimizationOutcome | null>(null);
  optimizationPasses = $state(250000);

  // DMX Mixer State
  selectedUniverse = $state(0);
  artnetTarget = $state("127.0.0.1");
  grandMaster = $state(100);
  blackoutActive = $state(false);
  dmxFaders = $state<number[]>(new Array(16).fill(0));
  dmxChannels = $state<number[]>(new Array(512).fill(0));

  // Desktop Windows State (KDE Plasma)
  windows = $state<PlasmaWindowState[]>([
    {
      id: "timeline",
      title: "Audio Waveform & Phase Timeline",
      icon: "📊",
      x: 30,
      y: 20,
      w: 860,
      h: 240,
      minW: 550,
      minH: 200,
      zIndex: 10,
      isOpen: true,
      isMinimized: false,
      isMaximized: false,
    },
    {
      id: "flowboard",
      title: "Node-Based Flowboard Studio (Sequence Bricks & Cables)",
      icon: "⚡",
      x: 30,
      y: 280,
      w: 860,
      h: 400,
      minW: 500,
      minH: 300,
      zIndex: 11,
      isOpen: true,
      isMinimized: false,
      isMaximized: false,
    },
    {
      id: "inspector",
      title: "Brick Parameter Inspector",
      icon: "🔍",
      x: 910,
      y: 20,
      w: 420,
      h: 360,
      minW: 320,
      minH: 280,
      zIndex: 12,
      isOpen: true,
      isMinimized: false,
      isMaximized: false,
    },
    {
      id: "optimizer",
      title: "Simulated Annealing Optimizer (Rust Rayon)",
      icon: "🧠",
      x: 910,
      y: 400,
      w: 420,
      h: 360,
      minW: 320,
      minH: 280,
      zIndex: 9,
      isOpen: true,
      isMinimized: false,
      isMaximized: false,
    },
    {
      id: "dmx",
      title: "DMX512 / Art-Net 4 Universe Console",
      icon: "🎛️",
      x: 120,
      y: 120,
      w: 800,
      h: 460,
      minW: 600,
      minH: 350,
      zIndex: 8,
      isOpen: false,
      isMinimized: false,
      isMaximized: false,
    },
  ]);

  topZIndex = $state(20);

  // Focus Window
  focusWindow(id: string) {
    const win = this.windows.find((w) => w.id === id);
    if (!win) return;
    this.topZIndex += 1;
    win.zIndex = this.topZIndex;
    if (win.isMinimized) {
      win.isMinimized = false;
    }
  }

  toggleMinimize(id: string) {
    const win = this.windows.find((w) => w.id === id);
    if (!win) return;
    win.isMinimized = !win.isMinimized;
    if (!win.isMinimized) {
      this.focusWindow(id);
    }
  }

  toggleMaximize(id: string) {
    const win = this.windows.find((w) => w.id === id);
    if (!win) return;
    if (win.isMaximized) {
      if (win.prevBounds) {
        win.x = win.prevBounds.x;
        win.y = win.prevBounds.y;
        win.w = win.prevBounds.w;
        win.h = win.prevBounds.h;
      }
      win.isMaximized = false;
    } else {
      win.prevBounds = { x: win.x, y: win.y, w: win.w, h: win.h };
      win.x = 0;
      win.y = 0;
      win.w = window.innerWidth;
      win.h = window.innerHeight - 38; // leave room for taskbar
      win.isMaximized = true;
    }
    this.focusWindow(id);
  }

  closeWindow(id: string) {
    const win = this.windows.find((w) => w.id === id);
    if (!win) return;
    win.isOpen = false;
  }

  openWindow(id: string) {
    const win = this.windows.find((w) => w.id === id);
    if (!win) return;
    win.isOpen = true;
    win.isMinimized = false;
    this.focusWindow(id);
  }

  // Audio & Analysis Actions
  async loadAndAnalyze(path: string, bpmVal?: number) {
    try {
      const res = await invoke<AnalysisResult>("load_and_analyze", {
        filePath: path,
        bpm: bpmVal ?? this.bpm,
      });
      this.analysis = res;
      this.nodes = res.nodes;
      this.connections = res.connections || [];
      this.gridOffsetSec = res.grid_offset_sec;
      this.bpm = res.bpm;
      this.playback.duration_ms = Math.round(res.duration_sec * 1000);
      return res;
    } catch (e: any) {
      console.error("load_and_analyze failed:", e);
      throw e;
    }
  }

  async autoDetectBpm(): Promise<number> {
    try {
      const detected = await invoke<number>("detect_track_bpm", {
        filePath: this.filePath,
      });
      this.bpm = detected;
      return detected;
    } catch (e: any) {
      console.error("autoDetectBpm failed:", e);
      throw e;
    }
  }

  addConnection(fromNodeId: string, fromPin: number, toNodeId: string, toPin: number) {
    if (fromNodeId === toNodeId) return;
    const existing = this.connections.find(
      (c) =>
        c.from_node_id === fromNodeId &&
        c.from_pin === fromPin &&
        c.to_node_id === toNodeId &&
        c.to_pin === toPin
    );
    if (existing) return;

    this.connections.push({
      id: `conn_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      from_node_id: fromNodeId,
      from_pin: fromPin,
      to_node_id: toNodeId,
      to_pin: toPin,
    });
  }

  removeConnection(id: string) {
    this.connections = this.connections.filter((c) => c.id !== id);
  }

  removeNode(id: string) {
    this.nodes = this.nodes.filter((n) => n.id !== id);
    this.connections = this.connections.filter(
      (c) => c.from_node_id !== id && c.to_node_id !== id
    );
    if (this.selectedNodeId === id) {
      this.selectedNodeId = null;
    }
  }

  async saveProfileToDb() {
    return await invoke<[string, number]>("save_profile", {
      dbPath: this.dbPath,
      slotIndex: this.slotIndex,
      nodes: this.nodes,
      connections: this.connections,
      confidence: this.optimizationOutcome?.final_fitness ?? 0.95,
      passes: this.optimizationOutcome?.total_passes ?? 0,
    });
  }

  async runOptimization(passes: number) {
    if (!this.analysis) {
      throw new Error("No track loaded");
    }
    this.isOptimizing = true;
    try {
      const outcome = await invoke<OptimizationOutcome>("run_optimization", {
        passes,
      });
      this.optimizationOutcome = outcome;
      this.nodes = outcome.optimized_nodes;
      return outcome;
    } finally {
      this.isOptimizing = false;
    }
  }

  async play() {
    await invoke("play_audio");
    this.playback.is_playing = true;
    this.startPlaybackPoller();
  }

  async pause() {
    await invoke("pause_audio");
    this.playback.is_playing = false;
    this.stopPlaybackPoller();
  }

  async stop() {
    await invoke("stop_audio");
    this.playback.is_playing = false;
    this.playback.position_ms = 0;
    this.playback.bar = 1;
    this.playback.beat_in_bar = 1;
    this.stopPlaybackPoller();
  }

  async seek(toMs: number) {
    await invoke("seek_audio", { toMs });
    this.playback.position_ms = toMs;
    await this.updatePlaybackStatus();
  }

  startPlaybackPoller() {
    if (this.playbackInterval) return;
    this.playbackInterval = window.setInterval(async () => {
      await this.updatePlaybackStatus();
    }, 40); // 25 Hz status update
  }

  stopPlaybackPoller() {
    if (this.playbackInterval) {
      clearInterval(this.playbackInterval);
      this.playbackInterval = null;
    }
  }

  async updatePlaybackStatus() {
    try {
      const st = await invoke<PlaybackStatus>("get_playback_status");
      this.playback = st;

      // Update active phase index based on current position
      const curSec = st.position_ms / 1000;
      let activeIdx = -1;
      for (let i = 0; i < this.nodes.length; i++) {
        const n = this.nodes[i];
        if (curSec >= n.start_time && curSec <= n.end_time) {
          activeIdx = i;
          break;
        }
      }
      this.playback.active_phase_idx = activeIdx;
    } catch (e) {
      // ignore
    }
  }

  async sendDmxFader(channelIdx: number, val: number) {
    this.dmxFaders[channelIdx] = val;
    this.dmxChannels[channelIdx] = this.blackoutActive
      ? 0
      : Math.round((val * this.grandMaster) / 100);

    try {
      await invoke("send_dmx_universe", {
        universe: this.selectedUniverse,
        targetAddr: this.artnetTarget,
        channels: this.dmxChannels,
      });
    } catch (e) {
      console.warn("ArtNet TX error:", e);
    }
  }

  toggleBlackout() {
    this.blackoutActive = !this.blackoutActive;
    // Broadcast DMX update with 0s if blackout
    const channelsToSend = this.blackoutActive
      ? new Array(512).fill(0)
      : this.dmxChannels.map((c) => Math.round((c * this.grandMaster) / 100));
    invoke("send_dmx_universe", {
      universe: this.selectedUniverse,
      targetAddr: this.artnetTarget,
      channels: channelsToSend,
    }).catch(() => {});
  }
}

export const studio = new StudioState();
