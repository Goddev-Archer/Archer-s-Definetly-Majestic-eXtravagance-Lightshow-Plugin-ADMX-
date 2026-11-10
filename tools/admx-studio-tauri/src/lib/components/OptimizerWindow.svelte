<script lang="ts">
  import { studio } from "../state.svelte";

  let saveSuccessMsg = $state<string | null>(null);
  let saveErrorMsg = $state<string | null>(null);

  const passPresets = [10000, 50000, 250000, 1000000];

  async function handleRunOptimization() {
    saveSuccessMsg = null;
    saveErrorMsg = null;
    try {
      await studio.runOptimization(studio.optimizationPasses);
    } catch (e: any) {
      saveErrorMsg = e?.toString() || "Fehler bei der Optimierung";
    }
  }

  async function handleSaveProfile() {
    saveSuccessMsg = null;
    saveErrorMsg = null;
    try {
      const [id, checksum] = await studio.saveProfileToDb();
      saveSuccessMsg = `Erfolgreich in admx.db exportiert! Show-ID: ${id} (CRC32: 0x${checksum.toString(16).toUpperCase()}) • Sofort abspielbar per '/admx flow play ${id}' und im Auto-Director!`;
    } catch (e: any) {
      saveErrorMsg = `Fehler beim Speichern: ${e?.toString()}`;
    }
  }
</script>

<div class="optimizer-root">
  <div class="optimizer-scroll">
    <div class="header-desc">
      Der Rust-Rayon-Optimierer simuliert physikalisches Annealing parallel auf allen CPU-Kernen, um die Lichtdynamik (Dimmer, Ausklang-Tau, Speed, Strobe) perfekt auf die Audio-DNA abzustimmen.
    </div>

    <!-- Pass presets -->
    <div class="card-box">
      <div class="card-title">Anzahl Durchläufe (Simulated Annealing Passes)</div>
      <div class="preset-buttons">
        {#each passPresets as passes}
          <button
            class="preset-btn {studio.optimizationPasses === passes ? 'active' : ''}"
            onclick={() => (studio.optimizationPasses = passes)}
          >
            {(passes / 1000).toLocaleString()}k Passes
          </button>
        {/each}
      </div>

      <div class="custom-pass-row">
        <span>Exakte Durchläufe:</span>
        <input
          type="number"
          class="num-input"
          min="1000"
          max="10000000"
          step="10000"
          bind:value={studio.optimizationPasses}
        />
      </div>

      <button
        class="btn-run {studio.isOptimizing ? 'loading' : ''}"
        onclick={handleRunOptimization}
        disabled={studio.isOptimizing || !studio.analysis}
      >
        {studio.isOptimizing
          ? "Berechne auf Multi-Core CPU..."
          : "⚡ Starte Multi-Core Optimierung (Rust)"}
      </button>
    </div>

    <!-- Results / Metrics Dashboard -->
    {#if studio.optimizationOutcome}
      <div class="metrics-grid">
        <div class="metric-card">
          <span class="m-val fitness">
            {(studio.optimizationOutcome.final_fitness * 100).toFixed(1)}%
          </span>
          <span class="m-title">Fitness (Acoustic Match)</span>
        </div>

        <div class="metric-card">
          <span class="m-val loss">
            {studio.optimizationOutcome.final_loss.toFixed(4)}
          </span>
          <span class="m-title">Total Loss</span>
        </div>

        <div class="metric-card">
          <span class="m-val speed">
            {Math.round(studio.optimizationOutcome.passes_per_sec).toLocaleString()}
          </span>
          <span class="m-title">Passes / Sekunde</span>
        </div>

        <div class="metric-card">
          <span class="m-val time">
            {(studio.optimizationOutcome.elapsed_sec * 1000).toFixed(1)} ms
          </span>
          <span class="m-title">Dauer</span>
        </div>
      </div>
    {/if}

    <!-- SQLite Persistence Card -->
    <div class="card-box persistence-card">
      <div class="card-title">💾 In admx.db (Flowboards & Auto-Show) exportieren</div>

      <div class="field-row">
        <label for="db-path">SQLite Pfad:</label>
        <div class="db-path-col">
          <input
            id="db-path"
            type="text"
            class="text-input"
            bind:value={studio.dbPath}
            placeholder="plugins/ADMX/admx.db"
          />
          <div class="path-presets">
            <button
              class="mini-preset-btn {studio.dbPath === 'plugins/ADMX/admx.db' ? 'active' : ''}"
              onclick={() => (studio.dbPath = 'plugins/ADMX/admx.db')}
            >
              plugins/ADMX/admx.db
            </button>
            <button
              class="mini-preset-btn {studio.dbPath === 'run/plugins/ADMX/admx.db' ? 'active' : ''}"
              onclick={() => (studio.dbPath = 'run/plugins/ADMX/admx.db')}
            >
              run/plugins/ADMX/admx.db
            </button>
          </div>
        </div>
      </div>

      <div class="field-row">
        <label for="slot-idx">Profil Slot:</label>
        <select
          id="slot-idx"
          class="select-input"
          bind:value={studio.slotIndex}
        >
          {#each Array.from({ length: 8 }, (_, i) => i + 1) as slot}
            <option value={slot}>Slot #{slot}</option>
          {/each}
        </select>
      </div>

      <button
        class="btn-save"
        onclick={handleSaveProfile}
        disabled={studio.nodes.length === 0}
      >
        Als Auto-Show Profil in Datenbank schreiben
      </button>

      {#if saveSuccessMsg}
        <div class="success-banner">
          ✅ {saveSuccessMsg}
        </div>
      {/if}

      {#if saveErrorMsg}
        <div class="error-banner">
          ⚠️ {saveErrorMsg}
        </div>
      {/if}
    </div>
  </div>
</div>

<style>
  .optimizer-root {
    height: 100%;
    background: #17191b;
    color: #eff0f1;
    font-family: 'Segoe UI', system-ui, sans-serif;
    font-size: 11.5px;
    overflow-y: auto;
  }

  .optimizer-scroll {
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .header-desc {
    color: #8c939d;
    line-height: 1.4;
    font-size: 11px;
    background: #1c1f22;
    padding: 8px 10px;
    border-radius: 4px;
    border: 1px solid #282c30;
  }

  .card-box {
    background: #1c1f22;
    border: 1px solid #282c30;
    border-radius: 5px;
    padding: 10px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .card-title {
    font-size: 11px;
    font-weight: 700;
    color: #3daee9;
    text-transform: uppercase;
    letter-spacing: 0.4px;
  }

  .preset-buttons {
    display: flex;
    gap: 6px;
  }

  .preset-btn {
    flex: 1;
    background: #23272b;
    border: 1px solid #363c42;
    border-radius: 4px;
    color: #cbd3dc;
    font-size: 11px;
    font-weight: 600;
    padding: 6px 4px;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .preset-btn:hover {
    background: #2e343b;
    color: #ffffff;
    border-color: #3daee9;
  }

  .preset-btn.active {
    background: #1e5a87;
    border-color: #3daee9;
    color: #ffffff;
  }

  .custom-pass-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 11px;
    color: #8c939d;
  }

  .num-input,
  .text-input,
  .select-input {
    background: #121416;
    border: 1px solid #31363b;
    border-radius: 4px;
    color: #ffffff;
    font-size: 11.5px;
    padding: 4px 8px;
    outline: none;
  }

  .btn-run {
    background: linear-gradient(180deg, #10b981 0%, #059669 100%);
    border: 1px solid #34d399;
    border-radius: 4px;
    color: #ffffff;
    font-size: 11.5px;
    font-weight: 700;
    padding: 8px 12px;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .btn-run:hover:not(:disabled) {
    background: linear-gradient(180deg, #34d399 0%, #10b981 100%);
  }

  .btn-run:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .metrics-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }

  .metric-card {
    background: #14171a;
    border: 1px solid #23272b;
    border-radius: 4px;
    padding: 8px;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .m-val {
    font-size: 16px;
    font-weight: 700;
    font-family: 'Consolas', monospace;
  }

  .m-val.fitness {
    color: #34d399;
  }
  .m-val.loss {
    color: #f87171;
  }
  .m-val.speed {
    color: #38bdf8;
  }
  .m-val.time {
    color: #fbbf24;
  }

  .m-title {
    font-size: 9.5px;
    color: #8c939d;
    margin-top: 2px;
  }

  .persistence-card {
    border-top: 2px solid #3daee9;
  }

  .field-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .field-row label {
    width: 80px;
    color: #8c939d;
  }

  .field-row input,
  .field-row select {
    flex: 1;
  }

  .db-path-col {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .path-presets {
    display: flex;
    gap: 6px;
  }

  .mini-preset-btn {
    background: #1b1e22;
    border: 1px solid #2d3339;
    border-radius: 3px;
    color: #8c939d;
    font-size: 9.5px;
    font-family: 'Consolas', monospace;
    padding: 2px 6px;
    cursor: pointer;
    transition: all 0.12s ease;
  }

  .mini-preset-btn:hover {
    background: #252a30;
    color: #eff0f1;
    border-color: #3daee9;
  }

  .mini-preset-btn.active {
    background: #1e3a5f;
    color: #38bdf8;
    border-color: #3daee9;
  }

  .btn-save {
    background: #2563eb;
    border: 1px solid #3b82f6;
    border-radius: 4px;
    color: #ffffff;
    font-size: 11px;
    font-weight: 600;
    padding: 6px 12px;
    cursor: pointer;
    margin-top: 4px;
    transition: background 0.15s ease;
  }

  .btn-save:hover:not(:disabled) {
    background: #1d4ed8;
  }

  .btn-save:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .success-banner {
    background: rgba(16, 185, 129, 0.2);
    border: 1px solid #10b981;
    color: #34d399;
    padding: 6px 10px;
    border-radius: 4px;
    font-size: 10.5px;
  }

  .error-banner {
    background: rgba(239, 68, 68, 0.2);
    border: 1px solid #ef4444;
    color: #f87171;
    padding: 6px 10px;
    border-radius: 4px;
    font-size: 10.5px;
  }
</style>
