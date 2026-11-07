package de.archer.admx.render.entity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zentraler Tracker für das deklarative Entity-Lifecycle-Management in ADMX.
 * <ul>
 *   <li>Verhindert Duplikate, "Ghost-Entities" und Memory-Leaks.</li>
 *   <li>Besitzt eine konfigurierbare Obergrenze ({@link #setMaxEntities(int)}), um Server/Client vor Überlastung zu schützen.</li>
 *   <li>Nutzt eine 60-Tick Dark-Grace-Periode (via {@code setViewRange(0f)}) für Display-Entities, damit Stroboskope
 *       und Blinder bei kurzen Dunkelphasen keine massiven Spawn/Destroy-Paketstürme auslösen.</li>
 *   <li>Unterstützt gezieltes Entfernen einzelner Gerätebäume ({@link #removeByRoot(Object)}) sowie den
 *       Photosensitivitäts-Schutzmodus ({@code /admx safe}).</li>
 * </ul>
 */
public final class EntityTracker {

    public static final String ENTITY_TAG = "admx:rendered";
    private static final int DARK_GRACE_TICKS = 60;
    private static final float DEFAULT_RESTORED_VIEW_RANGE = 0.60f;

    private static final Map<Object, Entity> rendered = new ConcurrentHashMap<>();
    private static final Set<Object> used = ConcurrentHashMap.newKeySet();
    private static final Map<Object, Integer> unusedTicks = new ConcurrentHashMap<>();
    private static final Map<UUID, Matrix4f> lastMatrices = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> savedViewRanges = new ConcurrentHashMap<>();
    private static final Set<UUID> safeModePlayers = ConcurrentHashMap.newKeySet();

    private static volatile int maxEntities = 8000;
    private static volatile Plugin pluginInstance;

    private EntityTracker() {
    }

    public static void setPluginInstance(Plugin plugin) {
        pluginInstance = plugin;
    }

    public static void setMaxEntities(int max) {
        maxEntities = Math.max(100, max);
    }

    public static int getMaxEntities() {
        return maxEntities;
    }

    /**
     * Reicht eine Entity-Definition ein. Wiederverwendet vorhandene Entities oder spawnt neu.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Entity> void submit(Object handle, RenderEntity<T> item) {
        Location cleanLoc = item.location().clone();
        if (Display.class.isAssignableFrom(item.entityClass())) {
            cleanLoc.setYaw(0f);
            cleanLoc.setPitch(0f);
        }

        Entity existing = rendered.get(handle);
        boolean isValid = existing != null && existing.isValid() && item.entityClass().isInstance(existing);

        if (isValid) {
            T casted = (T) existing;
            // Falls die Display-Entity in einer kurzen Dunkelphase (z. B. Strobe) per ViewRange=0 ausgeblendet war,
            // stelle ihre ursprüngliche Sichtweite sofort wieder her, ohne neu zu spawnen!
            if (unusedTicks.remove(handle) != null && casted instanceof Display display) {
                Float restored = savedViewRanges.remove(display.getUniqueId());
                display.setViewRange(restored != null ? restored : DEFAULT_RESTORED_VIEW_RANGE);
            }
            if (!casted.isInsideVehicle() && !sameBlockAndCoords(casted.getLocation(), cleanLoc)) {
                casted.teleport(cleanLoc);
            }
            item.update().accept(casted);
            used.add(handle);
        } else {
            if (existing != null) {
                UUID oldId = existing.getUniqueId();
                lastMatrices.remove(oldId);
                savedViewRanges.remove(oldId);
                unusedTicks.remove(handle);
                if (existing.isValid()) {
                    existing.remove();
                }
                rendered.remove(handle);
            }

            // Globales Sicherheits-Budget prüfen (SEC-03)
            if (rendered.size() >= maxEntities) {
                return;
            }

            World world = cleanLoc.getWorld();
            if (world == null) return;

            T spawned = world.spawn(cleanLoc, item.entityClass(), entity -> {
                entity.addScoreboardTag(ENTITY_TAG);
                entity.setPersistent(false); // Niemals im Chunk-Speicher auf Festplatte ablegen
                item.init().accept(entity);
            });

            // Falls Spieler den Photosensitivitäts-Schutzmodus (/admx safe) aktiviert haben, Entity für diese ausblenden
            if (pluginInstance != null && !safeModePlayers.isEmpty()) {
                for (UUID safePlayerId : safeModePlayers) {
                    Player p = Bukkit.getPlayer(safePlayerId);
                    if (p != null && p.isOnline()) {
                        p.hideEntity(pluginInstance, spawned);
                    }
                }
            }

            item.update().accept(spawned);
            rendered.put(handle, spawned);
            used.add(handle);
        }
    }

    private static boolean sameBlockAndCoords(Location a, Location b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (!Objects.equals(a.getWorld(), b.getWorld())) return false;
        return Double.doubleToLongBits(a.getX()) == Double.doubleToLongBits(b.getX())
                && Double.doubleToLongBits(a.getY()) == Double.doubleToLongBits(b.getY())
                && Double.doubleToLongBits(a.getZ()) == Double.doubleToLongBits(b.getZ());
    }

    /**
     * Verarbeitet alle Entities, die im aktuellen Tick nicht durch ein submit() aktualisiert wurden.
     * Display-Entities erhalten eine 60-Tick Grace-Periode (mit {@code viewRange = 0f}), um bei
     * Strobe-/Blinder-Intervallen kein Entity-Spawn/Destroy-Churn zu verursachen.
     */
    public static void removeUnused() {
        Iterator<Map.Entry<Object, Entity>> it = rendered.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Entity> entry = it.next();
            Object key = entry.getKey();
            if (!used.contains(key)) {
                Entity entity = entry.getValue();
                if (!entity.isValid()) {
                    cleanupEntityCaches(key, entity);
                    it.remove();
                    continue;
                }

                if (entity instanceof Display display) {
                    int missed = unusedTicks.getOrDefault(key, 0) + 1;
                    if (missed == 1) {
                        float currentRange = display.getViewRange();
                        if (currentRange > 0.001f) {
                            savedViewRanges.put(display.getUniqueId(), currentRange);
                        }
                        display.setViewRange(0.0f);
                    }
                    if (missed < DARK_GRACE_TICKS) {
                        unusedTicks.put(key, missed);
                        continue;
                    }
                }

                entity.remove();
                cleanupEntityCaches(key, entity);
                it.remove();
            }
        }
        used.clear();
    }

    /**
     * Entfernt gezielt alle Entities, die zu einem bestimmten Wurzel-Handle (z. B. einer Fixture-UUID
     * oder einem geschlossenen Holo-Steuerpult) gehören, ohne benachbarte Geräte zu beeinflussen (SEC-06).
     */
    public static int removeByRoot(Object rootHandle) {
        if (rootHandle == null) return 0;
        int removedCount = 0;
        Iterator<Map.Entry<Object, Entity>> it = rendered.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Entity> entry = it.next();
            Object key = entry.getKey();
            if (Objects.equals(rootHandle, RenderGroup.extractRootHandle(key))) {
                Entity entity = entry.getValue();
                if (entity.isValid()) {
                    entity.remove();
                }
                cleanupEntityCaches(key, entity);
                used.remove(key);
                it.remove();
                removedCount++;
            }
        }
        return removedCount;
    }

    /**
     * Entfernt alle Entities in einer bestimmten Welt (z. B. bei WorldUnloadEvent).
     */
    public static int removeByWorld(World world) {
        if (world == null) return 0;
        int count = 0;
        Iterator<Map.Entry<Object, Entity>> it = rendered.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Entity> entry = it.next();
            Entity entity = entry.getValue();
            if (!entity.isValid() || world.equals(entity.getWorld())) {
                if (entity.isValid()) {
                    entity.remove();
                }
                cleanupEntityCaches(entry.getKey(), entity);
                used.remove(entry.getKey());
                it.remove();
                count++;
            }
        }
        return count;
    }

    private static void cleanupEntityCaches(Object key, Entity entity) {
        unusedTicks.remove(key);
        if (entity != null) {
            UUID id = entity.getUniqueId();
            lastMatrices.remove(id);
            savedViewRanges.remove(id);
        }
    }

    /**
     * Entfernt alle aktuell vom Tracker verwalteten Entities (z. B. bei Plugin-Disable).
     */
    public static void removeAll() {
        for (Entity entity : rendered.values()) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        rendered.clear();
        used.clear();
        unusedTicks.clear();
        lastMatrices.clear();
        savedViewRanges.clear();
    }

    /**
     * Schaltet den Photosensitivitäts-Schutzmodus (/admx safe) für einen Spieler ein oder aus.
     *
     * @return {@code true}, wenn der Schutzmodus jetzt aktiv ist, sonst {@code false}.
     */
    public static boolean toggleSafeMode(Plugin plugin, Player player) {
        UUID pid = player.getUniqueId();
        boolean enable = !safeModePlayers.contains(pid);
        setSafeMode(plugin, player, enable);
        return enable;
    }

    public static void setSafeMode(Plugin plugin, Player player, boolean enabled) {
        UUID pid = player.getUniqueId();
        if (enabled) {
            safeModePlayers.add(pid);
            for (Entity entity : rendered.values()) {
                if (entity.isValid()) {
                    player.hideEntity(plugin, entity);
                }
            }
        } else {
            safeModePlayers.remove(pid);
            for (Entity entity : rendered.values()) {
                if (entity.isValid()) {
                    player.showEntity(plugin, entity);
                }
            }
        }
    }

    public static boolean isSafeMode(UUID playerId) {
        return safeModePlayers.contains(playerId);
    }

    public static void clearPlayerState(UUID playerId) {
        safeModePlayers.remove(playerId);
    }

    /**
     * Bereinigt verwaiste Entities aus früheren Sessions oder Chunks anhand des Scoreboard-Tags in allen geladenen Welten.
     * @return Anzahl der entfernten Entities
     */
    public static int purgeDanglingEntities() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            count += purgeDanglingEntities(world);
        }
        return count;
    }

    /**
     * Bereinigt verwaiste Entities in einer bestimmten Welt.
     * @return Anzahl der entfernten Entities
     */
    public static int purgeDanglingEntities(World world) {
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (entity.getScoreboardTags().contains(ENTITY_TAG)) {
                entity.remove();
                count++;
            }
        }
        return count;
    }

    /**
     * Hilfsmethode zur flüssigen Transformation von Display-Entities mit Interpolation.
     * Prüft, ob sich die Matrix überhaupt verändert hat, um unnötige Metadaten-Pakete
     * und Interpolations-Resets (visuelles Flackern) zu verhindern.
     */
    public static void updateDisplayTransform(Display display, Matrix4f matrix, int durationTicks) {
        Matrix4f prev = lastMatrices.get(display.getUniqueId());
        if (prev != null && prev.equals(matrix)) {
            return;
        }
        lastMatrices.put(display.getUniqueId(), new Matrix4f(matrix));
        display.setTransformationMatrix(matrix);
        display.setInterpolationDuration(durationTicks);
        display.setInterpolationDelay(0);
    }

    /**
     * Liefert die Anzahl der aktuell aktiven Entities.
     */
    public static int activeEntityCount() {
        return rendered.size();
    }
}
