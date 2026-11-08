package de.archer.admx.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryoBazookaTest {

    @Test
    void testLedColorIndexingAndModulo() {
        assertEquals(CryoBazookaManager.LedColor.ICE_WHITE, CryoBazookaManager.LedColor.fromIndex(0));
        assertEquals(CryoBazookaManager.LedColor.CRYO_CYAN, CryoBazookaManager.LedColor.fromIndex(1));
        assertEquals(CryoBazookaManager.LedColor.NEON_MAGENTA, CryoBazookaManager.LedColor.fromIndex(2));
        assertEquals(CryoBazookaManager.LedColor.TOXIC_GREEN, CryoBazookaManager.LedColor.fromIndex(3));
        assertEquals(CryoBazookaManager.LedColor.UV_VIOLET, CryoBazookaManager.LedColor.fromIndex(4));

        // Modulo Test für Zyklus
        assertEquals(CryoBazookaManager.LedColor.ICE_WHITE, CryoBazookaManager.LedColor.fromIndex(5));
        assertEquals(CryoBazookaManager.LedColor.CRYO_CYAN, CryoBazookaManager.LedColor.fromIndex(6));
        assertEquals(CryoBazookaManager.LedColor.UV_VIOLET, CryoBazookaManager.LedColor.fromIndex(-1));
    }

    @Test
    void testLedColorAttributes() {
        for (CryoBazookaManager.LedColor col : CryoBazookaManager.LedColor.values()) {
            assertNotNull(col.displayName);
            assertNotNull(col.bukkitColor);
            assertNotNull(col.textColor);
            assertFalse(col.displayName.isBlank());
        }
    }
}
