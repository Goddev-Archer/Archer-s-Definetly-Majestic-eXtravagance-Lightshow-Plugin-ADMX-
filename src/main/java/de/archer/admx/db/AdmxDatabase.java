package de.archer.admx.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import de.archer.admx.AdmxPlugin;
import de.archer.admx.audio.AutoShowProfile;
import de.archer.admx.control.ControlMode;
import de.archer.admx.control.FixtureGroup;
import de.archer.admx.control.MovementPattern;
import de.archer.admx.control.SceneCue;
import de.archer.admx.dmx.ConsoleModelType;
import de.archer.admx.dmx.DmxUniverse;
import de.archer.admx.dmx.PlacedDmxConsole;
import de.archer.admx.flow.Flowboard;
import de.archer.admx.render.fixture.FixtureType;
import de.archer.admx.render.fixture.MovingHeadProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * Hochperformante relationale SQLite-Datenbank (WAL-Modus + Foreign-Key-Constraints + CRC32-Integritätsprüfung)
 * für ADMX-Gerätegruppen, eindeutig adressierbare Bühnengeräte und programmierte Szenen-Cues.
 * <p>
 * Unterstützt sowohl synchrone Persistierung (für Unit-Tests und Shutdown-Flush) als auch
 * entkoppeltes, gebündeltes Async-Schreiben ({@link #saveGroupAsync(FixtureGroup)}), damit
 * Live-UI-Interaktionen niemals den Main-Server-Thread blockieren (PERF-09).
 */
public final class AdmxDatabase implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type SNAPSHOT_MAP_TYPE = new TypeToken<Map<String, SceneCue.GroupSnapshot>>() {}.getType();

    private final File dbFile;
    private final String jdbcUrl;
    private Connection connection;

    private final Map<String, FixtureGroup> dirtyGroups = new ConcurrentHashMap<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final ScheduledExecutorService asyncWriter = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ADMX-SQLite-Writer");
        t.setDaemon(true);
        return t;
    });

    public AdmxDatabase(File dataFolder) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.dbFile = new File(dataFolder, "admx.db");
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        initializeDatabase();
    }

    private synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
            }
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL;");
                st.execute("PRAGMA synchronous = NORMAL;");
                st.execute("PRAGMA foreign_keys = ON;");
                st.execute("PRAGMA busy_timeout = 1000;");
            }
        }
        return connection;
    }

    private synchronized void initializeDatabase() {
        try {
            Connection conn = getConnection();
            try (Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery("PRAGMA quick_check;")) {
                    if (rs.next()) {
                        String status = rs.getString(1);
                        if (!"ok".equalsIgnoreCase(status)) {
                            Bukkit.getLogger().warning("[ADMX-DB] SQLite quick_check meldet: " + status);
                        }
                    }
                }

                st.execute("""
                        CREATE TABLE IF NOT EXISTS fixture_groups (
                            group_id TEXT PRIMARY KEY,
                            display_name TEXT NOT NULL,
                            icon_material TEXT NOT NULL,
                            accent_rgb INTEGER NOT NULL,
                            control_mode TEXT NOT NULL,
                            dimmer REAL NOT NULL,
                            pan_deg REAL NOT NULL,
                            tilt_deg REAL NOT NULL,
                            zoom_deg REAL NOT NULL,
                            strobe_hz REAL NOT NULL,
                            hue_deg REAL NOT NULL,
                            saturation REAL NOT NULL,
                            brightness REAL NOT NULL,
                            movement_pattern TEXT NOT NULL,
                            movement_speed REAL NOT NULL,
                            movement_amplitude REAL NOT NULL,
                            phase_spread REAL NOT NULL,
                            sound_config_json TEXT
                        );
                        """);
                try {
                    st.execute("ALTER TABLE fixture_groups ADD COLUMN sound_config_json TEXT;");
                } catch (SQLException ignored) {
                }

                st.execute("""
                        CREATE TABLE IF NOT EXISTS fixtures (
                            id TEXT PRIMARY KEY,
                            group_id TEXT NOT NULL,
                            fixture_order INTEGER NOT NULL,
                            world TEXT NOT NULL,
                            x REAL NOT NULL,
                            y REAL NOT NULL,
                            z REAL NOT NULL,
                            mount_yaw REAL NOT NULL,
                            ceiling_mounted INTEGER NOT NULL,
                            type TEXT NOT NULL,
                            use_cmd INTEGER NOT NULL,
                            integrity_crc INTEGER NOT NULL,
                            FOREIGN KEY (group_id) REFERENCES fixture_groups(group_id)
                                ON UPDATE CASCADE ON DELETE RESTRICT
                        );
                        """);

                st.execute("CREATE INDEX IF NOT EXISTS idx_fixtures_group ON fixtures(group_id, fixture_order);");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS scene_cues (
                            cue_index INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            fade_ticks INTEGER NOT NULL,
                            hold_ticks INTEGER NOT NULL,
                            snapshots_json TEXT NOT NULL
                        );
                        """);

                st.execute("""
                        CREATE TABLE IF NOT EXISTS flowboards (
                            board_id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            duration_ticks INTEGER NOT NULL,
                            loop_mode INTEGER NOT NULL,
                            nodes_json TEXT NOT NULL,
                            connections_json TEXT NOT NULL
                        );
                        """);

                st.execute("""
                        CREATE TABLE IF NOT EXISTS placed_consoles (
                            id TEXT PRIMARY KEY,
                            model_type TEXT NOT NULL,
                            universe_id INTEGER NOT NULL,
                            fader_bank INTEGER NOT NULL,
                            world TEXT NOT NULL,
                            x REAL NOT NULL,
                            y REAL NOT NULL,
                            z REAL NOT NULL,
                            mount_yaw REAL NOT NULL,
                            use_cmd INTEGER NOT NULL,
                            owner_id TEXT,
                            integrity_crc INTEGER NOT NULL
                        );
                        """);

                st.execute("""
                        CREATE TABLE IF NOT EXISTS auto_show_profiles (
                            profile_id TEXT PRIMARY KEY,
                            display_name TEXT NOT NULL,
                            slot_index INTEGER NOT NULL,
                            profile_json TEXT NOT NULL,
                            integrity_crc INTEGER NOT NULL
                        );
                        """);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[ADMX-DB] Fehler beim Initialisieren der SQLite-Datenbank: " + e.getMessage());
        }
    }

    /**
     * Lädt alle Gerätegruppen aus der Datenbank (und legt die 6 Standard-Bühnengruppen an, falls leer).
     */
    public synchronized Map<String, FixtureGroup> loadOrSeedGroups() {
        Map<String, FixtureGroup> groups = new LinkedHashMap<>();
        try {
            Connection conn = getConnection();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM fixture_groups ORDER BY rowid ASC;")) {
                while (rs.next()) {
                    FixtureGroup group = parseGroupRow(rs);
                    groups.put(group.groupId(), group);
                }
            }

            // Standard-Bühnengruppen initialisieren bzw. neue Kategorien (z.B. Upper-Truss) ergänzen
            for (FixtureGroup def : FixtureGroup.createDefaultGroups()) {
                if (!groups.containsKey(def.groupId())) {
                    saveGroup(def);
                    groups.put(def.groupId(), def);
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Laden der Gruppen: " + e.getMessage());
            for (FixtureGroup def : FixtureGroup.createDefaultGroups()) {
                groups.put(def.groupId(), def);
            }
        }
        return groups;
    }

    private FixtureGroup parseGroupRow(ResultSet rs) throws SQLException {
        String id = rs.getString("group_id");
        String name = rs.getString("display_name");
        Material mat;
        try {
            mat = Material.valueOf(rs.getString("icon_material"));
        } catch (IllegalArgumentException e) {
            mat = Material.CYAN_STAINED_GLASS;
        }
        int rgb = rs.getInt("accent_rgb");
        ControlMode mode;
        try {
            mode = ControlMode.valueOf(rs.getString("control_mode"));
        } catch (IllegalArgumentException e) {
            mode = ControlMode.MANUAL_LIVE;
        }
        MovementPattern pattern;
        try {
            pattern = MovementPattern.valueOf(rs.getString("movement_pattern"));
        } catch (IllegalArgumentException e) {
            pattern = MovementPattern.PAN_SWEEP;
        }

        FixtureGroup group = new FixtureGroup(
                id,
                name,
                mat,
                rgb,
                mode,
                rs.getFloat("dimmer"),
                rs.getFloat("pan_deg"),
                rs.getFloat("tilt_deg"),
                rs.getFloat("zoom_deg"),
                rs.getFloat("strobe_hz"),
                rs.getFloat("hue_deg"),
                rs.getFloat("saturation"),
                rs.getFloat("brightness"),
                pattern,
                rs.getFloat("movement_speed"),
                rs.getFloat("movement_amplitude"),
                rs.getFloat("phase_spread")
        );
        try {
            String soundJson = rs.getString("sound_config_json");
            if (soundJson != null && !soundJson.isBlank()) {
                group.setSoundConfig(de.archer.admx.audio.SoundToLightConfig.fromJsonString(soundJson));
            }
        } catch (SQLException ignored) {
        }
        return group;
    }

    /**
     * Merkt eine Gruppe für das gebündelte, asynchrone Hintergrund-Speichern vor (Debounced 250ms),
     * damit Live-Fader-Klicks im Chest-UI oder 3D-Holo-UI niemals den Main-Thread blockieren (PERF-09).
     */
    public void saveGroupAsync(FixtureGroup g) {
        if (g == null) return;
        dirtyGroups.put(g.groupId(), g);
        scheduleDirtyFlush();
    }

    /**
     * Merkt mehrere Gruppen für das gebündelte, asynchrone Hintergrund-Speichern vor.
     */
    public void saveAllGroupsAsync(Collection<FixtureGroup> groups) {
        if (groups == null || groups.isEmpty()) return;
        for (FixtureGroup g : groups) {
            dirtyGroups.put(g.groupId(), g);
        }
        scheduleDirtyFlush();
    }

    private void scheduleDirtyFlush() {
        if ( asyncWriter.isShutdown()) {
            flushDirtyGroupsSync();
            return;
        }
        if (flushScheduled.compareAndSet(false, true)) {
            asyncWriter.schedule(() -> {
                try {
                    flushDirtyGroupsSync();
                } finally {
                    flushScheduled.set(false);
                    if (!dirtyGroups.isEmpty()) {
                        scheduleDirtyFlush();
                    }
                }
            }, 250, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void flushDirtyGroupsSync() {
        if (dirtyGroups.isEmpty()) return;
        List<FixtureGroup> batch = new ArrayList<>(dirtyGroups.values());
        for (FixtureGroup g : batch) {
            dirtyGroups.remove(g.groupId());
        }
        saveAllGroups(batch);
    }

    public synchronized void saveGroup(FixtureGroup g) {
        String sql = """
                INSERT INTO fixture_groups (
                    group_id, display_name, icon_material, accent_rgb, control_mode,
                    dimmer, pan_deg, tilt_deg, zoom_deg, strobe_hz,
                    hue_deg, saturation, brightness,
                    movement_pattern, movement_speed, movement_amplitude, phase_spread,
                    sound_config_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(group_id) DO UPDATE SET
                    display_name = excluded.display_name,
                    icon_material = excluded.icon_material,
                    accent_rgb = excluded.accent_rgb,
                    control_mode = excluded.control_mode,
                    dimmer = excluded.dimmer,
                    pan_deg = excluded.pan_deg,
                    tilt_deg = excluded.tilt_deg,
                    zoom_deg = excluded.zoom_deg,
                    strobe_hz = excluded.strobe_hz,
                    hue_deg = excluded.hue_deg,
                    saturation = excluded.saturation,
                    brightness = excluded.brightness,
                    movement_pattern = excluded.movement_pattern,
                    movement_speed = excluded.movement_speed,
                    movement_amplitude = excluded.movement_amplitude,
                    phase_spread = excluded.phase_spread,
                    sound_config_json = excluded.sound_config_json;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, g.groupId());
            ps.setString(2, g.displayName());
            ps.setString(3, g.iconMaterial().name());
            ps.setInt(4, g.accentRgb());
            ps.setString(5, g.controlMode().name());
            ps.setFloat(6, g.dimmer());
            ps.setFloat(7, g.panDegrees());
            ps.setFloat(8, g.tiltDegrees());
            ps.setFloat(9, g.zoomDegrees());
            ps.setFloat(10, g.strobeHz());
            ps.setFloat(11, g.hueDegrees());
            ps.setFloat(12, g.saturation());
            ps.setFloat(13, g.brightness());
            ps.setString(14, g.movementPattern().name());
            ps.setFloat(15, g.movementSpeed());
            ps.setFloat(16, g.movementAmplitude());
            ps.setFloat(17, g.phaseSpread());
            ps.setString(18, g.getSoundConfig() != null ? g.getSoundConfig().toJson().toString() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Speichern der Gruppe " + g.groupId() + ": " + e.getMessage());
        }
    }

    public synchronized boolean deleteGroup(String groupId) {
        dirtyGroups.remove(groupId);
        try (PreparedStatement ps = getConnection().prepareStatement("DELETE FROM fixture_groups WHERE group_id = ?;")) {
            ps.setString(1, groupId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Löschen der Gruppe " + groupId + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized void saveAllGroups(Collection<FixtureGroup> groups) {
        try {
            Connection conn = getConnection();
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (FixtureGroup g : groups) {
                    saveGroup(g);
                }
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Transaktionsfehler beim Speichern aller Gruppen: " + e.getMessage());
        }
    }

    public void saveFixtureAsync(AdmxPlugin.PlacedFixture fixture) {
        if (asyncWriter.isShutdown()) {
            saveFixture(fixture);
        } else {
            asyncWriter.execute(() -> saveFixture(fixture));
        }
    }

    /**
     * Speichert ein einzelnes Gerät transaktionssicher inkl. CRC32-Integritätssignatur.
     */
    public synchronized void saveFixture(AdmxPlugin.PlacedFixture fixture) {
        World world = fixture.location().isWorldLoaded() ? fixture.location().getWorld() : null;
        String worldName = (world != null) ? world.getName() : "world";
        long crc = computeFixtureCrc(
                fixture.id(),
                fixture.groupId(),
                fixture.fixtureOrder(),
                worldName,
                fixture.location().getX(),
                fixture.location().getY(),
                fixture.location().getZ(),
                fixture.type().name()
        );

        String sql = """
                INSERT INTO fixtures (
                    id, group_id, fixture_order, world, x, y, z,
                    mount_yaw, ceiling_mounted, type, use_cmd, integrity_crc
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    group_id = excluded.group_id,
                    fixture_order = excluded.fixture_order,
                    world = excluded.world,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    mount_yaw = excluded.mount_yaw,
                    ceiling_mounted = excluded.ceiling_mounted,
                    type = excluded.type,
                    use_cmd = excluded.use_cmd,
                    integrity_crc = excluded.integrity_crc;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, fixture.id().toString());
            ps.setString(2, fixture.groupId());
            ps.setInt(3, fixture.fixtureOrder());
            ps.setString(4, worldName);
            ps.setDouble(5, fixture.location().getX());
            ps.setDouble(6, fixture.location().getY());
            ps.setDouble(7, fixture.location().getZ());
            ps.setFloat(8, fixture.mountYaw());
            ps.setInt(9, fixture.ceilingMounted() ? 1 : 0);
            ps.setString(10, fixture.type().name());
            ps.setInt(11, fixture.profile().useCustomModelData() ? 1 : 0);
            ps.setLong(12, crc);
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Speichern von Gerät " + fixture.id() + ": " + e.getMessage());
        }
    }

    public void deleteFixtureAsync(UUID fixtureId) {
        if (asyncWriter.isShutdown()) {
            deleteFixture(fixtureId);
        } else {
            asyncWriter.execute(() -> deleteFixture(fixtureId));
        }
    }

    public synchronized void deleteFixture(UUID fixtureId) {
        try (PreparedStatement ps = getConnection().prepareStatement("DELETE FROM fixtures WHERE id = ?;")) {
            ps.setString(1, fixtureId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Löschen von Gerät " + fixtureId + ": " + e.getMessage());
        }
    }

    public synchronized void deleteAllFixtures() {
        try (Statement st = getConnection().createStatement()) {
            st.executeUpdate("DELETE FROM fixtures;");
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Leeren der Geräte-Tabelle: " + e.getMessage());
        }
    }

    public synchronized Map<UUID, AdmxPlugin.PlacedFixture> loadFixtures() {
        Map<UUID, AdmxPlugin.PlacedFixture> result = new LinkedHashMap<>();
        String sql = "SELECT * FROM fixtures ORDER BY group_id ASC, fixture_order ASC;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                String groupId = rs.getString("group_id");
                int order = Math.max(0, rs.getInt("fixture_order"));
                String worldName = rs.getString("world");
                double x = rs.getDouble("x");
                double y = rs.getDouble("y");
                double z = rs.getDouble("z");
                float mountYaw = rs.getFloat("mount_yaw");
                boolean ceiling = rs.getInt("ceiling_mounted") != 0;
                String typeName = rs.getString("type");
                boolean useCmd = rs.getInt("use_cmd") != 0;
                long storedCrc = rs.getLong("integrity_crc");

                long expectedCrc = computeFixtureCrc(id, groupId, order, worldName, x, y, z, typeName);
                if (storedCrc != expectedCrc) {
                    Bukkit.getLogger().warning("[ADMX-DB] CRC32-Abweichung bei Gerät " + id
                            + " erkannt (gespeichert=" + storedCrc + ", berechnet=" + expectedCrc + ").");
                }

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    continue;
                }

                FixtureType type;
                try {
                    type = FixtureType.valueOf(typeName);
                } catch (IllegalArgumentException e) {
                    type = FixtureType.MOVING_HEAD_SPOT;
                }

                Location loc = new Location(world, x, y, z, 0f, 0f);
                MovingHeadProfile profile = MovingHeadProfile.defaultBlockbenchModel(useCmd);

                result.put(id, new AdmxPlugin.PlacedFixture(
                        id,
                        groupId,
                        order,
                        loc,
                        mountYaw,
                        ceiling,
                        type,
                        profile
                ));
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Laden der Geräte: " + e.getMessage());
        }
        return result;
    }

    public void saveSceneCueAsync(SceneCue cue) {
        if (asyncWriter.isShutdown()) {
            saveSceneCue(cue);
        } else {
            asyncWriter.execute(() -> saveSceneCue(cue));
        }
    }

    public synchronized void saveSceneCue(SceneCue cue) {
        String sql = """
                INSERT INTO scene_cues (cue_index, name, fade_ticks, hold_ticks, snapshots_json)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(cue_index) DO UPDATE SET
                    name = excluded.name,
                    fade_ticks = excluded.fade_ticks,
                    hold_ticks = excluded.hold_ticks,
                    snapshots_json = excluded.snapshots_json;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, cue.cueIndex());
            ps.setString(2, cue.name());
            ps.setInt(3, cue.fadeTicks());
            ps.setInt(4, cue.holdTicks());
            ps.setString(5, GSON.toJson(cue.groupSnapshots(), SNAPSHOT_MAP_TYPE));
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Speichern von SceneCue " + cue.cueIndex() + ": " + e.getMessage());
        }
    }

    public synchronized List<SceneCue> loadSceneCues() {
        List<SceneCue> list = new ArrayList<>();
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM scene_cues ORDER BY cue_index ASC;")) {
            while (rs.next()) {
                int idx = Math.clamp(rs.getInt("cue_index"), 0, 7);
                String name = rs.getString("name");
                int fade = Math.clamp(rs.getInt("fade_ticks"), 1, 1200);
                int hold = Math.clamp(rs.getInt("hold_ticks"), 1, 6000);
                String json = rs.getString("snapshots_json");
                Map<String, SceneCue.GroupSnapshot> snaps = GSON.fromJson(json, SNAPSHOT_MAP_TYPE);
                if (snaps != null) {
                    list.add(new SceneCue(idx, name, fade, hold, snaps));
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Laden der SceneCues: " + e.getMessage());
        }
        return list;
    }

    public void saveFlowboardAsync(Flowboard board) {
        if (board == null) return;
        if (asyncWriter.isShutdown()) {
            saveFlowboard(board);
        } else {
            asyncWriter.execute(() -> saveFlowboard(board));
        }
    }

    public synchronized void saveFlowboard(Flowboard board) {
        if (board == null) return;
        String sql = """
                INSERT INTO flowboards (board_id, name, duration_ticks, loop_mode, nodes_json, connections_json)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(board_id) DO UPDATE SET
                    name = excluded.name,
                    duration_ticks = excluded.duration_ticks,
                    loop_mode = excluded.loop_mode,
                    nodes_json = excluded.nodes_json,
                    connections_json = excluded.connections_json;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, board.boardId());
            ps.setString(2, board.name());
            ps.setInt(3, board.durationTicks());
            ps.setInt(4, board.isLoopMode() ? 1 : 0);
            ps.setString(5, board.serializeNodesJson());
            ps.setString(6, board.serializeConnectionsJson());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Speichern von Flowboard " + board.boardId() + ": " + e.getMessage());
        }
    }

    public synchronized Map<String, Flowboard> loadFlowboards() {
        Map<String, Flowboard> map = new LinkedHashMap<>();
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM flowboards;")) {
            while (rs.next()) {
                String id = rs.getString("board_id");
                String name = rs.getString("name");
                int dur = Math.clamp(rs.getInt("duration_ticks"), Flowboard.MIN_SHOW_DURATION_TICKS, Flowboard.MAX_SHOW_DURATION_TICKS);
                boolean loop = rs.getInt("loop_mode") != 0;
                String nodesJson = rs.getString("nodes_json");
                String connsJson = rs.getString("connections_json");

                Flowboard b = new Flowboard(id, name, dur, loop);
                b.populateFromJson(nodesJson, connsJson);
                map.put(id, b);
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Laden der Flowboards: " + e.getMessage());
        }
        return map;
    }

    public synchronized void deleteFlowboard(String boardId) {
        try (PreparedStatement ps = getConnection().prepareStatement("DELETE FROM flowboards WHERE board_id = ?;")) {
            ps.setString(1, boardId);
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Löschen von Flowboard " + boardId + ": " + e.getMessage());
        }
    }

    public void saveConsoleAsync(PlacedDmxConsole console) {
        if (console == null) return;
        if (asyncWriter.isShutdown()) {
            saveConsole(console);
        } else {
            asyncWriter.execute(() -> saveConsole(console));
        }
    }

    public synchronized void saveConsole(PlacedDmxConsole console) {
        if (console == null) return;
        World world = console.location().isWorldLoaded() ? console.location().getWorld() : null;
        String worldName = (world != null) ? world.getName() : "world";
        long crc = computeConsoleCrc(
                console.id(),
                console.modelType().name(),
                console.universeId(),
                worldName,
                console.location().getX(),
                console.location().getY(),
                console.location().getZ()
        );

        String sql = """
                INSERT INTO placed_consoles (
                    id, model_type, universe_id, fader_bank, world, x, y, z,
                    mount_yaw, use_cmd, owner_id, integrity_crc
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    model_type = excluded.model_type,
                    universe_id = excluded.universe_id,
                    fader_bank = excluded.fader_bank,
                    world = excluded.world,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    mount_yaw = excluded.mount_yaw,
                    use_cmd = excluded.use_cmd,
                    owner_id = excluded.owner_id,
                    integrity_crc = excluded.integrity_crc;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, console.id().toString());
            ps.setString(2, console.modelType().name());
            ps.setInt(3, console.universeId());
            ps.setInt(4, console.faderBank());
            ps.setString(5, worldName);
            ps.setDouble(6, console.location().getX());
            ps.setDouble(7, console.location().getY());
            ps.setDouble(8, console.location().getZ());
            ps.setFloat(9, console.mountYaw());
            ps.setInt(10, console.useCustomModelData() ? 1 : 0);
            ps.setString(11, console.ownerId() != null ? console.ownerId().toString() : null);
            ps.setLong(12, crc);
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Speichern von DMX-Pult " + console.id() + ": " + e.getMessage());
        }
    }

    public void deleteConsoleAsync(UUID consoleId) {
        if (consoleId == null) return;
        if (asyncWriter.isShutdown()) {
            deleteConsole(consoleId);
        } else {
            asyncWriter.execute(() -> deleteConsole(consoleId));
        }
    }

    public synchronized void deleteConsole(UUID consoleId) {
        if (consoleId == null) return;
        try (PreparedStatement ps = getConnection().prepareStatement("DELETE FROM placed_consoles WHERE id = ?;")) {
            ps.setString(1, consoleId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Löschen von DMX-Pult " + consoleId + ": " + e.getMessage());
        }
    }

    public synchronized void deleteAllConsoles() {
        try (Statement st = getConnection().createStatement()) {
            st.executeUpdate("DELETE FROM placed_consoles;");
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Leeren der placed_consoles-Tabelle: " + e.getMessage());
        }
    }

    public synchronized Map<UUID, PlacedDmxConsole> loadConsoles() {
        Map<UUID, PlacedDmxConsole> result = new LinkedHashMap<>();
        String sql = "SELECT * FROM placed_consoles ORDER BY rowid ASC;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                String modelName = rs.getString("model_type");
                int universeId = Math.clamp(rs.getInt("universe_id"), 0, DmxUniverse.MAX_UNIVERSES - 1);
                int faderBank = Math.clamp(rs.getInt("fader_bank"), 0, 63);
                String worldName = rs.getString("world");
                double x = rs.getDouble("x");
                double y = rs.getDouble("y");
                double z = rs.getDouble("z");
                float mountYaw = rs.getFloat("mount_yaw");
                boolean useCmd = rs.getInt("use_cmd") != 0;
                String rawOwner = rs.getString("owner_id");
                long storedCrc = rs.getLong("integrity_crc");

                long expectedCrc = computeConsoleCrc(id, modelName, universeId, worldName, x, y, z);
                if (storedCrc != expectedCrc) {
                    Bukkit.getLogger().warning("[ADMX-DB] CRC32-Abweichung bei DMX-Pult " + id
                            + " erkannt (gespeichert=" + storedCrc + ", berechnet=" + expectedCrc + ").");
                }

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    continue;
                }

                ConsoleModelType modelType;
                try {
                    modelType = ConsoleModelType.valueOf(modelName);
                } catch (IllegalArgumentException e) {
                    modelType = ConsoleModelType.GRAND_CONSOLE;
                }

                UUID ownerId = null;
                if (rawOwner != null && !rawOwner.isBlank()) {
                    try {
                        ownerId = UUID.fromString(rawOwner);
                    } catch (IllegalArgumentException ignored) {
                    }
                }

                Location loc = new Location(world, x, y, z, 0f, 0f);
                result.put(id, new PlacedDmxConsole(id, modelType, universeId, faderBank, loc, mountYaw, useCmd, ownerId));
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Laden der DMX-Pulte: " + e.getMessage());
        }
        return result;
    }

    public static long computeConsoleCrc(
            UUID id,
            String modelType,
            int universeId,
            String world,
            double x,
            double y,
            double z
    ) {
        CRC32 crc = new CRC32();
        String payload = id + "|" + modelType + "|" + universeId + "|" + world + "|"
                + String.format(Locale.ROOT, "%.4f|%.4f|%.4f", x, y, z);
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    public void saveAutoShowProfileAsync(AutoShowProfile profile) {
        if (profile == null) return;
        if (asyncWriter.isShutdown()) {
            saveAutoShowProfile(profile);
        } else {
            asyncWriter.execute(() -> saveAutoShowProfile(profile));
        }
    }

    public synchronized void saveAutoShowProfile(AutoShowProfile profile) {
        if (profile == null) return;
        long crc = profile.computeIntegrityCrc();
        String sql = """
                INSERT INTO auto_show_profiles (
                    profile_id, display_name, slot_index, profile_json, integrity_crc
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(profile_id) DO UPDATE SET
                    display_name = excluded.display_name,
                    slot_index = excluded.slot_index,
                    profile_json = excluded.profile_json,
                    integrity_crc = excluded.integrity_crc;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, profile.profileId());
            ps.setString(2, profile.displayName());
            ps.setInt(3, profile.slotIndex());
            ps.setString(4, profile.toJson());
            ps.setLong(5, crc);
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Speichern von AutoShowProfile " + profile.profileId() + ": " + e.getMessage());
        }
    }

    public synchronized Map<String, AutoShowProfile> loadOrSeedAutoShowProfiles() {
        Map<String, AutoShowProfile> map = new LinkedHashMap<>();
        try {
            Connection conn = getConnection();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM auto_show_profiles ORDER BY slot_index ASC, profile_id ASC;")) {
                while (rs.next()) {
                    String json = rs.getString("profile_json");
                    long storedCrc = rs.getLong("integrity_crc");
                    AutoShowProfile profile = AutoShowProfile.fromJson(json);
                    long expectedCrc = profile.computeIntegrityCrc();
                    if (storedCrc != expectedCrc) {
                        Bukkit.getLogger().warning("[ADMX-DB] CRC32-Abweichung bei AutoShowProfile " + profile.profileId()
                                + " erkannt (gespeichert=" + storedCrc + ", berechnet=" + expectedCrc + ").");
                    }
                    map.put(profile.profileId(), profile);
                }
            }
            if (map.isEmpty()) {
                for (AutoShowProfile seed : AutoShowProfile.createDefaultSeedProfiles()) {
                    saveAutoShowProfile(seed);
                    map.put(seed.profileId(), seed);
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Laden der AutoShowProfiles: " + e.getMessage());
            for (AutoShowProfile seed : AutoShowProfile.createDefaultSeedProfiles()) {
                map.put(seed.profileId(), seed);
            }
        }
        return map;
    }

    public void deleteAutoShowProfileAsync(String profileId) {
        if (profileId == null || profileId.isBlank()) return;
        if (asyncWriter.isShutdown()) {
            deleteAutoShowProfile(profileId);
        } else {
            asyncWriter.execute(() -> deleteAutoShowProfile(profileId));
        }
    }

    public synchronized boolean deleteAutoShowProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) return false;
        try (PreparedStatement ps = getConnection().prepareStatement("DELETE FROM auto_show_profiles WHERE profile_id = ?;")) {
            ps.setString(1, profileId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[ADMX-DB] Fehler beim Löschen von AutoShowProfile " + profileId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Berechnet die CRC32-Prüfsumme mit Locale.ROOT, damit länderspezifische Dezimaltrennzeichen
     * (z. B. Komma unter de_DE) niemals zu inkonsistenten Signaturen führen (SEC-08).
     */
    public static long computeFixtureCrc(
            UUID id,
            String groupId,
            int order,
            String world,
            double x,
            double y,
            double z,
            String type
    ) {
        CRC32 crc = new CRC32();
        String payload = id + "|" + groupId + "|" + order + "|" + world + "|"
                + String.format(Locale.ROOT, "%.4f|%.4f|%.4f|", x, y, z) + type;
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    @Override
    public synchronized void close() {
        asyncWriter.shutdown();
        try {
            asyncWriter.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        flushDirtyGroupsSync();
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }
}
