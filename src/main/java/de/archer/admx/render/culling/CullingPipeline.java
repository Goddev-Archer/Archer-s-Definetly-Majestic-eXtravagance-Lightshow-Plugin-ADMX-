package de.archer.admx.render.culling;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.joml.Vector3f;

/**
 * Performanz- & Culling-Engine (Schritt 5):
 * Reduziert Entity- und Netzwerk-Overhead durch gezieltes Ein-/Ausblenden
 * von Display-Entities abhängig von Distanz, Frustum und Flächennormale.
 */
public final class CullingPipeline {

    public static final float DEFAULT_MAX_DISTANCE = 64.0f;
    public static final float BACKFACE_THRESHOLD_COS = -0.087f; // ca. 95 Grad

    private CullingPipeline() {
    }

    /**
     * Prüft, ob ein Punkt innerhalb der maximalen Sichtweite eines Spielers liegt.
     */
    public static boolean isWithinDistance(Player player, Location target, float maxDistance) {
        if (!player.getWorld().equals(target.getWorld())) {
            return false;
        }
        return player.getLocation().distanceSquared(target) <= (maxDistance * maxDistance);
    }

    /**
     * Prüft über das Skalarprodukt mit der Flächennormale, ob ein Auge auf die Rückseite
     * einer Laserfläche blickt (Backface-Culling).
     *
     * @param eyePos      3D-Position der Kamera / des Auges
     * @param surfacePos  Zentrum der Fläche
     * @param planeNormal Flächennormale
     * @return true, wenn die Fläche abgewandt ist und ausgeblendet werden kann
     */
    public static boolean isBackface(Vector3f eyePos, Location surfacePos, Vector3f planeNormal) {
        Vector3f viewDir = new Vector3f(
                eyePos.x - (float) surfacePos.getX(),
                eyePos.y - (float) surfacePos.getY(),
                eyePos.z - (float) surfacePos.getZ()
        );

        if (viewDir.lengthSquared() < 0.001f) {
            return false;
        }
        viewDir.normalize();

        float dot = viewDir.dot(planeNormal);
        return dot < BACKFACE_THRESHOLD_COS;
    }

    /**
     * Prüft über das Skalarprodukt mit der Flächennormale, ob ein Spieler auf die Rückseite
     * einer Laserfläche blickt (Backface-Culling).
     */
    public static boolean isBackface(Player player, Location surfacePos, Vector3f planeNormal) {
        Location eye = player.getEyeLocation();
        Vector3f eyeVec = new Vector3f((float) eye.getX(), (float) eye.getY(), (float) eye.getZ());
        return isBackface(eyeVec, surfacePos, planeNormal);
    }

    /**
     * Prüft, ob sich ein Zielpunkt im ungefähren Blickfeld (Frustum) des Spielers befindet.
     *
     * @param player          Der Spieler
     * @param target          Der Zielpunkt
     * @param maxAngleDegrees Maximaler Blickwinkel (z. B. 80 Grad für typisches FOV)
     * @return true, wenn das Objekt im Blickfeld liegt
     */
    public static boolean isInFrustum(Player player, Location target, float maxAngleDegrees) {
        if (!player.getWorld().equals(target.getWorld())) {
            return false;
        }

        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector toTarget = target.toVector().subtract(eye.toVector()).normalize();
        org.bukkit.util.Vector eyeDir = eye.getDirection().normalize();

        double angle = Math.toDegrees(eyeDir.angle(toTarget));
        return angle <= maxAngleDegrees;
    }

    /**
     * Wendet Distanz- und Backface-Culling direkt auf ein Entity für einen Spieler an.
     */
    public static void updateVisibility(
            Plugin plugin,
            Player player,
            Entity entity,
            Location entityLocation,
            Vector3f normal,
            float maxDistance
    ) {
        boolean visible = isWithinDistance(player, entityLocation, maxDistance)
                && !isBackface(player, entityLocation, normal)
                && isInFrustum(player, entityLocation, 85.0f);

        if (visible) {
            player.showEntity(plugin, entity);
        } else {
            player.hideEntity(plugin, entity);
        }
    }
}
