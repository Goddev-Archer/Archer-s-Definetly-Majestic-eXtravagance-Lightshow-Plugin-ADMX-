package de.archer.admx.render.beam;

import org.joml.Vector3f;

/**
 * Ergebnis einer Kollisionsabfrage eines Licht- oder Laserstrahls mit der Welt.
 *
 * @param hitPoint      Exakter 3D-Auftreffpunkt im Raum
 * @param surfaceNormal Flächennormale der getroffenen Blockseite (z. B. (0, 1, 0) für Boden)
 * @param distance      Distanz vom Strahlursprung bis zum Treffer
 * @param didHitBlock   true, wenn ein Block getroffen wurde; false, wenn maximale Reichweite erreicht wurde
 */
public record RayHit(
        Vector3f hitPoint,
        Vector3f surfaceNormal,
        float distance,
        boolean didHitBlock
) {
    public static RayHit miss(Vector3f endPoint, float maxDistance) {
        return new RayHit(endPoint, new Vector3f(0, 1, 0), maxDistance, false);
    }

    public boolean isHit() {
        return didHitBlock;
    }

    public Vector3f position() {
        return hitPoint;
    }

    public Vector3f normal() {
        return surfaceNormal;
    }
}
