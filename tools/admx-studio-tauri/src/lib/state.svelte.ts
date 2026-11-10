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
    typeof localStorage !== "undefined"
      ? localStorage.getItem("admx_last_audio_file") || ""
      : ""
  );
  dbPath = $state(
    typeof localStorage !== "undefined"
      ? localStorage.getItem("admx_last_db_path") || "plugins/ADMX/admx.db"
      : "plugins/ADMX/admx.db"
  );
  slotIndex = $state(1);
  bpm = $state(120.0);
  gridOffsetSec = $state(0.0);
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
      if (typeof localStorage !== "undefined") {
        localStorage.setItem("admx_last_audio_file", path);
      }
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

  disconnectPin(nodeId: string, pinType: "in" | "out" | "all", pinIndex?: number) {
    if (pinType === "in" || pinType === "all") {
      this.connections = this.connections.filter(
        (c) => !(c.to_node_id === nodeId && (pinIndex === undefined || c.to_pin === pinIndex))
      );
    }
    if (pinType === "out" || pinType === "all") {
      this.connections = this.connections.filter(
        (c) => !(c.from_node_id === nodeId && (pinIndex === undefined || c.from_pin === pinIndex))
      );
    }
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

  splitNodeAtBar(nodeId: string, splitBar: number): SongSection | null {
    const idx = this.nodes.findIndex((n) => n.id === nodeId);
    if (idx === -1) return null;

    const node = this.nodes[idx];
    if (splitBar <= node.start_bar || splitBar >= node.end_bar) {
      return null;
    }

    const totalBars = node.bars > 0 ? node.bars : node.end_bar - node.start_bar;
    const barRatio = (splitBar - node.start_bar) / totalBars;
    const splitTime = node.start_time + barRatio * (node.end_time - node.start_time);

    const origEndBar = node.end_bar;
    const origEndTime = node.end_time;
    const origName = node.name.replace(/\s*\(Teil [A-Z0-9]+\)/g, "").replace(/\s*\(Bars \d+-\d+\)/g, "");

    // Split events
    const leftCliff = (node.cliffhangers || []).filter((c) => c.bar < splitBar);
    const rightCliff = (node.cliffhangers || []).filter((c) => c.bar >= splitBar);
    const leftHits = (node.cinematic_hits || []).filter((c) => c.bar < splitBar);
    const rightHits = (node.cinematic_hits || []).filter((c) => c.bar >= splitBar);

    // Update left node (Part A)
    node.end_bar = splitBar;
    node.bars = splitBar - node.start_bar;
    node.end_time = splitTime;
    node.cliffhangers = leftCliff;
    node.cinematic_hits = leftHits;
    node.name = `${origName} (Teil A)`;

    // Create right node (Part B)
    const newId = `node_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`;
    const newNode: SongSection = {
      ...JSON.parse(JSON.stringify(node)),
      id: newId,
      name: `${origName} (Teil B)`,
      start_bar: splitBar,
      end_bar: origEndBar,
      bars: origEndBar - splitBar,
      start_time: splitTime,
      end_time: origEndTime,
      cliffhangers: rightCliff,
      cinematic_hits: rightHits,
      x: node.x + 230,
      y: node.y,
    };

    // Splice newNode right after node
    this.nodes.splice(idx + 1, 0, newNode);

    // Rewire connections:
    // Outgoing connections from node on pin 0 (NEXT) move to newNode
    for (const c of this.connections) {
      if (c.from_node_id === node.id && c.from_pin === 0) {
        c.from_node_id = newNode.id;
      }
    }

    // Connect node -> newNode
    this.addConnection(node.id, 0, newNode.id, 0);

    this.selectedNodeId = newNode.id;
    return newNode;
  }

  splitAtPlayhead(): SongSection | null {
    if (this.nodes.length === 0) return null;
    const curSec = this.playback.position_ms / 1000;
    const barDuration = (60 / Math.max(20, this.bpm)) * 4;
    const approxBar = (curSec - this.gridOffsetSec) / barDuration;

    // Find node that contains this time or bar
    let targetNode = this.nodes.find((n) => curSec >= n.start_time && curSec < n.end_time);
    if (!targetNode && this.playback.active_phase_idx >= 0 && this.playback.active_phase_idx < this.nodes.length) {
      targetNode = this.nodes[this.playback.active_phase_idx];
    }
    if (!targetNode) {
      targetNode = this.nodes.find((n) => approxBar >= n.start_bar && approxBar <= n.end_bar);
    }
    if (!targetNode) return null;

    let splitBar = Math.round(approxBar);
    if (splitBar <= targetNode.start_bar) {
      splitBar = targetNode.start_bar + 1;
    }
    if (splitBar >= targetNode.end_bar) {
      splitBar = targetNode.end_bar - 1;
    }

    if (splitBar > targetNode.start_bar && splitBar < targetNode.end_bar) {
      return this.splitNodeAtBar(targetNode.id, splitBar);
    }
    return null;
  }

  mergeWithNextNode(nodeId: string): boolean {
    const idx = this.nodes.findIndex((n) => n.id === nodeId);
    if (idx === -1 || idx >= this.nodes.length - 1) return false;

    const nodeA = this.nodes[idx];
    const nodeB = this.nodes[idx + 1];

    // Combine bar boundaries
    nodeA.end_bar = nodeB.end_bar;
    nodeA.bars = nodeA.end_bar - nodeA.start_bar;
    nodeA.end_time = nodeB.end_time;
    nodeA.cliffhangers = [...(nodeA.cliffhangers || []), ...(nodeB.cliffhangers || [])];
    nodeA.cinematic_hits = [...(nodeA.cinematic_hits || []), ...(nodeB.cinematic_hits || [])];

    // Clean up name
    nodeA.name = nodeA.name.replace(/\s*\(Teil [A-Z0-9]+\)/g, "");

    // Rewire connections of nodeB to nodeA
    for (const c of this.connections) {
      if (c.from_node_id === nodeB.id) {
        c.from_node_id = nodeA.id;
      }
      if (c.to_node_id === nodeB.id) {
        c.to_node_id = nodeA.id;
      }
    }

    // Remove any self connections nodeA -> nodeA
    this.connections = this.connections.filter(
      (c) => !(c.from_node_id === nodeA.id && c.to_node_id === nodeA.id)
    );

    // Remove nodeB
    this.removeNode(nodeB.id);
    this.selectedNodeId = nodeA.id;
    return true;
  }

  insertNodeAfter(fromNodeId: string, template: any): SongSection {
    const idx = this.nodes.findIndex((n) => n.id === fromNodeId);
    const fromNode = idx !== -1 ? this.nodes[idx] : this.nodes[this.nodes.length - 1];

    const newX = fromNode ? fromNode.x + 240 : 60;
    const newY = fromNode ? fromNode.y : 120;
    const startBar = fromNode ? fromNode.end_bar : 0;
    const endBar = startBar + template.bars;
    const barSec = (60 / Math.max(20, this.bpm)) * 4;
    const startTime = this.gridOffsetSec + startBar * barSec;
    const endTime = this.gridOffsetSec + endBar * barSec;

    const newSection: SongSection = {
      id: `custom_node_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      name: `${template.name}`,
      section_type: "CUSTOM",
      start_bar: startBar,
      end_bar: endBar,
      bars: template.bars,
      start_time: startTime,
      end_time: endTime,
      color: template.color,
      pattern: template.pattern,
      speed: template.speed,
      amp: template.amp,
      strobe: template.strobe,
      flame: template.flame,
      dim: template.dim,
      ausklang_mode: "NATURAL_EXPONENTIAL",
      decay_tau_ms: template.tau,
      has_ausklang: template.tau > 200,
      cliffhangers: [],
      cinematic_hits: [],
      x: newX,
      y: newY,
      w: 190,
      h: 120,
    };

    if (fromNode) {
      // Shift downstream nodes to the right so they don't overlap
      for (let i = idx + 1; i < this.nodes.length; i++) {
        this.nodes[i].x += 240;
      }

      // Splice in new node right after fromNode
      this.nodes.splice(idx + 1, 0, newSection);

      // Re-route outgoing NEXT connections of fromNode to newSection
      for (const c of this.connections) {
        if (c.from_node_id === fromNode.id && c.from_pin === 0) {
          c.from_node_id = newSection.id;
        }
      }

      // Connect fromNode -> newSection
      this.addConnection(fromNode.id, 0, newSection.id, 0);
    } else {
      this.nodes.push(newSection);
    }

    this.selectedNodeId = newSection.id;
    return newSection;
  }

  async saveProfileToDb() {
    return await invoke<[string, number]>("save_profile", {
      dbPath: this.dbPath,
      slotIndex: this.slotIndex,
      nodes: this.nodes,
      connections: this.connections,
      confidence: this.optimizationOutcome?.final_fitness ?? 0.0,
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
