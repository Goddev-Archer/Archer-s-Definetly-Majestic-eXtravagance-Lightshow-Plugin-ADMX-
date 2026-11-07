package de.archer.admx.render.beam;

import de.archer.admx.render.entity.EmptyRenderItem;
import de.archer.admx.render.entity.RenderGroup;
import de.archer.admx.render.entity.RenderItem;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.geom.LaserGeometry;
import org.bukkit.Location;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Kern des Hybrid-Beam-Renderers:
 * Berechnet kinematische Ausrichtung (Pan/Tilt), Block-Kollisionen im Raum und
 * erzeugt volumetrische Laser-Fächer, Kreuz-Cones oder Nadelstrahlen.
 * Alle Strahl-Entities werden fest an {@code baseLocation} verankert und ausschließlich
 * über ihre 4x4-Transformationsmatrix (inkl. {@code lensOffset}) verschoben, sodass bei
 * Pan/Tilt-Bewegung keinerlei Entity-Teleports ausgelöst werden.
 */
public final class HybridBeamRenderer {

    private final RaycastProvider raycastProvider;

    public HybridBeamRenderer(RaycastProvider raycastProvider) {
        this.raycastProvider = raycastProvider;
    }

    public HybridBeamRenderer() {
        this(new RaycastProvider.PaperRaycastProvider());
    }

    public record BeamResult(
            RenderItem renderItem,
            RayHit hit,
            Location lensLocation,
            Vector3f forward
    ) {
    }

    /**
     * Berechnet die vollständige Beam-Geometrie für den aktuellen Tick mit anpassbarer Vorwärtsachse
     * und Montage-Orientierung und liefert detaillierte Kollisions- und Ausrichtungsdaten zurück.
     */
    public BeamResult calculateBeam(
            Location baseLocation,
            Quaternionf mountRotation,
            Vector3f lensOffset,
            Vector3f forwardAxis,
            FixtureState state,
            BeamMode mode,
            float maxReach
    ) {
        if (!state.isIlluminating()) {
            return new BeamResult(
                    EmptyRenderItem.INSTANCE,
                    RayHit.miss(new Vector3f(), 0f),
                    baseLocation.clone(),
                    new Vector3f(forwardAxis)
            );
        }

        // 1. Kinematik: Mount-Rotation * Pan * Tilt
        Quaternionf qPan = new Quaternionf().rotationY(state.panRadians());
        Quaternionf qTilt = new Quaternionf().rotationX(state.tiltRadians());
        Quaternionf qHead = new Quaternionf(mountRotation).mul(qPan).mul(qTilt);

        // 2. Richtungsvektor und Querachsen im Raum
        Vector3f forward = new Vector3f(forwardAxis).rotate(qHead).normalize();

        // Senkrechte Querachsen zur Vorwärtsachse berechnen
        Vector3f spreadAxisX;
        if (Math.abs(forwardAxis.y) < 0.99f) {
            spreadAxisX = new Vector3f(forwardAxis).cross(new Vector3f(0f, 1f, 0f)).normalize().rotate(qHead);
        } else {
            spreadAxisX = new Vector3f(1f, 0f, 0f).rotate(qHead);
        }

        // 3. Globale Linsenposition für Raycast & Lichtbrechung (Entities bleiben statisch an baseLocation!)
        Location lensLoc = baseLocation.clone().add(lensOffset.x, lensOffset.y, lensOffset.z);
        Vector3f originVec = new Vector3f((float) lensLoc.getX(), (float) lensLoc.getY(), (float) lensLoc.getZ());

        // 4. Raycast gegen die Blöcke der Welt
        RayHit hit = raycastProvider.raycast(baseLocation.getWorld(), originVec, forward, maxReach);
        float distance = Math.max(hit.distance(), 0.1f);

        // 5. Geometrie abhängig vom Strahlmodus konstruieren (verankert an baseLocation + lensOffset)
        RenderItem item = switch (mode) {
            case LASER_FAN -> LaserGeometry.buildProjectedLaserFan(
                    baseLocation, lensOffset, forward, spreadAxisX, state.zoomAngleDegrees(), distance, state.color(), state.dimmer(), 1
            );

            case SPOT_CONE -> LaserGeometry.buildVolumetricCone(
                    baseLocation, lensOffset, forward, spreadAxisX, state.zoomAngleDegrees(), distance, 12, state.color(), state.dimmer(), 1
            );

            case LASER_NEEDLE -> {
                // Dünner Nadelstrahl als gekreuztes 3D-Laserband (aus allen Richtungen sichtbar)
                float needleWidth = 0.045f;
                Vector3f spreadAxisY = new Vector3f(forward).cross(spreadAxisX).normalize();
                Vector3f halfX = new Vector3f(spreadAxisX).mul(needleWidth * 0.5f);
                Vector3f halfY = new Vector3f(spreadAxisY).mul(needleWidth * 0.5f);
                Vector3f farCenter = new Vector3f(lensOffset).add(new Vector3f(forward).mul(distance));

                RenderGroup needleGroup = new RenderGroup();
                needleGroup.set("plane_x", LaserGeometry.buildLaserQuad(
                        baseLocation,
                        new Vector3f(lensOffset).sub(halfX),
                        new Vector3f(lensOffset).add(halfX),
                        new Vector3f(farCenter).add(halfX),
                        new Vector3f(farCenter).sub(halfX),
                        state.color(), state.dimmer() * 0.85f, 1
                ));
                needleGroup.set("plane_y", LaserGeometry.buildLaserQuad(
                        baseLocation,
                        new Vector3f(lensOffset).sub(halfY),
                        new Vector3f(lensOffset).add(halfY),
                        new Vector3f(farCenter).add(halfY),
                        new Vector3f(farCenter).sub(halfY),
                        state.color(), state.dimmer() * 0.85f, 1
                ));
                yield needleGroup;
            }
        };

        return new BeamResult(item, hit, lensLoc, forward);
    }

    /**
     * Berechnet die vollständige Beam-Geometrie für den aktuellen Tick mit anpassbarer Vorwärtsachse
     * und Montage-Orientierung.
     */
    public RenderItem renderBeam(
            Location baseLocation,
            Quaternionf mountRotation,
            Vector3f lensOffset,
            Vector3f forwardAxis,
            FixtureState state,
            BeamMode mode,
            float maxReach
    ) {
        return calculateBeam(baseLocation, mountRotation, lensOffset, forwardAxis, state, mode, maxReach).renderItem();
    }

    /**
     * Abwärtskompatible Überladung ohne explizite Mount-Rotation.
     */
    public RenderItem renderBeam(
            Location baseLocation,
            Vector3f lensOffset,
            Vector3f forwardAxis,
            FixtureState state,
            BeamMode mode,
            float maxReach
    ) {
        Quaternionf qPan = new Quaternionf().rotationY(state.panRadians());
        Quaternionf qTilt = new Quaternionf().rotationX(state.tiltRadians());
        Quaternionf qHead = new Quaternionf(qPan).mul(qTilt);
        Vector3f rotatedLens = new Vector3f(lensOffset).rotate(qHead);
        return renderBeam(baseLocation, new Quaternionf(), rotatedLens, forwardAxis, state, mode, maxReach);
    }

    /**
     * Überladung mit Standard-Vorwärtsachse (0, 0, 1).
     */
    public RenderItem renderBeam(
            Location baseLocation,
            Vector3f lensOffset,
            FixtureState state,
            BeamMode mode,
            float maxReach
    ) {
        return renderBeam(baseLocation, lensOffset, new Vector3f(0f, 0f, 1f), state, mode, maxReach);
    }
}
