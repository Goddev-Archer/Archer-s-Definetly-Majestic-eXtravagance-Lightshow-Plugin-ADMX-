package de.archer.admx.render.light;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FakeLightManagerTest {

    @Test
    void testBlockPosEqualityAndConversion() {
        Location loc1 = new Location(null, 10.4, 64.9, -15.1);
        BlockPos pos1 = BlockPos.fromLocation(loc1);

        assertEquals(10, pos1.x());
        assertEquals(64, pos1.y());
        assertEquals(-16, pos1.z()); // floor(-15.1) = -16

        BlockPos pos2 = new BlockPos(10, 64, -16);
        assertEquals(pos1, pos2);
        assertEquals(pos1.hashCode(), pos2.hashCode());
    }

    @Test
    void testInitialManagerEmpty() {
        FakeLightManager manager = new FakeLightManager();
        assertEquals(0, manager.activeLightCount());

        manager.clearFixtureLight(UUID.randomUUID(), null, Collections.emptyList());
        assertEquals(0, manager.activeLightCount());

        manager.clearAll(null, Collections.emptyList());
        assertEquals(0, manager.activeLightCount());
    }
}
