package de.archer.admx.render.fog;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Abstraktion zur Abfrage der Block-Durchlässigkeit für die physikalische Raum-Nebelberechnung.
 */
@FunctionalInterface
public interface BlockPassabilityProvider {

    /**
     * Gibt true zurück, wenn Nebel durch den Block an (x, y, z) strömen kann (z. B. Luft, offene Tore, Teppiche).
     */
    boolean isPassable(int x, int y, int z);

    /**
     * Erstellt einen Provider für eine echte Bukkit-Welt.
     */
    static BlockPassabilityProvider forWorld(World world) {
        return (x, y, z) -> {
            if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return false;
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            Block block = world.getBlockAt(x, y, z);
            return block.isPassable() && !block.isLiquid();
        };
    }
}
