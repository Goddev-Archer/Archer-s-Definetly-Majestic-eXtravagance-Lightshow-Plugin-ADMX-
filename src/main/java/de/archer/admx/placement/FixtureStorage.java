package de.archer.admx.placement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import de.archer.admx.AdmxPlugin;
import de.archer.admx.render.fixture.FixtureType;
import de.archer.admx.render.fixture.MovingHeadProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistiert platzierte ADMX-Geräte als sauberes JSON auf der Festplatte (plugins/ADMX/fixtures.json).
 * Dadurch bleiben Bühnenbauten nach Server-Neustarts dauerhaft erhalten.
 */
public final class FixtureStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<StoredFixture>>() {}.getType();

    private final File file;

    public record StoredFixture(
            UUID id,
            String world,
            double x,
            double y,
            double z,
            float mountYaw,
            boolean ceilingMounted,
            String type,
            boolean useCustomModelData
    ) {
    }

    public FixtureStorage(File dataFolder) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.file = new File(dataFolder, "fixtures.json");
    }

    public Map<UUID, AdmxPlugin.PlacedFixture> load() {
        Map<UUID, AdmxPlugin.PlacedFixture> map = new ConcurrentHashMap<>();
        if (!file.exists()) {
            return map;
        }

        try (Reader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<StoredFixture> list = GSON.fromJson(reader, LIST_TYPE);
            if (list == null) return map;

            for (StoredFixture s : list) {
                World world = Bukkit.getWorld(s.world());
                if (world == null) continue;

                Location loc = new Location(world, s.x(), s.y(), s.z(), 0f, 0f);
                FixtureType type;
                try {
                    type = FixtureType.valueOf(s.type());
                } catch (IllegalArgumentException e) {
                    type = FixtureType.MOVING_HEAD_SPOT;
                }

                MovingHeadProfile profile = MovingHeadProfile.defaultBlockbenchModel(s.useCustomModelData());
                map.put(s.id(), new AdmxPlugin.PlacedFixture(
                        s.id(),
                        loc,
                        s.mountYaw(),
                        s.ceilingMounted(),
                        type,
                        profile
                ));
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ADMX] Fehler beim Laden von fixtures.json: " + e.getMessage());
        }
        return map;
    }

    public void save(Map<UUID, AdmxPlugin.PlacedFixture> map) {
        List<StoredFixture> list = new ArrayList<>();
        for (AdmxPlugin.PlacedFixture f : map.values()) {
            World world = f.location().getWorld();
            if (world == null) continue;

            list.add(new StoredFixture(
                    f.id(),
                    world.getName(),
                    f.location().getX(),
                    f.location().getY(),
                    f.location().getZ(),
                    f.mountYaw(),
                    f.ceilingMounted(),
                    f.type().name(),
                    f.profile().useCustomModelData()
            ));
        }

        try (Writer writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(list, LIST_TYPE, writer);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[ADMX] Fehler beim Speichern von fixtures.json: " + e.getMessage());
        }
    }
}
