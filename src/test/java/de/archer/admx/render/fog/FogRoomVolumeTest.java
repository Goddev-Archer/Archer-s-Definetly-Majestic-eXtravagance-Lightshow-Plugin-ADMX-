package de.archer.admx.render.fog;

import de.archer.admx.render.color.Oklab;
import org.bukkit.Color;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FogRoomVolumeTest {

    @Test
    void testClosedRoomRespectsWalls() {
        // Geschlossener 9x9 Raum (X: -4..4, Z: -4..4, Boden bei Y=0, Decke bei Y=5)
        BlockPassabilityProvider closedRoom = (x, y, z) ->
                x >= -4 && x <= 4 && z >= -4 && z <= 4 && y >= 0 && y < 5;

        FogRoomVolume volume = FogRoomVolume.compute(0, 0, 0, FogType.ROOM_HAZE, 30.0f, closedRoom);

        // 9 * 9 = 81 erreichbare Säulen
        assertEquals(81, volume.columnCount());
        // Da der Raum durch Wände begrenzt ist, wurde die 30-Block-Freifeld-Grenze NICHT erreicht
        assertFalse(volume.reachedMaxLimit());
        assertTrue(volume.openBoundaryCells().isEmpty());

        // Innerhalb des Raumes herrscht hohe Nebeldichte, außerhalb der Wand exakt 0
        assertTrue(volume.getFogDensityAt(2.5, 1.5, 2.5, FogType.ROOM_HAZE, 30.0f) > 0.3f);
        assertEquals(0.0f, volume.getFogDensityAt(6.0, 1.5, 0.0, FogType.ROOM_HAZE, 30.0f));
    }

    @Test
    void testOpenFieldCapsAt30BlocksAndMarksBoundary() {
        // Unendliches offenes Feld mit festem Boden unter Y=0
        BlockPassabilityProvider openField = (x, y, z) -> y >= 0 && y < 20;

        FogRoomVolume volume = FogRoomVolume.compute(0, 0, 0, FogType.LOW_FOG, 45.0f, openField);

        // Muss strikt bei 30.0 Blöcken gekappt werden
        assertEquals(FogType.MAX_REACH_BLOCKS, volume.maxReach(), 1e-4f);
        assertTrue(volume.reachedMaxLimit());
        assertFalse(volume.openBoundaryCells().isEmpty());

        // Keine einzige generierte Säule darf weiter als 30 Blöcke entfernt sein
        for (FogRoomVolume.ColumnCell cell : volume.columns()) {
            assertTrue(cell.distance() <= FogType.MAX_REACH_BLOCKS + 1e-4f,
                    "Cell exceeded 30 block limit: " + cell.distance());
        }
    }

    @Test
    void testLowFogStaysNearFloorWhileHazeFillsHeight() {
        BlockPassabilityProvider tallRoom = (x, y, z) ->
                Math.abs(x) <= 6 && Math.abs(z) <= 6 && y >= 0 && y < 10;

        FogRoomVolume lowFogVol = FogRoomVolume.compute(0, 0, 0, FogType.LOW_FOG, 30.0f, tallRoom);
        FogRoomVolume hazeVol = FogRoomVolume.compute(0, 0, 0, FogType.ROOM_HAZE, 30.0f, tallRoom);

        // Am Boden (Y=0.3) haben beide hohe Dichte
        assertTrue(lowFogVol.getFogDensityAt(0.5, 0.3, 0.5, FogType.LOW_FOG, 20.0f) > 0.5f);
        assertTrue(hazeVol.getFogDensityAt(0.5, 0.3, 0.5, FogType.ROOM_HAZE, 20.0f) > 0.5f);

        // Auf 3.0m Höhe ist Kaltnebel 0, während Raumnebel dort noch dicht vorhanden ist
        assertEquals(0.0f, lowFogVol.getFogDensityAt(0.5, 3.0, 0.5, FogType.LOW_FOG, 20.0f));
        assertTrue(hazeVol.getFogDensityAt(0.5, 3.0, 0.5, FogType.ROOM_HAZE, 20.0f) > 0.3f);
    }

    @Test
    void testLightScatteringRefractsColorAndBoostsAlpha() {
        LightScatteringField field = new LightScatteringField();
        Oklab redBeam = Oklab.fromRGB(Color.fromRGB(255, 20, 40));
        Oklab baseFog = FogType.ROOM_HAZE.baseColor();

        field.registerBeam(
                new Vector3f(0f, 2f, 0f),
                new Vector3f(0f, 0f, -1f),
                redBeam,
                24f,
                1.0f,
                30f
        );

        // Punkt mitten im Lichtkegel (Z = -6)
        LightScatteringField.ScatteringResult inside = field.sampleAt(new Vector3f(0f, 2f, -6f), baseFog, 0.16f);
        assertTrue(inside.totalScatteredIntensity() > 0.5f);
        assertTrue(inside.refractedAlpha() > 0.16f);
        // Die gebrochene Farbe muss sich deutlich Richtung Rot verschoben haben (a-Kanal in OKLab > baseFog.a)
        assertTrue(inside.refractedColor().a() > baseFog.a() + 0.05f);
    }

    @Test
    void testWaveHeightCoplanarityAndLowFogDoesNotClimbHills() {
        int floorY = 64;
        int ceilY = 72;
        float layerOffset = FogType.LOW_FOG.baseLayerHeight();

        // Prüfe, dass auf ebenem Boden y11 == y10 + y01 - y00 exakt gilt (verhindert jegliches Z-Fighting/Knicke)
        for (long tick = 0; tick < 40; tick += 5) {
            float y00 = FogRenderer.computeWaveHeight(10, 20, floorY, ceilY, layerOffset, FogType.LOW_FOG, tick, 0f);
            float y10 = FogRenderer.computeWaveHeight(12, 20, floorY, ceilY, layerOffset, FogType.LOW_FOG, tick, 0f);
            float y01 = FogRenderer.computeWaveHeight(10, 22, floorY, ceilY, layerOffset, FogType.LOW_FOG, tick, 0f);
            float y11 = FogRenderer.computeWaveHeight(12, 22, floorY, ceilY, layerOffset, FogType.LOW_FOG, tick, 0f);

            assertEquals(y10 + y01 - y00, y11, 1e-5f, "Connected wave quad must be strictly coplanar");
        }

        // Hügel: Für x > 2 steigt der Boden um +1 pro Block an -> Kaltnebel darf diesen Hang NICHT hinaufklettern
        BlockPassabilityProvider hillTerrain = (x, y, z) -> {
            int groundY = (x > 2) ? (x - 2) : 0;
            return y >= groundY && y < groundY + 10 && Math.abs(x) <= 8 && Math.abs(z) <= 4;
        };

        FogRoomVolume lowFog = FogRoomVolume.compute(0, 0, 0, FogType.LOW_FOG, 30.0f, hillTerrain);
        assertNotNull(lowFog.getColumn(2, 0));
        assertNull(lowFog.getColumn(3, 0), "Heavy low fog must not climb uphill onto higher blocks");
    }
}
