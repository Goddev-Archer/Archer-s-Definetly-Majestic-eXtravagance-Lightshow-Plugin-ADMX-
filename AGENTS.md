# AGENTS.md – Engineering, Security & Performance Guidelines for ADMX

## 1. Executive Reflection on the Security & Performance Audit

ADMX (Archer's Definitely Majestic eXtravagance Lightshow-Plugin) pushes vanilla Paper/Minecraft rendering to its limits by constructing 3D moving heads, volumetric beams, fluid fog wave meshes, and interactive 3D holographic consoles entirely out of `Display` (`ItemDisplay`, `TextDisplay`, `BlockDisplay`) and `Interaction` entities without requiring client-side mods.

The static security and performance audit identified a clear pattern across **37 findings**:
1. **What worked well:** SQL injection safety (`PreparedStatement`), Adventure `Component.text()` XSS/injection safety, `InventoryHolder` type checking, `setPersistent(false)` entity hygiene, and declarative immediate-mode rendering via `EntityTracker`.
2. **Where the systemic flaws lay:**
   - **Trusting Client & World State (Security):** Event listeners trusted a forgeable PersistentDataContainer (PDC) flag on a Blaze Rod without checking `player.hasPermission(...)`. In-world 3D Holo Consoles allowed any nearby player to hijack the show, trigger main-thread SQLite writes, or gain melee PvP immunity via a player-centered `Interaction` hitbox.
   - **Unbounded Immediate-Mode Entity Multiplication (Performance):** Treating every polygon fragment as a ticked, tracked Paper server entity that updates or teleports at 20 Hz caused quadratic/linear explosion in packets, SVD matrix decompositions, and entity spawn/despawn churn (especially on strobes, blinders, moving-head lens offsets, and 1,400-cell fog meshes).
   - **Synchronous Main-Thread Blocking & World Lifecycle Edge Cases (Robustness):** Calling SQLite writes synchronously on every UI click, running 32,000-block flood fills on flash ticks, assuming `Location.getWorld()` returns `null` instead of throwing `IllegalArgumentException("World unloaded")`, and leaking UUID caches on chunk unloads/player quits.

Every agent or developer working on ADMX **must** enforce the invariants documented below.

---

## 2. Security Invariants (SEC-01 – SEC-11)

### 2.1 Strict Permission Gating in All Listeners (`SEC-01`)
- **Rule:** Never rely solely on an item's PDC tag (`isPlacementWand`) or an open inventory to authorize an action. Creative-mode clients can forge arbitrary item NBT/PDC components, and wands can be dropped or stolen.
- **Enforcement:** Every entry point (`PlayerInteractEvent`, `InventoryClickEvent`, `InventoryDragEvent`, `PlayerInteractEntityEvent`, `EntityDamageByEntityEvent`) must check granular permissions (`admx.admin`, `admx.place`, `admx.remove`, `admx.control`, `admx.console`).

### 2.2 Hologram Console Access Control & Static Hitboxes (`SEC-02`, `SEC-04`, `PERF-15`)
- **Rule 1 (Access Control):** Only the console owner or players with `admx.control` / `admx.admin` may interact with or close a `HoloConsoleUi`. Filter raycast candidates and click handlers by permission and ownership.
- **Rule 2 (No Hitboxes on Players):** **Never** spawn or teleport an `Interaction` entity onto a player's location (`lookingPlayer.getLocation()`). Doing so intercepts melee attacks (granting PvP god-mode) and blocks right-click interactions.
- **Enforcement:** Place a single stationary `Interaction` entity at the **console panel's world origin** (sized to cover the compact panel), or use arm-swing/interact events paired with `HoloPlanePointDetector` raycasting.

### 2.3 Resource Budgets & Rate Limiting (`SEC-03`)
- **Rule:** Every resource-creating or I/O-triggering action must be bounded by configurable limits (`config.yml`):
  - Max fixtures per world and global max tracked entities in `EntityTracker`.
  - Max fog machines per world and max fog cells per machine.
  - Per-player UI click cooldown (e.g., `75–100 ms`) to prevent packet/DB flood.

### 2.4 Client-Side Fake Light Safety (`SEC-05`)
- **Rule:** Never overwrite non-air blocks (water, grass, torches, rails) with client-side `Material.LIGHT` packets, and never broadcast fake light changes to players across the entire world.
- **Enforcement:**
  - Only send `Material.LIGHT` if `block.getType().isAir()` (specifically `AIR` or `CAVE_AIR`).
  - Only send block updates to viewers within `64` blocks in the **same world**.
  - Store the `World` reference/UUID inside `ActiveLightState` so multi-world cleanup restores the exact world's blocks.
  - Clear active lights on fixture removal, chunk unload, world unload, and `/admx clear`.

### 2.5 Targeted Entity Removal & GUI Drag Protection (`SEC-06`, `SEC-07`)
- **Rule:** Removing a fixture must only remove `EntityTracker` handles rooted at that fixture's `UUID` (`EntityTracker.removeByRoot(fixtureId)`), never a blind bounding-box purge of all `admx:rendered` entities.
- **Rule:** Always cancel `InventoryDragEvent` alongside `InventoryClickEvent` for custom `InventoryHolder` GUIs (`PlacementGuiHolder`, `ConsoleHolder`) to prevent item loss.

### 2.6 Data Validation, Photosensitivity & Destructive Commands (`SEC-08` – `SEC-11`)
- **Rule (`SEC-08`):** Always pass `Locale.ROOT` to `String.format` when computing checksums, and clamp all deserialized JSON snapshot fields in `FixtureGroup.applySnapshot`.
- **Rule (`SEC-09`):** Cap maximum strobe frequency (≤ 10 Hz) and provide `/admx safe` per-player photosensitivity opt-out that hides flashing/strobing beam entities for that viewer.
- **Rule (`SEC-10`):** Pin external MCP tooling in `.vscode/mcp.json` and `.agents/mcp_config.json` to explicit versions or commit SHAs.
- **Rule (`SEC-11`):** Require `/admx clear confirm` before wiping all fixtures from the database.

### 2.7 Native Art-Net 4, WebSocket Bridge & 3D DMX Console Security (`SEC-12` – `SEC-17`)
- **Rule (`SEC-12` – Art-Net UDP Spoofing & `ArtPollReply` Amplification Defense):**
  - Bind the native Art-Net 4 UDP node (`ArtNetUdpNode`, Port `6454`) to loopback (`127.0.0.1`) by default (`artnet-bridge.udp-bind-host`) so external WAN hosts cannot spoof unauthenticated UDP `OpDmx` frames.
  - Rate-limit `ArtPollReply` (`239` bytes) responses to at most 1 reply per `2000 ms` per source IP (`MAX_TRACKED_POLL_IPS = 256`) to prevent UDP reflection/amplification abuse.
- **Rule (`SEC-13` – WebSocket Frame Bombing / OOM & Write Synchronization):**
  - Never allocate a buffer from an untrusted 64-bit WebSocket payload length header. Strictly enforce `MAX_INCOMING_FRAME_BYTES = 1024` (an `ArtDmx` packet is at most `530` bytes) and disconnect immediately on oversized frames or premature EOF (`-1`).
  - Synchronize all socket writes (`sendPong` and `drainPendingFrames`) on a dedicated per-client `writeLock` so concurrent Pong and `OpDmx` frames never interleave or corrupt the TCP stream.
- **Rule (`SEC-14` – Malformed `ArtDmx` Packet & Universe Allocation DoS):**
  - Pre-allocate a fixed array of `MAX_UNIVERSES = 16` (`DmxUniverse[16]`) in `ArtNetDmxUniverseManager`. Never allocate new universes dynamically from untrusted packet `SubUni`/`Net` headers.
  - Validate the 8-byte `"Art-Net\0"` signature, `OpCode == 0x5000` (LE), `ProtVer >= 14` (BE), `universeId in [0..15]`, `dmxLength in [2..512]`, and actual buffer bounds before copying payload bytes.
- **Rule (`SEC-15` – Photosensitivity Cap on Raw DMX / Art-Net Strobe Channels):**
  - Even when a fixture is driven directly by external Art-Net `OpDmx` packets or manual in-game DMX512 faders (`DmxWorkstationUi`), `DmxUniverse.resolveMergedFixtureState` must clamp decoded strobe frequency to `<= maxStrobeHz` (`10.0 Hz`). External DMX data may never bypass PSE protection.
- **Rule (`SEC-16` – Placed 3D DMX Console Quotas, Permission Gating & Static Hitboxes):**
  - Enforce `limits.max-consoles-per-world` (default `32`) when placing 3D DMX consoles (`PlacedDmxConsole`).
  - Require `admx.place` / `admx.admin` to place consoles, `admx.remove` / `admx.admin` to delete them, and `admx.console` / `admx.control` / `admx.admin` to open `DmxWorkstationUi` or relink universes.
  - Place a single stationary `Interaction` entity at the console's block origin (`DmxConsoleRenderer`), never on a player.
- **Rule (`SEC-17` – Thread-Safe Cross-Thread DMX Bus Synchronization):**
  - Synchronize reads/writes between asynchronous Art-Net RX threads (UDP / WebSocket) and the 20 Hz main render thread inside `DmxUniverse`, and persist `PlacedDmxConsole` universe/bank changes asynchronously via `AdmxDatabase.saveConsoleAsync`.

---

## 3. Performance & Entity Budget Architecture (PERF-01 – PERF-15, CORE-01 – CORE-05)

### 3.1 Zero Despawn/Respawn Churn on Dark or Strobing Fixtures (`PERF-02`, `PERF-03`)
- **Rule:** Never despawn beam entities when a fixture dims to `0` or flashes off during a strobe cycle. Spawning and destroying 108 entities every 2 ticks floods Paper's entity tracker, network packets, and third-party protection plugins (WorldGuard, CoreProtect).
- **Enforcement:**
  - When a fixture is dark (`!state.isIlluminating()`), keep its beam entities alive for a grace period (e.g., 5 seconds / 100 ticks) with `Color.fromARGB(0, 0, 0, 0)` and `setViewRange(0f)`, or use lightweight geometry (`BLINDER_GLOW` / reduced faces).
  - **Fog Machines (`PERF-03`):** Fog machines must **never** be affected by group strobe flashing and must **never** call `clearCache()` on temporary dimmer drops. Cache clearing is reserved strictly for fixture deletion or room block changes.

### 3.2 Static Beam Anchoring & Reduced Polygon Counts (`PERF-04`, `BUG-07`, `CORE-01`)
- **Rule:** Never anchor beam `TextDisplay` entities at a moving head's rotating `lensLoc`. Changing entity `Location` every tick forces `entity.teleport()` packets (which snap on the client) while transformation matrices interpolate, causing both network bloat and visual tearing (`BUG-07`).
- **Enforcement:**
  - Spawn all beam entities at the **static fixture base `Location`**.
  - Bake the dynamic lens offset (`lensOffset`) directly into the `Matrix4f` transformation! This eliminates 100% of per-tick teleport packets for moving heads.
  - Keep `SPOT_CONE` geometry lean (e.g., single-sided or 6–8 outer segments + 3 core segments, or configurable LOD) and use dedicated low-entity modes for Blinders and Strobe Bars.

### 3.3 Low-Frequency Interpolated Fog Wave Mesh (`PERF-01`, `PERF-06`, `PERF-12`)
- **Rule:** Never update 1,400 fog wave `TextDisplay` entities every single tick.
- **Enforcement:**
  - Use a coarser grid step (`3.0` blocks instead of `2.0`), a hard cell cap per machine (default `180`), and 1 wave layer by default.
  - Update wave cell transforms only every **4 ticks** with `interpolationDuration = 4` so the client smoothly tweens the wave motion at 1/4th the CPU and network cost.
  - Stagger room flood-fill calculations across machines (`(tickCounter + Math.abs(fixtureId.hashCode())) % 100 == 0`) and skip cells in unloaded chunks (`BUG-05`).

### 3.4 Per-Owner Eye Visor & Viewer Distance Gating (`PERF-07`, `PERF-08`, `PERF-10`)
- **Rule (`PERF-07`):** A player's fog eye visor must only be visible to that player (`display.setVisibleByDefault(false)` + `player.showEntity(plugin, display)`), must set `setTeleportDuration(1)`, and must cache its handle `UUID` per player.
- **Rule (`PERF-08`, `PERF-10`):**
  - Pause rendering completely when `Bukkit.getOnlinePlayers().isEmpty()`.
  - Skip rendering any fixture that has no player within `maxRenderDistance` (default `96` blocks).
  - Fix `FogRenderer.isInAnyPlayerVisualArea` so `players.isEmpty()` returns `false`, not `true`.
  - Set `display.setViewRange(0.5f)` on beam and fog display entities.

### 3.5 Asynchronous, Debounced SQLite Persistence (`PERF-09`)
- **Rule:** Never run `INSERT` / `UPDATE` SQLite queries synchronously on the main server thread during UI clicks or fader drags.
- **Enforcement:**
  - Queue dirty groups, cues, and fixtures into a debounced single-threaded background executor (`AdmxDatabase` async write queue), flushing immediately only on `onDisable()`.
  - Keep `busy_timeout` reasonable (`1000 ms`) and reuse or batch statements.

### 3.6 O(1) Group Counts, Low Allocations & Leak Prevention (`PERF-11` – `PERF-14`)
- **Rule (`PERF-11`):** Maintain cached group member counts or compute them once per tick (`O(1)` lookup per fixture/UI tab).
- **Rule (`PERF-13`):** Throttle beam and flame particle emission to every `2–3` ticks.
- **Rule (`PERF-14`):**
  - Remove stale `lastMatrices` entries in `EntityTracker` whenever an invalid entity is replaced.
  - Listen to `PlayerQuitEvent` to remove disconnected players' active holo consoles, eye visors, and placement state maps.

---

## 4. Correctness, Lifecycle & Hygiene Invariants (BUG-01 – BUG-09, HYG-01 – HYG-04)

1. **World Unload Safety (`BUG-01`):** Always check `loc != null && loc.isWorldLoaded()` **before** calling `loc.getWorld()`. Wrap per-fixture rendering in `onRenderTick` in a `try/catch` with rate-limited error logging, and handle `WorldUnloadEvent`.
2. **Long-Uptime Float Precision (`BUG-02`):** Compute animation phases using `double` (`(double) tickCounter * speed`) wrapped modulo `2 * Math.PI` before casting to `float` so animations never stutter after days of server uptime.
3. **Monotonic Fixture Ordering (`BUG-03`):** Re-index `fixtureOrder` (`0 .. n-1`) within a group when a fixture is removed so phase fanning (`memberIndex / (total - 1)`) stays normalized in `[-0.5, +0.5]`.
4. **Group Management & Validation (`BUG-04`):** Validate group IDs (`[a-z0-9_]{1,24}`), prevent `/admx group create` from silently overwriting existing groups, add `/admx group delete <id>`, and support cycling/selecting groups beyond the first 6.
5. **Chunk & Entity Load Lifecycle (`BUG-05`, `BUG-06`):** Check chunk loaded status for every fog cell anchor before submitting to `EntityTracker`, and use `EntitiesLoadEvent` alongside `ChunkLoadEvent` to clean up any stray `admx:rendered` entities.
6. **Scene Cue Timing & Perceptual Oklab (`BUG-08`, `BUG-09`):** Use each `SceneCue`'s configured `holdTicks` and `fadeTicks` in `SceneTimelineManager.tickSequence`, and apply proper sRGB $\leftrightarrow$ linear gamma transfer (`srgbToLinear` / `linearToSrgb`) in `Oklab`.
7. **Resource Pack Packaging (`HYG-01`, `HYG-03`):** Keep a Gradle task `packageResourcePack` that builds `ADMX_ResourcePack.zip` cleanly from `resourcepack/` (excluding `*.zip` and duplicate folders), and avoid redundant feather overrides when `setItemModel` is used.
8. **Symmetric 16-Bit Pan/Tilt DMX Encoding & Core DMX512 Bus (`BUG-10`, `CORE-06`):** Encode Pan (`[-270°, +270°]`) and Tilt (`[-135°, +135°]`) symmetrically around DMX center `32768` (`Coarse = 128`) in `DmxUniverse` so negative angles are never clipped to `0`, and route all fixtures through the unified 512-channel `DmxUniverse` bus (supporting `HTP`, `LTP`, `CONSOLE_EXCLUSIVE`, and `ARTNET_EXCLUSIVE` merge modes).

---

## 5. Verification Checklist Before Committing

1. `./gradlew test build` must pass 100% of unit and integration tests.
2. Verify all event listeners check permissions before mutating state.
3. Verify no `Interaction` entity is ever spawned at a player's coordinates.
4. Verify no `Location.getWorld()` call occurs without `Location.isWorldLoaded()`.
5. Verify no SQLite write runs synchronously on a UI click event.
