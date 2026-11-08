<script lang="ts">
  import { studio } from "../state.svelte";

  let startMenuOpen = $state(false);
  let currentTime = $state("");

  // Update clock every second
  $effect(() => {
    const update = () => {
      const now = new Date();
      currentTime = now.toLocaleTimeString("de-DE", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
      });
    };
    update();
    const timer = setInterval(update, 1000);
    return () => clearInterval(timer);
  });

  // BPM pulse calculation
  let beatPulsing = $derived(
    studio.playback.is_playing && studio.playback.beat_in_bar === 1
  );
</script>

<!-- Start Menu Dropup -->
{#if startMenuOpen}
  <!-- svelte-ignore a11y_click_events_have_key_events -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="start-menu-backdrop" onclick={() => (startMenuOpen = false)}>
    <div class="start-menu" onclick={(e) => e.stopPropagation()}>
      <div class="menu-header">
        <span class="menu-logo">✦ ADMX Lightshow Studio</span>
        <span class="menu-sub">Tauri 2.0 + Svelte 5 • Rust Core</span>
      </div>

      <div class="menu-items">
        {#each studio.windows as win}
          <button
            class="menu-item"
            onclick={() => {
              studio.openWindow(win.id);
              startMenuOpen = false;
            }}
          >
            <span class="m-icon">{win.icon}</span>
            <div class="m-info">
              <span class="m-title">{win.title}</span>
              <span class="m-state">{win.isOpen ? "Geöffnet" : "Geschlossen"}</span>
            </div>
          </button>
        {/each}
      </div>
    </div>
  </div>
{/if}

<!-- Bottom Taskbar -->
<footer class="plasma-taskbar" role="navigation" aria-label="Plasma Taskleiste">
  <!-- Start Button -->
  <button
    class="start-btn {startMenuOpen ? 'active' : ''}"
    onclick={() => (startMenuOpen = !startMenuOpen)}
    title="ADMX Anwendungsmenü"
  >
    <span class="star-icon">✦</span>
    <span class="start-label">ADMX</span>
  </button>

  <div class="taskbar-divider"></div>

  <!-- Open Windows Task List -->
  <div class="task-list">
    {#each studio.windows as win}
      {#if win.isOpen}
        {@const isTop = studio.topZIndex === win.zIndex && !win.isMinimized}
        <button
          class="task-pill {isTop ? 'active-pill' : ''} {win.isMinimized ? 'minimized' : ''}"
          onclick={() => studio.toggleMinimize(win.id)}
          title={win.title}
        >
          <span class="pill-icon">{win.icon}</span>
          <span class="pill-title">{win.title}</span>
        </button>
      {/if}
    {/each}
  </div>

  <!-- System Tray (Right) -->
  <div class="system-tray">
    <!-- Pulsing BPM Badge -->
    <div class="tray-item bpm-badge {beatPulsing ? 'pulse' : ''}" title="Audio BPM Taktung">
      <span class="bpm-dot"></span>
      <span>{studio.bpm.toFixed(1)} BPM</span>
    </div>

    <!-- Universe & Merge Mode -->
    <div class="tray-item uni-badge" title="Aktives DMX512 / Art-Net Universum">
      <span>U{studio.selectedUniverse} [HTP]</span>
    </div>

    <!-- Art-Net TX status -->
    <div class="tray-item tx-badge" title="Art-Net 4 UDP Node">
      <span class="artnet-led"></span>
      <span>Art-Net 4</span>
    </div>

    <!-- Grand Master & DBO -->
    <button
      class="tray-dbo {studio.blackoutActive ? 'active' : ''}"
      onclick={() => studio.toggleBlackout()}
      title="Master Blackout Toggle"
    >
      🚨 DBO
    </button>

    <!-- Digital Clock -->
    <div class="tray-clock">
      {currentTime}
    </div>
  </div>
</footer>

<style>
  .plasma-taskbar {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 38px;
    background: #1e2124;
    border-top: 1px solid #31363b;
    display: flex;
    align-items: center;
    padding: 0 8px;
    gap: 8px;
    z-index: 1000;
    user-select: none;
    font-family: 'Segoe UI', system-ui, sans-serif;
  }

  .start-btn {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 28px;
    padding: 0 10px;
    border-radius: 4px;
    border: 1px solid transparent;
    background: #252a2f;
    color: #eff0f1;
    font-size: 12px;
    font-weight: 700;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .start-btn:hover,
  .start-btn.active {
    background: #3daee9;
    color: #ffffff;
    box-shadow: 0 0 10px rgba(61, 174, 233, 0.4);
  }

  .star-icon {
    font-size: 14px;
    color: #3daee9;
  }
  .start-btn:hover .star-icon,
  .start-btn.active .star-icon {
    color: #ffffff;
  }

  .taskbar-divider {
    width: 1px;
    height: 20px;
    background: #31363b;
  }

  .task-list {
    flex: 1;
    display: flex;
    align-items: center;
    gap: 4px;
    overflow-x: auto;
  }

  .task-pill {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 28px;
    padding: 0 10px;
    max-width: 200px;
    border-radius: 4px;
    border: 1px solid #282c30;
    background: #181b1d;
    color: #abb0b6;
    font-size: 11px;
    cursor: pointer;
    transition: all 0.15s ease;
    white-space: nowrap;
    overflow: hidden;
  }

  .task-pill:hover {
    background: #23272b;
    color: #eff0f1;
  }

  .task-pill.active-pill {
    background: #252e35;
    border-color: #3daee9;
    border-bottom: 2px solid #3daee9;
    color: #ffffff;
  }

  .task-pill.minimized {
    opacity: 0.6;
    background: #151719;
  }

  .pill-title {
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .system-tray {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-left: auto;
  }

  .tray-item {
    display: flex;
    align-items: center;
    gap: 5px;
    font-size: 11px;
    background: #181b1d;
    border: 1px solid #282c30;
    padding: 4px 8px;
    border-radius: 4px;
    color: #eff0f1;
    font-family: 'Consolas', monospace;
  }

  .bpm-badge.pulse {
    background: #003e66;
    border-color: #3daee9;
  }

  .bpm-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #3daee9;
  }

  .artnet-led {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #10b981;
    box-shadow: 0 0 6px #10b981;
  }

  .tray-dbo {
    background: #2b3137;
    border: 1px solid #3d444d;
    border-radius: 4px;
    color: #cbd3dc;
    font-size: 10.5px;
    font-weight: 700;
    padding: 4px 8px;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .tray-dbo.active {
    background: #e63946;
    border-color: #ff5252;
    color: #ffffff;
    box-shadow: 0 0 8px rgba(230, 57, 70, 0.6);
  }

  .tray-clock {
    font-family: 'Consolas', monospace;
    font-size: 12px;
    color: #eff0f1;
    padding: 0 6px;
  }

  /* Start Menu Dropup */
  .start-menu-backdrop {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 38px;
    z-index: 999;
  }

  .start-menu {
    position: absolute;
    bottom: 4px;
    left: 8px;
    width: 280px;
    background: #1b1e20;
    border: 1px solid #31363b;
    border-radius: 6px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.7);
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }

  .menu-header {
    background: linear-gradient(180deg, #2a2e32 0%, #232629 100%);
    border-bottom: 1px solid #16181a;
    padding: 10px 12px;
    display: flex;
    flex-direction: column;
  }

  .menu-logo {
    font-size: 13px;
    font-weight: 700;
    color: #ffffff;
  }

  .menu-sub {
    font-size: 10px;
    color: #8c939d;
  }

  .menu-items {
    padding: 6px;
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .menu-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 10px;
    border-radius: 4px;
    border: none;
    background: transparent;
    color: #eff0f1;
    cursor: pointer;
    text-align: left;
    transition: background 0.15s ease;
  }

  .menu-item:hover {
    background: #252a2f;
  }

  .m-icon {
    font-size: 18px;
  }

  .m-info {
    display: flex;
    flex-direction: column;
  }

  .m-title {
    font-size: 11.5px;
    font-weight: 600;
  }

  .m-state {
    font-size: 9.5px;
    color: #8c939d;
  }
</style>
