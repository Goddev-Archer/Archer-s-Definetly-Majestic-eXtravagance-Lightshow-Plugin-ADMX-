package de.archer.admx.render.geom;

import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.entity.EntityTracker;
import de.archer.admx.render.entity.RenderEntity;
import de.archer.admx.render.entity.RenderGroup;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * High-Level Polygon-Engine: Erzeugt volumetrische 3D-Lichtkegel (Cones),
 * flache Laser-Fächer (Fans) und Lichtbänder aus gescherten TextDisplay-Dreiecken.
 */
public final class LaserGeometry {

    public static final float DEFAULT_BEAM_VIEW_RANGE = 0.55f;
    private static final Component EMPTY_TEXT = Component.text(" ");
    private static final Display.Brightness FULL_BRIGHTNESS = new Display.Brightness(15, 15);

    private LaserGeometry() {
    }

    /**
     * Erzeugt eine RenderGroup aus TextDisplays für ein einzelnes Dreieck (optional doppelseitig,
     * um Backface-Culling in Minecraft aus jedem Blickwinkel zu verhindern).
     */
    public static RenderGroup buildLaserFan(
            Location origin,
            Vector3f apex,
            Vector3f leftBase,
            Vector3f rightBase,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        return buildLaserFan(origin, apex, leftBase, rightBase, color, alpha, interpolationTicks, true);
    }

    /**
     * Erzeugt eine RenderGroup aus TextDisplays für ein einzelnes Dreieck.
     */
    public static RenderGroup buildLaserFan(
            Location origin,
            Vector3f apex,
            Vector3f leftBase,
            Vector3f rightBase,
            Oklab color,
            float alpha,
            int interpolationTicks,
            boolean doubleSided
    ) {
        RenderGroup group = new RenderGroup();

        Color displayColor = toDisplayColor(color, alpha);

        TextDisplayTriangle frontTriangle = TextDisplayTriangle.calculate(apex, leftBase, rightBase);
        List<Matrix4f> frontTransforms = frontTriangle.pieceTransforms();
        for (int i = 0; i < frontTransforms.size(); i++) {
            Matrix4f transform = frontTransforms.get(i);
            group.set("f_" + i, createTrianglePieceEntity(origin, transform, displayColor, FULL_BRIGHTNESS, interpolationTicks));
        }

        if (doubleSided) {
            TextDisplayTriangle backTriangle = TextDisplayTriangle.calculate(apex, rightBase, leftBase);
            List<Matrix4f> backTransforms = backTriangle.pieceTransforms();
            for (int i = 0; i < backTransforms.size(); i++) {
                Matrix4f transform = backTransforms.get(i);
                group.set("b_" + i, createTrianglePieceEntity(origin, transform, displayColor, FULL_BRIGHTNESS, interpolationTicks));
            }
        }

        return group;
    }

    private static RenderEntity<TextDisplay> createTrianglePieceEntity(
            Location origin,
            Matrix4f transform,
            Color displayColor,
            Display.Brightness fullBrightness,
            int interpolationTicks
    ) {
        return new RenderEntity<>(
                TextDisplay.class,
                origin,
                display -> {
                    display.text(EMPTY_TEXT);
                    display.setBrightness(fullBrightness);
                    display.setBackgroundColor(displayColor);
                    display.setShadowed(false);
                    display.setSeeThrough(false);
                    display.setViewRange(DEFAULT_BEAM_VIEW_RANGE);
                    display.setTransformationMatrix(transform);
                    display.setInterpolationDuration(interpolationTicks);
                    display.setInterpolationDelay(0);
                },
                display -> {
                    if (!displayColor.equals(display.getBackgroundColor())) {
                        display.setBackgroundColor(displayColor);
                    }
                    EntityTracker.updateDisplayTransform(display, transform, interpolationTicks);
                }
        );
    }

    /**
     * Erzeugt einen flachen, 2D Laser-Fächer (Club Scanning Laser) am Ursprungsort.
     */
    public static RenderGroup buildProjectedLaserFan(
            Location origin,
            Vector3f forwardDir,
            Vector3f spreadAxis,
            float spreadAngleDegrees,
            float distance,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        return buildProjectedLaserFan(
                origin, new Vector3f(0f, 0f, 0f), forwardDir, spreadAxis,
                spreadAngleDegrees, distance, color, alpha, interpolationTicks
        );
    }

    /**
     * Erzeugt einen flachen, 2D Laser-Fächer mit relativem Linsen-Offset (verhindert Entity-Teleports bei Pan/Tilt).
     */
    public static RenderGroup buildProjectedLaserFan(
            Location staticAnchor,
            Vector3f apexOffset,
            Vector3f forwardDir,
            Vector3f spreadAxis,
            float spreadAngleDegrees,
            float distance,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        float halfAngleRad = (float) Math.toRadians(spreadAngleDegrees * 0.5f);
        float sinA = (float) Math.sin(halfAngleRad);
        float cosA = (float) Math.cos(halfAngleRad);

        Vector3f apex = new Vector3f(apexOffset);

        Vector3f leftDir = new Vector3f(forwardDir).mul(cosA).sub(new Vector3f(spreadAxis).mul(sinA)).normalize();
        Vector3f rightDir = new Vector3f(forwardDir).mul(cosA).add(new Vector3f(spreadAxis).mul(sinA)).normalize();

        Vector3f leftBase = new Vector3f(leftDir).mul(distance).add(apex);
        Vector3f rightBase = new Vector3f(rightDir).mul(distance).add(apex);

        float fanAlpha = Math.clamp(alpha * 0.38f, 0.06f, 0.45f);

        return buildLaserFan(staticAnchor, apex, leftBase, rightBase, color, fanAlpha, interpolationTicks);
    }

    /**
     * Erzeugt einen echten, dreidimensionalen volumetrischen Lichtkegel (3D Spotlight Cone).
     */
    public static RenderGroup buildVolumetricCone(
            Location origin,
            Vector3f forwardDir,
            Vector3f spreadAxis,
            float spreadAngleDegrees,
            float distance,
            int sides,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        return buildVolumetricCone(
                origin, new Vector3f(0f, 0f, 0f), forwardDir, spreadAxis,
                spreadAngleDegrees, distance, sides, color, alpha, interpolationTicks
        );
    }

    /**
     * Erzeugt einen dreidimensionalen volumetrischen Lichtkegel mit statischem Montage-Anker
     * und relativem Linsen-Offset (eliminiert jegliche Entity-Teleport-Pakete bei Pan/Tilt).
     */
    public static RenderGroup buildVolumetricCone(
            Location staticAnchor,
            Vector3f apexOffset,
            Vector3f forwardDir,
            Vector3f spreadAxis,
            float spreadAngleDegrees,
            float distance,
            int sides,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        RenderGroup coneGroup = new RenderGroup();

        Vector3f apex = new Vector3f(apexOffset);

        Vector3f u = new Vector3f(spreadAxis).normalize();
        Vector3f v = new Vector3f(forwardDir).cross(u).normalize();

        float halfAngleRad = (float) Math.toRadians(spreadAngleDegrees * 0.5f);
        float coneRadius = distance * (float) Math.tan(halfAngleRad);

        Vector3f centerAtDistance = new Vector3f(forwardDir).mul(distance).add(apex);

        // 1. Äußerer Haze-Mantel (weicher Dunst)
        float outerAlpha = Math.clamp(alpha * 0.11f, 0.02f, 0.15f);

        Vector3f[] outerRing = new Vector3f[sides];
        for (int i = 0; i < sides; i++) {
            float phi = (float) (2.0 * Math.PI * i / sides);
            float cosPhi = (float) Math.cos(phi);
            float sinPhi = (float) Math.sin(phi);

            outerRing[i] = new Vector3f(centerAtDistance)
                .add(new Vector3f(u).mul(coneRadius * cosPhi))
                .add(new Vector3f(v).mul(coneRadius * sinPhi));
        }

        for (int i = 0; i < sides; i++) {
            int next = (i + 1) % sides;
            coneGroup.set("face_" + i, buildLaserFan(staticAnchor, apex, outerRing[i], outerRing[next], color, outerAlpha, interpolationTicks, true));
        }

        // 2. Fokussierter Innenkern (Core Beam) für Leuchtkraft im Zentrum
        float coreRadius = coneRadius * 0.38f;
        float coreAlpha = Math.clamp(alpha * 0.18f, 0.04f, 0.22f);
        int coreSides = Math.max(6, sides / 2);

        Vector3f[] coreRing = new Vector3f[coreSides];
        for (int i = 0; i < coreSides; i++) {
            float phi = (float) (2.0 * Math.PI * i / coreSides);
            float cosPhi = (float) Math.cos(phi);
            float sinPhi = (float) Math.sin(phi);

            coreRing[i] = new Vector3f(centerAtDistance)
                .add(new Vector3f(u).mul(coreRadius * cosPhi))
                .add(new Vector3f(v).mul(coreRadius * sinPhi));
        }

        for (int i = 0; i < coreSides; i++) {
            int next = (i + 1) % coreSides;
            coneGroup.set("core_" + i, buildLaserFan(staticAnchor, apex, coreRing[i], coreRing[next], color, coreAlpha, interpolationTicks, true));
        }

        return coneGroup;
    }

    /**
     * Erzeugt ein rechteckiges Laser-Band (Quad) aus zwei Dreiecken.
     */
    public static RenderGroup buildLaserQuad(
            Location origin,
            Vector3f p1,
            Vector3f p2,
            Vector3f p3,
            Vector3f p4,
            Oklab color,
            float alpha,
            int interpolationTicks
    ) {
        RenderGroup quadGroup = new RenderGroup();
        quadGroup.set("tri1", buildLaserFan(origin, p1, p2, p3, color, alpha, interpolationTicks));
        quadGroup.set("tri2", buildLaserFan(origin, p1, p3, p4, color, alpha, interpolationTicks));
        return quadGroup;
    }

    private static Color toDisplayColor(Oklab oklab, float alpha) {
        Color base = oklab.toRGB();
        int a = Math.clamp((int) Math.round(alpha * 255.0f), 0, 255);
        return Color.fromARGB(a, base.getRed(), base.getGreen(), base.getBlue());
    }
}
