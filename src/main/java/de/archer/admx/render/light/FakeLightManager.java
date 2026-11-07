package de.archer.admx.render.light;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verwaltet clientseitige virtuelle Fake-Lichtblöcke ({@link Material#LIGHT}) an den Auftreffpunkten
 * von Beams und Moving Heads.
 * <ul>
 *   <li>Prüft vor dem Senden, ob der Zielblock tatsächlich Luft ist ({@code block.getType().isAir()}),
 *       damit niemals solide Blöcke, Schilder oder Kisten beim Client unsichtbar überschrieben werden (SEC-05).</li>
 *   <li>Sendet Block-Updates nur an Spieler in derselben Welt im Umkreis von 64 Blöcken.</li>
 *   <li>Speichert die zugehörige {@link World} pro aktivem Licht, damit Multi-World-Setups und
 *       Chunk-Unloads sauber bereinigt werden können.</li>
 * </ul>
 */
public final class FakeLightManager {

    private static final double MAX_LIGHT_PACKET_DISTANCE_SQ = 64.0 * 64.0;

    private final Map<UUID, ActiveLightState> activeLights = new ConcurrentHashMap<>();

    private record ActiveLightState(World world, BlockPos pos, int level) {
    }

    /**
     * Aktualisiert das virtuelle Licht am Auftreffpunkt einer Fixture.
     *
     * @param fixtureId     ID der Fixture
     * @param hitPoint      Auftreffpunkt des Strahls
     * @param surfaceNormal Flächennormale der getroffenen Wand/Boden
     * @param dimmer        Dimmerwert (0.0 bis 1.0)
     * @param audience      Liste der Spieler, die das Licht empfangen sollen
     */
    public void updateFixtureLight(
            UUID fixtureId,
            Location hitPoint,
            Vector3f surfaceNormal,
            float dimmer,
            Collection<? extends Player> audience
    ) {
        World world = hitPoint.getWorld();
        if (world == null || audience == null || audience.isEmpty()) {
            return;
        }

        int lightLevel = Math.clamp((int) Math.round(dimmer * 15.0f), 0, 15);

        // Licht aus -> Vorheriges Licht zurücksetzen
        if (lightLevel <= 0) {
            clearFixtureLight(fixtureId, world, audience);
            return;
        }

        // Koordinate leicht vor die getroffene Fläche versetzen (im Luftraum)
        Location targetLoc = hitPoint.clone().add(
                surfaceNormal.x * 0.35,
                surfaceNormal.y * 0.35,
                surfaceNormal.z * 0.35
        );
        BlockPos newPos = BlockPos.fromLocation(targetLoc);

        if (!world.isChunkLoaded(newPos.x() >> 4, newPos.z() >> 4)) {
            return;
        }

        // Sicherheitsprüfung (SEC-05): Niemals einen nicht-Luft-Block beim Client durch Material.LIGHT ersetzen!
        Block targetBlock = world.getBlockAt(newPos.x(), newPos.y(), newPos.z());
        if (!targetBlock.getType().isAir() && targetBlock.getType() != Material.LIGHT) {
            clearFixtureLight(fixtureId, world, audience);
            return;
        }

        ActiveLightState previous = activeLights.get(fixtureId);

        // Delta-Check: Wenn Welt, Position und Helligkeit identisch sind, keine Pakete senden!
        if (previous != null
                && world.equals(previous.world)
                && previous.pos.equals(newPos)
                && previous.level == lightLevel) {
            return;
        }

        // 1. Altes Fake-Licht beim Spieler zurücksetzen, falls die Position oder Welt sich geändert hat
        if (previous != null && (!previous.pos.equals(newPos) || !world.equals(previous.world))) {
            restoreBlockForAudience(previous, audience);
        }

        // 2. Neues Fake-Licht an nahe Spieler in derselben Welt senden
        Location newLoc = newPos.toLocation(world);
        BlockData lightBlockData = Material.LIGHT.createBlockData(data -> {
            if (data instanceof Light light) {
                light.setLevel(lightLevel);
            }
        });

        sendBlockChangeInRange(world, newLoc, lightBlockData, audience);
        activeLights.put(fixtureId, new ActiveLightState(world, newPos, lightLevel));
    }

    /**
     * Entfernt das Fake-Licht einer Fixture und stellt den realen Weltzustand wieder her.
     */
    public void clearFixtureLight(UUID fixtureId, World world, Collection<? extends Player> audience) {
        ActiveLightState removed = activeLights.remove(fixtureId);
        if (removed != null && audience != null) {
            restoreBlockForAudience(removed, audience);
        }
    }

    /**
     * Entfernt alle Fake-Lichter in einem entladenen Chunk, um veraltete Einträge zu vermeiden (PERF-14).
     */
    public void clearChunk(World world, int chunkX, int chunkZ) {
        if (world == null) return;
        Iterator<Map.Entry<UUID, ActiveLightState>> it = activeLights.entrySet().iterator();
        while (it.hasNext()) {
            ActiveLightState state = it.next().getValue();
            if (world.equals(state.world)
                    && (state.pos.x() >> 4) == chunkX
                    && (state.pos.z() >> 4) == chunkZ) {
                it.remove();
            }
        }
    }

    /**
     * Räumt alle aktiven Fake-Lichter einer bestimmten Welt auf.
     */
    public void clearWorld(World world, Collection<? extends Player> audience) {
        if (world == null) return;
        Iterator<Map.Entry<UUID, ActiveLightState>> it = activeLights.entrySet().iterator();
        while (it.hasNext()) {
            ActiveLightState state = it.next().getValue();
            if (world.equals(state.world)) {
                if (audience != null) {
                    restoreBlockForAudience(state, audience);
                }
                it.remove();
            }
        }
    }

    /**
     * Räumt alle aktiven Fake-Lichter auf (z. B. bei Plugin-Disable).
     */
    public void clearAll(World world, Collection<? extends Player> audience) {
        if (world != null) {
            clearWorld(world, audience);
        } else {
            activeLights.clear();
        }
    }

    private void restoreBlockForAudience(ActiveLightState state, Collection<? extends Player> audience) {
        World w = state.world;
        if (w == null || !w.isChunkLoaded(state.pos.x() >> 4, state.pos.z() >> 4)) {
            return;
        }
        Location oldLoc = state.pos.toLocation(w);
        BlockData realWorldData = w.getBlockData(oldLoc);
        sendBlockChangeInRange(w, oldLoc, realWorldData, audience);
    }

    private void sendBlockChangeInRange(
            World world,
            Location targetLoc,
            BlockData data,
            Collection<? extends Player> audience
    ) {
        for (Player player : audience) {
            if (!world.equals(player.getWorld())) {
                continue;
            }
            if (player.getLocation().distanceSquared(targetLoc) <= MAX_LIGHT_PACKET_DISTANCE_SQ) {
                player.sendBlockChange(targetLoc, data);
            }
        }
    }

    /**
     * Liefert die Anzahl aktuell aktiver virtueller Lichtquellen.
     */
    public int activeLightCount() {
        return activeLights.size();
    }
}
