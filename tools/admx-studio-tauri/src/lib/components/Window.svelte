<script lang="ts">
  import type { Snippet } from "svelte";
  import { studio } from "../state.svelte";
  import type { PlasmaWindowState } from "../types";

  let { win, children }: { win: PlasmaWindowState; children: Snippet } = $props();

  let isDragging = $state(false);
  let isResizing = $state(false);
  let dragStartX = 0;
  let dragStartY = 0;
  let initialX = 0;
  let initialY = 0;
  let initialW = 0;
  let initialH = 0;

  function handleHeaderPointerDown(e: PointerEvent) {
    if ((e.target as HTMLElement).closest(".win-btn")) return;
    studio.focusWindow(win.id);
    if (win.isMaximized) return;

    isDragging = true;
    dragStartX = e.clientX;
    dragStartY = e.clientY;
    initialX = win.x;
    initialY = win.y;

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", handlePointerUp);
  }

  function handleResizePointerDown(e: PointerEvent) {
    e.stopPropagation();
    studio.focusWindow(win.id);
    if (win.isMaximized) return;

    isResizing = true;
    dragStartX = e.clientX;
    dragStartY = e.clientY;
    initialW = win.w;
    initialH = win.h;

    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", handlePointerUp);
  }

  function handlePointerMove(e: PointerEvent) {
    if (isDragging) {
      const dx = e.clientX - dragStartX;
      const dy = e.clientY - dragStartY;
      win.x = Math.max(0, initialX + dx);
      win.y = Math.max(0, initialY + dy);
    } else if (isResizing) {
      const dw = e.clientX - dragStartX;
      const dh = e.clientY - dragStartY;
      win.w = Math.max(win.minW, initialW + dw);
      win.h = Math.max(win.minH, initialH + dh);
    }
  }

  function handlePointerUp() {
    isDragging = false;
    isResizing = false;
    window.removeEventListener("pointermove", handlePointerMove);
    window.removeEventListener("pointerup", handlePointerUp);
  }
</script>

{#if win.isOpen && !win.isMinimized}
  <div
    class="plasma-window {win.isMaximized ? 'maximized' : ''}"
    style="
      left: {win.x}px;
      top: {win.y}px;
      width: {win.w}px;
      height: {win.h}px;
      z-index: {win.zIndex};
    "
    onpointerdown={() => studio.focusWindow(win.id)}
    role="region"
    aria-label={win.title}
  >
    <!-- Window Titlebar -->
    <div
      class="win-titlebar"
      onpointerdown={handleHeaderPointerDown}
      role="banner"
    >
      <div class="win-title-left">
        <span class="win-icon">{win.icon}</span>
        <span class="win-title-text">{win.title}</span>
      </div>
      <div class="win-buttons">
        <button
          class="win-btn minimize"
          title="Minimieren"
          onclick={() => studio.toggleMinimize(win.id)}
          aria-label="Minimieren"
        >
          <span>—</span>
        </button>
        <button
          class="win-btn maximize"
          title={win.isMaximized ? 'Wiederherstellen' : 'Maximieren'}
          onclick={() => studio.toggleMaximize(win.id)}
          aria-label={win.isMaximized ? 'Wiederherstellen' : 'Maximieren'}
        >
          <span>{win.isMaximized ? '❐' : '◻'}</span>
        </button>
        <button
          class="win-btn close"
          title="Schließen"
          onclick={() => studio.closeWindow(win.id)}
          aria-label="Schließen"
        >
          <span>✕</span>
        </button>
      </div>
    </div>

    <!-- Window Body -->
    <div class="win-content">
      {@render children()}
    </div>

    <!-- Resize Handle -->
    {#if !win.isMaximized}
      <div
        class="resize-handle"
        onpointerdown={handleResizePointerDown}
        role="separator"
        aria-label="Größe anpassen"
      ></div>
    {/if}
  </div>
{/if}

<style>
  .plasma-window {
    position: absolute;
    background: #1b1e20;
    border: 1px solid #31363b;
    border-radius: 6px;
    box-shadow: 0 12px 36px rgba(0, 0, 0, 0.65), 0 0 0 1px rgba(255, 255, 255, 0.04);
    display: flex;
    flex-direction: column;
    overflow: hidden;
    color: #eff0f1;
    user-select: none;
  }

  .plasma-window.maximized {
    border-radius: 0;
    border: none;
  }

  .win-titlebar {
    height: 32px;
    background: linear-gradient(180deg, #2a2e32 0%, #232629 100%);
    border-bottom: 1px solid #16181a;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 8px;
    cursor: move;
  }

  .win-title-left {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.3px;
    color: #fcfcfc;
  }

  .win-icon {
    font-size: 14px;
  }

  .win-buttons {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .win-btn {
    width: 22px;
    height: 22px;
    border-radius: 4px;
    border: none;
    background: transparent;
    color: #abb0b6;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 11px;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .win-btn:hover {
    background: #31363b;
    color: #ffffff;
  }

  .win-btn.close:hover {
    background: #da4453;
    color: #ffffff;
  }

  .win-content {
    flex: 1;
    overflow: auto;
    position: relative;
    background: #17191b;
  }

  .resize-handle {
    position: absolute;
    right: 0;
    bottom: 0;
    width: 14px;
    height: 14px;
    cursor: nwse-resize;
    background: linear-gradient(135deg, transparent 50%, #3daee9 50%);
    opacity: 0.5;
  }

  .resize-handle:hover {
    opacity: 1;
  }
</style>
