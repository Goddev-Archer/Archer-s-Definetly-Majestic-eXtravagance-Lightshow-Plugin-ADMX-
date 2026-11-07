package de.archer.admx.render.beam;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

/**
 * Abstraktion für Block-Kollisionen von Laser- und Lichtstrahlen.
 */
public interface RaycastProvider {

    /**
     * Führt eine Kollisionsprüfung von einem Ursprungspunkt in eine Richtung durch.
     */
    RayHit raycast(World world, Vector3f origin, Vector3f direction, float maxDistance);

    /**
     * Standard-PaperMC-Implementierung via World.rayTraceBlocks.
     */
    class PaperRaycastProvider implements RaycastProvider {
        @Override
        public RayHit raycast(World world, Vector3f origin, Vector3f direction, float maxDistance) {
            if (world == null) {
                Vector3f end = new Vector3f(origin).add(new Vector3f(direction).mul(maxDistance));
                return RayHit.miss(end, maxDistance);
            }

            Location start = new Location(world, origin.x, origin.y, origin.z);
            Vector dir = new Vector(direction.x, direction.y, direction.z);

            RayTraceResult result = world.rayTraceBlocks(start, dir, maxDistance, FluidCollisionMode.NEVER, true);

            if (result != null && result.getHitPosition() != null) {
                Vector hitPos = result.getHitPosition();
                Vector3f hit = new Vector3f((float) hitPos.getX(), (float) hitPos.getY(), (float) hitPos.getZ());
                float dist = new Vector3f(hit).sub(origin).length();

                Vector normal = result.getHitBlockFace() != null
                        ? result.getHitBlockFace().getDirection()
                        : new Vector(0, 1, 0);

                return new RayHit(hit, new Vector3f((float) normal.getX(), (float) normal.getY(), (float) normal.getZ()), dist, true);
            }

            Vector3f end = new Vector3f(origin).add(new Vector3f(direction).mul(maxDistance));
            return RayHit.miss(end, maxDistance);
        }
    }
}
