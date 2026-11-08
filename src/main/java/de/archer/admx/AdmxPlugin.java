package de.archer.admx;

import de.archer.admx.audio.AudioDspManager;
import de.archer.admx.audio.AutoShowProfile;
import de.archer.admx.audio.BeatSnapshot;
import de.archer.admx.audio.MusicGenre;
import de.archer.admx.audio.SmartAutoDirector;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.SceneTimelineManager;
import de.archer.admx.db.AdmxDatabase;
import de.archer.admx.dmx.ConsoleModelType;
import de.archer.admx.dmx.DmxConsoleRenderer;
import de.archer.admx.dmx.DmxUniverse;
import de.archer.admx.dmx.PlacedDmxConsole;
import de.archer.admx.flow.FlowConnection;
import de.archer.admx.flow.FlowNode;
import de.archer.admx.flow.FlowNodeType;
import de.archer.admx.flow.Flowboard;
import de.archer.admx.flow.FlowboardEngine;
import de.archer.admx.flow.FlowboardGui;
import de.archer.admx.placement.FixturePlacementManager;
import de.archer.admx.placement.FixtureStorage;
import de.archer.admx.render.beam.BeamMode;
import de.archer.admx.render.beam.HybridBeamRenderer;
import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.entity.EntityTracker;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import de.archer.admx.render.fixture.MovingHeadProfile;
import de.archer.admx.render.fixture.MovingHeadRenderer;
import de.archer.admx.render.fog.FogType;
import de.archer.admx.render.light.FakeLightManager;
import de.archer.admx.ui.ChestConsoleUi;
import de.archer.admx.ui.DmxWorkstationUi;
import de.archer.admx.ui.HoloConsoleUi;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hauptklasse des ADMX Lightshow-Plugins für Paper 26.3 und Java 25.
 */
public final class AdmxPlugin extends JavaPlugin implements Listener {

    private final FakeLightManager fakeLightManager = new FakeLightManager();
    private final MovingHeadRenderer movingHeadRenderer = new MovingHeadRenderer(
            new HybridBeamRenderer(),
            fakeLightManager,
            MovingHeadProfile.defaultBlockbenchModel(true)
    );
    private final SceneTimelineManager sceneTimeline = new SceneTimelineManager();
    private final FlowboardEngine flowboardEngine = new FlowboardEngine();
    private final AudioDspManager audioDspManager = new AudioDspManager();
    private final SmartAutoDirector smartAutoDirector = new SmartAutoDirector();
    private final DmxConsoleRenderer dmxConsoleRenderer = new DmxConsoleRenderer();

    private AdmxDatabase database;
    private FixtureStorage legacyStorage;
    private FixturePlacementManager placementManager;
    private ChestConsoleUi chestConsoleUi;
    private HoloConsoleUi holoConsoleUi;
    private DmxWorkstationUi dmxWorkstationUi;
    private FlowboardGui flowboardGui;
    private de.archer.admx.ui.SoundToLightChestUi soundToLightChestUi;
    private de.archer.admx.ui.AutoShowStudioChestUi autoShowStudioChestUi;
    private de.archer.admx.artnet.ArtNetBridgeManager artNetBridgeManager;
    private de.archer.admx.fx.CryoBazookaManager cryoBazookaManager;

    private final Map<String, FixtureGroup> fixtureGroups = new LinkedHashMap<>();
    private final Map<UUID, PlacedFixture> placedFixtures = new ConcurrentHashMap<>();
    private final Map<UUID, PlacedDmxConsole> placedConsoles = new ConcurrentHashMap<>();
    private final Map<String, Integer> groupFixtureCounts = new ConcurrentHashMap<>();
    private final Map<String, Flowboard> flowboards = new ConcurrentHashMap<>();
    private final Map<String, AutoShowProfile> autoShowProfiles = new ConcurrentHashMap<>();

    // Konfigurierbare Sicherheits- und Performance-Budgets (aus config.yml)
    private int maxFixturesPerWorld = 250;
    private int maxFogMachinesPerWorld = 12;
    private int maxConsolesPerWorld = 32;
    private long uiClickCooldownMs = 75L;
    private double renderDistanceBlocksSq = 96.0 * 96.0;
    private float beamMaxReach = 48.0f;
    private float maxStrobeHz = 10.0f;

    private int tickCounter = 0;
    private long lastRenderErrorLogMs = 0L;

    public record PlacedFixture(
            UUID id,
            String groupId,
            int fixtureOrder,
            Location location,
            float mountYaw,
            boolean ceilingMounted,
            FixtureType type,
            MovingHeadProfile profile
    ) {
        public PlacedFixture(
                UUID id,
                Location location,
                float mountYaw,
                boolean ceilingMounted,
                FixtureType type,
                MovingHeadProfile profile
        ) {
            this(id, "front_truss", 0, location, mountYaw, ceilingMounted, type, profile);
        }

        public PlacedFixture withOrder(int newOrder) {
            return new PlacedFixture(id, groupId, newOrder, location, mountYaw, ceilingMounted, type, profile);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigurationSettings();

        EntityTracker.setPluginInstance(this);
        movingHeadRenderer.fogRenderer().setPluginInstance(this);

        // 1. Hochintegritäts-SQLite-Datenbank initialisieren (WAL-Modus + CRC32 + Foreign Keys)
        this.database = new AdmxDatabase(getDataFolder());
        this.fixtureGroups.putAll(this.database.loadOrSeedGroups());
        this.sceneTimeline.loadCues(this.database.loadSceneCues());
        this.placedFixtures.putAll(this.database.loadFixtures());
        this.placedConsoles.putAll(this.database.loadConsoles());
        this.autoShowProfiles.putAll(this.database.loadOrSeedAutoShowProfiles());
        if (!this.autoShowProfiles.isEmpty()) {
            this.smartAutoDirector.recallProfile(this.autoShowProfiles.values().iterator().next());
            this.smartAutoDirector.setAdaptiveLiveMode();
        }

        // Automatische Migration aus alter fixtures.json, falls die SQLite-Tabelle noch leer war
        this.legacyStorage = new FixtureStorage(getDataFolder());
        if (this.placedFixtures.isEmpty()) {
            Map<UUID, PlacedFixture> legacy = this.legacyStorage.load();
            if (!legacy.isEmpty()) {
                int idx = 0;
                for (PlacedFixture f : legacy.values()) {
                    String defGroup = f.type().isFogMachine() || f.type().isFlameProjector()
                            ? "fx_fog_pyro"
                            : "front_truss";
                    PlacedFixture migrated = new PlacedFixture(
                            f.id(), defGroup, idx++, f.location(), f.mountYaw(), f.ceilingMounted(), f.type(), f.profile()
                    );
                    this.placedFixtures.put(migrated.id(), migrated);
                    this.database.saveFixture(migrated);
                }
                getLogger().info("ADMX: " + legacy.size() + " Geräte aus fixtures.json in admx.db (SQLite) migriert.");
            }
        }

        rebuildAllGroupCountsAndOrders();
        EntityTracker.purgeDanglingEntities();

        this.placementManager = new FixturePlacementManager(this);
        this.chestConsoleUi = new ChestConsoleUi(this);
        this.holoConsoleUi = new HoloConsoleUi(this);
        this.dmxWorkstationUi = new DmxWorkstationUi(this);
        this.flowboardGui = new FlowboardGui(this);
        this.soundToLightChestUi = new de.archer.admx.ui.SoundToLightChestUi(this);
        this.autoShowStudioChestUi = new de.archer.admx.ui.AutoShowStudioChestUi(this);

        this.flowboards.putAll(this.database.loadFlowboards());
        if (this.flowboards.isEmpty()) {
            Flowboard demo = new Flowboard("main_show", "Intro Energy Show", 400, true);
            FlowNode n1 = demo.addNode(10, FlowNodeType.DIMMER_PULSE, "*", 40);
            FlowNode n2 = demo.addNode(12, FlowNodeType.COLOR_RAINBOW, "*", 60);
            FlowNode n3 = demo.addNode(14, FlowNodeType.MOVEMENT_SHAPE, "*", 80);
            FlowNode n4 = demo.addNode(16, FlowNodeType.STROBE_BLAST, "blinders", 30);
            if (n1 != null && n2 != null) {
                demo.addConnection(n1.nodeId(), n2.nodeId(), FlowConnection.ConnectionType.TRIGGER_ON_END);
            }
            if (n2 != null && n3 != null) {
                demo.addConnection(n2.nodeId(), n3.nodeId(), FlowConnection.ConnectionType.SYNC_START);
            }
            if (n3 != null && n4 != null) {
                demo.addConnection(n3.nodeId(), n4.nodeId(), FlowConnection.ConnectionType.TRIGGER_ON_END);
            }
            this.flowboards.put(demo.boardId(), demo);
            this.database.saveFlowboardAsync(demo);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(this.placementManager, this);
        getServer().getPluginManager().registerEvents(this.chestConsoleUi, this);
        getServer().getPluginManager().registerEvents(this.holoConsoleUi, this);
        getServer().getPluginManager().registerEvents(this.dmxWorkstationUi, this);
        getServer().getPluginManager().registerEvents(this.flowboardGui, this);
        getServer().getPluginManager().registerEvents(this.soundToLightChestUi, this);
        getServer().getPluginManager().registerEvents(this.autoShowStudioChestUi, this);
        this.cryoBazookaManager = new de.archer.admx.fx.CryoBazookaManager(this);
        getServer().getPluginManager().registerEvents(this.cryoBazookaManager, this);

        registerCommand("admx", new AdmxCommand(this));

        this.artNetBridgeManager = new de.archer.admx.artnet.ArtNetBridgeManager(this);
        this.artNetBridgeManager.start();

        de.archer.admx.boiler.AdmxBoilerIntegration.initialize(this);

        Bukkit.getScheduler().runTaskTimer(this, this::onRenderTick, 1L, 1L);

        getLogger().info("ADMX erfolgreich geladen! (" + placedFixtures.size() + " Geräte in "
                + fixtureGroups.size() + " SQLite-Gruppen bereit)");
    }

    private void loadConfigurationSettings() {
        var cfg = getConfig();
        this.maxFixturesPerWorld = Math.max(10, cfg.getInt("limits.max-fixtures-per-world", 250));
        this.maxFogMachinesPerWorld = Math.max(1, cfg.getInt("limits.max-fog-machines-per-world", 12));
        this.maxConsolesPerWorld = Math.clamp(cfg.getInt("limits.max-consoles-per-world", 32), 1, 128);
        int maxEntities = Math.max(250, cfg.getInt("limits.max-entities-total", 8000));
        int maxFogCells = Math.clamp(cfg.getInt("limits.max-fog-cells-per-machine", 180), 24, 600);
        this.uiClickCooldownMs = Math.clamp(cfg.getLong("limits.ui-click-cooldown-ms", 75L), 10L, 1000L);

        double renderDist = Math.clamp(cfg.getDouble("rendering.render-distance-blocks", 96.0), 16.0, 256.0);
        this.renderDistanceBlocksSq = renderDist * renderDist;
        this.beamMaxReach = (float) Math.clamp(cfg.getDouble("rendering.beam-max-reach", 48.0), 8.0, 96.0);
        this.maxStrobeHz = (float) Math.clamp(cfg.getDouble("rendering.max-strobe-hz", 10.0), 1.0, 10.0);
        int waveInterval = Math.clamp(cfg.getInt("rendering.fog-wave-update-interval-ticks", 4), 1, 20);

        EntityTracker.setMaxEntities(maxEntities);
        movingHeadRenderer.fogRenderer().setMaxCellsPerMachine(maxFogCells);
        movingHeadRenderer.fogRenderer().setWaveUpdateIntervalTicks(waveInterval);
    }

    @Override
    public void onDisable() {
        if (artNetBridgeManager != null) {
            artNetBridgeManager.close();
        }
        if (soundToLightChestUi != null) {
            soundToLightChestUi.stop();
        }
        if (autoShowStudioChestUi != null) {
            autoShowStudioChestUi.stop();
        }
        if (audioDspManager != null) {
            audioDspManager.close();
        }
        if (database != null) {
            database.saveAllGroups(fixtureGroups.values());
            database.close();
        }
        EntityTracker.removeAll();
        for (var world : Bukkit.getWorlds()) {
            fakeLightManager.clearWorld(world, Bukkit.getOnlinePlayers());
        }
        placedFixtures.clear();
        placedConsoles.clear();
        groupFixtureCounts.clear();
        getLogger().info("ADMX deaktiviert, Datenbank geschlossen und alle Display-Entities bereinigt.");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        purgeTaggedEntities(event.getChunk().getEntities());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        purgeTaggedEntities(event.getEntities());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        purgeTaggedEntities(event.getChunk().getEntities());
        fakeLightManager.clearChunk(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        World unloaded = event.getWorld();
        EntityTracker.removeByWorld(unloaded);
        fakeLightManager.clearWorld(unloaded, unloaded.getPlayers());
        if (holoConsoleUi != null) {
            holoConsoleUi.removeConsolesInWorld(unloaded);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID pid = event.getPlayer().getUniqueId();
        EntityTracker.clearPlayerState(pid);
        movingHeadRenderer.fogRenderer().clearPlayerVisor(pid);
    }

    private void purgeTaggedEntities(Entity[] entities) {
        for (Entity entity : entities) {
            if (entity.getScoreboardTags().contains(EntityTracker.ENTITY_TAG)) {
                entity.remove();
            }
        }
    }

    private void purgeTaggedEntities(List<Entity> entities) {
        for (Entity entity : entities) {
            if (entity.getScoreboardTags().contains(EntityTracker.ENTITY_TAG)) {
                entity.remove();
            }
        }
    }

    private void onRenderTick() {
        tickCounter++;

        var onlinePlayers = Bukkit.getOnlinePlayers();
        boolean hasArtNetClients = (artNetBridgeManager != null && artNetBridgeManager.isRunning() && artNetBridgeManager.getConnectedClientCount() > 0);

        // PERF-10: Wenn kein Spieler online ist und keine Art-Net Clients verbunden sind, pausiert die Pipeline
        if (onlinePlayers.isEmpty() && !hasArtNetClients) {
            if (EntityTracker.activeEntityCount() > 0) {
                EntityTracker.removeAll();
            }
            return;
        }

        try {
            // Szenen-Programmier-Sequencer aktualisieren (weiche Überblendung zwischen Cues)
            sceneTimeline.tickSequence(fixtureGroups);
            // Visuelle Flowboard-Engine aktualisieren (Signalfluss & Node-Modulation)
            flowboardEngine.tick(fixtureGroups);

            // Sound-to-Light Trigger für alle Gruppen im SOUND_ACTIVE Modus aktualisieren
            for (FixtureGroup g : fixtureGroups.values()) {
                if (g.controlMode() == ControlMode.SOUND_ACTIVE) {
                    audioDspManager.getSoundToLightEngine().updateGroupTrigger(g);
                }
            }

            // Core DMX512-Universum-Bus & Art-Net 4 Schnittstelle aktualisieren (Zero-Lag)
            List<PlacedFixture> allFixtures = new ArrayList<>(placedFixtures.values());
            if (artNetBridgeManager != null) {
                artNetBridgeManager.onRenderTick(allFixtures, this::resolveStateForFixture);
            }

            // Wenn kein Spieler online ist, müssen keine In-Game Display-Entities gerendert werden
            if (onlinePlayers.isEmpty()) {
                if (EntityTracker.activeEntityCount() > 0) {
                    EntityTracker.removeAll();
                }
                return;
            }

            movingHeadRenderer.scatteringField().clear();

            float hueSpot = (tickCounter * 1.5f) % 360f;
            float hueLaser = (tickCounter * 3.0f + 120f) % 360f;
            Oklab spotColor = Oklab.fromHSB(hueSpot, 0.85f, 1.0f);
            Oklab laserColor = Oklab.fromHSB(hueLaser, 0.95f, 1.0f);

            if (!allFixtures.isEmpty()) {

                // Pass 1: Rendere alle Licht-, Laser- und Pyro-Geräte in Sichtweite (inkl. DMX-Pult & Art-Net 4 Merge)
                for (int fixtureIdx = 0; fixtureIdx < allFixtures.size(); fixtureIdx++) {
                    PlacedFixture fixture = allFixtures.get(fixtureIdx);
                    if (fixture.type().isFogMachine()) {
                        continue;
                    }
                    Location loc = fixture.location();
                    if (!loc.isWorldLoaded()) {
                        continue;
                    }
                    World world = loc.getWorld();
                    if (world == null || !loc.isChunkLoaded() || !isAnyPlayerInRange(world, loc)) {
                        continue;
                    }

                    FixtureState generatorState = resolveStateForFixture(fixture, spotColor, laserColor);
                    FixtureState state = (artNetBridgeManager != null)
                            ? artNetBridgeManager.getUniverseManager().resolveMergedStateForFixture(fixtureIdx, generatorState, maxStrobeHz)
                            : generatorState;

                    movingHeadRenderer.render(
                            fixture.id(),
                            loc,
                            fixture.mountYaw(),
                            fixture.ceilingMounted(),
                            fixture.type(),
                            state,
                            fixture.type().beamMode(),
                            beamMaxReach,
                            fixture.profile(),
                            tickCounter
                    );
                }

                // Pass 2: Rendere alle Nebelmaschinen (ohne Cache-Wipe bei kurzen Dimmer-Pausen -> PERF-03)
                for (int fixtureIdx = 0; fixtureIdx < allFixtures.size(); fixtureIdx++) {
                    PlacedFixture fixture = allFixtures.get(fixtureIdx);
                    if (!fixture.type().isFogMachine()) {
                        continue;
                    }
                    Location loc = fixture.location();
                    if (!loc.isWorldLoaded()) {
                        continue;
                    }
                    World world = loc.getWorld();
                    if (world == null || !loc.isChunkLoaded() || !isAnyPlayerInRange(world, loc)) {
                        continue;
                    }

                    FixtureState generatorState = resolveStateForFixture(fixture, spotColor, laserColor);
                    FixtureState mergedFog = (artNetBridgeManager != null)
                            ? artNetBridgeManager.getUniverseManager().resolveMergedStateForFixture(fixtureIdx, generatorState, maxStrobeHz)
                            : generatorState;
                    // PERF-03: Nebelmaschinen dürfen niemals durch Strobe getriggert werden
                    FixtureState state = new FixtureState(0f, 0f, mergedFog.dimmer(), fixture.type().fogType().baseColor(), 30f, 0f);

                    movingHeadRenderer.render(
                            fixture.id(),
                            loc,
                            fixture.mountYaw(),
                            fixture.ceilingMounted(),
                            fixture.type(),
                            state,
                            fixture.type().beamMode(),
                            FogType.MAX_REACH_BLOCKS,
                            fixture.profile(),
                            tickCounter
                    );
                }

                // Pass 3: Spieler-Augen-Hologramm ("Sichtraub" im Nebel) aktualisieren
                movingHeadRenderer.fogRenderer().updatePlayerEyeVisors(
                        onlinePlayers,
                        movingHeadRenderer.scatteringField()
                );
            }

            // Pass 4: Interaktive 3D-Hologramm-Steuerpulte & platzierte 3D-DMX-Pulte rendern
            if (holoConsoleUi != null) {
                holoConsoleUi.renderAllConsoles();
            }
            if (!placedConsoles.isEmpty() && artNetBridgeManager != null) {
                for (PlacedDmxConsole console : placedConsoles.values()) {
                    Location cLoc = console.location();
                    if (!cLoc.isWorldLoaded()) continue;
                    World cWorld = cLoc.getWorld();
                    if (cWorld == null || !cLoc.isChunkLoaded() || !isAnyPlayerInRange(cWorld, cLoc)) continue;
                    DmxUniverse linkedUniverse = artNetBridgeManager.getUniverseManager().getUniverse(console.universeId());
                    dmxConsoleRenderer.render(console, linkedUniverse, this);
                }
            }

            EntityTracker.removeUnused();
        } catch (Exception ex) {
            long now = System.currentTimeMillis();
            if (now - lastRenderErrorLogMs > 5000L) {
                lastRenderErrorLogMs = now;
                getLogger().warning("[ADMX] Ausnahmefehler in onRenderTick abgefangen (Loop läuft weiter): " + ex.getMessage());
            }
        }
    }

    private boolean isAnyPlayerInRange(World world, Location loc) {
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return false;
        }
        for (Player p : players) {
            if (p.getLocation().distanceSquared(loc) <= renderDistanceBlocksSq) {
                return true;
            }
        }
        return false;
    }

    public FixtureState resolveStateForFixture(PlacedFixture fixture) {
        float hueSpot = (tickCounter * 1.5f) % 360f;
        float hueLaser = (tickCounter * 3.0f + 120f) % 360f;
        return resolveStateForFixture(fixture, Oklab.fromHSB(hueSpot, 0.85f, 1.0f), Oklab.fromHSB(hueLaser, 0.95f, 1.0f));
    }

    private FixtureState resolveStateForFixture(PlacedFixture fixture, Oklab autoSpotColor, Oklab autoLaserColor) {
        FixtureGroup group = fixtureGroups.get(fixture.groupId());
        int totalInGroup = Math.max(1, countFixturesInGroup(fixture.groupId()));
        if (group == null || group.controlMode() == ControlMode.AUTO_SHOW) {
            return smartAutoDirector.computeState(fixture, totalInGroup, tickCounter, audioDspManager.getSnapshot());
        }
        if (group.controlMode() == ControlMode.SOUND_ACTIVE) {
            return audioDspManager.getSoundToLightEngine().computeFixtureState(
                    group, fixture.fixtureOrder(), totalInGroup, (int) tickCounter, fixture.type()
            );
        }
        return group.computeMemberState(fixture.type(), fixture.fixtureOrder(), totalInGroup, tickCounter);
    }

    private FixtureState computeFixtureState(FixtureType type, Oklab spotColor, Oklab laserColor) {
        double wrapped = (tickCounter % 200_000L);
        return switch (type) {
            case MOVING_HEAD_SPOT -> {
                float pan = (float) (Math.sin(wrapped * 0.04) * 110f);
                float tilt = (float) (Math.cos(wrapped * 0.06) * 35f - 10f);
                yield new FixtureState(pan, tilt, 0.85f, spotColor, 24f, 0f);
            }
            case MOVING_HEAD_BEAM -> {
                float pan = (float) (Math.sin(wrapped * 0.04 + 1.2) * 110f);
                float tilt = (float) (Math.cos(wrapped * 0.06 + 1.2) * 35f - 10f);
                yield new FixtureState(pan, tilt, 0.95f, spotColor, 4f, 0f);
            }
            case MOVING_HEAD_WASH -> {
                float pan = (float) (Math.sin(wrapped * 0.035) * 85f);
                float tilt = (float) (Math.cos(wrapped * 0.05) * 28f - 15f);
                yield new FixtureState(pan, tilt, 0.80f, spotColor, 52f, 0f);
            }
            case LASER_FAN_PROJECTOR -> {
                float scanPan = (float) (Math.sin(wrapped * 0.05) * 12f);
                float scanTilt = (float) (Math.sin(wrapped * 0.14) * 22f - 5f);
                float fanAngle = 48f + (float) (Math.sin(wrapped * 0.08) * 14f);
                yield new FixtureState(scanPan, scanTilt, 0.90f, laserColor, fanAngle, 0f);
            }
            case LASER_BEAM_PROJECTOR -> {
                float beamPan = (float) (Math.sin(wrapped * 0.22) * 18f);
                float beamTilt = (float) (Math.cos(wrapped * 0.31) * 14f - 4f);
                yield new FixtureState(beamPan, beamTilt, 0.95f, laserColor, 4f, 0f);
            }
            case MOVING_LASER_HEAD -> {
                float pan = (float) (Math.sin(wrapped * 0.05 + 0.6) * 125f);
                float tilt = (float) (Math.cos(wrapped * 0.08) * 32f - 8f);
                float fanAngle = 44f + (float) (Math.cos(wrapped * 0.10) * 12f);
                yield new FixtureState(pan, tilt, 0.92f, laserColor, fanAngle, 0f);
            }
            case BLINDER_2LITE -> {
                float dimmer = (float) Math.max(0.0, Math.sin(wrapped * 0.08) * 0.95);
                yield new FixtureState(0f, -15f, dimmer, Oklab.fromRGB(Color.fromRGB(255, 214, 150)), 65f, 0f);
            }
            case STROBE_BAR -> {
                boolean flash = (tickCounter % 8) < 2;
                yield new FixtureState(0f, -20f, flash ? 1.0f : 0.0f, Oklab.fromRGB(Color.WHITE), 50f, 10f);
            }
            case FOG_MACHINE_HAZER, FOG_MACHINE_LOW_FOG -> new FixtureState(0f, 0f, 1.0f, type.fogType().baseColor(), 30f, 0f);
            case STAGE_FLAME -> {
                boolean burst = (tickCounter % 60) < 16;
                yield new FixtureState(0f, 0f, burst ? 1.0f : 0.0f, Oklab.fromRGB(Color.fromRGB(255, 140, 20)), 20f, 0f);
            }
        };
    }

    public long getUiClickCooldownMs() {
        return uiClickCooldownMs;
    }

    public AdmxDatabase getDatabase() {
        return database;
    }

    public SceneTimelineManager getSceneTimeline() {
        return sceneTimeline;
    }

    public FixturePlacementManager getPlacementManager() {
        return placementManager;
    }

    public ChestConsoleUi getChestConsoleUi() {
        return chestConsoleUi;
    }

    public HoloConsoleUi getHoloConsoleUi() {
        return holoConsoleUi;
    }

    public de.archer.admx.fx.CryoBazookaManager getCryoBazookaManager() {
        return cryoBazookaManager;
    }

    public Map<String, FixtureGroup> getFixtureGroups() {
        return fixtureGroups;
    }

    public FixtureGroup getFixtureGroup(String groupId) {
        return fixtureGroups.get(groupId);
    }

    /**
     * Liefert in O(1) die Anzahl der Geräte in einer Gruppe (PERF-11).
     */
    public int countFixturesInGroup(String groupId) {
        return groupFixtureCounts.getOrDefault(groupId, 0);
    }

    /**
     * Prüft, ob die konfigurierbaren Welt-Limits für Geräte oder Nebelmaschinen erreicht sind (SEC-03).
     *
     * @return {@code null}, wenn Platzierung erlaubt ist, sonst eine erklärende Fehlermeldung.
     */
    public String checkPlacementLimits(World world, FixtureType type) {
        if (world == null) {
            return "Zielwelt ist nicht geladen.";
        }
        int worldTotal = 0;
        int worldFog = 0;
        for (PlacedFixture f : placedFixtures.values()) {
            if (f.location().isWorldLoaded() && world.equals(f.location().getWorld())) {
                worldTotal++;
                if (f.type().isFogMachine()) {
                    worldFog++;
                }
            }
        }
        if (worldTotal >= maxFixturesPerWorld) {
            return "Maximales Gerätelimit für diese Welt erreicht (" + maxFixturesPerWorld + " Geräte).";
        }
        if (type.isFogMachine() && worldFog >= maxFogMachinesPerWorld) {
            return "Maximales Nebelmaschinen-Limit für diese Welt erreicht (" + maxFogMachinesPerWorld + " Nebelmaschinen).";
        }
        return null;
    }

    public PlacedFixture addPlacedFixtureWithGroup(
            UUID id,
            String groupId,
            Location location,
            float mountYaw,
            boolean ceilingMounted,
            FixtureType type,
            MovingHeadProfile profile
    ) {
        String validGroup = fixtureGroups.containsKey(groupId) ? groupId : "front_truss";
        int nextOrder = countFixturesInGroup(validGroup);

        Location cleanLoc = location.clone();
        cleanLoc.setYaw(0f);
        cleanLoc.setPitch(0f);

        PlacedFixture created = new PlacedFixture(id, validGroup, nextOrder, cleanLoc, mountYaw, ceilingMounted, type, profile);
        placedFixtures.put(id, created);
        groupFixtureCounts.put(validGroup, nextOrder + 1);
        if (database != null) {
            database.saveFixtureAsync(created);
        }
        return created;
    }

    public void addPlacedFixture(
            UUID id,
            Location location,
            float mountYaw,
            boolean ceilingMounted,
            FixtureType type,
            MovingHeadProfile profile
    ) {
        String defaultGroup = (type.isFogMachine() || type.isFlameProjector()) ? "fx_fog_pyro" : "front_truss";
        addPlacedFixtureWithGroup(id, defaultGroup, location, mountYaw, ceilingMounted, type, profile);
    }

    public void addDemoFixture(UUID id, Location location, BeamMode mode, MovingHeadProfile profile) {
        FixtureType type = switch (mode) {
            case SPOT_CONE -> FixtureType.MOVING_HEAD_SPOT;
            case LASER_FAN -> FixtureType.LASER_FAN_PROJECTOR;
            case LASER_NEEDLE -> FixtureType.LASER_BEAM_PROJECTOR;
        };
        addPlacedFixture(id, location, location.getYaw(), false, type, profile);
    }

    /**
     * Entfernt gezielt das nächstgelegene ADMX-Gerät inkl. seiner eigenen Display-Entities
     * ({@link EntityTracker#removeByRoot(Object)}) und normiert die {@code fixtureOrder}-Indizes
     * der betroffenen Gruppe neu (SEC-06 & BUG-03).
     */
    public boolean removeNearestFixture(Location target, double maxRadius) {
        if (target == null || !target.isWorldLoaded() || target.getWorld() == null) {
            return false;
        }
        World targetWorld = target.getWorld();
        UUID closestId = null;
        double closestDistSq = maxRadius * maxRadius;

        for (PlacedFixture fixture : placedFixtures.values()) {
            Location fLoc = fixture.location();
            if (!fLoc.isWorldLoaded() || !targetWorld.equals(fLoc.getWorld())) continue;
            double distSq = fLoc.distanceSquared(target);
            if (distSq <= closestDistSq) {
                closestDistSq = distSq;
                closestId = fixture.id();
            }
        }

        if (closestId == null) {
            return false;
        }

        PlacedFixture removed = placedFixtures.remove(closestId);
        EntityTracker.removeByRoot(closestId);
        movingHeadRenderer.fogRenderer().clearCache(closestId);
        if (database != null) {
            database.deleteFixtureAsync(closestId);
        }
        fakeLightManager.clearFixtureLight(closestId, targetWorld, targetWorld.getPlayers());

        if (removed != null) {
            resequenceGroupOrders(removed.groupId());
        }
        return true;
    }

    private void rebuildAllGroupCountsAndOrders() {
        groupFixtureCounts.clear();
        for (String gid : fixtureGroups.keySet()) {
            resequenceGroupOrders(gid);
        }
    }

    /**
     * Stellt sicher, dass nach dem Entfernen eines Geräts alle verbleibenden Geräte der Gruppe
     * wieder lückenlose Indizes {@code 0 .. N-1} besitzen, damit Phasen-Fanning symmetrisch bleibt (BUG-03).
     */
    private void resequenceGroupOrders(String groupId) {
        List<PlacedFixture> members = new ArrayList<>();
        for (PlacedFixture f : placedFixtures.values()) {
            if (f.groupId().equals(groupId)) {
                members.add(f);
            }
        }
        members.sort(Comparator.comparingInt(PlacedFixture::fixtureOrder));
        groupFixtureCounts.put(groupId, members.size());

        for (int i = 0; i < members.size(); i++) {
            PlacedFixture m = members.get(i);
            if (m.fixtureOrder() != i) {
                PlacedFixture updated = m.withOrder(i);
                placedFixtures.put(updated.id(), updated);
                if (database != null) {
                    database.saveFixtureAsync(updated);
                }
            }
        }
    }

    public FlowboardEngine getFlowboardEngine() {
        return flowboardEngine;
    }

    public FlowboardGui getFlowboardGui() {
        return flowboardGui;
    }

    public Map<String, Flowboard> getFlowboards() {
        return flowboards;
    }

    public Flowboard getFlowboard(String boardId) {
        return flowboards.get(boardId);
    }

    public Flowboard getOrCreateFlowboard(String boardId, String name) {
        return flowboards.computeIfAbsent(boardId, id -> {
            Flowboard b = new Flowboard(id, name);
            database.saveFlowboardAsync(b);
            return b;
        });
    }

    public AudioDspManager getAudioDspManager() {
        return audioDspManager;
    }

    public de.archer.admx.audio.SoundToLightEngine getSoundToLightEngine() {
        return audioDspManager.getSoundToLightEngine();
    }

    public de.archer.admx.ui.SoundToLightChestUi getSoundToLightChestUi() {
        return soundToLightChestUi;
    }

    public de.archer.admx.artnet.ArtNetBridgeManager getArtNetBridgeManager() {
        return artNetBridgeManager;
    }

    public SmartAutoDirector getSmartAutoDirector() {
        return smartAutoDirector;
    }

    public de.archer.admx.ui.AutoShowStudioChestUi getAutoShowStudioChestUi() {
        return autoShowStudioChestUi;
    }

    public Map<String, AutoShowProfile> getAutoShowProfiles() {
        return autoShowProfiles;
    }

    public AutoShowProfile getAutoShowProfile(String profileId) {
        if (profileId == null) return null;
        return autoShowProfiles.get(profileId.toLowerCase(java.util.Locale.ROOT));
    }

    public void saveAutoShowProfile(AutoShowProfile profile) {
        if (profile == null) return;
        autoShowProfiles.put(profile.profileId(), profile);
        if (database != null) {
            database.saveAutoShowProfileAsync(profile);
        }
    }

    public DmxWorkstationUi getDmxWorkstationUi() {
        return dmxWorkstationUi;
    }

    public Map<UUID, PlacedFixture> getPlacedFixtures() {
        return placedFixtures;
    }

    public Map<UUID, PlacedDmxConsole> getPlacedConsoles() {
        return placedConsoles;
    }

    public PlacedDmxConsole getPlacedConsole(UUID consoleId) {
        return placedConsoles.get(consoleId);
    }

    public String checkConsolePlacementLimits(World world) {
        if (world == null) {
            return "Zielwelt ist nicht geladen.";
        }
        int worldConsoles = 0;
        for (PlacedDmxConsole c : placedConsoles.values()) {
            if (c.location().isWorldLoaded() && world.equals(c.location().getWorld())) {
                worldConsoles++;
            }
        }
        if (worldConsoles >= maxConsolesPerWorld) {
            return "Maximales 3D-DMX-Pult-Limit für diese Welt erreicht (" + maxConsolesPerWorld + " Pulte).";
        }
        return null;
    }

    public PlacedDmxConsole addPlacedConsole(
            UUID id,
            ConsoleModelType modelType,
            int universeId,
            Location location,
            float mountYaw,
            boolean useCmd,
            UUID ownerId
    ) {
        Location cleanLoc = location.clone();
        cleanLoc.setYaw(0f);
        cleanLoc.setPitch(0f);
        PlacedDmxConsole console = new PlacedDmxConsole(id, modelType, universeId, 0, cleanLoc, mountYaw, useCmd, ownerId);
        placedConsoles.put(id, console);
        if (database != null) {
            database.saveConsoleAsync(console);
        }
        return console;
    }

    public boolean removeNearestConsole(Location target, double maxRadius) {
        if (target == null || !target.isWorldLoaded() || target.getWorld() == null) {
            return false;
        }
        World targetWorld = target.getWorld();
        UUID closestId = null;
        double closestDistSq = maxRadius * maxRadius;

        for (PlacedDmxConsole console : placedConsoles.values()) {
            Location cLoc = console.location();
            if (!cLoc.isWorldLoaded() || !targetWorld.equals(cLoc.getWorld())) continue;
            double distSq = cLoc.distanceSquared(target);
            if (distSq <= closestDistSq) {
                closestDistSq = distSq;
                closestId = console.id();
            }
        }

        if (closestId == null) {
            return false;
        }

        placedConsoles.remove(closestId);
        EntityTracker.removeByRoot(closestId);
        if (database != null) {
            database.deleteConsoleAsync(closestId);
        }
        return true;
    }

    public void clearDemoFixtures() {
        placedFixtures.clear();
        placedConsoles.clear();
        groupFixtureCounts.clear();
        movingHeadRenderer.fogRenderer().clearAll();
        for (World world : Bukkit.getWorlds()) {
            fakeLightManager.clearWorld(world, world.getPlayers());
        }
        if (database != null) {
            database.deleteAllFixtures();
            database.deleteAllConsoles();
        }
    }
}
