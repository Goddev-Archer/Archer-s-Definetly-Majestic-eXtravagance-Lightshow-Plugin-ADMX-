<script lang="ts">
  import { studio } from "../state.svelte";
  import type { FlowConnection, SongSection } from "../types";

  let canvasContainer: HTMLDivElement | null = $state(null);

  // Pan & Zoom State (Infinite Node Canvas)
  let panX = $state(0);
  let panY = $state(0);
  let zoom = $state(1.0);
  let isPanning = $state(false);
  let panStart = { x: 0, y: 0 };

  // Dragging Node state
  let isDraggingNode = $state(false);
  let draggedNodeId = $state<string | null>(null);
  let dragOffset = { x: 0, y: 0 };

  // Wiring (Cable drawing) state
  let isWiring = $state(false);
  let wiringFrom = $state<{ nodeId: string; pin: number; x: number; y: number } | null>(null);
  let wireCursor = $state<{ x: number; y: number }>({ x: 0, y: 0 });
  let hoveredInputPinNodeId = $state<string | null>(null);
  let selectedConnectionId = $state<string | null>(null);

  // Add Brick Catalog state
  let showAddMenu = $state(false);

  // Compute smart bezier curve between pins (handles forward, downward and loop-back routing)
  function computeCablePath(x1: number, y1: number, x2: number, y2: number): string {
    if (x2 >= x1 + 15) {
      // Forward Left-to-Right: elegant cubic bezier S-curve
      const dx = Math.max(40, (x2 - x1) * 0.5);
      return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`;
    } else {
      // Backward or vertical drop: route gracefully with a slack loop beneath the nodes
      const loopOffset = Math.max(50, Math.min(140, Math.abs(x1 - x2) * 0.18));
      const midY = (y1 + y2) * 0.5 + (y2 >= y1 ? 65 : -65);
      return `M ${x1} ${y1} C ${x1 + loopOffset} ${y1}, ${x1 + loopOffset} ${midY}, ${(x1 + x2) * 0.5} ${midY} C ${x2 - loopOffset} ${midY}, ${x2 - loopOffset} ${y2}, ${x2} ${y2}`;
    }
  }

  function getPinCoords(node: SongSection, pinType: "in" | "out", pinIndex: number = 0) {
    if (pinType === "in") {
      return { x: node.x, y: node.y + node.h * 0.5 };
    } else {
      const yOffset = pinIndex === 0 ? node.h * 0.35 : node.h * 0.70;
      return { x: node.x + node.w, y: node.y + yOffset };
    }
  }

  // Canvas Pan & Zoom Handlers
  function handleCanvasWheel(e: WheelEvent) {
    e.preventDefault();
    if (!canvasContainer) return;
    const rect = canvasContainer.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    const zoomFactor = e.deltaY < 0 ? 1.1 : 0.909;
    const newZoom = Math.max(0.4, Math.min(2.2, zoom * zoomFactor));

    // Zoom centered at cursor position
    panX = mouseX - (mouseX - panX) * (newZoom / zoom);
    panY = mouseY - (mouseY - panY) * (newZoom / zoom);
    zoom = newZoom;
  }

  function handleCanvasPointerDown(e: PointerEvent) {
    // If clicking on background or middle/right click, start panning
    if (
      e.button === 1 ||
      e.button === 2 ||
      (e.target as HTMLElement).classList.contains("canvas-viewport") ||
      (e.target as HTMLElement).classList.contains("cables-svg")
    ) {
      if (e.button === 2) {
        e.preventDefault();
      }
      isPanning = true;
      panStart = { x: e.clientX - panX, y: e.clientY - panY };
      window.addEventListener("pointermove", handleGlobalPointerMove);
      window.addEventListener("pointerup", handleGlobalPointerUp);
    }
  }

  // Node Dragging
  function handleNodePointerDown(e: PointerEvent, node: SongSection) {
    if (
      (e.target as HTMLElement).closest(".pin") ||
      (e.target as HTMLElement).closest(".btn-delete-node")
    ) {
      return;
    }
    studio.selectedNodeId = node.id;
    isDraggingNode = true;
    draggedNodeId = node.id;
    dragOffset = {
      x: (e.clientX - panX) / zoom - node.x,
      y: (e.clientY - panY) / zoom - node.y,
    };

    window.addEventListener("pointermove", handleGlobalPointerMove);
    window.addEventListener("pointerup", handleGlobalPointerUp);
  }

  function handleGlobalPointerMove(e: PointerEvent) {
    if (isPanning) {
      panX = e.clientX - panStart.x;
      panY = e.clientY - panStart.y;
    } else if (isDraggingNode && draggedNodeId) {
      const node = studio.nodes.find((n) => n.id === draggedNodeId);
      if (node) {
        node.x = Math.round((e.clientX - panX) / zoom - dragOffset.x);
        node.y = Math.round((e.clientY - panY) / zoom - dragOffset.y);
      }
    } else if (isWiring) {
      const curX = (e.clientX - panX) / zoom;
      const curY = (e.clientY - panY) / zoom;

      // Magnetic Snapping to closest valid Input Pin
      let snapped = false;
      hoveredInputPinNodeId = null;
      for (const n of studio.nodes) {
        if (wiringFrom && n.id !== wiringFrom.nodeId) {
          const pin = getPinCoords(n, "in");
          const dist = Math.hypot(curX - pin.x, curY - pin.y);
          if (dist < 32) {
            wireCursor = { x: pin.x, y: pin.y };
            hoveredInputPinNodeId = n.id;
            snapped = true;
            break;
          }
        }
      }
      if (!snapped) {
        wireCursor = { x: curX, y: curY };
      }
    }
  }

  function handleGlobalPointerUp() {
    isPanning = false;
    isDraggingNode = false;
    draggedNodeId = null;
    if (isWiring) {
      isWiring = false;
      wiringFrom = null;
      hoveredInputPinNodeId = null;
    }
    window.removeEventListener("pointermove", handleGlobalPointerMove);
    window.removeEventListener("pointerup", handleGlobalPointerUp);
  }

  // Wiring Handlers
  function startWiring(e: PointerEvent, node: SongSection, pinIndex: number) {
    e.stopPropagation();
    const pin = getPinCoords(node, "out", pinIndex);
    isWiring = true;
    wiringFrom = {
      nodeId: node.id,
      pin: pinIndex,
      x: pin.x,
      y: pin.y,
    };
    wireCursor = { x: pin.x, y: pin.y };

    window.addEventListener("pointermove", handleGlobalPointerMove);
    window.addEventListener("pointerup", handleGlobalPointerUp);
  }

  function completeWiring(e: PointerEvent, targetNode: SongSection) {
    e.stopPropagation();
    if (!isWiring || !wiringFrom) return;
    if (wiringFrom.nodeId !== targetNode.id) {
      studio.addConnection(wiringFrom.nodeId, wiringFrom.pin, targetNode.id, 0);
    }
    isWiring = false;
    wiringFrom = null;
    hoveredInputPinNodeId = null;
  }

  function deleteConnection(connId: string) {
    studio.removeConnection(connId);
    if (selectedConnectionId === connId) {
      selectedConnectionId = null;
    }
  }

  // 1. Horizontal Timeline Layout (Single Stream: 0 Crossings!)
  function layoutTimeline() {
    const startX = 60;
    const startY = 120;
    const stepX = 260;

    for (let i = 0; i < studio.nodes.length; i++) {
      const node = studio.nodes[i];
      node.x = startX + i * stepX;
      node.y = startY;
      node.w = 190;
      node.h = 120;
    }
    fitToView();
  }

  // 2. Snake Flow (Boustrophedon Zick-Zack: 0 Crossings!)
  function layoutSnake() {
    const perRow = 4;
    const startX = 60;
    const startY = 80;
    const stepX = 250;
    const stepY = 180;

    for (let i = 0; i < studio.nodes.length; i++) {
      const row = Math.floor(i / perRow);
      const colInRow = i % perRow;
      // Even rows go L -> R, odd rows go R -> L
      const col = row % 2 === 0 ? colInRow : perRow - 1 - colInRow;
      const node = studio.nodes[i];
      node.x = startX + col * stepX;
      node.y = startY + row * stepY;
      node.w = 190;
      node.h = 120;
    }
    fitToView();
  }

  // 3. Stem Lanes Layout (Choreography, Rhythm, FX/Cryo Tracks)
  function layoutStemLanes() {
    const totalBars = studio.analysis?.total_bars || 64;
    const canvasWidth = Math.max(1600, totalBars * 32);

    for (const node of studio.nodes) {
      const t = Math.max(0, Math.min(1, node.start_bar / totalBars));
      node.x = Math.round(60 + t * (canvasWidth - 320));
      if (node.flame || node.section_type.includes("CRYO") || node.section_type.includes("LOFI")) {
        node.y = 380; // FX & Special Effects Lane
      } else if (
        node.section_type.includes("DROP") ||
        node.section_type.includes("BUILDUP") ||
        node.section_type.includes("TRAP")
      ) {
        node.y = 230; // Rhythm & Bass Lane
      } else {
        node.y = 80; // Melodic & Moving Heads Lane
      }
      node.w = 190;
      node.h = 120;
    }
    fitToView();
  }

  // Fit all nodes into screen view
  function fitToView() {
    if (studio.nodes.length === 0 || !canvasContainer) {
      panX = 0;
      panY = 0;
      zoom = 1.0;
      return;
    }
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of studio.nodes) {
      minX = Math.min(minX, n.x);
      minY = Math.min(minY, n.y);
      maxX = Math.max(maxX, n.x + n.w);
      maxY = Math.max(maxY, n.y + n.h);
    }
    const rect = canvasContainer.getBoundingClientRect();
    const contentW = maxX - minX + 140;
    const contentH = maxY - minY + 140;
    const scaleX = rect.width / contentW;
    const scaleY = rect.height / contentH;
    const targetZoom = Math.max(0.45, Math.min(1.0, Math.min(scaleX, scaleY)));
    zoom = targetZoom;
    panX = (rect.width - contentW * zoom) / 2 - minX * zoom + 70 * zoom;
    panY = (rect.height - contentH * zoom) / 2 - minY * zoom + 70 * zoom;
  }

  function zoomIn() {
    zoom = Math.min(2.2, zoom * 1.2);
  }

  function zoomOut() {
    zoom = Math.max(0.4, zoom * 0.833);
  }

  function resetZoom() {
    zoom = 1.0;
    panX = 0;
    panY = 0;
  }

  // Expanded 20+ Brick Catalog covering all genres + Handheld Cryo Bazooka!
  const brickCatalog = [
    {
      category: "🎸 Akustik, Melodic & Pop",
      bricks: [
        {
          name: "Acoustic Guitar Wave",
          pattern: "GENTLE_FAN_SWAY",
          color: "#f59e0b",
          tau: 380,
          speed: 0.75,
          amp: 0.65,
          strobe: 0.0,
          flame: false,
          dim: 80,
          bars: 4,
          desc: "Weiches Fächerwiegen im Takt der Akustikgitarre (Warm Amber)",
        },
        {
          name: "Vocal Halo Spotlight",
          pattern: "BREATHING_CENTER_GLOW",
          color: "#fff3e0",
          tau: 520,
          speed: 0.60,
          amp: 0.50,
          strobe: 0.0,
          flame: false,
          dim: 75,
          bars: 8,
          desc: "Atmender Warmweiß-Spot auf die Bühnenmitte für Gesangspassagen",
        },
        {
          name: "Piano Arpeggio Cascade",
          pattern: "WIDE_ORBITAL_SUNSET",
          color: "#38bdf8",
          tau: 300,
          speed: 0.85,
          amp: 0.80,
          strobe: 0.0,
          flame: false,
          dim: 85,
          bars: 4,
          desc: "Fließender Farbmorph synchron zu Klavierläufen",
        },
        {
          name: "Lo-Fi Vinyl Crackle Fade",
          pattern: "CANDLE_FLICKER_STATIC",
          color: "#eab308",
          tau: 600,
          speed: 0.40,
          amp: 0.40,
          strobe: 0.0,
          flame: false,
          dim: 40,
          bars: 4,
          desc: "Vintage Kerzenschein-Ausglimmen mit sehr langem Tau",
        },
        {
          name: "String Ensemble Swell",
          pattern: "GENTLE_FAN_SWAY",
          color: "#c084fc",
          tau: 450,
          speed: 0.50,
          amp: 0.90,
          strobe: 0.0,
          flame: false,
          dim: 90,
          bars: 8,
          desc: "Breiter violetter Horizont für emotionale Streicher",
        },
      ],
    },
    {
      category: "💥 Trap, 808 & Hip-Hop",
      bricks: [
        {
          name: "808 Sub-Bass Pulse",
          pattern: "FLOOR_PUNCH_BREATHE",
          color: "#8b5cf6",
          tau: 450,
          speed: 1.10,
          amp: 1.00,
          strobe: 0.0,
          flame: true,
          dim: 100,
          bars: 8,
          desc: "Tiefviolettes Atmen auf 808-Gleitbässe mit Cryo/Flame-Akzent",
        },
        {
          name: "Hi-Hat Triplet Rolls (1/32)",
          pattern: "NEEDLE_RACE_STACCATO",
          color: "#00e5ff",
          tau: 180,
          speed: 1.40,
          amp: 0.75,
          strobe: 4.0,
          flame: false,
          dim: 90,
          bars: 4,
          desc: "Perlenartige Verfolger auf rollenden Hi-Hat Snaps",
        },
        {
          name: "Melodic Chorus Bloom",
          pattern: "WIDE_ORBITAL_SUNSET",
          color: "#ec4899",
          tau: 340,
          speed: 0.80,
          amp: 1.20,
          strobe: 0.0,
          flame: false,
          dim: 95,
          bars: 8,
          desc: "Majestätischer Farbmorph über den gesamten Saal",
        },
        {
          name: "808 Slide Pitch Glide",
          pattern: "PAN_SWEEP",
          color: "#a855f7",
          tau: 260,
          speed: 1.25,
          amp: 0.90,
          strobe: 0.0,
          flame: false,
          dim: 85,
          bars: 4,
          desc: "Pan-Schwenk synchron zur Tonhöhen-Kurve des 808-Glides",
        },
        {
          name: "Snare Clap Staccato Snap",
          pattern: "HARD_SNAP_STACCATO",
          color: "#f43f5e",
          tau: 140,
          speed: 1.60,
          amp: 1.00,
          strobe: 8.0,
          flame: false,
          dim: 100,
          bars: 2,
          desc: "Knackiger Blinder-Blitz auf Akzent-Snares und Claps",
        },
      ],
    },
    {
      category: "❄️ Cryo-Kanone & Pyrotechnik (Special FX)",
      bricks: [
        {
          name: "Handheld Cryo Bazooka Blast",
          pattern: "CLIFFHANGER_FREEZE",
          color: "#00ffff",
          tau: 120,
          speed: 0.0,
          amp: 1.5,
          strobe: 10.0,
          flame: true,
          dim: 100,
          bars: 2,
          desc: "Handheld-CO2-Kanone: 3s dichter Eiskegel + greller Weißblitz",
        },
        {
          name: "Stadium Flame Mortar Jet",
          pattern: "CINEMATIC_STOMP_SNAP",
          color: "#ff4500",
          tau: 220,
          speed: 1.30,
          amp: 1.20,
          strobe: 0.0,
          flame: true,
          dim: 100,
          bars: 4,
          desc: "Wuchtige synchrone Flammensäulen auf den Refrain-Auftakt",
        },
        {
          name: "Perimeter Ground Fog Wave",
          pattern: "TILT_WAVE",
          color: "#94a3b8",
          tau: 600,
          speed: 0.50,
          amp: 0.70,
          strobe: 0.0,
          flame: false,
          dim: 70,
          bars: 8,
          desc: "Dichter Kriechnebelteppich entlang gescannter Raumwände",
        },
        {
          name: "Cryo Jet Chaser (CO2 Staccato)",
          pattern: "NEEDLE_RACE_STACCATO",
          color: "#38bdf8",
          tau: 150,
          speed: 1.50,
          amp: 1.00,
          strobe: 5.0,
          flame: true,
          dim: 100,
          bars: 4,
          desc: "Rhythmische CO2-Stöße im 1/4-Takt von links nach rechts",
        },
      ],
    },
    {
      category: "⚡ Techno, Drop & Arena",
      bricks: [
        {
          name: "Brass Drop (Schranz Snap)",
          pattern: "HARD_SNAP_STACCATO",
          color: "#eb3b5a",
          tau: 120,
          speed: 1.50,
          amp: 1.00,
          strobe: 10.0,
          flame: true,
          dim: 100,
          bars: 16,
          desc: "146 BPM Schranz-Staccato mit Kick-Sprung & 10 Hz Strobe",
        },
        {
          name: "Snare Riser Buildup",
          pattern: "TILT_WAVE",
          color: "#f97316",
          tau: 160,
          speed: 1.35,
          amp: 0.85,
          strobe: 6.0,
          flame: false,
          dim: 95,
          bars: 4,
          desc: "Kletternder Fächer mit beschleunigendem Strobe-Takt",
        },
        {
          name: "Cliffhanger (Kick-Cut Freeze)",
          pattern: "CLIFFHANGER_FREEZE",
          color: "#ef4444",
          tau: 260,
          speed: 0.0,
          amp: 0.0,
          strobe: 0.0,
          flame: false,
          dim: 15,
          bars: 1,
          desc: "Spannungspause: Scheinwerfer frieren im Raum ein",
        },
        {
          name: "Cinematic Stomp Snap",
          pattern: "CINEMATIC_STOMP_SNAP",
          color: "#a855f7",
          tau: 280,
          speed: 1.20,
          amp: 0.95,
          strobe: 3.0,
          flame: true,
          dim: 95,
          bars: 8,
          desc: "Wuchtige Paukenschläge mit synchronem Cryo/Flammenstoß",
        },
        {
          name: "Laser Horizon Scan Grid",
          pattern: "FAN_SPREAD",
          color: "#10b981",
          tau: 190,
          speed: 1.10,
          amp: 0.80,
          strobe: 5.0,
          flame: false,
          dim: 90,
          bars: 8,
          desc: "Scharfes Lasergitter schneidet horizontal durch den Raum",
        },
      ],
    },
  ];

  function addBrickFromCatalog(template: any) {
    const idx = studio.nodes.length + 1;
    const lastNode = studio.nodes[studio.nodes.length - 1];
    const newX = lastNode ? lastNode.x + 250 : 60;
    const newY = lastNode ? lastNode.y : 120;

    const newSection: SongSection = {
      id: `custom_node_${idx}_${Date.now().toString(36).substring(4)}`,
      name: `${idx}. ${template.name}`,
      section_type: "CUSTOM",
      start_bar: lastNode ? lastNode.end_bar : 0,
      end_bar: lastNode ? lastNode.end_bar + template.bars : template.bars,
      bars: template.bars,
      start_time: 0,
      end_time: 0,
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

    studio.nodes.push(newSection);

    // Automatically connect from last node to maintain clean signal chain
    if (lastNode) {
      studio.addConnection(lastNode.id, 0, newSection.id, 0);
    }

    studio.selectedNodeId = newSection.id;
    showAddMenu = false;
  }
</script>

<div class="flowboard-root">
  <!-- Top Toolbar -->
  <div class="flow-toolbar">
    <button
      class="f-btn btn-add"
      onclick={() => (showAddMenu = !showAddMenu)}
    >
      ➕ Baustein hinzufügen ▾
    </button>

    <div class="divider"></div>

    <!-- Layout Engines -->
    <button class="f-btn" onclick={layoutTimeline} title="Alle Bausteine horizontal in Reihe (0 Kabelkreuzungen)">
      📏 Timeline Flow
    </button>
    <button class="f-btn" onclick={layoutSnake} title="Kompakte Zick-Zack-Schlange (0 Kabelkreuzungen)">
      🐍 Snake Flow
    </button>
    <button class="f-btn" onclick={layoutStemLanes} title="Getrennte Spuren für Melodie, Bass & Cryo/FX">
      📊 Stem Lanes
    </button>
    <button class="f-btn" onclick={fitToView} title="Alle Bausteine im Fenster zentrieren">
      ⛶ Fit View
    </button>

    <div class="divider"></div>

    <!-- Zoom Controls -->
    <div class="zoom-group">
      <button class="zoom-btn" onclick={zoomOut} title="Herauszoomen">-</button>
      <span class="zoom-label">{Math.round(zoom * 100)}%</span>
      <button class="zoom-btn" onclick={zoomIn} title="Heranzoomen">+</button>
      <button class="zoom-btn" onclick={resetZoom} title="100% Zoom">1:1</button>
    </div>

    <div class="divider"></div>

    <span class="info-label">
      {studio.nodes.length} Bausteine • {studio.connections.length} Kabel
      {#if studio.analysis?.genre}
        • Genre: <strong style="color: #3daee9;">{studio.analysis.genre}</strong>
      {/if}
    </span>
  </div>

  <!-- Add Brick Catalog Modal -->
  {#if showAddMenu}
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="modal-backdrop" onclick={() => (showAddMenu = false)}>
      <div class="catalog-modal" onclick={(e) => e.stopPropagation()}>
        <div class="catalog-header">
          <span>Bibliothek: 20+ Licht-, Sound- & Spezialeffekt-Bausteine</span>
          <button class="btn-close" onclick={() => (showAddMenu = false)}>✕</button>
        </div>
        <div class="catalog-body">
          {#each brickCatalog as cat}
            <div class="catalog-category">
              <div class="cat-title">{cat.category}</div>
              <div class="cat-grid">
                {#each cat.bricks as brick}
                  <button
                    class="brick-card"
                    onclick={() => addBrickFromCatalog(brick)}
                  >
                    <div class="card-color-stripe" style="background: {brick.color}"></div>
                    <div class="card-info">
                      <div class="card-name">{brick.name}</div>
                      <div class="card-desc">{brick.desc}</div>
                      <div class="card-meta">
                        <span>Pattern: {brick.pattern}</span>
                        <span>Tau: {brick.tau}ms</span>
                        {#if brick.strobe > 0}<span>⚡ {brick.strobe}Hz</span>{/if}
                        {#if brick.flame}<span>🔥 CRYO/FLAME</span>{/if}
                      </div>
                    </div>
                  </button>
                {/each}
              </div>
            </div>
          {/each}
        </div>
      </div>
    </div>
  {/if}

  <!-- Infinite Canvas Viewport (Pan & Zoom) -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div
    class="canvas-viewport {isPanning ? 'panning' : ''}"
    bind:this={canvasContainer}
    onwheel={handleCanvasWheel}
    onpointerdown={handleCanvasPointerDown}
    oncontextmenu={(e) => e.preventDefault()}
  >
    <!-- Scaled & Panned World Container -->
    <div
      class="canvas-world"
      style="transform: translate({panX}px, {panY}px) scale({zoom}); transform-origin: 0 0;"
    >
      <!-- SVG Cable Layer -->
      <svg class="cables-svg">
        <defs>
          <filter id="cableGlow" x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="3.5" result="blur" />
            <feComposite in="SourceGraphic" in2="blur" operator="over" />
          </filter>
        </defs>

        <!-- Render Existing Connections -->
        {#each studio.connections as conn (conn.id)}
          {@const fromNode = studio.nodes.find((n) => n.id === conn.from_node_id)}
          {@const toNode = studio.nodes.find((n) => n.id === conn.to_node_id)}
          {#if fromNode && toNode}
            {@const p1 = getPinCoords(fromNode, "out", conn.from_pin)}
            {@const p2 = getPinCoords(toNode, "in", conn.to_pin)}
            {@const pathD = computeCablePath(p1.x, p1.y, p2.x, p2.y)}
            {@const isPlaying = studio.playback.is_playing && studio.nodes[studio.playback.active_phase_idx]?.id === fromNode.id}
            {@const isSelected = selectedConnectionId === conn.id}

            <!-- Invisible Wide Hitarea for Cable Selection -->
            <!-- svelte-ignore a11y_click_events_have_key_events -->
            <!-- svelte-ignore a11y_no_static_element_interactions -->
            <path
              d={pathD}
              class="cable-hitarea"
              onclick={(e) => {
                e.stopPropagation();
                selectedConnectionId = isSelected ? null : conn.id;
              }}
            />

            <!-- Rendered Visible Bezier Cable -->
            <path
              d={pathD}
              class="bezier-cable {isPlaying ? 'active-cable' : ''} {isSelected ? 'selected-cable' : ''}"
              stroke={isSelected ? "#ff3838" : isPlaying ? "#00ffff" : conn.from_pin === 1 ? "#ff7675" : "#3daee9"}
              stroke-width={isPlaying ? 3.5 : isSelected ? 3.0 : 2.2}
              fill="none"
              filter={isPlaying ? "url(#cableGlow)" : "none"}
            />

            <!-- Midpoint Delete Badge when Cable is Selected -->
            {#if isSelected}
              {@const midX = (p1.x + p2.x) / 2}
              {@const midY = (p1.y + p2.y) / 2}
              <!-- svelte-ignore a11y_click_events_have_key_events -->
              <!-- svelte-ignore a11y_no_static_element_interactions -->
              <g class="cable-delete-badge" onclick={() => deleteConnection(conn.id)}>
                <circle cx={midX} cy={midY} r="11" fill="#e63946" stroke="#ffffff" stroke-width="1.5" />
                <text x={midX} y={midY + 4} font-size="11" fill="#ffffff" text-anchor="middle" font-weight="bold">✕</text>
              </g>
            {/if}
          {/if}
        {/each}

        <!-- Render Live Wire being dragged -->
        {#if isWiring && wiringFrom}
          <path
            d={computeCablePath(wiringFrom.x, wiringFrom.y, wireCursor.x, wireCursor.y)}
            class="bezier-cable live-wiring"
            stroke="#00ffff"
            stroke-width="2.5"
            stroke-dasharray="6 3"
            fill="none"
          />
        {/if}
      </svg>

      <!-- Node Bricks -->
      {#each studio.nodes as node, i (node.id)}
        {@const isSelected = studio.selectedNodeId === node.id}
        {@const isActive = studio.playback.active_phase_idx === i && studio.playback.is_playing}
        {@const isHoveredTarget = hoveredInputPinNodeId === node.id}

        <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
        <div
          class="sequence-brick {isSelected ? 'selected' : ''} {isActive ? 'active-playing' : ''} {isHoveredTarget ? 'magnet-snap' : ''}"
          style="
            left: {node.x}px;
            top: {node.y}px;
            width: {node.w}px;
            height: {node.h}px;
          "
          onpointerdown={(e) => handleNodePointerDown(e, node)}
          role="group"
          aria-label={node.name}
        >
          <!-- Brick Header -->
          <div class="brick-header" style="background: {node.color || '#3daee9'}">
            <span class="brick-num">#{i + 1}</span>
            <span class="brick-title" title={node.name}>{node.name}</span>
            <button
              class="btn-delete-node"
              title="Baustein löschen"
              onclick={() => studio.removeNode(node.id)}
            >
              ✕
            </button>
          </div>

          <!-- Brick Body -->
          <div class="brick-body">
            <div class="pattern-badge">{node.pattern}</div>

            <div class="meta-row">
              <span>Bars: <strong>{node.bars}</strong></span>
              <span>Dim: <strong>{node.dim}%</strong></span>
            </div>

            <div class="meta-row">
              <span>Tau: <strong>{node.decay_tau_ms}ms</strong></span>
              <span>Speed: <strong>{node.speed.toFixed(1)}x</strong></span>
            </div>

            <div class="badges-row">
              {#if node.strobe > 0}
                <span class="tag strobe">⚡ {node.strobe.toFixed(1)}Hz</span>
              {/if}
              {#if node.flame}
                <span class="tag flame">🔥 CRYO</span>
              {/if}
              {#if node.has_ausklang}
                <span class="tag ausklang">〰 Ausklang</span>
              {/if}
            </div>
          </div>

          <!-- Input Pin (Left: IN) -->
          <!-- svelte-ignore a11y_no_static_element_interactions -->
          <div
            class="pin pin-in {isHoveredTarget ? 'pin-snapped' : ''}"
            title="Eingang (Hier andocken)"
            onpointerup={(e) => completeWiring(e, node)}
          >
            <span class="pin-hint">IN</span>
          </div>

          <!-- Output Pin 0 (Right upper: NEXT) -->
          <!-- svelte-ignore a11y_no_static_element_interactions -->
          <div
            class="pin pin-out pin-out-next"
            title="Ausgang: Sequence NEXT (Kabel ziehen)"
            onpointerdown={(e) => startWiring(e, node, 0)}
          >
            <span class="pin-hint">NEXT</span>
          </div>

          <!-- Output Pin 1 (Right lower: ACCENT / CRYO) -->
          <!-- svelte-ignore a11y_no_static_element_interactions -->
          <div
            class="pin pin-out pin-out-accent"
            title="Ausgang: FX / 808 / CRYO Trigger (Kabel ziehen)"
            onpointerdown={(e) => startWiring(e, node, 1)}
          >
            <span class="pin-hint">FX</span>
          </div>
        </div>
      {/each}
    </div>
  </div>
</div>

<style>
  .flowboard-root {
    display: flex;
    flex-direction: column;
    height: 100%;
    background: #141618;
    position: relative;
    overflow: hidden;
    font-family: 'Segoe UI', system-ui, sans-serif;
    user-select: none;
  }

  .flow-toolbar {
    display: flex;
    align-items: center;
    gap: 6px;
    background: #1e2124;
    border-bottom: 1px solid #282c30;
    padding: 6px 10px;
    z-index: 10;
    flex-wrap: wrap;
  }

  .f-btn {
    background: #23272b;
    border: 1px solid #363c42;
    border-radius: 4px;
    color: #e0e4e8;
    font-size: 11px;
    font-weight: 500;
    padding: 4px 8px;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .f-btn:hover {
    background: #2f353b;
    border-color: #3daee9;
    color: #ffffff;
  }

  .f-btn.btn-add {
    background: linear-gradient(180deg, #2b7bb9 0%, #1d5f94 100%);
    border-color: #3daee9;
    color: #ffffff;
    font-weight: 600;
  }

  .divider {
    width: 1px;
    height: 16px;
    background: #31363b;
    margin: 0 4px;
  }

  .zoom-group {
    display: flex;
    align-items: center;
    background: #181b1d;
    border: 1px solid #31363b;
    border-radius: 4px;
    overflow: hidden;
  }

  .zoom-btn {
    background: transparent;
    border: none;
    color: #abb0b6;
    font-size: 11px;
    font-weight: bold;
    padding: 3px 7px;
    cursor: pointer;
  }
  .zoom-btn:hover {
    background: #2a2e32;
    color: #ffffff;
  }

  .zoom-label {
    font-size: 10px;
    color: #3daee9;
    font-family: 'Consolas', monospace;
    padding: 0 4px;
    min-width: 36px;
    text-align: center;
  }

  .info-label {
    font-size: 11px;
    color: #8c939d;
    margin-left: auto;
  }

  .canvas-viewport {
    flex: 1;
    position: relative;
    overflow: hidden;
    background-color: #121416;
    background-image: radial-gradient(#25292e 1.5px, transparent 1.5px);
    background-size: 24px 24px;
    cursor: grab;
  }

  .canvas-viewport.panning {
    cursor: grabbing;
  }

  .canvas-world {
    position: absolute;
    left: 0;
    top: 0;
    width: 10000px;
    height: 10000px;
    transform-origin: 0 0;
    pointer-events: none;
  }

  .cables-svg {
    position: absolute;
    left: 0;
    top: 0;
    width: 100%;
    height: 100%;
    z-index: 1;
    pointer-events: auto;
  }

  .cable-hitarea {
    stroke: transparent;
    stroke-width: 18px;
    fill: none;
    cursor: pointer;
  }

  .bezier-cable {
    pointer-events: none;
    transition: stroke 0.2s ease, stroke-width 0.2s ease;
  }

  .bezier-cable.active-cable {
    stroke-dasharray: 6 3;
    animation: flowPulse 0.5s linear infinite;
  }

  .bezier-cable.selected-cable {
    stroke-dasharray: 5 2;
  }

  .cable-delete-badge {
    cursor: pointer;
    transition: transform 0.12s ease;
  }
  .cable-delete-badge:hover {
    transform: scale(1.25);
  }

  @keyframes flowPulse {
    from {
      stroke-dashoffset: 18;
    }
    to {
      stroke-dashoffset: 0;
    }
  }

  .sequence-brick {
    position: absolute;
    background: #1c1f22;
    border: 1px solid #32383e;
    border-radius: 6px;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.45);
    display: flex;
    flex-direction: column;
    cursor: move;
    z-index: 2;
    overflow: visible;
    pointer-events: auto;
    transition: box-shadow 0.15s ease, border-color 0.15s ease;
  }

  .sequence-brick:hover {
    border-color: #464e56;
  }

  .sequence-brick.selected {
    border-color: #3daee9;
    box-shadow: 0 0 0 2px rgba(61, 174, 233, 0.4), 0 8px 24px rgba(0, 0, 0, 0.6);
  }

  .sequence-brick.active-playing {
    border-color: #00ffff;
    box-shadow: 0 0 16px rgba(0, 255, 255, 0.7);
  }

  .sequence-brick.magnet-snap {
    border-color: #2ecc71;
    box-shadow: 0 0 16px rgba(46, 204, 113, 0.7);
  }

  .brick-header {
    height: 25px;
    border-top-left-radius: 5px;
    border-top-right-radius: 5px;
    padding: 0 6px 0 8px;
    display: flex;
    align-items: center;
    gap: 6px;
    color: #ffffff;
    font-size: 11px;
    font-weight: 700;
    overflow: hidden;
    text-shadow: 0 1px 2px rgba(0, 0, 0, 0.6);
  }

  .brick-num {
    opacity: 0.9;
    font-size: 10px;
  }

  .brick-title {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    flex: 1;
  }

  .btn-delete-node {
    background: transparent;
    border: none;
    color: rgba(255, 255, 255, 0.65);
    font-size: 10px;
    cursor: pointer;
    padding: 2px 4px;
    border-radius: 2px;
  }
  .btn-delete-node:hover {
    background: rgba(0, 0, 0, 0.35);
    color: #ffffff;
  }

  .brick-body {
    flex: 1;
    padding: 6px 8px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 10px;
    color: #abb0b6;
  }

  .pattern-badge {
    background: #252a2f;
    border: 1px solid #363c43;
    border-radius: 3px;
    padding: 2px 4px;
    font-family: 'Consolas', monospace;
    font-size: 9.5px;
    color: #3daee9;
    text-align: center;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .meta-row {
    display: flex;
    justify-content: space-between;
  }

  .meta-row strong {
    color: #eff0f1;
  }

  .badges-row {
    display: flex;
    gap: 4px;
    flex-wrap: wrap;
    margin-top: 2px;
  }

  .tag {
    font-size: 8.5px;
    font-weight: 700;
    padding: 1px 4px;
    border-radius: 2px;
  }

  .tag.strobe {
    background: rgba(241, 196, 15, 0.2);
    color: #f1c40f;
  }

  .tag.flame {
    background: rgba(255, 87, 34, 0.25);
    color: #ff5722;
  }

  .tag.ausklang {
    background: rgba(16, 185, 129, 0.2);
    color: #34d399;
  }

  /* Interactive Pins with Labels */
  .pin {
    position: absolute;
    width: 14px;
    height: 14px;
    border-radius: 50%;
    border: 2px solid #141618;
    cursor: crosshair;
    transition: transform 0.12s ease, background 0.15s ease;
    z-index: 5;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .pin:hover {
    transform: scale(1.35);
  }

  .pin-hint {
    font-size: 7px;
    font-weight: bold;
    color: #ffffff;
    user-select: none;
    pointer-events: none;
  }

  .pin-in {
    left: -8px;
    top: calc(50% - 7px);
    background: #00e5ff;
  }

  .pin-snapped {
    background: #2ecc71 !important;
    transform: scale(1.5);
    box-shadow: 0 0 10px #2ecc71;
  }

  .pin-out-next {
    right: -8px;
    top: calc(35% - 7px);
    background: #3daee9;
  }

  .pin-out-accent {
    right: -8px;
    top: calc(70% - 7px);
    background: #ff7675;
  }

  /* Catalog Modal */
  .modal-backdrop {
    position: absolute;
    top: 36px;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0, 0, 0, 0.65);
    z-index: 100;
    display: flex;
    justify-content: center;
    align-items: flex-start;
    padding-top: 16px;
  }

  .catalog-modal {
    background: #1b1e20;
    border: 1px solid #31363b;
    border-radius: 8px;
    width: 720px;
    max-height: 85%;
    display: flex;
    flex-direction: column;
    box-shadow: 0 16px 48px rgba(0, 0, 0, 0.85);
    overflow: hidden;
  }

  .catalog-header {
    background: linear-gradient(180deg, #2a2e32 0%, #232629 100%);
    border-bottom: 1px solid #16181a;
    padding: 10px 14px;
    font-size: 12px;
    font-weight: 700;
    color: #ffffff;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .btn-close {
    background: transparent;
    border: none;
    color: #abb0b6;
    font-size: 14px;
    cursor: pointer;
  }
  .btn-close:hover {
    color: #ffffff;
  }

  .catalog-body {
    padding: 14px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .cat-title {
    font-size: 11px;
    font-weight: 700;
    color: #3daee9;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    margin-bottom: 6px;
  }

  .cat-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }

  .brick-card {
    background: #23272b;
    border: 1px solid #363c42;
    border-radius: 5px;
    display: flex;
    overflow: hidden;
    cursor: pointer;
    text-align: left;
    transition: all 0.15s ease;
    padding: 0;
  }

  .brick-card:hover {
    border-color: #3daee9;
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
  }

  .card-color-stripe {
    width: 6px;
  }

  .card-info {
    padding: 8px 10px;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .card-name {
    font-size: 11.5px;
    font-weight: 700;
    color: #ffffff;
  }

  .card-desc {
    font-size: 10px;
    color: #abb0b6;
    line-height: 1.3;
  }

  .card-meta {
    display: flex;
    gap: 8px;
    font-size: 9px;
    color: #8c939d;
    font-family: 'Consolas', monospace;
    margin-top: 2px;
  }
</style>
