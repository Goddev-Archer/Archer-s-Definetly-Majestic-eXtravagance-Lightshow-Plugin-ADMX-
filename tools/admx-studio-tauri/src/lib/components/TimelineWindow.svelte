<script lang="ts">
  import { studio } from "../state.svelte";
  import type { SongSection } from "../types";

  let canvasWrapperEl: HTMLDivElement | null = $state(null);
  let canvasEl: HTMLCanvasElement | null = $state(null);
  let canvasCssW = $state(800);
  let canvasCssH = $state(110);

  let isAnalyzing = $state(false);
  let analysisError = $state<string | null>(null);

  // Split / Razor tool & Context Menu state
  let splitMode = $state(false);
  let hoverInfo = $state<{
    x: number;
    y: number;
    bar: number;
    node: SongSection | null;
    isNearBoundary: boolean;
    boundaryLeftNode: SongSection | null;
    boundaryRightNode: SongSection | null;
  } | null>(null);

  let contextMenu = $state<{
    visible: boolean;
    x: number;
    y: number;
    node: SongSection;
    bar: number;
  } | null>(null);

  let isDraggingBoundary = $state(false);
  let dragBoundaryInfo = $state<{
    nodeLeft: SongSection;
    nodeRight: SongSection;
    minBar: number;
    maxBar: number;
  } | null>(null);

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

  let barSec = $derived((60 / Math.max(20, studio.bpm)) * 4);

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

  // Handle ResizeObserver for responsive canvas
  $effect(() => {
    if (!canvasWrapperEl) return;
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { width, height } = entry.contentRect;
        if (width > 20 && height > 20) {
          canvasCssW = Math.floor(width);
          canvasCssH = Math.floor(height);
          if (canvasEl) {
            const dpr = window.devicePixelRatio || 1;
            canvasEl.width = Math.floor(width * dpr);
            canvasEl.height = Math.floor(height * dpr);
          }
        }
      }
    });
    observer.observe(canvasWrapperEl);
    return () => observer.disconnect();
  });

  // Calculate Bar and Node at a specific canvas X coordinate
  function getTimelineCoords(clientX: number): { timeSec: number; bar: number; node: SongSection | null } {
    if (!canvasEl || durSec <= 0) return { timeSec: 0, bar: 0, node: null };
    const rect = canvasEl.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    const timeSec = ratio * durSec;
    const bar = Math.round((timeSec - studio.gridOffsetSec) / barSec);
    const node = studio.nodes.find((n) => timeSec >= n.start_time && timeSec <= n.end_time) || null;
    return { timeSec, bar, node };
  }

  // Pointer Move: Hovering & Dragging Boundary
  function handlePointerMove(e: PointerEvent) {
    if (!canvasEl || durSec <= 0) return;
    const rect = canvasEl.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    if (isDraggingBoundary && dragBoundaryInfo) {
      const curRatio = Math.max(0, Math.min(1, x / rect.width));
      const curTime = curRatio * durSec;
      const targetBar = Math.round((curTime - studio.gridOffsetSec) / barSec);
      const clampedBar = Math.max(dragBoundaryInfo.minBar, Math.min(dragBoundaryInfo.maxBar, targetBar));

      const { nodeLeft, nodeRight } = dragBoundaryInfo;
      if (clampedBar !== nodeLeft.end_bar) {
        const splitTime = studio.gridOffsetSec + clampedBar * barSec;
        nodeLeft.end_bar = clampedBar;
        nodeLeft.bars = clampedBar - nodeLeft.start_bar;
        nodeLeft.end_time = splitTime;

        nodeRight.start_bar = clampedBar;
        nodeRight.bars = nodeRight.end_bar - clampedBar;
        nodeRight.start_time = splitTime;
      }
      return;
    }

    // Check proximity to node boundaries in bottom half (y > 46)
    const { timeSec, bar, node } = getTimelineCoords(e.clientX);
    let isNearBoundary = false;
    let bLeft: SongSection | null = null;
    let bRight: SongSection | null = null;

    if (y > 46) {
      for (let i = 0; i < studio.nodes.length - 1; i++) {
        const n1 = studio.nodes[i];
        const n2 = studio.nodes[i + 1];
        const boundX = (n1.end_time / durSec) * rect.width;
        if (Math.abs(x - boundX) <= 6) {
          isNearBoundary = true;
          bLeft = n1;
          bRight = n2;
          break;
        }
      }
    }

    hoverInfo = {
      x,
      y,
      bar,
      node,
      isNearBoundary,
      boundaryLeftNode: bLeft,
      boundaryRightNode: bRight,
    };
  }

  function handlePointerLeave() {
    if (!isDraggingBoundary) {
      hoverInfo = null;
    }
  }

  // Pointer Down on Timeline
  function handleCanvasPointerDown(e: PointerEvent) {
    if (contextMenu) {
      contextMenu = null;
    }

    if (e.button === 2) {
      // Right-click: open context menu for splitting or merging
      e.preventDefault();
      const { bar, node } = getTimelineCoords(e.clientX);
      if (node) {
        contextMenu = {
          visible: true,
          x: e.clientX,
          y: e.clientY,
          node,
          bar,
        };
      }
      return;
    }

    if (e.button === 0) {
      // If hovering near a boundary, start boundary drag
      if (hoverInfo?.isNearBoundary && hoverInfo.boundaryLeftNode && hoverInfo.boundaryRightNode) {
        isDraggingBoundary = true;
        dragBoundaryInfo = {
          nodeLeft: hoverInfo.boundaryLeftNode,
          nodeRight: hoverInfo.boundaryRightNode,
          minBar: hoverInfo.boundaryLeftNode.start_bar + 1,
          maxBar: hoverInfo.boundaryRightNode.end_bar - 1,
        };
        window.addEventListener("pointerup", handleGlobalPointerUp);
        return;
      }

      // If in Split / Razor mode, execute split immediately
      if (splitMode) {
        const { bar, node } = getTimelineCoords(e.clientX);
        if (node && bar > node.start_bar && bar < node.end_bar) {
          studio.splitNodeAtBar(node.id, bar);
          splitMode = false;
          return;
        }
      }

      // Default: seek audio
      const { timeSec } = getTimelineCoords(e.clientX);
      studio.seek(Math.round(timeSec * 1000));
    }
  }

  function handleGlobalPointerUp() {
    isDraggingBoundary = false;
    dragBoundaryInfo = null;
    window.removeEventListener("pointerup", handleGlobalPointerUp);
  }

  // Double Click: Split section at double-clicked bar
  function handleCanvasDblClick(e: MouseEvent) {
    const { bar, node } = getTimelineCoords(e.clientX);
    if (node && bar > node.start_bar && bar < node.end_bar) {
      studio.splitNodeAtBar(node.id, bar);
    }
  }

  // Split at playhead or hovered position (Key S)
  function handleSplitAtPlayhead() {
    studio.splitAtPlayhead();
  }

  function handleMergeWithNext(nodeId: string) {
    studio.mergeWithNextNode(nodeId);
    if (contextMenu) contextMenu = null;
  }

  function handleContextSplit() {
    if (!contextMenu) return;
    const { node, bar } = contextMenu;
    if (bar > node.start_bar && bar < node.end_bar) {
      studio.splitNodeAtBar(node.id, bar);
    }
    contextMenu = null;
  }

  // Global keydown listener for 'S' (Split)
  function handleKeyDown(e: KeyboardEvent) {
    if ((e.target as HTMLElement).tagName === "INPUT") return;
    if (e.key === "s" || e.key === "S") {
      if (hoverInfo?.node && hoverInfo.bar > hoverInfo.node.start_bar && hoverInfo.bar < hoverInfo.node.end_bar) {
        studio.splitNodeAtBar(hoverInfo.node.id, hoverInfo.bar);
      } else {
        studio.splitAtPlayhead();
      }
    }
  }

  // Draw timeline waveform, phases, markers, and split overlay
  $effect(() => {
    // Reactive dependencies
    const _pos = studio.playback.position_ms;
    const nodes = studio.nodes;
    const analysis = studio.analysis;
    const w = canvasCssW;
    const h = canvasCssH;
    const hover = hoverInfo;
    const isSplitting = splitMode;

    if (!canvasEl) return;
    const ctx = canvasEl.getContext("2d");
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    ctx.save();
    ctx.scale(dpr, dpr);

    // Background
    ctx.fillStyle = "#121416";
    ctx.fillRect(0, 0, w, h);

    if (!analysis || durSec <= 0) {
      ctx.fillStyle = "#555b62";
      ctx.font = "12px 'Segoe UI', system-ui, sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("Keine Audiodatei geladen. Bitte Pfad angeben und 'Analysieren' klicken.", w / 2, h / 2);
      ctx.restore();
      return;
    }

    // 1. Draw Waveform (top half: Y = 0 .. 46)
    const waveH = 46;
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

    // Divider line between Waveform and Phase blocks
    ctx.strokeStyle = "#25292e";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, waveH);
    ctx.lineTo(w, waveH);
    ctx.stroke();

    // 2. Draw Phase Blocks (bottom half: Y = 48 .. h)
    const phaseY = 48;
    const phaseH = h - phaseY;

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
      if (blockW > 35) {
        ctx.save();
        ctx.beginPath();
        ctx.rect(startX + 2, phaseY, blockW - 4, phaseH);
        ctx.clip();

        ctx.fillStyle = "#ffffff";
        ctx.font = "bold 10.5px 'Segoe UI', system-ui, sans-serif";
        ctx.textAlign = "left";
        ctx.fillText(node.name, startX + 5, phaseY + 15);

        ctx.fillStyle = "rgba(255, 255, 255, 0.85)";
        ctx.font = "9px 'Segoe UI', system-ui, sans-serif";
        const patternShort = node.pattern.replace(/_/g, " ").slice(0, 12);
        ctx.fillText(`${node.bars} Bars • ${patternShort}`, startX + 5, phaseY + 28);

        ctx.restore();
      }

      // Cliffhanger marker icons (red dots)
      if (node.cliffhangers && node.cliffhangers.length > 0) {
        ctx.fillStyle = "#ff3838";
        for (const ch of node.cliffhangers) {
          const chX = (ch.time_sec / durSec) * w;
          ctx.beginPath();
          ctx.arc(chX, phaseY + phaseH - 5, 3, 0, Math.PI * 2);
          ctx.fill();
        }
      }

      // Cinematic Drum Hit markers (yellow dots)
      if (node.cinematic_hits && node.cinematic_hits.length > 0) {
        ctx.fillStyle = "#f1c40f";
        for (const cin of node.cinematic_hits) {
          const cinX = (cin.time_sec / durSec) * w;
          ctx.beginPath();
          ctx.arc(cinX, phaseY + 5, 3, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    }

    // 3. Hover Cut / Razor Indicator Line
    if (hover && (isSplitting || hover.y > 46)) {
      const snapTime = studio.gridOffsetSec + hover.bar * barSec;
      const snapX = (snapTime / durSec) * w;

      if (hover.isNearBoundary) {
        // Highlight boundary resize handle
        ctx.strokeStyle = "#00e5ff";
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(snapX, phaseY);
        ctx.lineTo(snapX, h);
        ctx.stroke();
      } else if (hover.node && hover.bar > hover.node.start_bar && hover.bar < hover.node.end_bar) {
        // Dotted Cut Line
        ctx.strokeStyle = isSplitting ? "#ff3838" : "#ffffff";
        ctx.lineWidth = 1.5;
        ctx.setLineDash([4, 2]);
        ctx.beginPath();
        ctx.moveTo(snapX, 0);
        ctx.lineTo(snapX, h);
        ctx.stroke();
        ctx.setLineDash([]);

        // Cut Scissor Badge
        ctx.fillStyle = isSplitting ? "#ff3838" : "rgba(30, 33, 36, 0.9)";
        ctx.strokeStyle = "#3daee9";
        ctx.lineWidth = 1;
        const badgeW = 60;
        const badgeX = Math.max(4, Math.min(w - badgeW - 4, snapX - badgeW / 2));
        ctx.fillRect(badgeX, phaseY + 4, badgeW, 16);
        ctx.strokeRect(badgeX, phaseY + 4, badgeW, 16);

        ctx.fillStyle = "#ffffff";
        ctx.font = "bold 9px 'Segoe UI', system-ui, sans-serif";
        ctx.textAlign = "center";
        ctx.fillText(`✂ Bar ${hover.bar}`, badgeX + badgeW / 2, phaseY + 15);
      }
    }

    // 4. Draw Scrubber Needle (white playhead line)
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

    ctx.restore();
  });
</script>

<svelte:window onkeydown={handleKeyDown} />

<div class="timeline-container">
  <!-- Top Bar: Audio File & Configuration -->
  <div class="audio-control-bar">
    <div class="input-group path-group">
      <span class="label">Audiodatei:</span>
      <input
        type="text"
        class="input-path"
        bind:value={studio.filePath}
        placeholder="C:/path/to/song.wav"
      />
    </div>

    <div class="input-group num-group">
      <span class="label">BPM:</span>
      <input
        type="number"
        step="0.1"
        class="input-num bpm-input"
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

    <div class="input-group num-group offset-group">
      <span class="label">Offset:</span>
      <input
        type="number"
        step="0.001"
        class="input-num offset-input"
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
  <div
    class="canvas-wrapper {splitMode ? 'split-cursor' : hoverInfo?.isNearBoundary ? 'resize-cursor' : ''}"
    bind:this={canvasWrapperEl}
    onpointermove={handlePointerMove}
    onpointerleave={handlePointerLeave}
    onpointerdown={handleCanvasPointerDown}
    ondblclick={handleCanvasDblClick}
    role="slider"
    tabindex="0"
    aria-label="Audio Waveform & Phase Timeline"
    aria-valuenow={curSec}
    aria-valuemin={0}
    aria-valuemax={durSec}
  >
    <canvas bind:this={canvasEl}></canvas>

    <!-- Context Menu for Track Splitting / Merging -->
    {#if contextMenu && contextMenu.visible}
      <div
        class="timeline-context-menu"
        style="left: {contextMenu.x}px; top: {contextMenu.y}px;"
      >
        <button class="ctx-item" onclick={handleContextSplit}>
          ✂️ Bei Bar {contextMenu.bar} trennen (Split)
        </button>
        <button class="ctx-item" onclick={() => handleMergeWithNext(contextMenu!.node.id)}>
          🔗 Mit nächstem Baustein verschmelzen (Merge)
        </button>
        <button class="ctx-item danger" onclick={() => { studio.removeNode(contextMenu!.node.id); contextMenu = null; }}>
          🗑 Baustein löschen
        </button>
      </div>
    {/if}
  </div>

  <!-- Bottom Transport Bar -->
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

    <!-- Interactive Track Slice / Cut Tools -->
    <div class="slice-tools">
      <button
        class="t-btn split-btn {splitMode ? 'active' : ''}"
        onclick={() => (splitMode = !splitMode)}
        title="Schere / Schnitt-Werkzeug aktivieren (oder Doppelklick auf Phase)"
      >
        ✂️ Schere {splitMode ? 'AN' : ''}
      </button>
      <button
        class="t-btn cut-cursor-btn"
        onclick={handleSplitAtPlayhead}
        title="Spur exakt an aktueller Playhead-Position teilen (Shortcut: Taste S)"
      >
        ✂️ Teilen bei Cursor [S]
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
          Aktive Phase: {activeSection.name} ({activeSection.pattern.slice(0, 12)})
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
    padding: 8px 10px;
    box-sizing: border-box;
    background: #17191b;
    gap: 6px;
    font-family: 'Segoe UI', system-ui, sans-serif;
    position: relative;
    overflow: hidden;
  }

  .audio-control-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    background: #1e2124;
    padding: 6px 10px;
    border-radius: 4px;
    border: 1px solid #282c30;
    flex-wrap: wrap;
  }

  .input-group {
    display: flex;
    align-items: center;
    gap: 5px;
  }

  .path-group {
    flex: 1 1 200px;
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

  .bpm-input {
    width: 65px;
  }

  .offset-input {
    width: 90px;
    min-width: 80px;
  }

  .btn-analyze {
    background: linear-gradient(180deg, #2b7bb9 0%, #1d5f94 100%);
    border: 1px solid #3daee9;
    border-radius: 4px;
    color: #ffffff;
    font-size: 11px;
    font-weight: 600;
    padding: 5px 12px;
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
    min-height: 80px;
    display: flex;
    background: #121416;
    border: 1px solid #282c30;
    border-radius: 4px;
    overflow: hidden;
    position: relative;
    cursor: crosshair;
  }

  .canvas-wrapper.split-cursor {
    cursor: cell;
  }

  .canvas-wrapper.resize-cursor {
    cursor: col-resize;
  }

  canvas {
    width: 100%;
    height: 100%;
    display: block;
  }

  /* Context Menu */
  .timeline-context-menu {
    position: fixed;
    background: #23272b;
    border: 1px solid #3daee9;
    border-radius: 6px;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.7);
    padding: 4px;
    z-index: 1000;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .ctx-item {
    background: transparent;
    border: none;
    border-radius: 4px;
    color: #eff0f1;
    font-size: 11px;
    padding: 6px 10px;
    text-align: left;
    cursor: pointer;
    white-space: nowrap;
    transition: background 0.12s ease;
  }

  .ctx-item:hover {
    background: #31363b;
    color: #3daee9;
  }

  .ctx-item.danger:hover {
    background: #da4453;
    color: #ffffff;
  }

  /* Transport Bar */
  .transport-bar {
    display: flex;
    align-items: center;
    background: #14171a;
    border: 1px solid #23272b;
    border-radius: 4px;
    padding: 5px 8px;
    gap: 12px;
    flex-wrap: wrap;
  }

  .transport-buttons,
  .slice-tools {
    display: flex;
    gap: 4px;
  }

  .t-btn {
    border: none;
    border-radius: 3px;
    font-size: 11px;
    font-weight: 700;
    padding: 4px 8px;
    cursor: pointer;
    letter-spacing: 0.4px;
    transition: all 0.15s ease;
    white-space: nowrap;
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

  .t-btn.split-btn {
    background: #252a2f;
    border: 1px solid #3a424a;
    color: #e0e4e8;
  }
  .t-btn.split-btn:hover,
  .t-btn.split-btn.active {
    background: #d63031;
    border-color: #ff7675;
    color: #ffffff;
  }

  .t-btn.cut-cursor-btn {
    background: #1e293b;
    border: 1px solid #3b82f6;
    color: #60a5fa;
  }
  .t-btn.cut-cursor-btn:hover {
    background: #2563eb;
    color: #ffffff;
  }

  .time-display {
    display: flex;
    align-items: center;
    gap: 6px;
    font-family: 'Consolas', 'Courier New', monospace;
    font-size: 11.5px;
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
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .active-label {
    color: #f87171;
  }

  .idle-label {
    color: #555e69;
  }
</style>