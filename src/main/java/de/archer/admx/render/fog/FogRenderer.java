package de.archer.admx.render.fog;

import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.entity.EntityTracker;
import de.archer.admx.render.entity.RenderEntity;
import de.archer.admx.render.entity.RenderGroup;
import de.archer.admx.render.entity.RenderItem;
import de.archer.admx.render.geom.TextDisplayTriangle;
import de.archer.admx.render.light.FakeLightManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Physikalisch-volumetrischer Nebel-Renderer für ADMX (Kaltnebel & Raumnebel):
 * <ul>
 *   <li>Berechnet das füllbare Raumvolumen per 3D/2.5D-Flood-Fill bis maximal 30 Blöcke (gestaffelt gecacht).</li>
 *   <li>Gibt am Ausstoßpunkt leichte Hologramm-Nebelpartikel ab.</li>
 *   <li>Spannt im Raum eine zusammenhängende, fluide Wellen-Oberfläche aus verbundenen Dreiecken
 *       ({@link TextDisplayTriangle}) auf, die sich organisch an Wände und Raumgröße anpasst (mit konfigurierbarem
 *       Zellen-Budget und mehrtickiger Interpolation für maximale Server- & Client-Performance).</li>
 *   <li>Eliminiert harte Kanten bei Erreichen der 30-Block-Maximalgrenze, indem im Sichtbereich des
 *       Spielers dieselben Hologramm-Nebelpartikel wie beim Ausstoß generiert werden.</li>
 *   <li>Bricht einfallendes Scheinwerfer- und Laserlicht im Nebel (Tyndall-Scattering & Shader-Licht).</li>
 *   <li>Wendet extrem ressourcenschonend ein kamera-gekoppeltes, spieler-exklusives HologramDisplay
 *       direkt auf die Augen von Spielern im Nebel an, um authentischen Sichtraub zu erzeugen.</li>
 * </ul>
 */
public final class FogRenderer {

    /**
     * Gitter-Abstand (in Blöcken) für das verbundene Dreiecks-Wellennetz.
     * 3 Blöcke reduziert die Entity-Anzahl um über 55 % bei identisch flüssiger Wellenoberfläche.
     */
    public static final int WAVE_GRID_STEP = 3;

    private static final float FOG_DISPLAY_VIEW_RANGE = 0.48f;
    private static final int NOZZLE_PUFF_COUNT = 5;
    private static final int MAX_BOUNDARY_PUFFS = 12;
    private static final int VOLUME_RECOMPUTE_INTERVAL_TICKS = 80;
    private static final int[][] NEIGHBOR_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final Component EMPTY_TEXT = Component.text(" ");
    private static final Display.Brightness FULL_BRIGHTNESS = new Display.Brightness(15, 15);

    public record ActiveFogInstance(
            UUID fixtureId,
            Location nozzleLocation,
            Vector3f forwardDirection,
            FogType fogType,
            FogRoomVolume roomVolume,
            float currentSpreadRadius,
            float dimmer
    ) {
    }

    private static final class CachedVolume {
        final FogRoomVolume volume;
        final long computedAtTick;
        final int originBlockX;
        final int originBlockY;
        final int originBlockZ;

        CachedVolume(FogRoomVolume volume, long computedAtTick, int ox, int oy, int oz) {
            this.volume = volume;
            this.computedAtTick = computedAtTick;
            this.originBlockX = ox;
            this.originBlockY = oy;
            this.originBlockZ = oz;
        }
    }

    private static final class CachedWaveMesh {
        final RenderGroup waveGroup;
        final long builtAtTick;

        CachedWaveMesh(RenderGroup waveGroup, long builtAtTick) {
            this.waveGroup = waveGroup;
            this.builtAtTick = builtAtTick;
        }
    }

    private final Map<UUID, CachedVolume> volumeCache = new ConcurrentHashMap<>();
    private final Map<UUID, CachedWaveMesh> waveMeshCache = new ConcurrentHashMap<>();
    private final Map<UUID, Float> expansionRadiusByFixture = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveFogInstance> activeInstances = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> visorIdsByPlayer = new ConcurrentHashMap<>();

    private volatile int maxCellsPerMachine = 180;
    private volatile int waveUpdateIntervalTicks = 4;
    private volatile Plugin pluginInstance;

    public void setPluginInstance(Plugin plugin) {
        this.pluginInstance = plugin;
    }

    public void setMaxCellsPerMachine(int maxCells) {
        this.maxCellsPerMachine = Math.clamp(maxCells, 24, 600);
    }

    public void setWaveUpdateIntervalTicks(int intervalTicks) {
        this.waveUpdateIntervalTicks = Math.clamp(intervalTicks, 1, 20);
    }

    /**
     * Deaktiviert eine Nebelmaschine im Rendering (Entfernt Wellen-Mesh und aktive Instanz),
     * behält jedoch das berechnete Raumvolumen im Cache für sofortigen Wiedereinsatz (PERF-03/PERF-06).
     */
    public void deactivateInstance(UUID fixtureId) {
        activeInstances.remove(fixtureId);
        waveMeshCache.remove(fixtureId);
        expansionRadiusByFixture.remove(fixtureId);
    }

    public void clearCache(UUID fixtureId) {
        volumeCache.remove(fixtureId);
        waveMeshCache.remove(fixtureId);
        expansionRadiusByFixture.remove(fixtureId);
        activeInstances.remove(fixtureId);
    }

    public void clearPlayerVisor(UUID playerId) {
        UUID visorId = visorIdsByPlayer.remove(playerId);
        if (visorId != null) {
            EntityTracker.removeByRoot(visorId);
        }
    }

    public void clearAll() {
        volumeCache.clear();
        waveMeshCache.clear();
        expansionRadiusByFixture.clear();
        activeInstances.clear();
        visorIdsByPlayer.clear();
    }

    public Collection<ActiveFogInstance> activeInstances() {
        return activeInstances.values();
    }

    /**
     * Berechnet und erstellt die komplette RenderGroup (Düsen-Hologramm-Partikel + verbundenes
     * Dreiecks-Wellen-Mesh + 30-Block-Rand-Hologramm-Partikel) für eine Nebelmaschine.
     */
    public RenderGroup buildFogRenderGroup(
            UUID fixtureId,
            Location nozzleLocation,
            Vector3f forwardDirection,
            FogType fogType,
            float dimmer,
            long tickCounter,
            LightScatteringField scatteringField,
            FakeLightManager fakeLightManager
    ) {
        RenderGroup fogGroup = new RenderGroup();
        World world = nozzleLocation.getWorld();
        if (world == null) {
            return fogGroup;
        }

        float clampedDimmer = Math.clamp(dimmer, 0f, 1f);

        // 1. Fortschreitende Ausbreitung im Raum bis max. 30 Blöcke
        float prevRadius = expansionRadiusByFixture.getOrDefault(fixtureId, 2.5f);
        float growthRate = (fogType == FogType.LOW_FOG) ? 0.12f : 0.16f;
        float currentRadius = Math.min(FogType.MAX_REACH_BLOCKS, prevRadius + growthRate);
        expansionRadiusByFixture.put(fixtureId, currentRadius);

        // 2. Physikalische Raumberechnung (gestaffelt gecacht, alle 80 Ticks aktualisiert -> PERF-06)
        int bx = nozzleLocation.getBlockX();
        int by = nozzleLocation.getBlockY();
        int bz = nozzleLocation.getBlockZ();

        int staggerOffset = Math.floorMod(fixtureId.hashCode(), VOLUME_RECOMPUTE_INTERVAL_TICKS);
        CachedVolume cached = volumeCache.get(fixtureId);
        boolean shouldRecompute = cached == null
                || cached.originBlockX != bx
                || cached.originBlockY != by
                || cached.originBlockZ != bz
                || ((tickCounter - cached.computedAtTick) >= VOLUME_RECOMPUTE_INTERVAL_TICKS
                    && ((tickCounter + staggerOffset) % VOLUME_RECOMPUTE_INTERVAL_TICKS == 0));

        if (shouldRecompute) {
            FogRoomVolume computed = FogRoomVolume.compute(
                    bx,
                    by,
                    bz,
                    fogType,
                    FogType.MAX_REACH_BLOCKS,
                    BlockPassabilityProvider.forWorld(world)
            );
            cached = new CachedVolume(computed, tickCounter, bx, by, bz);
            volumeCache.put(fixtureId, cached);
        }

        FogRoomVolume roomVolume = cached.volume;
        activeInstances.put(
                fixtureId,
                new ActiveFogInstance(fixtureId, nozzleLocation.clone(), new Vector3f(forwardDirection), fogType, roomVolume, currentRadius, clampedDimmer)
        );

        // 3. Stufe A: Ausstoß an der Düse als leichte Hologramm-Render-Partikel
        buildNozzleHologramPuffs(
                fogGroup,
                nozzleLocation,
                forwardDirection,
                fogType,
                clampedDimmer,
                tickCounter,
                scatteringField
        );

        // 4. Stufe B: Verbundene Dreiecks-Wellen-Oberfläche (gecacht über waveUpdateIntervalTicks -> PERF-01)
        int interval = this.waveUpdateIntervalTicks;
        CachedWaveMesh cachedWave = waveMeshCache.get(fixtureId);
        if (cachedWave == null || (tickCounter - cachedWave.builtAtTick) >= interval) {
            RenderGroup newWaveGroup = new RenderGroup();
            buildConnectedWaveMesh(
                    newWaveGroup,
                    fixtureId,
                    world,
                    roomVolume,
                    fogType,
                    currentRadius,
                    clampedDimmer,
                    tickCounter,
                    scatteringField,
                    interval
            );
            cachedWave = new CachedWaveMesh(newWaveGroup, tickCounter);
            waveMeshCache.put(fixtureId, cachedWave);
        }
        fogGroup.set("wave_mesh", cachedWave.waveGroup);

        // 5. Stufe C: Bei Erreichen der 30-Block-Maximalgröße ohne Wand -> Hologramm-Partikel im Spieler-Sichtbereich
        if (roomVolume.reachedMaxLimit() && currentRadius >= FogType.MAX_REACH_BLOCKS - 2.5f) {
            buildBoundaryHologramPuffs(
                    fogGroup,
                    world,
                    roomVolume,
                    fogType,
                    clampedDimmer,
                    tickCounter,
                    scatteringField
            );
        }

        return fogGroup;
    }

    /**
     * Erzeugt die ausgestoßenen Hologramm-Partikel entlang des Düsenstrahls.
     */
    private void buildNozzleHologramPuffs(
            RenderGroup group,
            Location nozzleLoc,
            Vector3f forward,
            FogType fogType,
            float dimmer,
            long tickCounter,
            LightScatteringField scatteringField
    ) {
        World world = nozzleLoc.getWorld();
        if (world == null) return;

        Vector3f fwd = new Vector3f(forward);
        if (fwd.lengthSquared() < 1e-6f) {
            fwd.set(0f, 0f, -1f);
        } else {
            fwd.normalize();
        }

        Vector3f up = new Vector3f(0f, 1f, 0f);
        Vector3f right = new Vector3f(fwd).cross(up);
        if (right.lengthSquared() < 1e-6f) {
            right.set(1f, 0f, 0f);
        } else {
            right.normalize();
        }
        Vector3f orthoUp = new Vector3f(right).cross(fwd).normalize();

        Location staticNozzleAnchor = new Location(world, nozzleLoc.getX(), nozzleLoc.getY(), nozzleLoc.getZ());
        long wrappedTicks = tickCounter % 200_000L;

        for (int i = 0; i < NOZZLE_PUFF_COUNT; i++) {
            double speed = 0.045;
            double rawPhase = (wrappedTicks * speed) + (i / (double) NOZZLE_PUFF_COUNT);
            float phase = (float) (rawPhase - Math.floor(rawPhase));
            boolean justWrapped = phase < (float) (speed * 1.25);

            float dist = 0.25f + phase * 2.8f;
            float size = 0.18f + phase * 0.55f;

            float swirl = (float) Math.sin(wrappedTicks * 0.12 + i * 1.7) * (0.16f * phase);
            float verticalDrift = (fogType == FogType.LOW_FOG)
                    ? -0.18f * phase
                    : 0.28f * phase * phase;

            Vector3f relCenter = new Vector3f(
                    fwd.x * dist + right.x * swirl,
                    fwd.y * dist + verticalDrift,
                    fwd.z * dist + right.z * swirl
            );
            Vector3f worldCenter = new Vector3f(
                    (float) staticNozzleAnchor.getX() + relCenter.x,
                    (float) staticNozzleAnchor.getY() + relCenter.y,
                    (float) staticNozzleAnchor.getZ() + relCenter.z
            );

            float envelope = (float) Math.sin(phase * Math.PI);
            float basePuffAlpha = envelope * 0.22f * dimmer;
            LightScatteringField.ScatteringResult scattered = scatteringField.sampleAt(worldCenter, fogType.baseColor(), basePuffAlpha);

            RenderItem puffItem = createHologramFogPuff(
                    staticNozzleAnchor,
                    relCenter,
                    right,
                    orthoUp,
                    size,
                    (float) ((wrappedTicks * 0.05 + i) % (2.0 * Math.PI)),
                    scattered.refractedColor(),
                    scattered.refractedAlpha(),
                    justWrapped ? 0 : 1
            );
            group.set("nozzle_puff_" + i, puffItem);
        }
    }

    /**
     * Baut die zusammenhängende, fluide Wellen-Oberfläche aus miteinander verbundenen Dreiecken
     * ({@link TextDisplayTriangle}) über dem physikalisch berechneten Raumvolumen auf.
     */
    private void buildConnectedWaveMesh(
            RenderGroup group,
            UUID fixtureId,
            World world,
            FogRoomVolume roomVolume,
            FogType fogType,
            float currentRadius,
            float dimmer,
            long tickCounter,
            LightScatteringField scatteringField,
            int interpolationTicks
    ) {
        int ox = roomVolume.originX();
        int oz = roomVolume.originZ();
        int maxSteps = (int) Math.ceil(Math.min(currentRadius, FogType.MAX_REACH_BLOCKS) / WAVE_GRID_STEP);

        int layerCount = fogType.waveLayerCount();
        int cellBudget = this.maxCellsPerMachine;
        int cellsSpawned = 0;

        for (int layer = 0; layer < layerCount && cellsSpawned < cellBudget; layer++) {
            float layerOffset = fogType.baseLayerHeight() + layer * 1.45f;
            float layerAlphaScale = (layer == 0) ? 1.0f : 0.65f;
            float phaseOffset = layer * 1.8f;

            int gridDim = maxSteps * 2 + 2;
            Vector3f[][] vertices = new Vector3f[gridDim][gridDim];
            int[][] floors = new int[gridDim][gridDim];

            for (int gx = -maxSteps; gx <= maxSteps + 1; gx++) {
                for (int gz = -maxSteps; gz <= maxSteps + 1; gz++) {
                    int wx = ox + gx * WAVE_GRID_STEP;
                    int wz = oz + gz * WAVE_GRID_STEP;

                    if (!world.isChunkLoaded(wx >> 4, wz >> 4)) {
                        continue;
                    }

                    FogRoomVolume.ColumnCell cell = findNearestReachableColumn(roomVolume, wx, wz, currentRadius);
                    if (cell == null) {
                        continue;
                    }

                    if (cell.floorY() + layerOffset > cell.ceilingY() - 0.3f) {
                        continue;
                    }

                    float waveY = computeWaveHeight(
                            wx,
                            wz,
                            cell.floorY(),
                            cell.ceilingY(),
                            layerOffset,
                            fogType,
                            tickCounter,
                            phaseOffset
                    );
                    vertices[gx + maxSteps][gz + maxSteps] = new Vector3f(wx + 0.5f, waveY, wz + 0.5f);
                    floors[gx + maxSteps][gz + maxSteps] = cell.floorY();
                }
            }

            for (int gx = -maxSteps; gx <= maxSteps && cellsSpawned < cellBudget; gx++) {
                for (int gz = -maxSteps; gz <= maxSteps && cellsSpawned < cellBudget; gz++) {
                    int ix = gx + maxSteps;
                    int iz = gz + maxSteps;

                    Vector3f v00 = vertices[ix][iz];
                    Vector3f v10 = vertices[ix + 1][iz];
                    Vector3f v01 = vertices[ix][iz + 1];
                    Vector3f v11 = vertices[ix + 1][iz + 1];

                    int f00 = floors[ix][iz];
                    int f10 = floors[ix + 1][iz];
                    int f01 = floors[ix][iz + 1];
                    int f11 = floors[ix + 1][iz + 1];

                    String cellKey = "wave_L" + layer + "_" + gx + "_" + gz;

                    if (v00 != null && v10 != null && v01 != null && v11 != null) {
                        int minF = Math.min(Math.min(f00, f10), Math.min(f01, f11));
                        int maxF = Math.max(Math.max(f00, f10), Math.max(f01, f11));
                        if (maxF - minF > 0) {
                            continue;
                        }

                        if (!world.isChunkLoaded(((int) Math.floor(v00.x)) >> 4, ((int) Math.floor(v00.z)) >> 4)) {
                            continue;
                        }

                        Vector3f cellCenter = new Vector3f(v00).add(v10).add(v01).add(v11).mul(0.25f);
                        float distFromOrigin = (float) Math.hypot(cellCenter.x - (ox + 0.5f), cellCenter.z - (oz + 0.5f));
                        if (distFromOrigin > currentRadius) {
                            continue;
                        }

                        float edgeFade = Math.clamp((currentRadius - distFromOrigin) / 3.5f, 0.18f, 1.0f);
                        float baseCellAlpha = fogType.surfaceBaseAlpha() * layerAlphaScale * edgeFade * dimmer;

                        LightScatteringField.ScatteringResult scattered = scatteringField.sampleAt(
                                cellCenter,
                                fogType.baseColor(),
                                baseCellAlpha
                        );

                        Location cellAnchor = new Location(world, v00.x, f00, v00.z);
                        Vector3f rel00 = new Vector3f(0f, v00.y - f00, 0f);
                        Vector3f rel01 = new Vector3f(v01.x - v00.x, v01.y - f00, v01.z - v00.z);
                        Vector3f rel10 = new Vector3f(v10.x - v00.x, v10.y - f00, v10.z - v00.z);

                        RenderItem waveQuad = buildConnectedWaveCell(
                                cellAnchor,
                                rel00,
                                rel01,
                                rel10,
                                scattered.refractedColor(),
                                scattered.refractedAlpha(),
                                interpolationTicks
                        );
                        group.set(cellKey, waveQuad);
                        cellsSpawned++;
                    } else {
                        Vector3f a = null, b = null, c = null;
                        int baseF = 0;
                        if (v00 != null && v01 != null && v10 != null && f00 == f01 && f00 == f10) {
                            a = v00; b = v01; c = v10; baseF = f00;
                        } else if (v11 != null && v10 != null && v01 != null && f11 == f10 && f11 == f01) {
                            a = v11; b = v10; c = v01; baseF = f11;
                        } else if (v00 != null && v01 != null && v11 != null && f00 == f01 && f00 == f11) {
                            a = v01; b = v11; c = v00; baseF = f01;
                        } else if (v00 != null && v11 != null && v10 != null && f00 == f11 && f00 == f10) {
                            a = v10; b = v00; c = v11; baseF = f10;
                        }

                        if (a != null && world.isChunkLoaded(((int) Math.floor(a.x)) >> 4, ((int) Math.floor(a.z)) >> 4)) {
                            Vector3f triCenter = new Vector3f(a).add(b).add(c).div(3f);
                            float distFromOrigin = (float) Math.hypot(triCenter.x - (ox + 0.5f), triCenter.z - (oz + 0.5f));
                            if (distFromOrigin <= currentRadius) {
                                float edgeFade = Math.clamp((currentRadius - distFromOrigin) / 3.5f, 0.18f, 1.0f);
                                float baseCellAlpha = fogType.surfaceBaseAlpha() * layerAlphaScale * edgeFade * dimmer;
                                LightScatteringField.ScatteringResult scattered = scatteringField.sampleAt(
                                        triCenter,
                                        fogType.baseColor(),
                                        baseCellAlpha
                                );
                                Location triAnchor = new Location(world, a.x, baseF, a.z);
                                Vector3f ra = new Vector3f(0f, a.y - baseF, 0f);
                                Vector3f rb = new Vector3f(b.x - a.x, b.y - baseF, b.z - a.z);
                                Vector3f rc = new Vector3f(c.x - a.x, c.y - baseF, c.z - a.z);
                                group.set(cellKey + "_corner", buildSingleUpwardTriangle(
                                        triAnchor, ra, rb, rc, scattered.refractedColor(), scattered.refractedAlpha(), interpolationTicks
                                ));
                                cellsSpawned++;
                            }
                        }
                    }
                }
            }
        }
    }

    private FogRoomVolume.ColumnCell findNearestReachableColumn(
            FogRoomVolume volume,
            int wx,
            int wz,
            float currentRadius
    ) {
        FogRoomVolume.ColumnCell direct = volume.getColumn(wx, wz);
        if (direct != null && direct.distance() <= currentRadius) {
            return direct;
        }
        for (int[] off : NEIGHBOR_OFFSETS) {
            FogRoomVolume.ColumnCell neighbor = volume.getColumn(wx + off[0], wz + off[1]);
            if (neighbor != null && neighbor.distance() <= currentRadius) {
                return neighbor;
            }
        }
        return null;
    }

    public static float computeWaveHeight(
            int wx,
            int wz,
            int floorY,
            int ceilingY,
            float layerOffset,
            FogType fogType,
            long tickCounter,
            float phaseOffset
    ) {
        double freq = fogType.waveFrequency();
        float amp = fogType.waveAmplitude();
        // Berechne Phase in double mit Modulo, um Float-Präzisionsverlust nach langer Server-Laufzeit zu verhindern (BUG-02)
        double t = (tickCounter % 200_000L) * 0.045 + phaseOffset;

        // Additiv-separable 2D-Wellenfunktion: Garantiert y11 == y10 + y01 - y00 exakt an jedem Gitterpunkt,
        // sodass verbundene Dreieckspaare 100% koplanar, lückenlos und ohne Z-Fighting ineinander übergehen!
        float waveX = (float) (0.65 * Math.sin(wx * freq + t) + 0.35 * Math.cos(wx * freq * 1.6 - t * 0.7));
        float waveZ = (float) (0.65 * Math.cos(wz * freq - t * 0.85) + 0.35 * Math.sin(wz * freq * 1.4 + t * 0.6));
        float y = floorY + layerOffset + amp * 0.5f * (waveX + waveZ);

        return Math.clamp(y, floorY + 0.18f, ceilingY - 0.22f);
    }

    /**
     * Wenn die maximale Raumgröße von 30 Blöcken erreicht ist (ohne Wand), werden im Sichtbereich
     * der Spieler dieselben Hologramm-Nebelpartikel wie beim Ausstoß generiert, um jede harte Kante
     * vollständig aufzulösen.
     */
    private void buildBoundaryHologramPuffs(
            RenderGroup group,
            World world,
            FogRoomVolume roomVolume,
            FogType fogType,
            float dimmer,
            long tickCounter,
            LightScatteringField scatteringField
    ) {
        List<FogRoomVolume.ColumnCell> boundary = roomVolume.openBoundaryCells();
        if (boundary.isEmpty()) {
            return;
        }

        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return;
        }

        int spawnedPuffs = 0;
        int stride = Math.max(1, boundary.size() / MAX_BOUNDARY_PUFFS);
        long wrappedTicks = tickCounter % 200_000L;

        for (int idx = 0; idx < boundary.size() && spawnedPuffs < MAX_BOUNDARY_PUFFS; idx += stride) {
            FogRoomVolume.ColumnCell cell = boundary.get(idx);
            if (!world.isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) {
                continue;
            }

            double cx = cell.x() + 0.5;
            double cy = cell.floorY() + fogType.baseLayerHeight();
            double cz = cell.z() + 0.5;

            if (!isInAnyPlayerVisualArea(players, cx, cy, cz, 32.0)) {
                continue;
            }

            Location staticBoundaryAnchor = new Location(world, cx, cell.floorY(), cz);

            float phase = (float) (0.5 + 0.5 * Math.sin(wrappedTicks * 0.045 + idx * 0.7));
            float driftX = (float) Math.sin(wrappedTicks * 0.05 + idx) * 0.55f;
            float driftZ = (float) Math.cos(wrappedTicks * 0.045 + idx * 1.3) * 0.55f;
            float driftY = fogType.baseLayerHeight() + (float) Math.sin(wrappedTicks * 0.04 + idx) * 0.22f;

            Vector3f relPos = new Vector3f(driftX, driftY, driftZ);
            Vector3f worldPos = new Vector3f((float) cx + driftX, cell.floorY() + driftY, (float) cz + driftZ);
            Vector3f right = new Vector3f((float) Math.cos(idx), 0f, (float) Math.sin(idx));
            Vector3f up = new Vector3f(0f, 1f, 0f);

            float baseAlpha = fogType.surfaceBaseAlpha() * 0.75f * (0.4f + 0.6f * phase) * dimmer;
            LightScatteringField.ScatteringResult scattered = scatteringField.sampleAt(worldPos, fogType.baseColor(), baseAlpha);

            RenderItem boundaryPuff = createHologramFogPuff(
                    staticBoundaryAnchor,
                    relPos,
                    right,
                    up,
                    0.85f + 0.25f * phase,
                    (float) ((wrappedTicks * 0.025 + idx) % (2.0 * Math.PI)),
                    scattered.refractedColor(),
                    scattered.refractedAlpha(),
                    1
            );
            group.set("boundary_puff_" + idx, boundaryPuff);
            spawnedPuffs++;

            if (tickCounter % 4 == 0) {
                Particle edgeParticle = (fogType == FogType.LOW_FOG) ? Particle.CLOUD : Particle.CAMPFIRE_COSY_SMOKE;
                world.spawnParticle(edgeParticle, worldPos.x, worldPos.y, worldPos.z, 1, 0.35, 0.15, 0.35, 0.002);
            }
        }
    }

    private boolean isInAnyPlayerVisualArea(List<Player> players, double x, double y, double z, double maxViewDist) {
        if (players.isEmpty()) {
            return false; // PERF-10: Wenn kein Spieler in der Welt ist, keine Rand-Puffs spawnen!
        }
        double maxDistSq = maxViewDist * maxViewDist;
        for (Player player : players) {
            Location eye = player.getEyeLocation();
            double dx = x - eye.getX();
            double dy = y - eye.getY();
            double dz = z - eye.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxDistSq) {
                continue;
            }
            if (distSq < 9.0) {
                return true;
            }
            var look = eye.getDirection();
            double invLen = 1.0 / Math.sqrt(distSq);
            double dot = (dx * look.getX() + dy * look.getY() + dz * look.getZ()) * invLen;
            if (dot > -0.25) {
                return true;
            }
        }
        return false;
    }

    private RenderItem createHologramFogPuff(
            Location staticAnchor,
            Vector3f relCenter,
            Vector3f rightAxis,
            Vector3f upAxis,
            float radius,
            float rollAngleRad,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        float cos = (float) Math.cos(rollAngleRad);
        float sin = (float) Math.sin(rollAngleRad);

        Vector3f r = new Vector3f(rightAxis).mul(cos).add(new Vector3f(upAxis).mul(sin)).normalize().mul(radius);
        Vector3f u = new Vector3f(upAxis).mul(cos).sub(new Vector3f(rightAxis).mul(sin)).normalize().mul(radius);

        Vector3f p0 = new Vector3f(relCenter).sub(r).sub(u);
        Vector3f p1 = new Vector3f(relCenter).add(r).sub(u);
        Vector3f p2 = new Vector3f(relCenter).sub(r).add(u);

        return buildConnectedWaveCell(
                staticAnchor,
                p0,
                p1,
                p2,
                color,
                Math.clamp(alpha, 0.01f, 0.38f),
                interpolationTicks
        );
    }

    private RenderItem buildConnectedWaveCell(
            Location staticAnchor,
            Vector3f rel00,
            Vector3f rel01,
            Vector3f rel10,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        Matrix4f transform = TextDisplayTriangle.calculateConnectedTrianglePair(rel00, rel01, rel10);
        Color rgb = color.toRGB();
        int a = Math.clamp(Math.round(alpha * 255.0f), 0, 255);
        Color displayColor = Color.fromARGB(a, rgb.getRed(), rgb.getGreen(), rgb.getBlue());

        return new RenderEntity<>(
                TextDisplay.class,
                staticAnchor,
                display -> {
                    display.text(EMPTY_TEXT);
                    display.setBrightness(FULL_BRIGHTNESS);
                    display.setBackgroundColor(displayColor);
                    display.setShadowed(false);
                    display.setSeeThrough(false);
                    display.setViewRange(FOG_DISPLAY_VIEW_RANGE);
                    display.setTransformationMatrix(transform);
                    display.setInterpolationDuration(interpolationTicks);
                    display.setInterpolationDelay(0);
                },
                display -> {
                    if (!displayColor.equals(display.getBackgroundColor())) {
                        display.setBackgroundColor(displayColor);
                    }
                    EntityTracker.updateDisplayTransform(display, transform, interpolationTicks);
                }
        );
    }

    private RenderItem buildSingleUpwardTriangle(
            Location staticAnchor,
            Vector3f relA,
            Vector3f relB,
            Vector3f relC,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        RenderGroup group = new RenderGroup();
        Color rgb = color.toRGB();
        int a = Math.clamp(Math.round(alpha * 255.0f), 0, 255);
        Color displayColor = Color.fromARGB(a, rgb.getRed(), rgb.getGreen(), rgb.getBlue());

        TextDisplayTriangle tri = TextDisplayTriangle.calculate(relA, relB, relC);
        List<Matrix4f> pieces = tri.pieceTransforms();
        for (int i = 0; i < pieces.size(); i++) {
            Matrix4f m = new Matrix4f()
                    .translate(0f, i * 0.002f, 0f)
                    .mul(pieces.get(i));
            group.set("p_" + i, new RenderEntity<>(
                    TextDisplay.class,
                    staticAnchor,
                    display -> {
                        display.text(EMPTY_TEXT);
                        display.setBrightness(FULL_BRIGHTNESS);
                        display.setBackgroundColor(displayColor);
                        display.setShadowed(false);
                        display.setSeeThrough(false);
                        display.setViewRange(FOG_DISPLAY_VIEW_RANGE);
                        display.setTransformationMatrix(m);
                        display.setInterpolationDuration(interpolationTicks);
                        display.setInterpolationDelay(0);
                    },
                    display -> {
                        if (!displayColor.equals(display.getBackgroundColor())) {
                            display.setBackgroundColor(displayColor);
                        }
                        EntityTracker.updateDisplayTransform(display, m, interpolationTicks);
                    }
            ));
        }
        return group;
    }

    /**
     * Wendet extrem rechenleistungsschonend ein spieler-exklusives HologramDisplay direkt vor den Augen
     * eines Spielers an, sobald dieser im physikalisch berechneten Nebel steht ("Sichtraub"-Effekt inkl. Lichtbrechung).
     */
    public void updatePlayerEyeVisors(
            Collection<? extends Player> players,
            LightScatteringField scatteringField
    ) {
        if (activeInstances.isEmpty()) {
            if (!visorIdsByPlayer.isEmpty()) {
                for (UUID visorId : visorIdsByPlayer.values()) {
                    EntityTracker.removeByRoot(visorId);
                }
                visorIdsByPlayer.clear();
            }
            return;
        }

        for (Player player : players) {
            if (EntityTracker.isSafeMode(player.getUniqueId())) {
                UUID visorId = visorIdsByPlayer.remove(player.getUniqueId());
                if (visorId != null) {
                    EntityTracker.removeByRoot(visorId);
                }
                continue;
            }
            Location eyeLoc = player.getEyeLocation();
            World world = eyeLoc.getWorld();
            if (world == null) {
                continue;
            }

            float maxDensity = 0f;
            FogType dominantType = FogType.ROOM_HAZE;

            for (ActiveFogInstance inst : activeInstances.values()) {
                if (!world.equals(inst.nozzleLocation().getWorld())) {
                    continue;
                }
                float eyeDensity = inst.roomVolume().getFogDensityAt(
                        eyeLoc.getX(),
                        eyeLoc.getY(),
                        eyeLoc.getZ(),
                        inst.fogType(),
                        inst.currentSpreadRadius()
                ) * inst.dimmer();
                float bodyDensity = inst.roomVolume().getFogDensityAt(
                        eyeLoc.getX(),
                        eyeLoc.getY() - 0.85,
                        eyeLoc.getZ(),
                        inst.fogType(),
                        inst.currentSpreadRadius()
                ) * (0.45f * inst.dimmer());

                float combined = Math.max(eyeDensity, bodyDensity);
                if (combined > maxDensity) {
                    maxDensity = combined;
                    dominantType = inst.fogType();
                }
            }

            UUID visorGroupId = visorIdsByPlayer.computeIfAbsent(
                    player.getUniqueId(),
                    pid -> UUID.nameUUIDFromBytes(("admx:eye_fog_visor:" + pid).getBytes(StandardCharsets.UTF_8))
            );

            if (maxDensity <= 0.02f) {
                EntityTracker.removeByRoot(visorGroupId);
                continue;
            }

            float rawVisorAlpha = Math.clamp(maxDensity * dominantType.maxEyeVisorAlpha(), 0.04f, dominantType.maxEyeVisorAlpha());
            Vector3f eyeVec = new Vector3f((float) eyeLoc.getX(), (float) eyeLoc.getY(), (float) eyeLoc.getZ());

            LightScatteringField.ScatteringResult scattered = scatteringField.sampleAt(
                    eyeVec,
                    dominantType.baseColor(),
                    rawVisorAlpha
            );

            Color rgb = scattered.refractedColor().toRGB();
            int alphaByte = Math.clamp(Math.round(scattered.refractedAlpha() * 255f), 8, 185);
            Color argb = Color.fromARGB(alphaByte, rgb.getRed(), rgb.getGreen(), rgb.getBlue());

            var dir = eyeLoc.getDirection();
            Location visorAnchor = new Location(
                    world,
                    eyeLoc.getX() + dir.getX() * 0.36,
                    eyeLoc.getY() + dir.getY() * 0.36 - 0.18,
                    eyeLoc.getZ() + dir.getZ() * 0.36
            );

            Matrix4f visorMatrix = new Matrix4f()
                    .scale(14.0f, 7.0f, 1.0f)
                    .translate(-0.025f, -0.05f, 0f);

            Plugin pluginRef = this.pluginInstance;
            RenderGroup visorGroup = new RenderGroup();
            visorGroup.set("eye_visor", new RenderEntity<>(
                    TextDisplay.class,
                    visorAnchor,
                    display -> {
                        display.text(EMPTY_TEXT);
                        display.setBackgroundColor(argb);
                        display.setBillboard(Display.Billboard.CENTER);
                        display.setSeeThrough(true);
                        display.setShadowed(false);
                        display.setBrightness(FULL_BRIGHTNESS);
                        display.setViewRange(0.15f);
                        display.setTeleportDuration(1);
                        if (pluginRef != null) {
                            display.setVisibleByDefault(false);
                            player.showEntity(pluginRef, display);
                        }
                        display.setTransformationMatrix(visorMatrix);
                        display.setInterpolationDuration(1);
                        display.setInterpolationDelay(0);
                    },
                    display -> {
                        if (!argb.equals(display.getBackgroundColor())) {
                            display.setBackgroundColor(argb);
                        }
                        EntityTracker.updateDisplayTransform(display, visorMatrix, 1);
                    }
            ));
            visorGroup.submit(visorGroupId);
        }
    }
}
