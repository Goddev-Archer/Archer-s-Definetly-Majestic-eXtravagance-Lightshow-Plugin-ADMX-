<script lang="ts">
  import { studio } from "$lib/state.svelte";
  import Window from "$lib/components/Window.svelte";
  import TimelineWindow from "$lib/components/TimelineWindow.svelte";
  import FlowboardWindow from "$lib/components/FlowboardWindow.svelte";
  import InspectorPanel from "$lib/components/InspectorPanel.svelte";
  import OptimizerWindow from "$lib/components/OptimizerWindow.svelte";
  import DmxMixerWindow from "$lib/components/DmxMixerWindow.svelte";
  import Taskbar from "$lib/components/Taskbar.svelte";
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
