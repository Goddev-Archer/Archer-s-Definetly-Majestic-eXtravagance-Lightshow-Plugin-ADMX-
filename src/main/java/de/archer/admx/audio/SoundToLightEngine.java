package de.archer.admx.audio;

import de.archer.admx.control.FixtureGroup;
import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import org.bukkit.Color;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kern-Engine für das Hardware-Style Sound-to-Light System.
 * Reagiert unabhängig von BPM oder Songstruktur rein auf die Pegelspitzen in den
 * vom Benutzer konfigurierten Frequenzbändern (z. B. Sub-Bass, Mid-Punch, High-Transienten).
 */
public final class SoundToLightEngine {

    // Lebendige Farb-Palette für COLOR_CYCLE Trigger
    private static final Color[] COLOR_PALETTE = new Color[]{
            Color.fromRGB(0x00, 0xF5, 0xFF), // Cyan
            Color.fromRGB(0xFF, 0x00, 0x55), // Neon Red
            Color.fromRGB(0x39, 0xFF, 0x14), // Lime Green
            Color.fromRGB(0xFF, 0xAA, 0x00), // Amber Gold
            Color.fromRGB(0x9D, 0x00, 0xFF), // Deep Purple
            Color.fromRGB(0xFF, 0xFF, 0xFF), // Pure White
            Color.fromRGB(0x00, 0x55, 0xFF)  // Cobalt Blue
    };

    private final AudioSpectrumAnalyzer spectrumAnalyzer;

    // Per-Group Laufzeit-Triggerzustand
    private static final class GroupTriggerState {
        boolean isTriggered = false;
        long lastTriggerTimeMs = 0;
        int chaseStep = 0;
        int colorStep = 0;
        float currentBandLevel = 0.0f;
    }

    private final Map<String, GroupTriggerState> states = new ConcurrentHashMap<>();

    public SoundToLightEngine(AudioSpectrumAnalyzer spectrumAnalyzer) {
        this.spectrumAnalyzer = spectrumAnalyzer;
    }

    public AudioSpectrumAnalyzer getSpectrumAnalyzer() {
        return spectrumAnalyzer;
    }

    /**
     * Aktualisiert den Triggerzustand einer Gruppe für den aktuellen Tick.
     */
    public void updateGroupTrigger(FixtureGroup group) {
        if (group == null) return;
        SoundToLightConfig cfg = group.getSoundConfig();
        if (cfg == null || !cfg.isEnabled()) return;

        GroupTriggerState st = states.computeIfAbsent(group.groupId(), k -> new GroupTriggerState());
        long now = System.currentTimeMillis();

        float level = spectrumAnalyzer.getLevelForRange(cfg.lowHz(), cfg.highHz());
        st.currentBandLevel = level;

        boolean thresholdExceeded = level >= cfg.threshold();

        if (thresholdExceeded) {
            // Entprell-Sperre (mindestens 70 ms zwischen zwei Triggersprüngen für Chase/Farbe)
            if ((now - st.lastTriggerTimeMs) > 70) {
                st.chaseStep++;
                st.colorStep = (st.colorStep + 1) % COLOR_PALETTE.length;
            }
            st.lastTriggerTimeMs = now;
            st.isTriggered = true;
        } else {
            st.isTriggered = (now - st.lastTriggerTimeMs) <= cfg.holdMs();
        }
    }

    /**
     * Prüft, ob eine Gruppe im aktuellen Moment aktiv getriggert ist (Pegel > Threshold oder noch in Hold-Phase).
     */
    public boolean isGroupTriggered(String groupId) {
        GroupTriggerState st = states.get(groupId);
        return st != null && st.isTriggered;
    }

    /**
     * Liefert den aktuellen gemessenen Bandpegel [0.0 .. 1.0] für das Frequenzfenster der Gruppe.
     */
    public float getGroupBandLevel(String groupId) {
        GroupTriggerState st = states.get(groupId);
        return st != null ? st.currentBandLevel : 0.0f;
    }

    /**
     * Berechnet den gerenderten FixtureState für ein einzelnes Gerät einer Gruppe
     * basierend auf der Sound-To-Light Konfiguration.
     */
    public FixtureState computeFixtureState(
            FixtureGroup group,
            int memberIndex,
            int totalInGroup,
            int tickCounter,
            FixtureType type
    ) {
        SoundToLightConfig cfg = group.getSoundConfig();
        if (cfg == null) {
            cfg = new SoundToLightConfig();
        }

        GroupTriggerState st = states.computeIfAbsent(group.groupId(), k -> new GroupTriggerState());
        boolean active = st.isTriggered;
        SoundAction action = active ? cfg.aboveAction() : cfg.belowAction();

        float dimmer = 0.0f;
        float strobeHz = 0.0f;
        Color color = group.toBukkitColor();
        float pan = group.panDegrees();
        float tilt = group.tiltDegrees();
        float zoom = group.zoomDegrees();

        // 1. SoundAction in Beleuchtungszustand übersetzen
        switch (action) {
            case BLACKOUT -> {
                dimmer = 0.0f;
                strobeHz = 0.0f;
            }
            case DIMMED_SWEEP -> {
                dimmer = 0.22f;
                strobeHz = 0.0f;
                // Sanfte Pan-Bewegung im Ruhezustand
                double phase = ((tickCounter * 0.04) + (double) memberIndex / Math.max(1, totalInGroup)) % (2.0 * Math.PI);
                pan += (float) (Math.sin(phase) * 25.0);
            }
            case STATIC_COLOR -> {
                dimmer = 0.45f;
                strobeHz = 0.0f;
            }
            case STROBE_10HZ -> {
                dimmer = 1.0f;
                // Strikt gedeckelt auf <= 10.0 Hz gem. SEC-09
                strobeHz = 10.0f;
                color = (type.isLaser()) ? Color.fromRGB(0x00, 0xFF, 0x88) : Color.fromRGB(0xFF, 0xFF, 0xFF);
            }
            case BLINDER_HIT -> {
                // Decay-Verlauf des Blinder-Schlags
                long elapsed = System.currentTimeMillis() - st.lastTriggerTimeMs;
                float decay = Math.clamp(1.0f - ((float) elapsed / Math.max(1, cfg.holdMs())), 0.2f, 1.0f);
                dimmer = decay;
                strobeHz = 0.0f;
                color = Color.fromRGB(0xFF, 0xFF, 0xFF); // Strahlend Weiß
            }
            case COLOR_CYCLE -> {
                dimmer = 1.0f;
                strobeHz = 0.0f;
                color = COLOR_PALETTE[st.colorStep % COLOR_PALETTE.length];
            }
            case CHASE_STEP -> {
                int activeIndex = Math.floorMod(st.chaseStep, Math.max(1, totalInGroup));
                boolean isCurrent = (memberIndex == activeIndex);
                dimmer = isCurrent ? 1.0f : 0.05f;
                strobeHz = 0.0f;
                if (isCurrent) {
                    tilt += 15.0f;
                }
            }
            case LASER_FAN_BURST -> {
                dimmer = 1.0f;
                strobeHz = 8.0f; // SEC-09 safe
                zoom = 48.0f;    // Breiter Fächerwinkel
                color = Color.fromRGB(0x00, 0xF5, 0xFF);
            }
        }

        return new FixtureState(
                pan,
                tilt,
                dimmer,
                Oklab.fromRGB(color),
                zoom,
                strobeHz
        );
    }
}
