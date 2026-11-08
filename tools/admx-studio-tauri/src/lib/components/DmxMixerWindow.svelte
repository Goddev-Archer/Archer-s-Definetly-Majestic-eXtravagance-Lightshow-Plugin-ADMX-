<script lang="ts">
  import { studio } from "../state.svelte";

  let txCounter = $state(0);

  function handleFaderChange(ch: number, val: number) {
    studio.sendDmxFader(ch, val);
    txCounter++;
  }

  function handleFlash(ch: number, pressed: boolean) {
    const val = pressed ? 255 : 0;
    studio.sendDmxFader(ch, val);
    txCounter++;
  }
</script>

<div class="mixer-root">
  <!-- Top bar: Universe, Target, Grand Master, DBO -->
  <div class="mixer-topbar">
    <div class="top-group">
      <span class="label">Universum:</span>
      <select class="select-uni" bind:value={studio.selectedUniverse}>
        {#each Array.from({ length: 16 }, (_, i) => i) as u}
          <option value={u}>Universe {u} (Art-Net SubUni {u})</option>
        {/each}
      </select>
    </div>

    <div class="top-group">
      <span class="label">Ziel IP:</span>
      <input
        type="text"
        class="input-ip"
        bind:value={studio.artnetTarget}
        placeholder="127.0.0.1"
      />
    </div>

    <div class="top-group master-group">
      <span class="label">Grand Master:</span>
      <input
        type="range"
        min="0"
        max="100"
        step="1"
        bind:value={studio.grandMaster}
      />
      <span class="master-val">{studio.grandMaster}%</span>
    </div>

    <button
      class="btn-dbo {studio.blackoutActive ? 'active' : ''}"
      onclick={() => studio.toggleBlackout()}
    >
      🚨 DBO (Blackout)
    </button>

    <div class="tx-badge">
      <span class="dot"></span>
      <span>TX Frames: {txCounter}</span>
    </div>
  </div>

  <!-- Fader Bank (16 Channels) -->
  <div class="fader-bank">
    {#each Array.from({ length: 16 }, (_, i) => i) as ch}
      <div class="fader-strip">
        <div class="ch-label">Ch {ch + 1}</div>
        <div class="val-display">{studio.dmxFaders[ch]}</div>

        <div class="slider-wrapper">
          <input
            type="range"
            min="0"
            max="255"
            step="1"
            class="v-slider"
            value={studio.dmxFaders[ch]}
            oninput={(e) =>
              handleFaderChange(ch, parseInt((e.target as HTMLInputElement).value))}
          />
        </div>

        <button
          class="flash-btn"
          onpointerdown={() => handleFlash(ch, true)}
          onpointerup={() => handleFlash(ch, false)}
          onpointerleave={() => handleFlash(ch, false)}
        >
          FL
        </button>
      </div>
    {/each}
  </div>
</div>

<style>
  .mixer-root {
    display: flex;
    flex-direction: column;
    height: 100%;
    background: #17191b;
    color: #eff0f1;
    font-family: 'Segoe UI', system-ui, sans-serif;
  }

  .mixer-topbar {
    display: flex;
    align-items: center;
    gap: 12px;
    background: #1e2124;
    border-bottom: 1px solid #282c30;
    padding: 8px 12px;
  }

  .top-group {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 11px;
  }

  .label {
    color: #8c939d;
  }

  .select-uni,
  .input-ip {
    background: #121416;
    border: 1px solid #31363b;
    border-radius: 4px;
    color: #eff0f1;
    font-size: 11px;
    padding: 3px 6px;
    outline: none;
  }

  .input-ip {
    width: 85px;
  }

  .master-group {
    margin-left: auto;
  }

  .master-val {
    font-family: 'Consolas', monospace;
    font-weight: 700;
    color: #3daee9;
    width: 36px;
  }

  .btn-dbo {
    background: #2b3137;
    border: 1px solid #3d444d;
    border-radius: 4px;
    color: #cbd3dc;
    font-size: 11px;
    font-weight: 700;
    padding: 4px 10px;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .btn-dbo.active {
    background: #e63946;
    border-color: #ff5252;
    color: #ffffff;
    box-shadow: 0 0 10px rgba(230, 57, 70, 0.6);
  }

  .tx-badge {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 10.5px;
    color: #8c939d;
    font-family: 'Consolas', monospace;
  }

  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #10b981;
    box-shadow: 0 0 6px #10b981;
  }

  .fader-bank {
    flex: 1;
    display: flex;
    gap: 6px;
    padding: 12px;
    overflow-x: auto;
    background: #121416;
  }

  .fader-strip {
    flex: 1;
    min-width: 38px;
    background: #1a1d20;
    border: 1px solid #292d32;
    border-radius: 4px;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 6px 2px;
    gap: 6px;
  }

  .ch-label {
    font-size: 10px;
    font-weight: 700;
    color: #8c939d;
  }

  .val-display {
    font-family: 'Consolas', monospace;
    font-size: 11px;
    color: #3daee9;
    font-weight: 600;
    background: #111315;
    padding: 2px 4px;
    border-radius: 3px;
    border: 1px solid #23272b;
  }

  .slider-wrapper {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 4px 0;
  }

  .v-slider {
    writing-mode: vertical-lr;
    direction: rtl;
    width: 6px;
    height: 100%;
    accent-color: #3daee9;
    cursor: pointer;
  }

  .flash-btn {
    width: 28px;
    height: 20px;
    border: 1px solid #363c43;
    border-radius: 3px;
    background: #252a2f;
    color: #cbd3dc;
    font-size: 9.5px;
    font-weight: 700;
    cursor: pointer;
    user-select: none;
  }

  .flash-btn:active {
    background: #3daee9;
    color: #ffffff;
  }
</style>
