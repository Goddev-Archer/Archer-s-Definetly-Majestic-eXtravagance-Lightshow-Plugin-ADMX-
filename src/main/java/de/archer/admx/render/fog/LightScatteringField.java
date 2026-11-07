package de.archer.admx.render.fog;

import de.archer.admx.render.color.Oklab;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Berechnet die physikalische Lichtbrechung (Tyndall-Streuung / Mie-Scattering) von aktiven
 * Bühnenscheinwerfern und Lasern innerhalb des Nebelvolumens.
 * Wenn ein Lichtkegel auf Nebel trifft, bricht sich das Licht in den Nebeldreiecken und im
 * Spieler-Sichtfeld, färbt den Nebel im OKLab-Farbraum ein und verstärkt die Shader-Luminanz.
 */
public final class LightScatteringField {

    public record BeamSource(
            Vector3f origin,
            Vector3f direction,
            Oklab color,
            float coneHalfAngleRad,
            float dimmer,
            float maxReach
    ) {
    }

    public record ScatteringResult(
            Oklab refractedColor,
            float refractedAlpha,
            float totalScatteredIntensity
    ) {
    }

    private final List<BeamSource> activeBeams = new ArrayList<>();

    public void clear() {
        activeBeams.clear();
    }

    public void registerBeam(
            Vector3f origin,
            Vector3f direction,
            Oklab color,
            float coneAngleDegrees,
            float dimmer,
            float maxReach
    ) {
        if (dimmer <= 0.01f || maxReach <= 0.1f) {
            return;
        }
        Vector3f normDir = new Vector3f(direction);
        if (normDir.lengthSquared() < 1e-6f) {
            return;
        }
        normDir.normalize();
        float halfAngleRad = (float) Math.toRadians(Math.clamp(coneAngleDegrees * 0.5f, 2.0f, 65.0f));
        activeBeams.add(new BeamSource(new Vector3f(origin), normDir, color, halfAngleRad, dimmer, maxReach));
    }

    public List<BeamSource> activeBeams() {
        return activeBeams;
    }

    /**
     * Berechnet für einen 3D-Punkt im Nebel die resultierende gebrochene Lichtfarbe und Opazität.
     */
    public ScatteringResult sampleAt(Vector3f point, Oklab baseFogColor, float baseAlpha) {
        if (activeBeams.isEmpty()) {
            return new ScatteringResult(baseFogColor, baseAlpha, 0f);
        }

        Oklab mixedColor = baseFogColor;
        float accumulatedIntensity = 0f;

        for (BeamSource beam : activeBeams) {
            float dx = point.x - beam.origin.x;
            float dy = point.y - beam.origin.y;
            float dz = point.z - beam.origin.z;

            // Projektion entlang der Strahlachse
            float along = dx * beam.direction.x + dy * beam.direction.y + dz * beam.direction.z;
            if (along < -0.5f || along > beam.maxReach) {
                continue;
            }

            float clampedAlong = Math.max(0.2f, along);
            float px = beam.origin.x + beam.direction.x * clampedAlong;
            float py = beam.origin.y + beam.direction.y * clampedAlong;
            float pz = beam.origin.z + beam.direction.z * clampedAlong;

            float perpDistSq = (point.x - px) * (point.x - px)
                    + (point.y - py) * (point.y - py)
                    + (point.z - pz) * (point.z - pz);

            // Kegelradius an dieser Distanz + Streuungs-Halo im Nebel (Lichtbrechungs-Radius)
            float coneRadius = (float) (clampedAlong * Math.tan(beam.coneHalfAngleRad));
            float scatterRadius = Math.max(1.35f, coneRadius + 1.65f);
            float scatterRadiusSq = scatterRadius * scatterRadius;

            if (perpDistSq > scatterRadiusSq) {
                continue;
            }

            float perpFactor = 1.0f - (float) Math.sqrt(perpDistSq / scatterRadiusSq);
            float distAttenuation = 1.0f - (clampedAlong / (beam.maxReach * 1.25f));
            float contribution = Math.clamp(perpFactor * distAttenuation * beam.dimmer, 0f, 1f);

            if (contribution > 0.01f) {
                float blendWeight = Math.clamp(contribution * 0.78f, 0f, 0.88f);
                mixedColor = mixedColor.lerp(beam.color, blendWeight);
                accumulatedIntensity += contribution;
            }
        }

        float clampedScatter = Math.clamp(accumulatedIntensity, 0f, 1.5f);
        // Gebrochenes Licht erhöht die lokale Sichtbarkeit des Nebels (Tyndall-Glow)
        float boostedAlpha = Math.clamp(baseAlpha * (1.0f + 0.55f * clampedScatter), 0.02f, 0.72f);

        return new ScatteringResult(mixedColor, boostedAlpha, clampedScatter);
    }
}
