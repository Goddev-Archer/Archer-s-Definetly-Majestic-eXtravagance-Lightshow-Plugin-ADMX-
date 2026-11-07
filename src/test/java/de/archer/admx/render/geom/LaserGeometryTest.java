package de.archer.admx.render.geom;

import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.entity.RenderGroup;
import org.bukkit.Location;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LaserGeometryTest {

    @Test
    void testBuildLaserFan() {
        Location origin = new Location(null, 0, 10, 0);

        Vector3f apex = new Vector3f(0, 0, 0);
        Vector3f left = new Vector3f(-5, -10, 10);
        Vector3f right = new Vector3f(5, -10, 10);

        Oklab color = new Oklab(0.7f, 0.1f, 0.2f);

        RenderGroup fan = LaserGeometry.buildLaserFan(origin, apex, left, right, color, 0.6f, 1);

        assertNotNull(fan);
        assertEquals(6, fan.children().size(), "Ein doppelseitiges Laser-Fan-Dreieck muss aus 6 Teil-TextDisplays (3 Vorder-, 3 Rückseite) bestehen");
    }

    @Test
    void testBuildProjectedLaserFan() {
        Location origin = new Location(null, 0, 10, 0);

        Vector3f forward = new Vector3f(0, 0, 1);
        Vector3f spreadAxis = new Vector3f(1, 0, 0);

        Oklab cyan = new Oklab(0.8f, -0.2f, -0.1f);

        RenderGroup projectedFan = LaserGeometry.buildProjectedLaserFan(
                origin, forward, spreadAxis, 45.0f, 20.0f, cyan, 0.75f, 2
        );

        assertNotNull(projectedFan);
        assertEquals(6, projectedFan.children().size());
    }

    @Test
    void testBuildLaserQuad() {
        Location origin = new Location(null, 0, 5, 0);

        Vector3f p1 = new Vector3f(-2, 0, 0);
        Vector3f p2 = new Vector3f(2, 0, 0);
        Vector3f p3 = new Vector3f(2, 0, 10);
        Vector3f p4 = new Vector3f(-2, 0, 10);

        Oklab purple = new Oklab(0.6f, 0.25f, -0.2f);

        RenderGroup quad = LaserGeometry.buildLaserQuad(origin, p1, p2, p3, p4, purple, 0.5f, 1);

        assertNotNull(quad);
        assertEquals(2, quad.children().size(), "Ein Quad besteht aus 2 Triangles");
    }
}
