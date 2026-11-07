package de.archer.admx.render.fixture;

import de.archer.admx.render.color.Oklab;
import org.bukkit.Color;

/**
 * Repräsentiert den aktuellen Aussteuerungs-Zustand einer Licht-Fixture im Server-Tick.
 *
 * @param panDegrees       Pan-Winkel in Grad (0.0 bis 540.0)
 * @param tiltDegrees      Tilt-Winkel in Grad (0.0 bis 270.0)
 * @param dimmer           Master-Dimmer (0.0f bis 1.0f)
 * @param color            Farbe im perzeptiven Oklab-Farbraum
 * @param zoomAngleDegrees Streuwinkel / Zoom in Grad (z. B. 2.0 bis 45.0)
 * @param strobeHz         Strobe-Frequenz in Hz (0 = Aus / Dauerlicht)
 */
public record FixtureState(
        float panDegrees,
        float tiltDegrees,
        float dimmer,
        Oklab color,
        float zoomAngleDegrees,
        float strobeHz
) {

    public FixtureState {
        dimmer = Math.clamp(dimmer, 0.0f, 1.0f);
        zoomAngleDegrees = Math.clamp(zoomAngleDegrees, 1.0f, 90.0f);
        strobeHz = Math.max(0.0f, strobeHz);
        if (color == null) {
            color = Oklab.fromRGB(Color.WHITE);
        }
    }

    public static FixtureState defaultSpot() {
        return new FixtureState(0f, 0f, 0f, Oklab.fromRGB(Color.WHITE), 15f, 0f);
    }

    public float panRadians() {
        return (float) Math.toRadians(panDegrees);
    }

    public float tiltRadians() {
        return (float) Math.toRadians(tiltDegrees);
    }

    public boolean isIlluminating() {
        return dimmer > 0.001f;
    }

    public FixtureState withPanTilt(float pan, float tilt) {
        return new FixtureState(pan, tilt, this.dimmer, this.color, this.zoomAngleDegrees, this.strobeHz);
    }

    public FixtureState withDimmer(float newDimmer) {
        return new FixtureState(this.panDegrees, this.tiltDegrees, newDimmer, this.color, this.zoomAngleDegrees, this.strobeHz);
    }

    public FixtureState withColor(Oklab newColor) {
        return new FixtureState(this.panDegrees, this.tiltDegrees, this.dimmer, newColor, this.zoomAngleDegrees, this.strobeHz);
    }
}
