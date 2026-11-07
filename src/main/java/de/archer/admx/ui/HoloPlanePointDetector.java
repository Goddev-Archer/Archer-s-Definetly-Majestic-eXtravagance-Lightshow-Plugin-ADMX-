package de.archer.admx.ui;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D-Strahl-Ebenen-Schnittpunktdetektor für das Hologramm-UI
 * (inspiriert von {@code PlanePointDetector} aus {@code minecraft-text-display-experiments}).
 *
 * Transformiert den Blickstrahl des Spielers über die invertierte {@link Matrix4f} eines
 * Hologramm-Elements in dessen lokalen Einheitsraum {@code [0..1] x [0..1]} bei {@code Z = 0}.
 */
public final class HoloPlanePointDetector {

    public record RaySample(Player player, Vector3f p1, Vector3f p2) {
    }

    public record PlaneHit(Player player, float u, float v, float distance) {
    }

    private final List<RaySample> playerRays = new ArrayList<>();

    public HoloPlanePointDetector(Iterable<? extends Player> players, Location displayOrigin, double maxDistance) {
        double maxDistSq = maxDistance * maxDistance;
        float ox = (float) displayOrigin.getX();
        float oy = (float) displayOrigin.getY();
        float oz = (float) displayOrigin.getZ();

        for (Player player : players) {
            if (!player.getWorld().equals(displayOrigin.getWorld())) {
                continue;
            }
            Location eye = player.getEyeLocation();
            if (eye.distanceSquared(displayOrigin) > maxDistSq) {
                continue;
            }
            var dir = eye.getDirection();
            Vector3f p1 = new Vector3f(
                    (float) eye.getX() - ox,
                    (float) eye.getY() - oy,
                    (float) eye.getZ() - oz
            );
            Vector3f p2 = new Vector3f(
                    p1.x + (float) dir.getX(),
                    p1.y + (float) dir.getY(),
                    p1.z + (float) dir.getZ()
            );
            playerRays.add(new RaySample(player, p1, p2));
        }
    }

    /**
     * Prüft, ob ein Spieler auf das durch {@code planeTransform} definierte Einheitsquadrat {@code [0..1] x [0..1]}
     * blickt, und liefert die exakten lokalen UV-Koordinaten {@code (u, v) in [0..1]^2} zurück.
     */
    public PlaneHit detectHit(Matrix4f planeTransform) {
        return detectHit(planeTransform, 0f, 1f, 0f, 1f);
    }

    public PlaneHit detectHit(Matrix4f planeTransform, float minX, float maxX, float minY, float maxY) {
        if (playerRays.isEmpty()) {
            return null;
        }
        Matrix4f inv = new Matrix4f(planeTransform).invert();

        for (RaySample ray : playerRays) {
            Vector4f t1 = inv.transform(new Vector4f(ray.p1.x, ray.p1.y, ray.p1.z, 1f));
            Vector4f t2 = inv.transform(new Vector4f(ray.p2.x, ray.p2.y, ray.p2.z, 1f));

            float dz = t2.z - t1.z;
            if (Math.abs(dz) < 1e-6f) {
                continue;
            }

            // Schnittpunkt mit der Ebene Z = 0; t > 0 bedeutet vor den Augen des Spielers
            float t = (0f - t1.z) / dz;
            if (t < 0.05f || t > 7.5f) {
                continue;
            }

            float ix = t1.x + (t2.x - t1.x) * t;
            float iy = t1.y + (t2.y - t1.y) * t;

            if (ix >= minX && ix <= maxX && iy >= minY && iy <= maxY) {
                float u = Math.clamp((ix - minX) / Math.max(1e-5f, maxX - minX), 0f, 1f);
                float v = Math.clamp((iy - minY) / Math.max(1e-5f, maxY - minY), 0f, 1f);
                return new PlaneHit(ray.player, u, v, t);
            }
        }
        return null;
    }
}
