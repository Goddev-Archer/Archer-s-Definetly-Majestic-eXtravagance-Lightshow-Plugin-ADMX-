<script lang="ts">
  import { studio } from "../state.svelte";
  import type { SongSection } from "../types";

  let canvasEl: HTMLCanvasElement | null = $state(null);
  let isAnalyzing = $state(false);
  let analysisError = $state<string | null>(null);

  // Formatting helpers
  function formatTime(sec: number): string {
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`;
  }

  let curSec = $derived(studio.playback.position_ms / 1000);
  let durSec = $derived(
    studio.analysis ? studio.analysis.duration_sec : studio.playback.duration_ms / 1000
  );

  let activeSection = $derived<SongSection | null>(
    studio.nodes.length > 0 && studio.playback.active_phase_idx >= 0
      ? studio.nodes[studio.playback.active_phase_idx]
      : null
  );

  // Tap-Tempo & BPM Helpers
  let tapTimes: number[] = [];
  let tapLabel = $state("TAP");

  function handleTap() {
    const now = performance.now();
    if (tapTimes.length > 0 && now - tapTimes[tapTimes.length - 1] > 2500) {
      tapTimes = [];
    }
    tapTimes.push(now);
    if (tapTimes.length > 5) {
      tapTimes.shift();
    }
    if (tapTimes.length >= 2) {
      let totalDiff = 0;
      for (let i = 1; i < tapTimes.length; i++) {
        totalDiff += tapTimes[i] - tapTimes[i - 1];
      }
      const avgDiffMs = totalDiff / (tapTimes.length - 1);
      const calculatedBpm = Math.round((60000 / avgDiffMs) * 10) / 10;
      studio.bpm = Math.max(40, Math.min(240, calculatedBpm));
      tapLabel = `${studio.bpm.toFixed(0)}`;
    } else {
      tapLabel = "TAP...";
    }
  }

  function halveBpm() {
    studio.bpm = Math.round((studio.bpm / 2) * 10) / 10;
  }

  function doubleBpm() {
    studio.bpm = Math.round((studio.bpm * 2) * 10) / 10;
  }

  async function handleAutoDetectBpm() {
    if (!studio.filePath) return;
    isAnalyzing = true;
    analysisError = null;
    try {
      const detected = await studio.autoDetectBpm();
      await studio.loadAndAnalyze(studio.filePath, detected);
    } catch (e: any) {
      analysisError = e?.toString() || "Fehler bei automatischer BPM-Erkennung";
    } finally {
      isAnalyzing = false;
    }
  }

  async function handleAnalyze() {
    if (!studio.filePath) return;
    isAnalyzing = true;
    analysisError = null;
    try {
      await studio.loadAndAnalyze(studio.filePath, studio.bpm);
    } catch (e: any) {
      analysisError = e?.toString() || "Fehler bei der Audioanalyse";
    } finally {
      isAnalyzing = false;
    }
  }

  function handleTimelineClick(e: MouseEvent) {
    if (!canvasEl || durSec <= 0) return;
    const rect = canvasEl.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    const seekMs = Math.round(ratio * durSec * 1000);
    studio.seek(seekMs);
  }

  // Draw timeline waveform and phases
  $effect(() => {
    // depend on reactive values
    const _pos = studio.playback.position_ms;
    const nodes = studio.nodes;
    const analysis = studio.analysis;

    if (!canvasEl) return;
    const ctx = canvasEl.getContext("2d");
    if (!ctx) return;

    const w = canvasEl.width;
    const h = canvasEl.height;

    // Background
    ctx.fillStyle = "#121416";
    ctx.fillRect(0, 0, w, h);

    if (!analysis || durSec <= 0) {
      ctx.fillStyle = "#555b62";
      ctx.font = "13px 'Segoe UI', system-ui, sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("Keine Audiodatei geladen. Bitte Pfad angeben und 'Analysieren' klicken.", w / 2, h / 2);
      return;
    }

    // 1. Draw Waveform (top half: Y = 0 .. 50)
    const waveH = 48;
    const energy = analysis.energy_curve;
    if (energy && energy.length > 0) {
      const step = w / energy.length;
      ctx.fillStyle = "#204a60";
      for (let i = 0; i < energy.length; i++) {
        const val = energy[i];
        const barH = val * waveH;
        ctx.fillRect(i * step, waveH - barH, Math.max(1, step), barH);
      }

      // Bass punch overlay
      const bass = analysis.bass_curve;
      if (bass) {
        ctx.fillStyle = "rgba(61, 174, 233, 0.45)";
        for (let i = 0; i < bass.length; i++) {
          const bVal = bass[i];
          if (bVal > 0.3) {
            const bH = bVal * waveH * 0.7;
            ctx.fillRect(i * step, waveH - bH, Math.max(1, step), bH);
          }
        }
      }
    }

    // 2. Draw Phase Blocks (bottom half: Y = 52 .. h - 4)
    const phaseY = 52;
    const phaseH = h - phaseY - 4;

    for (let i = 0; i < nodes.length; i++) {
      const node = nodes[i];
      const startX = (node.start_time / durSec) * w;
      const endX = (node.end_time / durSec) * w;
      const blockW = Math.max(2, endX - startX);

      // Block background
      ctx.fillStyle = node.color || "#3daee9";
      ctx.fillRect(startX, phaseY, blockW, phaseH);

      // Border between sections
      ctx.strokeStyle = "#121416";
      ctx.lineWidth = 1.5;
      ctx.strokeRect(startX, phaseY, blockW, phaseH);

      // Section Text
      if (blockW > 45) {
        ctx.save();
        ctx.beginPath();
        ctx.rect(startX + 2, phaseY, blockW - 4, phaseH);
        ctx.clip();

        ctx.fillStyle = "#ffffff";
        ctx.font = "bold 11px 'Segoe UI', system-ui, sans-serif";
        ctx.textAlign = "left";
        ctx.fillText(node.name, startX + 6, phaseY + 16);

        ctx.fillStyle = "rgba(255, 255, 255, 0.85)";
        ctx.font = "9.5px 'Segoe UI', system-ui, sans-serif";
        const patternShort = node.pattern.replace(/_/g, " ").slice(0, 10);
        ctx.fillText(`${node.bars} Bars • ${patternShort}`, startX + 6, phaseY + 30);

        ctx.restore();
      }

      // Cliffhanger marker icons
      if (node.cliffhangers && node.cliffhangers.length > 0) {
        ctx.fillStyle = "#ff3838";
        for (const ch of node.cliffhangers) {
          const chX = (ch.time_sec / durSec) * w;
          ctx.beginPath();
          ctx.arc(chX, phaseY + phaseH - 6, 3, 0, Math.PI * 2);
          ctx.fill();
        }
      }

      // Cinematic Drum Hit markers
      if (node.cinematic_hits && node.cinematic_hits.length > 0) {
        ctx.fillStyle = "#f1c40f";
        for (const cin of node.cinematic_hits) {
          const cinX = (cin.time_sec / durSec) * w;
          ctx.beginPath();
          ctx.arc(cinX, phaseY + 6, 3, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    }

    // 3. Draw Scrubber Needle (white playhead line)
    const playheadX = (curSec / durSec) * w;
    ctx.strokeStyle = "#ffffff";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(playheadX, 0);
    ctx.lineTo(playheadX, h);
    ctx.stroke();

    // Playhead head cap
    ctx.fillStyle = "#ffffff";
    ctx.beginPath();
    ctx.moveTo(playheadX - 5, 0);
    ctx.lineTo(playheadX + 5, 0);
    ctx.lineTo(playheadX, 8);
    ctx.closePath();
    ctx.fill();
  });
</script>

<div class="timeline-container">
  <!-- Top Bar: Audio File & Configuration -->
  <div class="audio-control-bar">
    <div class="input-group path-group">
      <span class="label">Audiodatei:</span>
      <input
        type="text"
        class="input-path"
        bind:value={studio.filePath}
        placeholder="C:/path/to/song.mp3"
      />
    </div>

    <div class="input-group num-group">
      <span class="label">BPM:</span>
      <input
        type="number"
        step="0.1"
        class="input-num"
        bind:value={studio.bpm}
        title="Tempo manuell eingeben (z. B. 100.0)"
      />
      <!-- Quick BPM Tools -->
      <button class="t-mini-btn" onclick={handleAutoDetectBpm} title="BPM automatisch mit Multi-Kammfilter erkennen">
        ⚡ Auto
      </button>
      <button class="t-mini-btn tap-btn" onclick={handleTap} title="Im Takt klicken zum Erfassen des Tempos">
        {tapLabel}
      </button>
      <button class="t-mini-btn" onclick={halveBpm} title="Halbe BPM (½)">
        ½
      </button>
      <button class="t-mini-btn" onclick={doubleBpm} title="Doppelte BPM (2×)">
        2×
      </button>
    </div>

    <div class="input-group num-group">
      <span class="label">Offset:</span>
      <input
        type="number"
        step="0.001"
        class="input-num"
        bind:value={studio.gridOffsetSec}
        title="Taktanfang / Downbeat-Offset in Sekunden"
      />
      <span class="unit">s</span>
    </div>

    <button
      class="btn-analyze {isAnalyzing ? 'loading' : ''}"
      onclick={handleAnalyze}
      disabled={isAnalyzing}
      title="Track mit aktuellem Tempo analysieren und Flowboard-Phasen neu takten"
    >
      {isAnalyzing ? "Analysiere DSP..." : "⚡ Neu takten (Rust DSP)"}
    </button>

    {#if studio.analysis?.genre}
      <div class="genre-badge" title="Automatisch klassifizierter Musikstil">
        {studio.analysis.genre}
      </div>
    {/if}
  </div>

  {#if analysisError}
    <div class="error-banner">
      ⚠️ {analysisError}
    </div>
  {/if}

  <!-- Interactive Waveform & Phase Canvas -->
  <div class="canvas-wrapper">
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <canvas
      bind:this={canvasEl}
      width="1200"
      height="100"
      onclick={handleTimelineClick}
      title="Klicken zum Navigieren / Scrubben"
    ></canvas>
  </div>

  <!-- Bottom Transport Bar (Matches User Screenshot) -->
  <div class="transport-bar">
    <div class="transport-buttons">
      <button
        class="t-btn play"
        onclick={() => studio.play()}
        title="Abspielen"
      >
        ▶ PLAY
      </button>
      <button
        class="t-btn pause"
        onclick={() => studio.pause()}
        title="Pausieren"
      >
        ⏸ PAUSE
      </button>
      <button
        class="t-btn stop"
        onclick={() => studio.stop()}
        title="Stoppen"
      >
        ⏹ STOP
      </button>
    </div>

    <div class="time-display">
      <span class="time-val">{formatTime(curSec)} / {formatTime(durSec)}</span>
      <span class="bullet">•</span>
      <span class="bar-val">Bar: {studio.playback.bar}.{studio.playback.beat_in_bar}</span>
    </div>

    <div class="active-phase-tag">
      {#if activeSection}
        <span class="active-label">
          Aktive Phase: {activeSection.name} ({activeSection.pattern.slice(0, 10)})
        </span>
      {:else}
        <span class="idle-label">Keine aktive Phase</span>
      {/if}
    </div>
  </div>
</div>

<style>
  .timeline-container {
    display: flex;
    flex-direction: column;
    height: 100%;
    padding: 8px 12px;
    box-sizing: border-box;
    background: #17191b;
    gap: 8px;
    font-family: 'Segoe UI', system-ui, sans-serif;
  }

  .audio-control-bar {
    display: flex;
    align-items: center;
    gap: 10px;
    background: #1e2124;
    padding: 6px 10px;
    border-radius: 4px;
    border: 1px solid #282c30;
  }

  .input-group {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .path-group {
    flex: 1;
  }

  .label {
    font-size: 11px;
    color: #8c939d;
    font-weight: 500;
  }

  .unit {
    font-size: 10px;
    color: #6c737c;
  }

  input[type="text"],
  input[type="number"] {
    background: #121416;
    border: 1px solid #31363b;
    border-radius: 3px;
    color: #eff0f1;
    font-size: 11.5px;
    padding: 4px 8px;
    outline: none;
    transition: border-color 0.15s ease;
  }

  input[type="text"]:focus,
  input[type="number"]:focus {
    border-color: #3daee9;
  }

  .input-path {
    width: 100%;
  }

  .input-num {
    width: 65px;
  }

  .btn-analyze {
    background: linear-gradient(180deg, #2b7bb9 0%, #1d5f94 100%);
    border: 1px solid #3daee9;
    border-radius: 4px;
    color: #ffffff;
    font-size: 11px;
    font-weight: 600;
    padding: 5px 14px;
    cursor: pointer;
    white-space: nowrap;
    transition: background 0.15s ease;
  }

  .btn-analyze:hover {
    background: linear-gradient(180deg, #358ccc 0%, #226ba5 100%);
  }

  .btn-analyze:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .t-mini-btn {
    background: #25292e;
    border: 1px solid #363c42;
    border-radius: 3px;
    color: #e0e4e8;
    font-size: 10px;
    font-weight: 600;
    padding: 3px 6px;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .t-mini-btn:hover {
    background: #31363b;
    border-color: #3daee9;
    color: #ffffff;
  }

  .tap-btn {
    background: #2d261e;
    border-color: #f59e0b;
    color: #f59e0b;
  }

  .tap-btn:hover {
    background: #f59e0b;
    color: #000000;
  }

  .genre-badge {
    background: #1e3a5f;
    border: 1px solid #3daee9;
    color: #38bdf8;
    font-size: 10px;
    font-weight: 700;
    padding: 3px 8px;
    border-radius: 4px;
    letter-spacing: 0.5px;
    white-space: nowrap;
  }

  .error-banner {
    background: rgba(218, 68, 83, 0.2);
    border: 1px solid #da4453;
    color: #ff7882;
    padding: 4px 10px;
    border-radius: 4px;
    font-size: 11px;
  }

  .canvas-wrapper {
    flex: 1;
    display: flex;
    background: #121416;
    border: 1px solid #282c30;
    border-radius: 4px;
    overflow: hidden;
    position: relative;
    cursor: crosshair;
  }

  canvas {
    width: 100%;
    height: 100%;
    display: block;
  }

  /* Transport Bar matching Screenshot */
  .transport-bar {
    display: flex;
    align-items: center;
    background: #14171a;
    border: 1px solid #23272b;
    border-radius: 4px;
    padding: 5px 10px;
    gap: 16px;
  }

  .transport-buttons {
    display: flex;
    gap: 4px;
  }

  .t-btn {
    border: none;
    border-radius: 3px;
    font-size: 11px;
    font-weight: 700;
    padding: 4px 10px;
    cursor: pointer;
    letter-spacing: 0.5px;
    transition: all 0.15s ease;
  }

  .t-btn.play {
    background: #00b4d8;
    color: #0b1e28;
  }
  .t-btn.play:hover {
    background: #48cae4;
  }

  .t-btn.pause {
    background: #2b3137;
    color: #cbd3dc;
    border: 1px solid #3d444d;
  }
  .t-btn.pause:hover {
    background: #394047;
    color: #ffffff;
  }

  .t-btn.stop {
    background: #e63946;
    color: #ffffff;
  }
  .t-btn.stop:hover {
    background: #f0525f;
  }

  .time-display {
    display: flex;
    align-items: center;
    gap: 8px;
    font-family: 'Consolas', 'Courier New', monospace;
    font-size: 12px;
    color: #00b4d8;
    font-weight: 600;
  }

  .bullet {
    color: #555e69;
  }

  .bar-val {
    color: #e0e6ed;
  }

  .active-phase-tag {
    margin-left: auto;
    font-size: 11px;
    font-weight: 600;
  }

  .active-label {
    color: #f87171;
  }

  .idle-label {
    color: #555e69;
  }
</style>
