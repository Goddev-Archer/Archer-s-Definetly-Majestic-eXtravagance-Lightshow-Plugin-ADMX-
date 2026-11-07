package de.archer.admx.audio;

import de.archer.admx.AdmxPlugin;
import de.archer.admx.render.color.Oklab;
import de.archer.admx.render.fixture.FixtureState;
import de.archer.admx.render.fixture.FixtureType;
import org.bukkit.Color;

/**
 * Autonomer intelligenter Lichtshow-Director.
 * <p>
 * Berechnet in Echtzeit genre-spezifische, musikalisch getaktete Bewegungen, Farben,
 * Dimmer-Kurven und FX-Einsätze anhand des aktuellen {@link BeatSnapshot}.
 * <p>
 * Erfüllt 100 % aller Sicherheitsregeln aus AGENTS.md:
 * <ul>
 *   <li>SEC-09: Striktes Hardware-Strobe-Cap (<= 10.0 Hz).</li>
 *   <li>PERF-02 / PERF-04: Statische Verankerung, kein Entity-Churn in Beat-Pausen.</li>
 *   <li>BUG-02: Double-Präzision bei Phasen-Berechnungen.</li>
 * </ul>
 */
public final class SmartAutoDirector {

    // Techno-Farbpalette: Kaltweiß, Acid-Grün, Club-Rot, Cyan
    private static final Oklab COLOR_TECHNO_WHITE = Oklab.fromRGB(Color.fromRGB(240, 252, 255));
    private static final Oklab COLOR_TECHNO_ACID = Oklab.fromRGB(Color.fromRGB(165, 255, 30));
    private static final Oklab COLOR_TECHNO_RED = Oklab.fromRGB(Color.fromRGB(255, 20, 40));
    private static final Oklab COLOR_TECHNO_CYAN = Oklab.fromRGB(Color.fromRGB(20, 235, 255));

    // Rap/Trap-Farbpalette: Deep Purple, Amber Gold, Neon Magenta, Moody Teal
    private static final Oklab COLOR_RAP_PURPLE = Oklab.fromRGB(Color.fromRGB(150, 30, 240));
    private static final Oklab COLOR_RAP_GOLD = Oklab.fromRGB(Color.fromRGB(255, 185, 45));
    private static final Oklab COLOR_RAP_MAGENTA = Oklab.fromRGB(Color.fromRGB(255, 35, 140));
    private static final Oklab COLOR_RAP_TEAL = Oklab.fromRGB(Color.fromRGB(25, 190, 195));

    // House-Farbpalette: Sunset Orange, Pink, Warm Cyan
    private static final Oklab COLOR_HOUSE_ORANGE = Oklab.fromRGB(Color.fromRGB(255, 120, 30));
    private static final Oklab COLOR_HOUSE_PINK = Oklab.fromRGB(Color.fromRGB(255, 75, 160));
    private static final Oklab COLOR_HOUSE_CYAN = Oklab.fromRGB(Color.fromRGB(40, 210, 230));

    // DnB-Farbpalette: Electric Blue, Laser Green, High-Volt Yellow
    private static final Oklab COLOR_DNB_BLUE = Oklab.fromRGB(Color.fromRGB(30, 80, 255));
    private static final Oklab COLOR_DNB_GREEN = Oklab.fromRGB(Color.fromRGB(40, 255, 80));
    private static final Oklab COLOR_DNB_YELLOW = Oklab.fromRGB(Color.fromRGB(255, 240, 30));

    public FixtureState computeState(
            AdmxPlugin.PlacedFixture fixture,
            int totalInGroup,
            int tickCounter,
            BeatSnapshot beat
    ) {
        FixtureType type = fixture.type();
        int order = fixture.fixtureOrder();
        float normOrder = (totalInGroup > 1) ? ((float) order / (totalInGroup - 1) - 0.5f) : 0.0f; // [-0.5 .. +0.5]

        return switch (beat.genre()) {
            case TECHNO -> computeTechnoState(type, order, normOrder, tickCounter, beat);
            case RAP_HIPHOP -> computeRapState(type, order, normOrder, tickCounter, beat);
            case DRUM_AND_BASS -> computeDnBState(type, order, normOrder, tickCounter, beat);
            case HOUSE_CLUB -> computeHouseState(type, order, normOrder, tickCounter, beat);
            case AMBIENT -> computeAmbientState(type, order, normOrder, tickCounter, beat);
        };
    }

    /**
     * TECHNO / SCHRANZ: Starre 4-on-the-Floor Kicks, Kaltweiß/Acid, schnelle geometrische Snapshot-Positionen,
     * intelligente Laser- und Strobe-Aktivierung basierend auf Beat-Erkennung.
     */
    private FixtureState computeTechnoState(
            FixtureType type,
            int order,
            float normOrder,
            int tickCounter,
            BeatSnapshot beat
    ) {
        double phase = beat.phase();
        long bar = beat.barCount();
        int beatInBar = beat.beatInBar();
        boolean isKick = beat.isKick() || phase < 0.14;
        boolean strobeActive = beat.strobeActive();
        // Hardware-Sicherheitsbegrenzung strikt <= 10.0 Hz (SEC-09)
        float safeStrobeHz = Math.min(10.0f, beat.strobeHz() > 0.0f ? beat.strobeHz() : 9.5f);

        // Farbwechsel alle 4 Takte
        Oklab color = switch ((int) (bar % 4)) {
            case 0 -> COLOR_TECHNO_WHITE;
            case 1 -> COLOR_TECHNO_ACID;
            case 2 -> COLOR_TECHNO_CYAN;
            default -> COLOR_TECHNO_RED;
        };

        if (type.isMovingHead()) {
            // Geometrische Formations-Snapshots (wechseln alle 2 Takte)
            int patternMode = (int) (bar % 4);
            float pan, tilt;

            if (patternMode == 0) {
                // V-Fächer (Symmetrisch nach außen gespreizt)
                pan = normOrder * 120.0f;
                tilt = -18.0f + (float) Math.sin(phase * Math.PI) * 12.0f;
            } else if (patternMode == 1) {
                // Parallele Säulen mit hartem Beat-Snap
                pan = (order % 2 == 0) ? -35.0f : 35.0f;
                tilt = -25.0f + ((beatInBar % 2 == 0) ? 15.0f : 0.0f);
            } else if (patternMode == 2) {
                // Kreuzende Strahlen (X-Formation)
                pan = -normOrder * 135.0f;
                tilt = -10.0f - Math.abs(normOrder) * 20.0f;
            } else {
                // Schneller Rotations-Chase
                double chasePhase = (phase + normOrder * 0.8) % 1.0;
                pan = (float) (Math.sin(chasePhase * 2.0 * Math.PI) * 85.0);
                tilt = (float) (Math.cos(chasePhase * 2.0 * Math.PI) * 25.0 - 15.0);
            }

            // Dimmer-Dynamik: Bei treibendem Kick knallt der Dimmer auf 100%,
            // im Breakdown schweben die Heads sanfter
            float dimmer;
            if (strobeActive) {
                dimmer = isKick ? 1.0f : (float) Math.clamp(1.0 - (phase * 0.45), 0.50, 0.95);
            } else {
                dimmer = (float) Math.clamp(0.40 + Math.sin(phase * Math.PI) * 0.35, 0.35, 0.75);
            }
            float zoom = (type == FixtureType.MOVING_HEAD_BEAM) ? 4.0f : (isKick ? 16.0f : 24.0f);

            return new FixtureState(pan, tilt, dimmer, color, zoom, 0.0f);
        }

        if (type.isLaser()) {
            // Schnelle Fächerscans
            float scanPan = (float) (Math.sin(phase * 4.0 * Math.PI + normOrder * 2.0) * 24.0);
            float scanTilt = -10.0f + (float) (Math.cos(phase * 2.0 * Math.PI) * 15.0);
            float fanAngle = 45.0f + (float) (Math.sin(phase * 8.0 * Math.PI) * 15.0);

            // AUTONOME LASER-STROBO-LOGIK:
            // Wenn Kicks treiben (strobeActive = true), nutzt der Laser aggressives Strobo / Chopping!
            // Im Breakdown oder ohne Kick schaltet er das Strobo komplett ab.
            float laserStrobe;
            float laserDimmer;
            if (strobeActive) {
                boolean flash = isKick || (beatInBar == 3 && phase > 0.45) || beat.dropImpact();
                laserStrobe = flash ? safeStrobeHz : ((order % 2 == 0 && phase < 0.3) ? safeStrobeHz : 0.0f);
                laserDimmer = 1.0f;
            } else {
                laserStrobe = 0.0f;
                laserDimmer = 0.65f;
            }

            return new FixtureState(scanPan, scanTilt, laserDimmer, color, fanAngle, laserStrobe);
        }

        if (type == FixtureType.STROBE_BAR) {
            // AUTONOME STROBE-BAR-LOGIK:
            // Bei treibenden Kicks (strobeActive = true):
            // - Auf jedem Kick-Downbeat: Scharfer 10 Hz Impuls
            // - Bei Takt-Fills (Beat 4) und Drop-Impact: Dauerhafter Strobe-Blast (10.0 Hz)
            // Bei Ruhephase/Breakdown: Stroboskop bleibt komplett AUS (0.0 Hz, 0 Dimmer)!
            if (strobeActive) {
                boolean fillActive = (beatInBar == 3 && phase > 0.40) || beat.dropImpact();
                boolean kickPunch = isKick || phase < 0.18;
                float effectiveHz = fillActive ? 10.0f : (kickPunch ? safeStrobeHz : 0.0f);
                float dimmer = fillActive ? 1.0f : (kickPunch ? 1.0f : 0.0f);
                return new FixtureState(0.0f, -20.0f, dimmer, COLOR_TECHNO_WHITE, 50.0f, effectiveHz);
            } else {
                return new FixtureState(0.0f, -20.0f, 0.0f, COLOR_TECHNO_WHITE, 50.0f, 0.0f);
            }
        }

        if (type == FixtureType.BLINDER_2LITE) {
            // Blinder zündet auf Drop-Impacts und Takt-Abschlüssen, schweigt im Breakdown
            if (strobeActive) {
                boolean blind = beat.dropImpact() || (beatInBar == 3 && phase > 0.70);
                float dimmer = blind ? 1.0f : (isKick ? 0.45f : 0.0f);
                return new FixtureState(0.0f, -15.0f, dimmer, Oklab.fromRGB(Color.fromRGB(255, 220, 160)), 65.0f, 0.0f);
            } else {
                return new FixtureState(0.0f, -15.0f, 0.0f, Oklab.fromRGB(Color.fromRGB(255, 220, 160)), 65.0f, 0.0f);
            }
        }

        if (type.isFogMachine()) {
            return new FixtureState(0.0f, 0.0f, 0.95f, type.fogType().baseColor(), 30.0f, 0.0f);
        }

        if (type.isFlameProjector()) {
            boolean burst = beat.dropImpact() || (strobeActive && bar % 8 == 0 && beatInBar == 0 && phase < 0.35);
            return new FixtureState(0.0f, 0.0f, burst ? 1.0f : 0.0f, Oklab.fromRGB(Color.fromRGB(255, 140, 20)), 20.0f, 0.0f);
        }

        return new FixtureState(0.0f, 0.0f, 0.85f, color, 24.0f, 0.0f);
    }

    /**
     * RAP / TRAP: Synkopierte 808-Kicks, sanftes Nodding, weiche Dimmer-Resonanz, atmosphärisches Violett/Gold.
     */
    private FixtureState computeRapState(
            FixtureType type,
            int order,
            float normOrder,
            int tickCounter,
            BeatSnapshot beat
    ) {
        double phase = beat.phase();
        long bar = beat.barCount();

        // Warme Oklab Farbüberblendung zwischen Violett, Gold und Neon-Teal
        double colorPhase = ((bar % 8) + phase) / 8.0;
        Oklab color = (colorPhase < 0.5)
                ? COLOR_RAP_PURPLE.lerp(COLOR_RAP_GOLD, (float) (colorPhase * 2.0))
                : COLOR_RAP_GOLD.lerp(COLOR_RAP_TEAL, (float) ((colorPhase - 0.5) * 2.0));

        if (type.isMovingHead()) {
            // Langsame, fließende Pan-Bewegung mit vertikalem Nodding (Kopfnicken) im Half-Time Takt
            double slowPhase = (tickCounter % 200_000L) * 0.015 + (normOrder * 0.7);
            float pan = (float) (Math.sin(slowPhase) * 75.0);

            // Vertikales Nodding: Taucht auf dem Beat nach unten und schwingt sanft nach oben
            float nodding = (float) (Math.sin(phase * 2.0 * Math.PI) * 16.0);
            float tilt = -18.0f + nodding;

            // 808-Bass-Resonanz: Schneller Anschlag, langes weiches Ausklingen über den Sub-Bass
            float bassResonance = (float) Math.pow(Math.clamp(1.0 - phase, 0.0, 1.0), 2.0);
            float dimmer = Math.clamp(0.25f + bassResonance * 0.75f, 0.25f, 1.0f);
            float zoom = 32.0f + bassResonance * 12.0f; // Breiterer, weicherer Lichtkegel

            return new FixtureState(pan, tilt, dimmer, color, zoom, 0.0f);
        }

        if (type.isLaser()) {
            // Breite, langsame Fächerprojektion ohne aggressives Strobo
            float laserPan = (float) (Math.sin(phase * Math.PI + normOrder) * 16.0);
            float laserTilt = -8.0f;
            float fanAngle = 55.0f;
            float dimmer = (float) Math.clamp(0.4 + (1.0 - phase) * 0.55, 0.4, 0.95);
            return new FixtureState(laserPan, laserTilt, dimmer, color, fanAngle, 0.0f);
        }

        if (type == FixtureType.BLINDER_2LITE) {
            // Sanftes Mitschwingen mit dem 808-Sub-Bass (Bernsteinfarbenes Glühen)
            float bassGlow = (float) Math.pow(Math.clamp(1.0 - phase, 0.0, 1.0), 2.5);
            return new FixtureState(0.0f, -15.0f, bassGlow * 0.85f, Oklab.fromRGB(Color.fromRGB(255, 190, 80)), 60.0f, 0.0f);
        }

        if (type == FixtureType.STROBE_BAR) {
            // Strobes im Rap extrem dosiert einsetzen (nur bei Drop-Impacts, SEC-09)
            float hz = beat.dropImpact() ? 8.0f : 0.0f;
            float dimmer = beat.dropImpact() ? 1.0f : 0.0f;
            return new FixtureState(0.0f, -20.0f, dimmer, COLOR_RAP_GOLD, 50.0f, hz);
        }

        if (type.isFlameProjector()) {
            // Flammensäule auf schwere Downbeat-Hooks
            boolean burst = beat.dropImpact() || (bar % 8 == 0 && beat.beatInBar() == 0 && phase < 0.25);
            return new FixtureState(0.0f, 0.0f, burst ? 1.0f : 0.0f, Oklab.fromRGB(Color.fromRGB(255, 130, 20)), 20.0f, 0.0f);
        }

        if (type.isFogMachine()) {
            return new FixtureState(0.0f, 0.0f, 0.70f, type.fogType().baseColor(), 25.0f, 0.0f);
        }

        return new FixtureState(0.0f, 0.0f, 0.80f, color, 30.0f, 0.0f);
    }

    /**
     * DRUM & BASS: 170 BPM, schnelle alternierende Beam-Positionen, Laser-Sweeps, energiegeladen.
     */
    private FixtureState computeDnBState(
            FixtureType type,
            int order,
            float normOrder,
            int tickCounter,
            BeatSnapshot beat
    ) {
        double phase = beat.phase();
        long bar = beat.barCount();
        int beatInBar = beat.beatInBar();
        float safeStrobeHz = Math.min(10.0f, beat.strobeHz() > 0.0f ? beat.strobeHz() : 10.0f);

        Oklab color = (bar % 2 == 0) ? COLOR_DNB_BLUE : COLOR_DNB_GREEN;

        if (type.isMovingHead()) {
            // Alternierender Positions-Swap auf Snare (Schlag 2 und 4)
            boolean swap = (beatInBar == 1 || beatInBar == 3);
            float basePan = (order % 2 == 0) ? -45.0f : 45.0f;
            float pan = swap ? -basePan : basePan;
            float tilt = -15.0f + (float) (Math.sin(phase * 2.0 * Math.PI) * 12.0);
            float dimmer = (float) Math.clamp(0.6 + (1.0 - phase) * 0.4, 0.6, 1.0);

            return new FixtureState(pan, tilt, dimmer, color, 8.0f, 0.0f);
        }

        if (type.isLaser()) {
            float scanPan = (float) (Math.sin(phase * 4.0 * Math.PI + normOrder * 3.0) * 30.0);
            float laserStrobe = beat.strobeActive() && (beatInBar == 1 || beatInBar == 3) ? safeStrobeHz : 0.0f;
            return new FixtureState(scanPan, -12.0f, 1.0f, COLOR_DNB_YELLOW, 50.0f, laserStrobe);
        }

        if (type == FixtureType.STROBE_BAR) {
            // Kurze Strobe-Fills (strikt <= 10 Hz)
            boolean flash = beat.strobeActive() && (beatInBar == 3 && phase > 0.60);
            return new FixtureState(0.0f, -20.0f, flash ? 1.0f : 0.0f, COLOR_TECHNO_WHITE, 50.0f, flash ? safeStrobeHz : 0.0f);
        }

        return new FixtureState(0.0f, -15.0f, 0.85f, color, 20.0f, 0.0f);
    }

    /**
     * HOUSE / CLUB: 124 BPM 4/4 Bounce, harmonische Achter- und Kreis-Muster, warme Farbverläufe.
     */
    private FixtureState computeHouseState(
            FixtureType type,
            int order,
            float normOrder,
            int tickCounter,
            BeatSnapshot beat
    ) {
        double phase = beat.phase();
        long bar = beat.barCount();

        Oklab color = switch ((int) (bar % 3)) {
            case 0 -> COLOR_HOUSE_ORANGE;
            case 1 -> COLOR_HOUSE_PINK;
            default -> COLOR_HOUSE_CYAN;
        };

        if (type.isMovingHead()) {
            // Bouncendes Figure-8 Muster
            double t = (bar * 4.0 + phase) * 0.5 + normOrder * 1.2;
            float pan = (float) (Math.sin(t) * 60.0);
            float tilt = (float) (Math.sin(t * 2.0) * 20.0 - 15.0);
            float dimmer = (float) (0.65 + Math.sin(phase * Math.PI) * 0.35);

            return new FixtureState(pan, tilt, dimmer, color, 24.0f, 0.0f);
        }

        if (type == FixtureType.BLINDER_2LITE) {
            float dim = (float) (0.2 + Math.sin(phase * Math.PI) * 0.6);
            return new FixtureState(0.0f, -15.0f, dim, Oklab.fromRGB(Color.fromRGB(255, 210, 140)), 60.0f, 0.0f);
        }

        return new FixtureState(0.0f, 0.0f, 0.85f, color, 28.0f, 0.0f);
    }

    /**
     * AMBIENT: Sanfte, schwebende Dämmerungsbewegungen, geringe Dynamik.
     */
    private FixtureState computeAmbientState(
            FixtureType type,
            int order,
            float normOrder,
            int tickCounter,
            BeatSnapshot beat
    ) {
        double t = (tickCounter % 200_000L) * 0.012 + (normOrder * 1.5);
        float pan = (float) (Math.sin(t) * 45.0);
        float tilt = (float) (Math.cos(t * 0.7) * 15.0 - 18.0);
        float dimmer = (float) (0.45 + Math.sin(t * 0.5) * 0.25);

        float hue = (float) (((tickCounter * 0.2) + order * 40.0) % 360.0);
        Oklab color = Oklab.fromHSB(hue, 0.65f, 0.95f);

        return new FixtureState(pan, tilt, dimmer, color, 35.0f, 0.0f);
    }
}
