package de.archer.admx.render.light;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Leichtgewichtige ganzzahlige Block-Koordinate als Java 25 Record.
 */
public record BlockPos(int x, int y, int z) {

    public static BlockPos fromLocation(Location location) {
        return new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z);
    }
}
