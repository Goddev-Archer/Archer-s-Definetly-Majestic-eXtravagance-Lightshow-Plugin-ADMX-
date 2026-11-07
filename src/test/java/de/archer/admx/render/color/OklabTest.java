package de.archer.admx.render.color;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OklabTest {

    @Test
    void testPureColorsRoundtrip() {
        Color[] testColors = new Color[]{
                Color.fromRGB(255, 0, 0),
                Color.fromRGB(0, 255, 0),
                Color.fromRGB(0, 0, 255),
                Color.fromRGB(255, 255, 255),
                Color.fromRGB(0, 0, 0),
                Color.fromRGB(255, 128, 0),
                Color.fromRGB(128, 0, 255)
        };

        for (Color original : testColors) {
            Oklab oklab = Oklab.fromRGB(original);
            Color recovered = oklab.toRGB();

            assertEquals(original.getRed(), recovered.getRed(), 1, "Red channel mismatch for " + original);
            assertEquals(original.getGreen(), recovered.getGreen(), 1, "Green channel mismatch for " + original);
            assertEquals(original.getBlue(), recovered.getBlue(), 1, "Blue channel mismatch for " + original);
        }
    }

    @Test
    void testInterpolationMidpoint() {
        Oklab red = Oklab.fromRGB(Color.fromRGB(255, 0, 0));
        Oklab green = Oklab.fromRGB(Color.fromRGB(0, 255, 0));

        Oklab mid = red.lerp(green, 0.5f);
        Color midRgb = mid.toRGB();

        // Im Oklab-Farbraum behält der Übergang zwischen Rot und Grün einen warmen, lebendigen Bernsteinton
        // (ca. Rot=161, Grün=100, Blau=0) statt in dunklem, ungesättigtem Matsch zu versinken.
        assertTrue(midRgb.getRed() >= 150, "Midpoint red should remain vibrant: " + midRgb);
        assertTrue(midRgb.getGreen() >= 95, "Midpoint green should remain vibrant: " + midRgb);
        assertEquals(0, midRgb.getBlue(), "Midpoint blue should remain 0: " + midRgb);
    }
}
