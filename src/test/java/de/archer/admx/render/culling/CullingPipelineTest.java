package de.archer.admx.render.culling;

import org.bukkit.Location;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CullingPipelineTest {

    @Test
    void testBackfaceCullingFrontView() {
        Location planeCenter = new Location(null, 0, 5, 0);
        Vector3f normal = new Vector3f(0, 0, 1); // Normale zeigt nach +Z

        // Kamera steht bei +Z und blickt auf die Vorderseite
        Vector3f frontCamera = new Vector3f(0, 5, 10);
        boolean culled = CullingPipeline.isBackface(frontCamera, planeCenter, normal);

        assertFalse(culled, "Kamera vor der Fläche darf NICHT als Backface erkannt werden");
    }

    @Test
    void testBackfaceCullingRearView() {
        Location planeCenter = new Location(null, 0, 5, 0);
        Vector3f normal = new Vector3f(0, 0, 1); // Normale zeigt nach +Z

        // Kamera steht bei -Z und blickt von hinten auf die Fläche
        Vector3f rearCamera = new Vector3f(0, 5, -10);
        boolean culled = CullingPipeline.isBackface(rearCamera, planeCenter, normal);

        assertTrue(culled, "Kamera hinter der Fläche MUSS als Backface gecullt werden");
    }
}
