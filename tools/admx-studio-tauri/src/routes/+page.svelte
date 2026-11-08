<script lang="ts">
  import { studio } from "$lib/state.svelte";
  import Window from "$lib/components/Window.svelte";
  import TimelineWindow from "$lib/components/TimelineWindow.svelte";
  import FlowboardWindow from "$lib/components/FlowboardWindow.svelte";
  import InspectorPanel from "$lib/components/InspectorPanel.svelte";
  import OptimizerWindow from "$lib/components/OptimizerWindow.svelte";
  import DmxMixerWindow from "$lib/components/DmxMixerWindow.svelte";
  import Taskbar from "$lib/components/Taskbar.svelte";

  // Preload default demo nodes if none loaded yet
  $effect(() => {
    if (studio.nodes.length === 0) {
      studio.nodes = [
        {
          id: "p1",
          name: "1. Intro Stomp",
          section_type: "INTRO",
          start_bar: 1,
          end_bar: 5,
          bars: 4,
          start_time: 0.0,
          end_time: 6.575,
          color: "#f59e0b",
          pattern: "PAN_SYN",
          speed: 1.0,
          amp: 1.0,
          strobe: 0.0,
          flame: false,
          dim: 80,
          ausklang_mode: "NATURAL_EXPONENTIAL",
          decay_tau_ms: 220,
          has_ausklang: true,
          cliffhangers: [],
          cinematic_hits: [],
          x: 50,
          y: 100,
          w: 190,
          h: 120,
        },
        {
          id: "p2",
          name: "2. Verse Rap",
          section_type: "VERSE",
          start_bar: 5,
          end_bar: 19,
          bars: 14,
          start_time: 6.575,
          end_time: 29.589,
          color: "#06b6d4",
          pattern: "PAN_SWEE",
          speed: 1.0,
          amp: 0.8,
          strobe: 0.0,
          flame: false,
          dim: 65,
          ausklang_mode: "NATURAL_EXPONENTIAL",
          decay_tau_ms: 240,
          has_ausklang: true,
          cliffhangers: [],
          cinematic_hits: [],
          x: 280,
          y: 100,
          w: 190,
          h: 120,
        },
        {
          id: "p3",
          name: "3. Pre-Build/up",
          section_type: "BUILDUP",
          start_bar: 19,
          end_bar: 22,
          bars: 3,
          start_time: 29.589,
          end_time: 34.52,
          color: "#8b5cf6",
          pattern: "TILT_WAV",
          speed: 1.5,
          amp: 1.0,
          strobe: 2.0,
          flame: false,
          dim: 90,
          ausklang_mode: "TENSION_HOLD",
          decay_tau_ms: 320,
          has_ausklang: true,
          cliffhangers: [
            {
              beat: 12,
              bar: 21,
              beat_in_bar: 4,
              time_sec: 34.0,
              gap_beats: 1,
              has_ausklang: true,
            },
          ],
          cinematic_hits: [],
          x: 510,
          y: 100,
          w: 190,
          h: 120,
        },
        {
          id: "p4",
          name: "4. Snare Riser",
          section_type: "RISER",
          start_bar: 22,
          end_bar: 25,
          bars: 3,
          start_time: 34.52,
          end_time: 39.452,
          color: "#f97316",
          pattern: "TILT_WAV",
          speed: 2.0,
          amp: 1.2,
          strobe: 6.0,
          flame: false,
          dim: 100,
          ausklang_mode: "TENSION_HOLD",
          decay_tau_ms: 180,
          has_ausklang: true,
          cliffhangers: [],
          cinematic_hits: [],
          x: 740,
          y: 100,
          w: 190,
          h: 120,
        },
        {
          id: "p5",
          name: "5. Bass Drop Snap",
          section_type: "DROP",
          start_bar: 25,
          end_bar: 39,
          bars: 14,
          start_time: 39.452,
          end_time: 62.465,
          color: "#ec4899",
          pattern: "PANC_SNA",
          speed: 2.5,
          amp: 1.5,
          strobe: 8.0,
          flame: true,
          dim: 100,
          ausklang_mode: "HARD_CUT",
          decay_tau_ms: 90,
          has_ausklang: false,
          cliffhangers: [],
          cinematic_hits: [
            {
              beat: 1,
              bar: 25,
              beat_in_bar: 1,
              time_sec: 39.452,
              strength: 1.0,
              has_ausklang: false,
              decay_tau_ms: 80,
            },
          ],
          x: 970,
          y: 100,
          w: 190,
          h: 120,
        },
        {
          id: "p6",
          name: "6. Vocal Breakdown",
          section_type: "BREAKDOWN",
          start_bar: 39,
          end_bar: 53,
          bars: 14,
          start_time: 62.465,
          end_time: 85.479,
          color: "#3b82f6",
          pattern: "TILT_WAV",
          speed: 0.8,
          amp: 0.7,
          strobe: 0.0,
          flame: false,
          dim: 50,
          ausklang_mode: "NATURAL_EXPONENTIAL",
          decay_tau_ms: 380,
          has_ausklang: true,
          cliffhangers: [],
          cinematic_hits: [],
          x: 1200,
          y: 100,
          w: 190,
          h: 120,
        },
        {
          id: "p7",
          name: "7. Anthem Chorus",
          section_type: "CHORUS",
          start_bar: 53,
          end_bar: 67,
          bars: 14,
          start_time: 85.479,
          end_time: 108.493,
          color: "#10b981",
          pattern: "CIRCLE",
          speed: 1.5,
          amp: 1.2,
          strobe: 3.0,
          flame: true,
          dim: 95,
          ausklang_mode: "NATURAL_EXPONENTIAL",
          decay_tau_ms: 200,
          has_ausklang: true,
          cliffhangers: [],
          cinematic_hits: [],
          x: 1430,
          y: 100,
          w: 190,
          h: 120,
        },
        {
          id: "p8",
          name: "8. Outro Sweep",
          section_type: "OUTRO",
          start_bar: 67,
          end_bar: 71,
          bars: 4,
          start_time: 108.493,
          end_time: 115.068,
          color: "#eab308",
          pattern: "FOUR_S",
          speed: 0.6,
          amp: 0.6,
          strobe: 0.0,
          flame: false,
          dim: 40,
          ausklang_mode: "NATURAL_EXPONENTIAL",
          decay_tau_ms: 500,
          has_ausklang: true,
          cliffhangers: [],
          cinematic_hits: [],
          x: 1660,
          y: 100,
          w: 190,
          h: 120,
        },
      ];
      studio.playback.duration_ms = 115068;
    }

    if (studio.connections.length === 0) {
      studio.connections = [
        { id: "c1", from_node_id: "p1", from_pin: 0, to_node_id: "p2", to_pin: 0 },
        { id: "c2", from_node_id: "p2", from_pin: 0, to_node_id: "p3", to_pin: 0 },
        { id: "c3", from_node_id: "p3", from_pin: 0, to_node_id: "p4", to_pin: 0 },
        { id: "c4", from_node_id: "p4", from_pin: 0, to_node_id: "p5", to_pin: 0 },
        { id: "c5", from_node_id: "p5", from_pin: 0, to_node_id: "p6", to_pin: 0 },
        { id: "c6", from_node_id: "p6", from_pin: 0, to_node_id: "p7", to_pin: 0 },
        { id: "c7", from_node_id: "p7", from_pin: 0, to_node_id: "p8", to_pin: 0 },
      ];
    }
  });
</script>

<div class="desktop-workspace">
  <!-- Desktop Windows -->
  {#each studio.windows as win (win.id)}
    <Window {win}>
      {#if win.id === "timeline"}
        <TimelineWindow />
      {:else if win.id === "flowboard"}
        <FlowboardWindow />
      {:else if win.id === "inspector"}
        <InspectorPanel />
      {:else if win.id === "optimizer"}
        <OptimizerWindow />
      {:else if win.id === "dmx"}
        <DmxMixerWindow />
      {/if}
    </Window>
  {/each}

  <!-- KDE Plasma Taskbar -->
  <Taskbar />
</div>

<style>
  :global(body) {
    margin: 0;
    padding: 0;
    overflow: hidden;
    background: #111315;
    user-select: none;
    -webkit-user-select: none;
  }

  .desktop-workspace {
    width: 100vw;
    height: 100vh;
    position: relative;
    overflow: hidden;
    background: 
      radial-gradient(circle at 50% 30%, #1e2630 0%, #0d1013 85%),
      linear-gradient(135deg, #151a1e 0%, #0c0e10 100%);
  }
</style>
