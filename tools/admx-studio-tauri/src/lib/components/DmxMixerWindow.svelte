<script lang="ts">
  import { studio } from "../state.svelte";

  let txCounter = $state(0);
  let activeBank = $state(0); // 0 = Ch 1-16, 1 = Ch 17-32, etc.
  const CHANNELS_PER_BANK = 16;
  const TOTAL_BANKS = 4; // covers channels 1 to 64 directly

  let preFlashValues: Record<number, number> = {};

  function handleFaderChange(chIdx: number, val: number) {
    const clamped = Math.max(0, Math.min(255, Math.round(val)));
    studio.sendDmxFader(chIdx, clamped);
    txCounter++;
  }

  function handleFlash(chIdx: number, pressed: boolean) {
    if (pressed) {
      preFlashValues[chIdx] = studio.dmxFaders[chIdx] || 0;
      handleFaderChange(chIdx, 255);
    } else {
      const restoreVal = preFlashValues[chIdx] ?? 0;
      handleFaderChange(chIdx, restoreVal);
    }
  }

  function handleFaderTrackPointerDown(e: PointerEvent, chIdx: number, trackEl: HTMLElement) {
    e.preventDefault();
    trackEl.setPointerCapture(e.pointerId);

    const updateFromY = (clientY: number) => {
      const rect = trackEl.getBoundingClientRect();
      const ratio = Math.max(0, Math.min(1, (rect.bottom - clientY) / rect.height));
      handleFaderChange(chIdx, ratio * 255);
    };

    updateFromY(e.clientY);

    const onMove = (ev: PointerEvent) => {
      updateFromY(ev.clientY);
    };

    const onUp = (ev: PointerEvent) => {
      try {
        trackEl.releasePointerCapture(ev.pointerId);
      } catch (_) {}
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };

    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  }

  function handleWheel(e: WheelEvent, chIdx: number) {
    e.preventDefault();
    const cur = studio.dmxFaders[chIdx] || 0;
    const delta = e.deltaY < 0 ? 5 : -5;
    handleFaderChange(chIdx, cur + delta);
  }

  let bankStartCh = $derived(activeBank * CHANNELS_PER_BANK);
  let visibleChannels = $derived(
    Array.from({ length: CHANNELS_PER_BANK }, (_, i) => bankStartCh + i)
  );
</script>

<div class="mixer-root">
  <!-- Top Bar: Universe, Target IP, Grand Master & Blackout -->
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

    <!-- Bank Switcher Tabs -->
    <div class="bank-tabs">
      {#each Array.from({ length: TOTAL_BANKS }, (_, b) => b) as b}
        {@const start = b * CHANNELS_PER_BANK + 1}
        {@const end = start + CHANNELS_PER_BANK - 1}
        <button
          class="bank-tab-btn {activeBank === b ? 'active' : ''}"
          onclick={() => (activeBank = b)}
        >
          Ch {start}–{end}
        </button>
      {/each}
    </div>

    <div class="top-group master-group">
      <span class="label">Grand Master:</span>
      <input
        type="range"
        min="0"
        max="100"
        step="1"
        class="master-slider"
        bind:value={studio.grandMaster}
      />
      <span class="master-val">{studio.grandMaster}%</span>
    </div>

    <button
      class="btn-dbo {studio.blackoutActive ? 'active' : ''}"
      onclick={() => studio.toggleBlackout()}
      title="Dead Black Out (Alle Kanäle sofort auf 0)"
    >
      🚨 DBO (Blackout)
    </button>

    <div class="tx-badge" title="Art-Net 4 DMX Frames gesendet">
      <span class="dot {txCounter > 0 ? 'pulse' : ''}"></span>
      <span>TX: {txCounter}</span>
    </div>
  </div>

  <!-- Hardware Console Fader Bank -->
  <div class="fader-bank">
    {#each visibleChannels as ch (ch)}
      {@const val = studio.dmxFaders[ch] || 0}
      {@const percent = Math.round((val / 255) * 100)}
      {@const isOutputting = val > 0 && !studio.blackoutActive && studio.grandMaster > 0}

      <div class="fader-strip {isOutputting ? 'active-output' : ''}">
        <!-- Channel Header -->
        <div class="strip-header">
          <div class="ch-label">CH {ch + 1}</div>
          <div class="val-display" title="DMX Wert: 0..255 ({percent}%)">
            {val}
          </div>
        </div>

        <!-- Scale Ticks (Left side of track) -->
        <div class="track-area">
          <div class="scale-ticks">
            <span class="tick-label">FL</span>
            <span class="tick-label">75</span>
            <span class="tick-label">50</span>
            <span class="tick-label">25</span>
            <span class="tick-label">0</span>
          </div>

          <!-- Interactive Fader Slot -->
          <!-- svelte-ignore a11y_no_static_element_interactions -->
          <div
            class="fader-track"
            onpointerdown={(e) => {
              const el = e.currentTarget as HTMLElement;
              handleFaderTrackPointerDown(e, ch, el);
            }}
            onwheel={(e) => handleWheel(e, ch)}
            ondblclick={() => handleFaderChange(ch, val > 0 ? 0 : 255)}
            title="Ziehen, Klicken oder Mausrad zum Einstellen (Doppelklick = 0/255)"
          >
            <!-- LED Fill Bar -->
            <div
              class="led-fill-bar"
              style="height: {percent}%;"
            ></div>

            <!-- Precision Hardware Aluminum Knob -->
            <div
              class="fader-cap"
              style="bottom: calc({percent}% - 11px);"
            >
              <div class="cap-ridge top"></div>
              <div class="cap-indicator"></div>
              <div class="cap-ridge bottom"></div>
            </div>
          </div>
        </div>

        <!-- Footer: Flash Button -->
        <div class="strip-footer">
          <button
            class="flash-btn"
            onpointerdown={(e) => {
              e.preventDefault();
              handleFlash(ch, true);
            }}
            onpointerup={() => handleFlash(ch, false)}
            onpointerleave={() => handleFlash(ch, false)}
            title="Flash (Hält 255 solange gedrückt)"
          >
            FLASH
          </button>
        </div>
      </div>
    {/each}
  </div>
</div>

<style>
  .mixer-root {
    display: flex;
    flex-direction: column;
    height: 100%;
    background: #111315;
    color: #eff0f1;
    font-family: 'Segoe UI', system-ui, sans-serif;
    overflow: hidden;
    user-select: none;
  }

  .mixer-topbar {
    display: flex;
    align-items: center;
    gap: 10px;
    background: #1a1d21;
    border-bottom: 1px solid #282d33;
    padding: 6px 12px;
    flex-wrap: wrap;
    z-index: 10;
  }

  .top-group {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 11px;
  }

  .label {
    color: #8c939d;
    font-weight: 500;
  }

  .select-uni,
  .input-ip {
    background: #111315;
    border: 1px solid #31363b;
    border-radius: 4px;
    color: #eff0f1;
    font-size: 11px;
    padding: 3px 6px;
    outline: none;
  }

  .select-uni:focus,
  .input-ip:focus {
    border-color: #3daee9;
  }

  .input-ip {
    width: 90px;
  }

  .bank-tabs {
    display: flex;
    gap: 3px;
    background: #121416;
    padding: 2px;
    border-radius: 4px;
    border: 1px solid #282c30;
  }

  .bank-tab-btn {
    background: transparent;
    border: none;
    border-radius: 3px;
    color: #8c939d;
    font-size: 10px;
    font-weight: 600;
    padding: 3px 8px;
    cursor: pointer;
    transition: all 0.12s ease;
  }

  .bank-tab-btn:hover {
    color: #eff0f1;
    background: #25292e;
  }

  .bank-tab-btn.active {
    background: #2b7bb9;
    color: #ffffff;
  }

  .master-group {
    margin-left: auto;
  }

  .master-slider {
    width: 90px;
    height: 6px;
    accent-color: #3daee9;
    cursor: pointer;
  }

  .master-val {
    font-family: 'Consolas', monospace;
    font-weight: 700;
    color: #3daee9;
    width: 36px;
    font-size: 11px;
  }

  .btn-dbo {
    background: #23272b;
    border: 1px solid #3d444d;
    border-radius: 4px;
    color: #cbd3dc;
    font-size: 10.5px;
    font-weight: 700;
    padding: 4px 10px;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .btn-dbo:hover {
    background: #2f353b;
  }

  .btn-dbo.active {
    background: #e63946;
    border-color: #ff5252;
    color: #ffffff;
    box-shadow: 0 0 12px rgba(230, 57, 70, 0.7);
  }

  .tx-badge {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 10px;
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

  .dot.pulse {
    animation: txPulse 0.4s ease-out;
  }

  @keyframes txPulse {
    0% { transform: scale(1.6); opacity: 1; }
    100% { transform: scale(1); opacity: 0.8; }
  }

  /* Hardware Console Fader Bank */
  .fader-bank {
    flex: 1;
    display: flex;
    gap: 6px;
    padding: 8px 10px;
    overflow-x: auto;
    overflow-y: hidden;
    background: #121416;
    scrollbar-width: thin;
    scrollbar-color: #3daee9 #16181b;
  }

  .fader-strip {
    flex: 1;
    min-width: 44px;
    max-width: 60px;
    height: 100%;
    background: linear-gradient(180deg, #1e2226 0%, #17191c 100%);
    border: 1px solid #2a2e33;
    border-radius: 5px;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05), 0 4px 12px rgba(0, 0, 0, 0.4);
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 6px 4px;
    box-sizing: border-box;
    gap: 6px;
    transition: border-color 0.15s ease;
  }

  .fader-strip.active-output {
    border-color: #205c87;
  }

  .strip-header {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 3px;
    width: 100%;
  }

  .ch-label {
    font-size: 9.5px;
    font-weight: 700;
    color: #8c939d;
    letter-spacing: 0.5px;
  }

  .val-display {
    font-family: 'Consolas', monospace;
    font-size: 11px;
    color: #38bdf8;
    font-weight: 700;
    background: #0d0f11;
    padding: 1px 4px;
    border-radius: 3px;
    border: 1px solid #1f2429;
    width: 32px;
    text-align: center;
  }

  .track-area {
    flex: 1;
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
    padding: 4px 0;
  }

  .scale-ticks {
    position: absolute;
    left: 2px;
    top: 4px;
    bottom: 4px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    pointer-events: none;
  }

  .tick-label {
    font-size: 7.5px;
    font-family: 'Consolas', monospace;
    color: #555e69;
    line-height: 1;
  }

  /* Vertical Fader Track Slot */
  .fader-track {
    width: 16px;
    height: 100%;
    background: #0b0c0e;
    border: 1px solid #23272c;
    border-radius: 8px;
    box-shadow: inset 0 2px 6px rgba(0, 0, 0, 0.85);
    position: relative;
    cursor: ns-resize;
    overflow: visible;
  }

  /* LED Fill Bar inside Slot */
  .led-fill-bar {
    position: absolute;
    left: 3px;
    right: 3px;
    bottom: 3px;
    background: linear-gradient(180deg, #38bdf8 0%, #0284c7 60%, #0369a1 100%);
    border-radius: 5px;
    box-shadow: 0 0 8px rgba(56, 189, 248, 0.5);
    pointer-events: none;
    transition: height 0.05s linear;
  }

  /* Hardware Aluminum Fader Knob */
  .fader-cap {
    position: absolute;
    left: -10px;
    width: 36px;
    height: 22px;
    background: linear-gradient(180deg, #444b52 0%, #2b3035 45%, #1e2226 55%, #353b42 100%);
    border: 1px solid #555e66;
    border-radius: 3px;
    box-shadow: 0 4px 8px rgba(0, 0, 0, 0.75), inset 0 1px 0 rgba(255, 255, 255, 0.2);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    pointer-events: none;
    z-index: 5;
  }

  .cap-ridge {
    width: 18px;
    height: 1px;
    background: rgba(0, 0, 0, 0.6);
    box-shadow: 0 1px 0 rgba(255, 255, 255, 0.1);
  }

  .cap-indicator {
    width: 22px;
    height: 2px;
    background: #00ffff;
    box-shadow: 0 0 6px #00ffff;
    border-radius: 1px;
  }

  .strip-footer {
    width: 100%;
    display: flex;
    justify-content: center;
    padding-top: 2px;
  }

  .flash-btn {
    width: 100%;
    height: 22px;
    border: 1px solid #363c43;
    border-radius: 3px;
    background: linear-gradient(180deg, #272c32 0%, #1d2125 100%);
    color: #abb0b6;
    font-size: 9px;
    font-weight: 800;
    letter-spacing: 0.5px;
    cursor: pointer;
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.4);
    transition: all 0.1s ease;
  }

  .flash-btn:hover {
    background: #31363d;
    color: #ffffff;
    border-color: #3daee9;
  }

  .flash-btn:active {
    background: #3daee9;
    border-color: #00ffff;
    color: #000000;
    box-shadow: 0 0 12px #3daee9;
  }
</style>