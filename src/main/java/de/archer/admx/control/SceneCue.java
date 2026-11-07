package de.archer.admx.control;

import java.util.Map;

/**
 * Ein einzelner programmierbarer Szenen-Cue (Snapshot aller Gruppen-Einstellungen zu einem Zeitpunkt).
 */
public record SceneCue(
        int cueIndex,
        String name,
        int fadeTicks,
        int holdTicks,
        Map<String, GroupSnapshot> groupSnapshots
) {

    public record GroupSnapshot(
            String groupId,
            float dimmer,
            float panDegrees,
            float tiltDegrees,
            float zoomDegrees,
            float strobeHz,
            float hueDegrees,
            float saturation,
            float brightness,
            MovementPattern movementPattern,
            float movementSpeed,
            float movementAmplitude,
            float phaseSpread
    ) {
        public static GroupSnapshot interpolate(GroupSnapshot a, GroupSnapshot b, float t) {
            float clamped = Math.clamp(t, 0f, 1f);
            // Kürzester Weg auf dem Farbkreis für Hue
            float dHue = ((b.hueDegrees - a.hueDegrees + 540f) % 360f) - 180f;
            float interpHue = ((a.hueDegrees + dHue * clamped) % 360f + 360f) % 360f;

            return new GroupSnapshot(
                    b.groupId,
                    a.dimmer + (b.dimmer - a.dimmer) * clamped,
                    a.panDegrees + (b.panDegrees - a.panDegrees) * clamped,
                    a.tiltDegrees + (b.tiltDegrees - a.tiltDegrees) * clamped,
                    a.zoomDegrees + (b.zoomDegrees - a.zoomDegrees) * clamped,
                    clamped < 0.5f ? a.strobeHz : b.strobeHz,
                    interpHue,
                    a.saturation + (b.saturation - a.saturation) * clamped,
                    a.brightness + (b.brightness - a.brightness) * clamped,
                    clamped < 0.5f ? a.movementPattern : b.movementPattern,
                    a.movementSpeed + (b.movementSpeed - a.movementSpeed) * clamped,
                    a.movementAmplitude + (b.movementAmplitude - a.movementAmplitude) * clamped,
                    a.phaseSpread + (b.phaseSpread - a.phaseSpread) * clamped
            );
        }
    }
}
