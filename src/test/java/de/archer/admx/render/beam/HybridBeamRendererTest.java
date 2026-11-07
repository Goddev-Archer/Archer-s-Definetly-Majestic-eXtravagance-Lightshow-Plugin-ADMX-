package de.archer.admx.render.beam;

import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.entity.EmptyRenderItem;
import de.archer.admx.render.entity.RenderGroup;
import de.archer.admx.render.entity.RenderItem;
import de.archer.admx.render.fixture.FixtureState;
import org.bukkit.Location;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HybridBeamRendererTest {

    @Test
    void testBeamOffWhenDimmerZero() {
        HybridBeamRenderer renderer = new HybridBeamRenderer();
        Location origin = new Location(null, 0, 10, 0);
        FixtureState offState = FixtureState.defaultSpot(); // dimmer = 0

        RenderItem item = renderer.renderBeam(origin, new Vector3f(0, 0.5f, 0), offState, BeamMode.LASER_FAN, 48f);

        assertSame(EmptyRenderItem.INSTANCE, item, "Bei Dimmer = 0 darf kein Beam gerendert werden");
    }

    @Test
    void testLaserFanRendering() {
        RaycastProvider mockRaycast = (world, start, dir, maxDist) -> RayHit.miss(
                new Vector3f(start).add(new Vector3f(dir).mul(maxDist)), maxDist
        );

        HybridBeamRenderer renderer = new HybridBeamRenderer(mockRaycast);
        Location origin = new Location(null, 0, 10, 0);
        FixtureState activeState = new FixtureState(45f, 30f, 1.0f, new Oklab(0.7f, 0.2f, 0.1f), 30f, 0f);

        RenderItem item = renderer.renderBeam(origin, new Vector3f(0, 0.5f, 0), activeState, BeamMode.LASER_FAN, 48f);

        assertInstanceOf(RenderGroup.class, item);
        RenderGroup group = (RenderGroup) item;
        assertEquals(6, group.children().size(), "Doppelseitiger Laser-Fan muss aus 6 Teilen bestehen");
    }

    @Test
    void testSpotConeCrossQuads() {
        RaycastProvider mockRaycast = (world, start, dir, maxDist) -> RayHit.miss(
                new Vector3f(start).add(new Vector3f(dir).mul(maxDist)), maxDist
        );

        HybridBeamRenderer renderer = new HybridBeamRenderer(mockRaycast);
        Location origin = new Location(null, 0, 10, 0);
        FixtureState spotState = new FixtureState(0f, 45f, 0.8f, new Oklab(0.9f, 0f, 0f), 20f, 0f);

        RenderItem item = renderer.renderBeam(origin, new Vector3f(0, 0.5f, 0), spotState, BeamMode.SPOT_CONE, 48f);

        assertInstanceOf(RenderGroup.class, item);
        RenderGroup spotGroup = (RenderGroup) item;
        assertEquals(18, spotGroup.children().size(), "Spot-Cone muss aus 12 Außen- und 6 Kern-Mantelflächen für einen echten 3D-Kegel mit Core-Beam bestehen");
        assertNotNull(spotGroup.get("face_0"));
        assertNotNull(spotGroup.get("core_0"));
    }

    @Test
    void testRaycastHitClampsDistance() {
        float expectedHitDist = 14.2f;
        RaycastProvider hitRaycast = (world, start, dir, maxDist) -> new RayHit(
                new Vector3f(start).add(new Vector3f(dir).mul(expectedHitDist)),
                new Vector3f(0, 1, 0),
                expectedHitDist,
                true
        );

        HybridBeamRenderer renderer = new HybridBeamRenderer(hitRaycast);
        Location origin = new Location(null, 0, 10, 0);
        FixtureState state = new FixtureState(0f, 0f, 1.0f, new Oklab(0.7f, 0f, 0f), 10f, 0f);

        RenderItem item = renderer.renderBeam(origin, new Vector3f(0, 0.5f, 0), state, BeamMode.LASER_FAN, 50f);

        assertInstanceOf(RenderGroup.class, item);
    }
}
