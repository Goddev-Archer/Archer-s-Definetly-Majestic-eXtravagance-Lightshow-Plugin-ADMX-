<script lang="ts">
  import { studio } from "../state.svelte";
  import type { SongSection } from "../types";

  let selectedNode = $derived<SongSection | null>(
    studio.nodes.find((n) => n.id === studio.selectedNodeId) ??
      (studio.nodes.length > 0 ? studio.nodes[0] : null)
  );

  const availablePatterns = [
    "GENTLE_FAN_SWAY",
    "BREATHING_CENTER_GLOW",
    "FLOOR_PUNCH_BREATHE",
    "NEEDLE_RACE_STACCATO",
    "WIDE_ORBITAL_SUNSET",
    "CANDLE_FLICKER_STATIC",
    "HARD_SNAP_STACCATO",
    "PAN_SWEEP",
    "TILT_WAVE",
    "CIRCLE_VORTEX",
    "BALLYHOO_ARENA",
    "FOUR_CORNER_CHASE",
    "CLIFFHANGER_FREEZE",
    "CINEMATIC_STOMP_SNAP",
    "LASER_FAN",
    "BLINDER_STROBE_PULSE",
  ];

  const ausklangModes = [
    "NATURAL_EXPONENTIAL",
    "HARD_CUT",
    "TENSION_HOLD",
    "STACCATO_SNAP",
  ];

  const palette = [
    "#f59e0b", // Amber
    "#06b6d4", // Cyan
    "#8b5cf6", // Purple
    "#f97316", // Orange
    "#ec4899", // Pink
    "#3b82f6", // Blue
    "#10b981", // Emerald
    "#eab308", // Yellow
    "#ef4444", // Red
    "#14b8a6", // Teal
  ];
</script>

<div class="inspector-root">
  {#if selectedNode}
    <div class="inspector-scroll">
      <div class="section-title">
        <span>Brickeigenschaften: <strong>{selectedNode.name}</strong></span>
      </div>

      <!-- Brick Name & Color -->
      <div class="field-row">
        <label for="brick-name">Name:</label>
        <input
          id="brick-name"
          type="text"
          class="text-input"
          bind:value={selectedNode.name}
        />
      </div>

      <div class="field-row">
        <label for="color-native">Farbe:</label>
        <div class="color-picker-row">
          <input
            id="color-native"
            type="color"
            class="color-native"
            bind:value={selectedNode.color}
          />
          <div class="palette-swatches">
            {#each palette as col}
              <button
                class="swatch"
                style="background: {col}"
                onclick={() => {
                  if (selectedNode) selectedNode.color = col;
                }}
                aria-label="Farbe auswählen"
              ></button>
            {/each}
          </div>
        </div>
      </div>

      <!-- Movement Pattern -->
      <div class="field-row">
        <label for="brick-pattern">Bewegung:</label>
        <select
          id="brick-pattern"
          class="select-input"
          bind:value={selectedNode.pattern}
        >
          {#each availablePatterns as pat}
            <option value={pat}>{pat}</option>
          {/each}
        </select>
      </div>

      <!-- Bars & Duration -->
      <div class="field-row">
        <label for="brick-bars">Länge (Bars):</label>
        <input
          id="brick-bars"
          type="number"
          min="1"
          max="64"
          class="num-input"
          bind:value={selectedNode.bars}
        />
      </div>

      <!-- Ausklang Decay Parameters -->
      <div class="group-box">
        <div class="group-header">Ausklang & Decay Engine</div>
        <div class="field-row">
          <label for="ausklang-mode">Modus:</label>
          <select
            id="ausklang-mode"
            class="select-input"
            bind:value={selectedNode.ausklang_mode}
          >
            {#each ausklangModes as mode}
              <option value={mode}>{mode}</option>
            {/each}
          </select>
        </div>

        <div class="field-row slider-row">
          <div class="slider-label">
            <span>Decay Tau:</span>
            <span class="slider-val">{selectedNode.decay_tau_ms} ms</span>
          </div>
          <input
            type="range"
            min="0"
            max="600"
            step="10"
            bind:value={selectedNode.decay_tau_ms}
          />
        </div>
      </div>

      <!-- Movement & Light Dynamics -->
      <div class="group-box">
        <div class="group-header">Lichtdynamik</div>

        <div class="field-row slider-row">
          <div class="slider-label">
            <span>Dimmer:</span>
            <span class="slider-val">{selectedNode.dim}%</span>
          </div>
          <input
            type="range"
            min="0"
            max="100"
            step="1"
            bind:value={selectedNode.dim}
          />
        </div>

        <div class="field-row slider-row">
          <div class="slider-label">
            <span>Speed:</span>
            <span class="slider-val">{selectedNode.speed.toFixed(2)}x</span>
          </div>
          <input
            type="range"
            min="0.1"
            max="4.0"
            step="0.05"
            bind:value={selectedNode.speed}
          />
        </div>

        <div class="field-row slider-row">
          <div class="slider-label">
            <span>Strobe Frequenz:</span>
            <span class="slider-val strobe-val">
              {selectedNode.strobe.toFixed(1)} Hz (PSE-Cap &le; 10Hz)
            </span>
          </div>
          <input
            type="range"
            min="0"
            max="10.0"
            step="0.5"
            bind:value={selectedNode.strobe}
          />
        </div>

        <div class="field-row checkbox-row">
          <input
            type="checkbox"
            id="flame-toggle"
            bind:checked={selectedNode.flame}
          />
          <label for="flame-toggle">
            🔥 Cryo / Flame Jet auf Drops & Akzente aktivieren
          </label>
        </div>
      </div>

      <!-- Cliffhangers & Cinematic Hits stats -->
      <div class="stats-card">
        <div class="stat-item">
          <span class="stat-num">{selectedNode.cliffhangers?.length ?? 0}</span>
          <span class="stat-desc">Cliffhangers (fehlende Kicks)</span>
        </div>
        <div class="stat-item">
          <span class="stat-num">{selectedNode.cinematic_hits?.length ?? 0}</span>
          <span class="stat-desc">Cinematic Drum Hits</span>
        </div>
      </div>
    </div>
  {:else}
    <div class="no-selection">
      <span>Kein Baustein ausgewählt. Klicken Sie auf einen Brick im Flowboard oder in der Timeline.</span>
    </div>
  {/if}
</div>

<style>
  .inspector-root {
    height: 100%;
    background: #17191b;
    color: #eff0f1;
    font-family: 'Segoe UI', system-ui, sans-serif;
    font-size: 11.5px;
    overflow-y: auto;
  }

  .inspector-scroll {
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .section-title {
    font-size: 12px;
    color: #3daee9;
    border-bottom: 1px solid #282c30;
    padding-bottom: 6px;
  }

  .section-title strong {
    color: #ffffff;
  }

  .field-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .field-row label {
    width: 90px;
    color: #8c939d;
    font-weight: 500;
  }

  .text-input,
  .select-input,
  .num-input {
    background: #121416;
    border: 1px solid #31363b;
    border-radius: 4px;
    color: #ffffff;
    font-size: 11.5px;
    padding: 4px 8px;
    outline: none;
    flex: 1;
  }

  .text-input:focus,
  .select-input:focus,
  .num-input:focus {
    border-color: #3daee9;
  }

  .color-picker-row {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: 1;
  }

  .color-native {
    width: 28px;
    height: 24px;
    border: none;
    border-radius: 3px;
    cursor: pointer;
    background: transparent;
  }

  .palette-swatches {
    display: flex;
    gap: 3px;
  }

  .swatch {
    width: 16px;
    height: 16px;
    border-radius: 2px;
    border: 1px solid rgba(0, 0, 0, 0.4);
    cursor: pointer;
  }

  .swatch:hover {
    transform: scale(1.15);
  }

  .group-box {
    background: #1c1f22;
    border: 1px solid #282c30;
    border-radius: 5px;
    padding: 8px 10px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .group-header {
    font-size: 10.5px;
    font-weight: 700;
    text-transform: uppercase;
    color: #3daee9;
    letter-spacing: 0.5px;
  }

  .slider-row {
    flex-direction: column;
    align-items: stretch;
    gap: 4px;
  }

  .slider-label {
    display: flex;
    justify-content: space-between;
    font-size: 11px;
    color: #abb0b6;
  }

  .slider-val {
    color: #ffffff;
    font-weight: 600;
  }

  .strobe-val {
    color: #f1c40f;
  }

  input[type="range"] {
    accent-color: #3daee9;
    cursor: pointer;
    height: 4px;
  }

  .checkbox-row {
    display: flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
  }

  .checkbox-row label {
    width: auto;
    color: #eff0f1;
    cursor: pointer;
  }

  .stats-card {
    display: flex;
    gap: 12px;
    background: #14171a;
    border: 1px solid #23272b;
    border-radius: 4px;
    padding: 8px;
  }

  .stat-item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
  }

  .stat-num {
    font-size: 16px;
    font-weight: 700;
    color: #3daee9;
  }

  .stat-desc {
    font-size: 9.5px;
    color: #8c939d;
  }

  .no-selection {
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
    text-align: center;
    color: #6c737c;
  }
</style>
